package app.eikon.gallery.data.backup

import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The Nextcloud/WebDAV client against a stand-in server: what it asks, in which order, with which headers, and what it makes of each answer. */
class NextcloudTargetTest {
    private lateinit var server: MockWebServer
    private val content = "not really a jpeg".toByteArray()

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() = server.close()

    private fun target(folder: String = "eikon") = NextcloudTarget(Tls.client(null), server.url("/"), "me", "app-pass-word", folder)

    private fun file(name: String = "IMG_1234.jpg", sha1: String = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678") =
        BackupFile(1, name, "image/jpeg", false, takenAt = 1_755_000_000_000L, modifiedAt = 1_755_000_123_456L, isFavorite = false, length = content.size.toLong(), sha1 = sha1)

    private suspend fun upload(target: NextcloudTarget = target(), file: BackupFile = file()) = target.upload(file) { ByteArrayInputStream(content) }

    private fun answer(code: Int, body: String = "") = server.enqueue(MockResponse(code = code, body = body))

    private fun requests(count: Int) = (1..count).map { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!! }

    @Test
    fun aServerThatAnswersTheDavRootIsAccepted() = runTest {
        answer(207); answer(201); answer(200, """{"installed":true,"version":"29.0.4.1","productname":"Nextcloud"}""")

        val check = target().check()

        assertEquals("29.0.4.1", check.version)
        val (propfind, mkcol, status) = requests(3)
        assertEquals("PROPFIND", propfind.method)
        assertEquals("/remote.php/dav/files/me/", propfind.url.encodedPath)
        assertEquals("0", propfind.headers["Depth"])
        assertEquals(Credentials.basic("me", "app-pass-word"), propfind.headers["Authorization"])
        assertEquals("MKCOL", mkcol.method)
        assertEquals("/remote.php/dav/files/me/eikon", mkcol.url.encodedPath)
        assertEquals("/status.php", status.url.encodedPath)
    }

    @Test
    fun aServerWithoutStatusPhpIsStillFine() = runTest {
        answer(207); answer(405); answer(404)
        assertEquals(null, target().check().version)
    }

    @Test
    fun aRefusedPasswordIsSaidSo() = runTest {
        answer(401)
        try {
            target().check()
            fail()
        } catch (e: BackupException.Unauthorized) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun anAddressThatIsNotNextcloudOrTheWrongLoginIsSaidSo() = runTest {
        answer(404)
        try {
            target().check()
            fail()
        } catch (e: BackupException.Misconfigured) {
            assertTrue(e.message!!.contains("Nextcloud"))
        }
    }

    @Test
    fun aRedirectIsNeverFollowed() = runTest {
        server.enqueue(MockResponse(code = 301, headers = okhttp3.Headers.headersOf("Location", "https://elsewhere.example/")))
        try {
            target().check()
            fail()
        } catch (e: BackupException.Misconfigured) {
            assertTrue(e.message!!.contains("elsewhere.example"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aFileIsWrittenInTheDayFolderAfterMakingEachLevelAndNeverOverwrites() = runTest {
        answer(405); answer(201); answer(201); answer(404); answer(201) // eikon exists, 2025 and 08 made, HEAD says no, PUT

        assertEquals(UploadResult.STORED, upload())

        val r = requests(5)
        assertEquals(listOf("MKCOL", "MKCOL", "MKCOL", "HEAD", "PUT"), r.map { it.method })
        assertEquals("/remote.php/dav/files/me/eikon", r[0].url.encodedPath)
        assertEquals("/remote.php/dav/files/me/eikon/2025", r[1].url.encodedPath)
        assertEquals("/remote.php/dav/files/me/eikon/2025/08", r[2].url.encodedPath)
        val put = r[4]
        assertEquals("/remote.php/dav/files/me/eikon/2025/08/IMG_1234_a1b2c3d4.jpg", put.url.encodedPath)
        assertEquals("*", put.headers["If-None-Match"])
        assertEquals("SHA1:a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", put.headers["OC-Checksum"])
        assertEquals("1755000123", put.headers["X-OC-MTime"])
        assertEquals("image/jpeg", put.headers["Content-Type"])
        assertEquals(content.size.toLong(), put.headers["Content-Length"]!!.toLong())
        assertEquals(String(content), put.body!!.utf8())
    }

    @Test
    fun foldersAlreadyMadeAreNotAskedForAgain() = runTest {
        val target = target()
        answer(405); answer(201); answer(201); answer(404); answer(201)
        upload(target)
        requests(5)

        answer(404); answer(201)
        upload(target, file(name = "IMG_9999.jpg", sha1 = "ffffffff00000000"))

        assertEquals(listOf("HEAD", "PUT"), requests(2).map { it.method })
    }

    @Test
    fun aFileAlreadyThereIsLeftAloneAndNotSentAgain() = runTest {
        answer(405); answer(405); answer(405); answer(200)

        assertEquals(UploadResult.ALREADY_THERE, upload())

        assertEquals(listOf("MKCOL", "MKCOL", "MKCOL", "HEAD"), requests(4).map { it.method })
        assertEquals(4, server.requestCount)
    }

    @Test
    fun aRaceThatMakesTheServerAnswerPreconditionFailedCountsAsAlreadyThere() = runTest {
        answer(405); answer(405); answer(405); answer(404); answer(412)
        assertEquals(UploadResult.ALREADY_THERE, upload())
    }

    @Test
    fun aFullServerEndsTheRunButIsNotTheFilesFault() = runTest {
        answer(405); answer(405); answer(405); answer(404); answer(507)
        try {
            upload()
            fail()
        } catch (e: BackupException.Unreachable) {
            assertTrue(e.message!!.contains("507"))
        }
    }

    @Test
    fun aFileTheServerCannotTakeIsRefusedAsThatFilesProblem() = runTest {
        answer(405); answer(405); answer(405); answer(404); answer(413)
        try {
            upload()
            fail()
        } catch (e: BackupException.Rejected) {
            assertTrue(e.message!!.contains("413"))
        }
    }

    @Test
    fun aPasswordThatStopsWorkingMidRunIsSaidSo() = runTest {
        answer(405); answer(405); answer(405); answer(404); answer(401)
        try {
            upload()
            fail()
        } catch (_: BackupException.Unauthorized) {
        }
    }

    @Test
    fun aFolderTheServerWillNotLetUsMakeIsSaidSo() = runTest {
        answer(403)
        try {
            upload()
            fail()
        } catch (_: BackupException.Unauthorized) {
        }
    }

    @Test
    fun namesWithSpacesAndAccentsAreEncodedInTheAddress() = runTest {
        answer(405); answer(405); answer(405); answer(404); answer(201)
        upload(file = file(name = "Fotos de verão #1.jpg"))
        val put = requests(5).last()
        assertEquals("/remote.php/dav/files/me/eikon/2025/08/Fotos%20de%20ver%C3%A3o%20%231_a1b2c3d4.jpg", put.url.encodedPath)
    }

    @Test
    fun aFolderOfSeveralLevelsIsMadeLevelByLevel() = runTest {
        repeat(4) { answer(405) }; answer(404); answer(201)
        upload(target("Photos/phone"))
        val methods = requests(6).map { it.method to it.url.encodedPath }
        assertEquals("/remote.php/dav/files/me/Photos", methods[0].second)
        assertEquals("/remote.php/dav/files/me/Photos/phone", methods[1].second)
        assertEquals("PUT", methods.last().first)
    }

    @Test
    fun theSecretNeverGoesInTheAddress() = runTest {
        answer(405); answer(405); answer(405); answer(404); answer(201)
        upload()
        requests(5).forEach { assertFalse(it.url.toString().contains("app-pass-word")) }
    }

    @Test
    fun aServerThatIsNotThereIsUnreachableNotAnAccident() = runTest {
        val dead = NextcloudTarget(Tls.client(null), okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(1).build(), "me", "pw", "eikon")
        try {
            dead.check()
            fail()
        } catch (_: BackupException.Unreachable) {
        }
    }
}
