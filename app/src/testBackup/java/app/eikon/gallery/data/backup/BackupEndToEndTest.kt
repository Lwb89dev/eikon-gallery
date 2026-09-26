package app.eikon.gallery.data.backup

import app.eikon.gallery.domain.MediaItem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole path a photo takes, from the queue to a server, against stand-ins that behave like the real ones (they remember what they were given and refuse what the real
 * ones refuse): a run stores everything, a second run does nothing, another phone with the same photos adds nothing, and no photo ever overwrites another.
 */
class BackupEndToEndTest {
    private lateinit var server: MockWebServer

    @After
    fun stop() = server.close()

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Phone(val photos: Map<Long, Pair<String, ByteArray>>) {
        val queue = MemoryQueue(photos.keys.sortedDescending().map { id ->
            MediaItem(id, photos.getValue(id).first, "image/jpeg", false, takenAt = 1_755_000_000_000L + id * 1000, addedAt = 1, modifiedAt = 7, width = 1, height = 1, durationMs = 0, sizeBytes = photos.getValue(id).second.size.toLong(), relativePath = null, bucketName = null, isFavorite = false)
        })
        val source = object : BackupSource {
            override suspend fun fingerprint(item: MediaItem): Fingerprint {
                val bytes = photos.getValue(item.id).second
                return Fingerprint(bytes.size.toLong(), MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) })
            }

            override fun open(item: MediaItem): InputStream = ByteArrayInputStream(photos.getValue(item.id).second)
        }
    }

    private class MemoryQueue(private val all: List<MediaItem>) : BackupQueue {
        val done = HashSet<Long>()
        val failed = HashSet<Long>()
        override suspend fun pending(limit: Int) = all.filter { it.id !in done && it.id !in failed }.take(limit)
        override suspend fun markDone(item: MediaItem, file: BackupFile) {
            done += item.id
        }

        override suspend fun markFailed(item: MediaItem) {
            failed += item.id
        }
    }

    private val environment = object : BackupEnvironment {
        override fun settings() = BackupSettings(enabled = true, serverUrl = "https://x.example")
        override fun thermalStatus() = 0
        override fun isPowerSaveMode() = false
        override fun nowMillis() = System.currentTimeMillis()
    }

    // --- Nextcloud -----------------------------------------------------------------------------

    private class DavServer : Dispatcher() {
        val directories = HashSet<String>().apply { add("/remote.php/dav/files/me") }
        val files = HashMap<String, ByteArray>()
        val refusedChecksums = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath.trimEnd('/')
            return when (request.method) {
                "MKCOL" -> when {
                    path in directories -> MockResponse(code = 405)
                    path.substringBeforeLast('/') !in directories -> MockResponse(code = 409)
                    else -> { directories += path; MockResponse(code = 201) }
                }
                "HEAD" -> MockResponse(code = if (path in files) 200 else 404)
                "PUT" -> put(path, request)
                else -> MockResponse(code = 405)
            }
        }

        private fun put(path: String, request: RecordedRequest): MockResponse {
            if (path.substringBeforeLast('/') !in directories) return MockResponse(code = 409)
            if (path in files) return MockResponse(code = 412)
            val body = request.body!!.toByteArray()
            val claimed = request.headers["OC-Checksum"]?.removePrefix("SHA1:")
            if (claimed != MessageDigest.getInstance("SHA-1").digest(body).joinToString("") { "%02x".format(it) }) {
                refusedChecksums += path
                return MockResponse(code = 400)
            }
            files[path] = body
            return MockResponse(code = 201)
        }
    }

    private fun startDav(): DavServer {
        val dav = DavServer()
        server = MockWebServer().apply { dispatcher = dav; start() }
        return dav
    }

    private fun nextcloud() = NextcloudTarget(Tls.client(null), server.url("/"), "me", "pw", "eikon")

    @Test
    fun aFirstRunStoresEveryPhotoWithTheRightBytesAndAChecksumTheServerVerified() = runTest {
        val dav = startDav()
        val phone = Phone(mapOf(1L to ("IMG_1.jpg" to "first".toByteArray()), 2L to ("IMG_2.jpg" to "second".toByteArray())))

        val report = BackupRunner(phone.queue, phone.source, environment, nextcloud()).run(60_000)

        assertNull(report.stoppedBy)
        assertEquals(2, report.sent)
        assertEquals(setOf("first", "second"), dav.files.values.map { String(it) }.toSet())
        assertTrue(dav.refusedChecksums.isEmpty())
        assertTrue(dav.files.keys.all { it.startsWith("/remote.php/dav/files/me/eikon/2025/08/IMG_") })
    }

    @Test
    fun aSecondRunHasNothingToDo() = runTest {
        startDav()
        val phone = Phone(mapOf(1L to ("IMG_1.jpg" to "first".toByteArray())))
        BackupRunner(phone.queue, phone.source, environment, nextcloud()).run(60_000)
        val requestsAfterFirst = server.requestCount

        val second = BackupRunner(phone.queue, phone.source, environment, nextcloud()).run(60_000)

        assertEquals(0, second.sent)
        assertEquals(requestsAfterFirst, server.requestCount)
    }

    @Test
    fun anotherPhoneWithTheSamePhotosAddsNothingAndOverwritesNothing() = runTest {
        val dav = startDav()
        val bytes = mapOf(10L to ("IMG_1.jpg" to "same picture".toByteArray()))
        BackupRunner(Phone(bytes).queue, Phone(bytes).source, environment, nextcloud()).run(60_000)
        val stored = dav.files.toMap()

        // The same file on a phone that numbers its photos differently.
        val other = Phone(mapOf(99L to ("IMG_1.jpg" to "same picture".toByteArray())))
        val report = BackupRunner(other.queue, other.source, environment, nextcloud()).run(60_000)

        assertEquals(0, report.sent)
        assertEquals(1, report.alreadyThere)
        assertEquals(stored.keys, dav.files.keys)
    }

    @Test
    fun twoDifferentPhotosWithTheSameNameBothSurvive() = runTest {
        val dav = startDav()
        val phone = Phone(mapOf(1L to ("IMG_0001.jpg" to "from the camera".toByteArray()), 2L to ("IMG_0001.jpg" to "from a messaging app".toByteArray())))

        val report = BackupRunner(phone.queue, phone.source, environment, nextcloud()).run(60_000)

        assertEquals(2, report.sent)
        assertEquals(setOf("from the camera", "from a messaging app"), dav.files.values.map { String(it) }.toSet())
    }

    // --- Immich --------------------------------------------------------------------------------

    private class ImmichServer : Dispatcher() {
        val byChecksum = HashMap<String, ByteArray>()
        val uploads = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.headers["x-api-key"] != "key") return MockResponse(code = 401)
            return when (request.url.encodedPath) {
                "/api/assets/bulk-upload-check" -> bulkCheck(request)
                "/api/assets" -> upload(request)
                else -> MockResponse(code = 404)
            }
        }

        private fun bulkCheck(request: RecordedRequest): MockResponse {
            val assets = ((MiniJson.parse(request.body!!.utf8()) as Map<*, *>)["assets"] as List<*>).map { it as Map<*, *> }
            val results = assets.joinToString(",", "{\"results\":[", "]}") {
                val known = it["checksum"] in byChecksum
                "{\"id\":${MiniJson.quote(it["id"] as String)},\"action\":\"${if (known) "reject" else "accept"}\"${if (known) ",\"reason\":\"duplicate\"" else ""}}"
            }
            return MockResponse(code = 200, body = results)
        }

        private fun upload(request: RecordedRequest): MockResponse {
            val checksum = request.headers["x-immich-checksum"]!!
            if (checksum in byChecksum) return MockResponse(code = 200, body = "{\"id\":\"a\",\"status\":\"duplicate\"}")
            val body = request.body!!.utf8()
            uploads += Regex("filename=\"([^\"]+)\"").find(body)!!.groupValues[1]
            byChecksum[checksum] = ByteArray(0)
            return MockResponse(code = 201, body = "{\"id\":\"a\",\"status\":\"created\"}")
        }
    }

    private fun startImmich(): ImmichServer {
        val immich = ImmichServer()
        server = MockWebServer().apply { dispatcher = immich; start() }
        return immich
    }

    private fun immich(deviceId: String) = ImmichTarget(Tls.client(null), server.url("/"), "key", deviceId)

    @Test
    fun immichReceivesEachPhotoOnceAndRecognisesWhatAnotherPhoneAlreadySent() = runTest {
        val immich = startImmich()
        val mine = Phone(mapOf(1L to ("IMG_1.jpg" to "one".toByteArray()), 2L to ("IMG_2.jpg" to "two".toByteArray())))

        val first = BackupRunner(mine.queue, mine.source, environment, immich("eikon-aaaa1111")).run(60_000)
        assertEquals(2, first.sent)
        assertEquals(listOf("IMG_2.jpg", "IMG_1.jpg"), immich.uploads)

        val theirs = Phone(mapOf(5L to ("copy-of-1.jpg" to "one".toByteArray()), 6L to ("brand-new.jpg" to "three".toByteArray())))
        val second = BackupRunner(theirs.queue, theirs.source, environment, immich("eikon-bbbb2222")).run(60_000)

        assertEquals(1, second.sent)
        assertEquals(1, second.alreadyThere)
        assertEquals(3, immich.byChecksum.size)
        assertTrue(sha1("one".toByteArray()) in immich.byChecksum)
    }

    @Test
    fun aWrongKeyStopsTheRunBeforeAnyPhotoIsTouched() = runTest {
        startImmich()
        val phone = Phone(mapOf(1L to ("IMG_1.jpg" to "one".toByteArray())))
        val wrong = ImmichTarget(Tls.client(null), server.url("/"), "wrong-key", "eikon-cccc3333")

        val report = BackupRunner(phone.queue, phone.source, environment, wrong).run(60_000)

        assertEquals(BackupStop.UNAUTHORIZED, report.stoppedBy)
        assertTrue(phone.queue.done.isEmpty() && phone.queue.failed.isEmpty())
    }
}
