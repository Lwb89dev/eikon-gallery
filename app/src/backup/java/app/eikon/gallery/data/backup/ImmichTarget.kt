package app.eikon.gallery.data.backup

import app.eikon.gallery.data.backup.MiniJson.list
import app.eikon.gallery.data.backup.MiniJson.map
import java.io.InputStream
import java.time.Instant
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

/**
 * Immich (what Umbrel installs for Android): uploads with an API key, and lets Immich say which files it already has, by SHA-1, so nothing is sent twice, from this phone
 * or another. Written against the API of Immich 1.x, 2.x and 3.x, whose upload form differs: the form sent carries what each of them asks for and the ones that do not
 * know a field ignore it. Immich is only ever *added to*; nothing here deletes or changes what it holds.
 */
internal class ImmichTarget(
    private val client: OkHttpClient,
    baseUrl: HttpUrl,
    private val apiKey: String,
    private val deviceId: String,
) : BackupTarget {
    private val api = baseUrl.newBuilder().addPathSegment("api").build()

    private fun url(vararg segments: String): HttpUrl = api.newBuilder().apply { segments.forEach(::addPathSegments) }.build()

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("x-api-key", apiKey).header("Accept", "application/json").header("User-Agent", "eikon")

    override suspend fun check(): ServerCheck {
        ping()
        val version = version()
        validateKey()
        return ServerCheck("Immich", version, permissionWarnings())
    }

    private suspend fun ping() {
        Http.send(client, request(url("server/ping")).get().build()).use { response ->
            if (!response.isSuccessful) throw Http.failure(response, "server/ping").asAddressProblem("Immich")
            val body = json(response)
            if (body.map()?.get("res") != "pong") throw BackupException.Misconfigured("This address does not answer like an Immich server.")
        }
    }

    private suspend fun version(): String? = Http.send(client, request(url("server/version")).get().build()).use { response ->
        if (!response.isSuccessful) return null
        val v = json(response).map() ?: return null
        listOf("major", "minor", "patch").joinToString(".") { (v[it] as? Double)?.toInt()?.toString() ?: "?" }
    }

    private suspend fun validateKey() {
        val post = request(url("auth/validateToken")).post("".toRequestBody(null)).build()
        Http.send(client, post).use { response ->
            if (response.code == 401 || response.code == 403) throw BackupException.Unauthorized("Immich did not accept the API key.")
            if (!response.isSuccessful) throw Http.failure(response, "auth/validateToken")
            if (json(response).map()?.get("authStatus") != true) throw BackupException.Unauthorized("Immich did not accept the API key.")
        }
    }

    /** Best effort: whether the key may upload. An Immich too old to say, or a failure to ask, is not a reason to refuse. */
    private suspend fun permissionWarnings(): List<String> = try {
        Http.send(client, request(url("api-keys/me")).get().build()).use { response ->
            if (!response.isSuccessful) return emptyList()
            val permissions = json(response).map()?.get("permissions").list()?.filterIsInstance<String>() ?: return emptyList()
            if ("all" in permissions || "asset.upload" in permissions) emptyList() else listOf("This API key does not have the permission asset.upload, so uploads will be refused.")
        }
    } catch (_: BackupException) {
        emptyList()
    }

    override suspend fun missing(files: List<BackupFile>): List<BackupFile> {
        if (files.isEmpty()) return files
        val body = files.joinToString(",", "{\"assets\":[", "]}") { "{\"id\":${MiniJson.quote(it.mediaId.toString())},\"checksum\":${MiniJson.quote(it.sha1)}}" }
        val post = request(url("assets/bulk-upload-check")).post(body.toRequestBody(JSON)).build()
        val results = Http.send(client, post).use { response ->
            if (!response.isSuccessful) throw Http.failure(response, "assets/bulk-upload-check")
            json(response).map()?.get("results").list() ?: throw BackupException.Misconfigured("Immich answered in a way this version of eikon does not understand.")
        }
        val already = results.mapNotNull { it.map() }
            .filter { it["action"] == "reject" && it["reason"] == "duplicate" }
            .mapNotNull { it["id"] as? String }.toSet()
        return files.filter { it.mediaId.toString() !in already }
    }

    override suspend fun upload(file: BackupFile, open: () -> InputStream): UploadResult {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("deviceAssetId", "$deviceId-${file.mediaId}-${file.modifiedAt}")
            .addFormDataPart("deviceId", deviceId)
            .addFormDataPart("fileCreatedAt", Instant.ofEpochMilli(file.takenAt).toString())
            .addFormDataPart("fileModifiedAt", Instant.ofEpochMilli(file.modifiedAt).toString())
            .addFormDataPart("filename", file.name)
            .addFormDataPart("isFavorite", file.isFavorite.toString())
            .addFormDataPart("metadata", "[]")
            .addFormDataPart("assetData", file.name, StreamBody(file.mimeType.toMediaType(), file.length, open))
            .build()
        val post = request(url("assets")).header("x-immich-checksum", file.sha1).post(form).build()
        return Http.send(client, post).use { response ->
            when (response.code) {
                200 -> UploadResult.ALREADY_THERE
                201 -> UploadResult.STORED
                else -> throw Http.failure(response, "assets")
            }
        }
    }

    private fun json(response: Response): Any? = try {
        MiniJson.parse(response.body.string())
    } catch (_: MiniJson.ParseException) {
        throw BackupException.Misconfigured("This address does not answer like an Immich server.")
    }

    private fun BackupException.asAddressProblem(product: String): BackupException =
        if (this is BackupException.Rejected) BackupException.Misconfigured("This address does not look like a $product server ($message).") else this

    private companion object {
        val JSON: MediaType = "application/json".toMediaType()
    }
}

/** A file's bytes as a request body, with the length known in advance (so the upload is not chunked, which some servers in front of Immich and Nextcloud handle badly), read straight from [open] and never held in memory. */
internal class StreamBody(private val type: MediaType, private val length: Long, private val open: () -> InputStream) : RequestBody() {
    override fun contentType() = type

    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        open().use { input -> sink.writeAll(input.source()) }
    }

    override fun isOneShot() = true
}
