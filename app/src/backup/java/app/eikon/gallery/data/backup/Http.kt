package app.eikon.gallery.data.backup

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** What both servers' clients share: making a call the coroutine way, and turning every way it can go wrong into a [BackupException] that says what to do about it. */
internal object Http {
    /** Runs [request]; a response of any status is returned (the caller decides what a status means), a failure to get one is a [BackupException]. */
    suspend fun send(client: OkHttpClient, request: Request): Response = try {
        client.newCall(request).await()
    } catch (e: IOException) {
        throw explain(e)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response)
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
        })
    }

    fun explain(e: IOException): BackupException {
        // A name with several addresses is tried address by address and the error reported is the first one's; the certificate problem may be in one that was tried later.
        val all = everything(e).toList()
        val certificateProblem = all.firstOrNull { it is SSLHandshakeException && everything(it).any { cause -> cause is CertificateException } }
        val nameProblem = all.firstOrNull { it is SSLPeerUnverifiedException }
        return when {
            nameProblem != null -> BackupException.Untrusted("The server's certificate does not match its address.", null, e)
            certificateProblem != null -> BackupException.Untrusted("The server's certificate is not signed by an authority this phone trusts.", null, e)
            e is SSLException -> BackupException.Unreachable("Could not set up a secure connection: ${e.message}", e)
            e is UnknownHostException -> BackupException.Unreachable("Could not find the server (${e.message}). Is this phone on the right network?", e)
            e is SocketTimeoutException -> BackupException.Unreachable("The server did not answer in time.", e)
            e is ConnectException -> BackupException.Unreachable("Could not connect to the server: ${e.message}", e)
            else -> BackupException.Unreachable(e.message ?: e.javaClass.simpleName, e)
        }
    }

    /** [e], what caused it and what was suppressed along the way, and so on down. */
    private fun everything(e: Throwable): Sequence<Throwable> = sequence {
        val seen = HashSet<Throwable>()
        val queue = ArrayDeque(listOf(e))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            yield(next)
            next.cause?.let(queue::addLast)
            next.suppressed.forEach(queue::addLast)
        }
    }

    /**
     * What a status means for the *call*, when it is not one the caller was waiting for: the credential was not accepted, the server is asking to go somewhere else (never
     * followed: the address must be the final one), it failed for now, or it refused this request.
     */
    fun failure(response: Response, doing: String): BackupException {
        val status = response.code
        return when {
            status == 401 || status == 403 -> BackupException.Unauthorized("The server did not accept the credential ($doing: HTTP $status).")
            status in REDIRECTS -> BackupException.Misconfigured(
                "The server redirects to ${response.header("Location") ?: "another address"}. Use the final https address of the server.",
            )
            status == 408 || status == 429 || status >= SERVER_ERROR -> BackupException.Unreachable("The server is not able to take this now ($doing: HTTP $status).")
            status == 413 -> BackupException.Rejected("The file is larger than the server accepts (HTTP 413).")
            else -> BackupException.Rejected("The server refused it ($doing: HTTP $status).")
        }
    }

    private val REDIRECTS = 300..399
    private const val SERVER_ERROR = 500
}
