package app.eikon.gallery.data.backup

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What the phone trusts and what the user can add to it, on real TLS handshakes against a stand-in server. */
class TlsTest {
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    /** A server presenting [chain] (leaf first), and a trust manager that knows nothing of it, like a phone that has never heard of a home server's certificate. */
    private fun serve(vararg chain: HeldCertificate): MockWebServer {
        val certificates = HandshakeCertificates.Builder().heldCertificate(chain.first(), *chain.drop(1).map { it.certificate }.toTypedArray()).build()
        return MockWebServer().apply {
            useHttps(certificates.sslSocketFactory())
            enqueue(MockResponse(code = 200, body = "hello"))
            start()
            servers += this
        }
    }

    /** The trust of a phone that knows some authorities, none of them this server's. */
    private fun strangerTrust() = HandshakeCertificates.Builder().addTrustedCertificate(HeldCertificate.Builder().certificateAuthority(0).build().certificate).build().trustManager

    private suspend fun get(server: MockWebServer, pin: String?, trust: javax.net.ssl.X509TrustManager = strangerTrust(), host: String = "localhost"): String {
        val client = Tls.client(pin, trust)
        val url = server.url("/").newBuilder().host(host).build()
        return Http.send(client, Request.Builder().url(url).build()).use { it.body.string() }
    }

    private fun selfSigned(name: String = "localhost") = HeldCertificate.Builder().addSubjectAlternativeName(name).build()

    @Test
    fun aCertificateOfAnAuthorityThePhoneKnowsNeedsNoPin() = runTest {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(root).build()
        val server = serve(leaf, root)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager

        assertEquals("hello", get(server, pin = null, trust = trust))
    }

    @Test
    fun aSelfSignedCertificateIsRefusedUntilTheUserPinsIt() = runTest {
        val cert = selfSigned()
        val server = serve(cert)

        try {
            get(server, pin = null)
            fail("accepted an unknown certificate")
        } catch (e: BackupException.Untrusted) {
            assertTrue(e.message!!.contains("certificate"))
        }
    }

    @Test
    fun theSameCertificateIsAcceptedOnceItsFingerprintIsPinned() = runTest {
        val cert = selfSigned()
        val server = serve(cert)

        assertEquals("hello", get(server, pin = Tls.fingerprint(cert.certificate)))
    }

    @Test
    fun aPinOfAnotherCertificateChangesNothing() = runTest {
        val server = serve(selfSigned())
        val other = selfSigned()

        try {
            get(server, pin = Tls.fingerprint(other.certificate))
            fail("accepted a certificate that was not the pinned one")
        } catch (_: BackupException.Untrusted) {
        }
    }

    @Test
    fun aCertificateThatChangedSinceItWasPinnedIsRefused() = runTest {
        val original = selfSigned()
        val replacement = selfSigned()
        val server = serve(replacement)

        try {
            get(server, pin = Tls.fingerprint(original.certificate))
            fail("accepted a replaced certificate")
        } catch (_: BackupException.Untrusted) {
        }
    }

    @Test
    fun aPinnedServersOwnCertificateIsFineEvenIfItsNameIsNotTheAddress() = runTest {
        // A home server reached at one name with a certificate made for another.
        val cert = selfSigned(name = "nas.home.test")
        val server = serve(cert)

        assertEquals("hello", get(server, pin = Tls.fingerprint(cert.certificate), host = "localhost"))
    }

    @Test
    fun withoutAPinAWrongNameIsRefusedEvenForACertificateThePhoneTrusts() = runTest {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().addSubjectAlternativeName("nas.home.test").signedBy(root).build()
        val server = serve(leaf, root)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager

        try {
            get(server, pin = null, trust = trust)
            fail("accepted a certificate made for another name")
        } catch (_: BackupException.Untrusted) {
        }
    }

    @Test
    fun aPinnedAuthorityDoesNotVouchForOtherNames() = runTest {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().addSubjectAlternativeName("someone-else.test").signedBy(root).build()
        val server = serve(leaf, root)

        try {
            get(server, pin = Tls.fingerprint(root.certificate))
            fail("a pinned authority was used to accept a certificate for another name")
        } catch (_: BackupException.Untrusted) {
        }
    }

    @Test
    fun aPinnedAuthorityAcceptsItsCertificateForTheRightName() = runTest {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(root).build()
        val server = serve(leaf, root)

        assertEquals("hello", get(server, pin = Tls.fingerprint(root.certificate)))
    }

    @Test
    fun probingShowsTheCertificateAServerPresentsWithoutTrustingIt() {
        val cert = selfSigned()
        val server = serve(cert)

        val seen = Tls.probe("localhost", server.port)

        assertEquals(Tls.fingerprint(cert.certificate), seen.fingerprint)
        assertTrue(seen.selfSigned)
        assertNotNull(seen.subject)
        // The connection was closed after the handshake: the server never received a request.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aFingerprintIsWrittenTheWayPeopleCompareThem() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals("01:23:45:67:89:AB:CD:EF:" .repeat(4).dropLast(1), Tls.display(hex))
    }

    @Test
    fun aFingerprintTypedWithSeparatorsOrInAnyCaseIsRecognised() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals(hex, Tls.normalize(Tls.display(hex)))
        assertEquals(hex, Tls.normalize(" " + hex.uppercase() + " "))
        assertEquals(hex, Tls.normalize(hex.chunked(2).joinToString(" ")))
    }

    @Test
    fun somethingThatIsNotAFingerprintIsNotRecognised() {
        assertNull(Tls.normalize(""))
        assertNull(Tls.normalize("abc"))
        assertNull(Tls.normalize("zz".repeat(32)))
        assertNull(Tls.normalize("ab".repeat(33)))
    }
}
