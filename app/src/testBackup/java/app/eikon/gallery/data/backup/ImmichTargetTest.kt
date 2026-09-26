package app.eikon.gallery.data.backup

import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The Immich client against a stand-in server. */
class ImmichTargetTest {
    private lateinit var server: MockWebServer
    private val content = "pretend photo bytes".toByteArray()

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() = server.close()

    private fun target() = ImmichTarget(Tls.client(null), server.url("/"), "the-api-key", "eikon-1234abcd")

    private fun file(id: Long = 1, name: String = "IMG_1.jpg", favorite: Boolean = true) =
        BackupFile(id, name, "image/jpeg", false, takenAt = 1_755_000_000_000L, modifiedAt = 1_755_000_123_000L, isFavorite = favorite, length = content.size.toLong(), sha1 = "sha1-of-$id")

    private fun answer(code: Int, body: String = "") = server.enqueue(MockResponse(code = code, body = body))

    private fun requests(count: Int) = (1..count).map { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!! }

    @Test
    fun aServerThatKnowsTheKeyAndAllowsUploadsIsAccepted() = runTest {
        answer(200, """{"res":"pong"}"""); answer(200, """{"major":2,"minor":4,"patch":1}"""); answer(200, """{"authStatus":true}"""); answer(200, """{"permissions":["asset.upload"]}""")

        val check = target().check()

        assertEquals("Immich", check.product)
        assertEquals("2.4.1", check.version)
        assertTrue(check.warnings.isEmpty())
        val r = requests(4)
        assertEquals(listOf("/api/server/ping", "/api/server/version", "/api/auth/validateToken", "/api/api-keys/me"), r.map { it.url.encodedPath })
        assertEquals("POST", r[2].method)
        r.forEach { assertEquals("the-api-key", it.headers["x-api-key"]) }
    }

    @Test
    fun aKeyThatMayNotUploadIsWarnedAboutBeforeTheFirstUploadFails() = runTest {
        answer(200, """{"res":"pong"}"""); answer(200, """{"major":3,"minor":2,"patch":0}"""); answer(200, """{"authStatus":true}"""); answer(200, """{"permissions":["asset.read"]}""")
        assertEquals(1, target().check().warnings.size)
    }

    @Test
    fun aKeyWithAllPermissionsOrAnOldServerThatDoesNotSayIsFine() = runTest {
        answer(200, """{"res":"pong"}"""); answer(200, """{"major":1,"minor":100,"patch":0}"""); answer(200, """{"authStatus":true}"""); answer(200, """{"permissions":["all"]}""")
        assertTrue(target().check().warnings.isEmpty())

        answer(200, """{"res":"pong"}"""); answer(200, """{"major":1,"minor":100,"patch":0}"""); answer(200, """{"authStatus":true}"""); answer(404)
        assertTrue(target().check().warnings.isEmpty())
    }

    @Test
    fun aRejectedKeyIsSaidSo() = runTest {
        answer(200, """{"res":"pong"}"""); answer(200, """{"major":2,"minor":4,"patch":1}"""); answer(401)
        try {
            target().check()
            fail()
        } catch (_: BackupException.Unauthorized) {
        }
    }

    @Test
    fun anAddressThatIsNotImmichIsSaidSo() = runTest {
        answer(200, "<html>welcome to my router</html>")
        try {
            target().check()
            fail()
        } catch (e: BackupException.Misconfigured) {
            assertTrue(e.message!!.contains("Immich"))
        }
        answer(404)
        try {
            target().check()
            fail()
        } catch (_: BackupException.Misconfigured) {
        }
    }

    @Test
    fun whatTheServerAlreadyHasIsNotSentAgain() = runTest {
        answer(200, """{"results":[{"id":"1","action":"accept"},{"id":"2","action":"reject","reason":"duplicate","assetId":"x"},{"id":"3","action":"reject","reason":"unsupported-format"}]}""")

        val missing = target().missing(listOf(file(1), file(2), file(3)))

        assertEquals(listOf(1L, 3L), missing.map { it.mediaId }) // 3 is unsupported, not a duplicate: it is tried, and the server will say so
        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("/api/assets/bulk-upload-check", request.url.encodedPath)
        val body = MiniJson.parse(request.body!!.utf8()) as Map<*, *>
        val assets = body["assets"] as List<*>
        assertEquals(listOf("sha1-of-1", "sha1-of-2", "sha1-of-3"), assets.map { (it as Map<*, *>)["checksum"] })
        assertEquals(listOf("1", "2", "3"), assets.map { (it as Map<*, *>)["id"] })
    }

    @Test
    fun anAnswerInAnUnknownShapeIsNotGuessedAt() = runTest {
        answer(200, """{"unexpected":true}""")
        try {
            target().missing(listOf(file()))
            fail()
        } catch (_: BackupException.Misconfigured) {
        }
    }

    @Test
    fun nothingToAskAboutAsksNothing() = runTest {
        assertEquals(emptyList<BackupFile>(), target().missing(emptyList()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aFileIsUploadedAsAFormWithEverythingImmichAsksFor() = runTest {
        answer(201, """{"id":"11111111-1111-4111-8111-111111111111","status":"created"}""")

        assertEquals(UploadResult.STORED, target().upload(file()) { ByteArrayInputStream(content) })

        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/assets", request.url.encodedPath)
        assertEquals("the-api-key", request.headers["x-api-key"])
        assertEquals("sha1-of-1", request.headers["x-immich-checksum"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val form = request.body!!.utf8()
        fun part(name: String, value: String) = assertTrue("$name=$value in $form", Regex("name=\"$name\"\\r\\n(Content-Length: \\d+\\r\\n)?\\r\\n${Regex.escape(value)}\\r\\n").containsMatchIn(form))
        part("deviceId", "eikon-1234abcd")
        part("deviceAssetId", "eikon-1234abcd-1-1755000123000")
        part("fileCreatedAt", "2025-08-12T12:00:00Z")
        part("fileModifiedAt", "2025-08-12T12:02:03Z")
        part("filename", "IMG_1.jpg")
        part("isFavorite", "true")
        part("metadata", "[]")
        assertTrue(form.contains("name=\"assetData\"; filename=\"IMG_1.jpg\""))
        assertTrue(form.contains("Content-Type: image/jpeg"))
        assertTrue(form.contains(String(content)))
    }

    @Test
    fun aFileImmichAlreadyHasIsWrittenDownNotDuplicated() = runTest {
        answer(200, """{"id":"11111111-1111-4111-8111-111111111111","status":"duplicate"}""")
        assertEquals(UploadResult.ALREADY_THERE, target().upload(file()) { ByteArrayInputStream(content) })
    }

    @Test
    fun theStatusesMeanWhatTheyShouldForOneFile() = runTest {
        suspend fun outcome(code: Int): BackupException? = try {
            answer(code)
            target().upload(file()) { ByteArrayInputStream(content) }
            null
        } catch (e: BackupException) {
            e
        }
        assertTrue(outcome(401) is BackupException.Unauthorized)
        assertTrue(outcome(403) is BackupException.Unauthorized)
        assertTrue(outcome(413) is BackupException.Rejected)
        assertTrue(outcome(400) is BackupException.Rejected)
        assertTrue(outcome(415) is BackupException.Rejected)
        assertTrue(outcome(500) is BackupException.Unreachable)
        assertTrue(outcome(503) is BackupException.Unreachable)
        assertTrue(outcome(429) is BackupException.Unreachable)
        assertTrue(outcome(302) is BackupException.Misconfigured)
    }

    @Test
    fun aPhotoOfAnotherPhoneNeverSharesAnAssetIdWithThisOne() = runTest {
        answer(201, """{"id":"a","status":"created"}"""); answer(201, """{"id":"b","status":"created"}""")
        val a = ImmichTarget(Tls.client(null), server.url("/"), "k", "eikon-aaaa1111")
        val b = ImmichTarget(Tls.client(null), server.url("/"), "k", "eikon-bbbb2222")
        a.upload(file()) { ByteArrayInputStream(content) }
        b.upload(file()) { ByteArrayInputStream(content) }
        val (first, second) = requests(2)
        assertTrue(first.body!!.utf8().contains("eikon-aaaa1111-1-"))
        assertTrue(second.body!!.utf8().contains("eikon-bbbb2222-1-"))
    }
}
