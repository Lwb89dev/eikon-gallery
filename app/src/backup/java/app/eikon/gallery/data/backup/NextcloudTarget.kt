package app.eikon.gallery.data.backup

import app.eikon.gallery.data.backup.MiniJson.map
import java.io.InputStream
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * Nextcloud, or any server that speaks WebDAV: files are written into a folder of the user's files with an app password. A file's place carries the start of its SHA-1
 * (see [RemoteNames]), so the same picture always lands in the same place and different pictures never do: **nothing is ever overwritten**, a file that is already there is
 * left as it is, and nothing is ever deleted. The checksum and the modification date go with the file, so the server checks what it received and the copy keeps its date.
 */
internal class NextcloudTarget(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val username: String,
    appPassword: String,
    private val folder: String,
) : BackupTarget {
    private val authorization = Credentials.basic(username, appPassword, Charsets.UTF_8)
    private val known = HashSet<String>()

    private fun files(): HttpUrl.Builder = baseUrl.newBuilder().addPathSegments("remote.php/dav/files").addPathSegment(username)

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", authorization).header("User-Agent", "eikon")

    override suspend fun check(): ServerCheck {
        val propfind = request(files().addPathSegment("").build()).header("Depth", "0")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).build()
        Http.send(client, propfind).use { response ->
            when {
                response.code == 207 -> Unit
                response.code == 404 -> throw BackupException.Misconfigured("Not found. Is this the address of a Nextcloud server, and is the login right?")
                else -> throw Http.failure(response, "PROPFIND")
            }
        }
        ensureDirectories(RemoteNames.segments(folder))
        return ServerCheck("Nextcloud / WebDAV", productVersion())
    }

    private suspend fun productVersion(): String? = try {
        Http.send(client, Request.Builder().url(baseUrl.newBuilder().addPathSegment("status.php").build()).header("User-Agent", "eikon").get().build()).use { response ->
            if (!response.isSuccessful) return null
            val status = MiniJson.parse(response.body.string()).map() ?: return null
            status["version"] as? String
        }
    } catch (_: BackupException) {
        null
    } catch (_: MiniJson.ParseException) {
        null
    }

    override suspend fun upload(file: BackupFile, open: () -> InputStream): UploadResult {
        val segments = RemoteNames.path(folder, file).split('/')
        ensureDirectories(segments.dropLast(1))
        val target = files().apply { segments.forEach(::addPathSegment) }.build()
        if (exists(target)) return UploadResult.ALREADY_THERE
        val put = request(target)
            .header("X-OC-MTime", (file.modifiedAt / MILLIS).toString())
            .header("OC-Checksum", "SHA1:${file.sha1}")
            .header("If-None-Match", "*")
            .put(StreamBody(file.mimeType.toMediaType(), file.length, open))
            .build()
        return Http.send(client, put).use { response ->
            when (response.code) {
                201, 204 -> UploadResult.STORED
                412 -> UploadResult.ALREADY_THERE
                else -> throw Http.failure(response, "PUT")
            }
        }
    }

    private suspend fun exists(url: HttpUrl): Boolean = Http.send(client, request(url).head().build()).use { response ->
        when {
            response.code == 200 -> true
            response.code == 404 -> false
            else -> throw Http.failure(response, "HEAD")
        }
    }

    /** Makes each level of the folder in turn; one that is already there answers 405, which is fine. Levels made or seen are remembered so a run does not ask twice. */
    private suspend fun ensureDirectories(segments: List<String>) {
        for (depth in 1..segments.size) {
            val level = segments.take(depth)
            if (known.add(level.joinToString("/"))) makeCollection(level)
        }
    }

    private suspend fun makeCollection(level: List<String>) {
        val url = files().apply { level.forEach(::addPathSegment) }.build()
        Http.send(client, request(url).method("MKCOL", null).build()).use { response ->
            if (response.code == 201 || response.code == 405) return
            known.remove(level.joinToString("/"))
            throw Http.failure(response, "MKCOL")
        }
    }

    private companion object {
        const val MILLIS = 1000L
        val XML = "application/xml".toMediaType()
        const val PROPFIND_BODY = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
    }
}
