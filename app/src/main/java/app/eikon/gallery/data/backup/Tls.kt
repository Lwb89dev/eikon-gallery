package app.eikon.gallery.data.backup

import android.annotation.SuppressLint
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier

/**
 * What the phone trusts, and the one thing the user can add to it. A server is trusted when its certificate chains to an authority that ships with the phone, exactly like
 * anywhere else; user-installed authorities are not consulted. A home server usually has no such certificate, so the user can pin **one certificate**, after comparing its
 * fingerprint with the server's own: from then on that certificate, and nothing else, is accepted for this server. A pin is never a way to switch checking off.
 */
internal object Tls {
    /** SHA-256 of the certificate, in lower-case hex without separators. */
    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    /** The same, as people write it: `AB:CD:…`. */
    fun display(fingerprint: String): String = fingerprint.uppercase().chunked(2).joinToString(":")

    /** What was typed or pasted, brought to the form of [fingerprint] (any separators and case), or null if it is not 64 hex digits. */
    fun normalize(text: String): String? = text.filter { it.isLetterOrDigit() }.lowercase().takeIf { it.length == SHA256_HEX && it.all { c -> c in "0123456789abcdef" } }

    fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** The client both servers are talked to with: TLS only, the pin if there is one, no redirects (a redirect could take the credential somewhere else), generous timeouts for big files. */
    fun client(pin: String?, system: X509TrustManager = systemTrustManager()): OkHttpClient {
        val trust = PinnedTrust(system, pin)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier(PinnedHostnames(pin))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CONNECT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(WRITE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Reads the certificate a server presents, **without trusting it and without sending anything**: the handshake is started, the certificate is written down and refused, so
     * the handshake fails and no connection is ever established (there is no "accept everything" trust manager anywhere). It exists so the user can be shown a fingerprint to
     * compare before deciding to pin it; nothing about the server is believed because of it.
     */
    fun probe(host: String, port: Int, timeoutMs: Int = PROBE_TIMEOUT_MS): PresentedCertificate {
        val recorder = RecordingTrust()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(recorder), null) }
        // Closed whatever happens, including when the connection itself cannot be made.
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            handshake(context, socket, host, port)
        }
        val leaf = recorder.leaf ?: throw IOException("the server presented no certificate")
        return PresentedCertificate(
            fingerprint = fingerprint(leaf),
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notAfter = leaf.notAfter.time,
            selfSigned = leaf.subjectX500Principal == leaf.issuerX500Principal,
        )
    }

    private fun handshake(context: SSLContext, socket: Socket, host: String, port: Int) {
        try {
            (context.socketFactory.createSocket(socket, host, port, true) as SSLSocket).use { it.startHandshake() }
        } catch (_: SSLException) {
            // Expected: the recorder always refuses, so the handshake never completes and nothing is ever sent.
        }
    }

    private const val SHA256_HEX = 64
    private const val CONNECT_SECONDS = 20L
    private const val READ_SECONDS = 300L
    private const val WRITE_SECONDS = 60L
    private const val PROBE_TIMEOUT_MS = 15_000
}

/** A certificate as a server presented it, for showing to the user. */
class PresentedCertificate(val fingerprint: String, val subject: String, val issuer: String, val notAfter: Long, val selfSigned: Boolean)

/** Writes down the certificate a server presents and **always refuses it**: used only to let the user see a fingerprint (see [Tls.probe]); it can never make a connection succeed. */
@SuppressLint("CustomX509TrustManager") // it trusts nothing: every chain is refused after being recorded
private class RecordingTrust : X509TrustManager {
    var leaf: X509Certificate? = null
        private set

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        leaf = chain.firstOrNull()
        throw CertificateException("recorded, not trusted")
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("no client certificates")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** The phone's own trust first; only if that says no and the user pinned a certificate that is in the chain is the chain accepted. */
@SuppressLint("CustomX509TrustManager") // delegates to the platform's trust manager; the pin only ever adds one certificate the user compared by fingerprint
internal class PinnedTrust(private val system: X509TrustManager, private val pin: String?) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (untrusted: CertificateException) {
            if (pin == null || chain.none { Tls.fingerprint(it) == pin }) throw untrusted
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/**
 * The name in the certificate must match the address, as usual. The one exception is a server's own certificate that the user pinned: its name is what it is (a home
 * server reached by an IP address or a local name rarely has the right one), and the pin says exactly which certificate is meant. A pin of an authority does not
 * buy that exception, so it cannot be used to vouch for other names.
 */
internal class PinnedHostnames(private val pin: String?) : HostnameVerifier {
    override fun verify(host: String, session: SSLSession): Boolean {
        if (OkHostnameVerifier.verify(host, session)) return true
        if (pin == null) return false
        val leaf = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: SSLPeerUnverifiedException) {
            null
        }
        return leaf != null && Tls.fingerprint(leaf) == pin
    }
}
