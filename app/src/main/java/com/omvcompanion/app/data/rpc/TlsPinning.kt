package com.omvcompanion.app.data.rpc

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object CertUtils {
    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    fun info(cert: X509Certificate) = CertificateInfo(
        subject = cert.subjectX500Principal.name,
        issuer = cert.issuerX500Principal.name,
        sha256 = sha256(cert),
        notBefore = cert.notBefore.time,
        notAfter = cert.notAfter.time,
    )

    fun samePin(a: String?, b: String?): Boolean =
        a != null && b != null && a.replace(":", "").equals(b.replace(":", ""), ignoreCase = true)
}

/** Thrown from the trust manager; carries the rejected leaf certificate. */
class UntrustedServerCertificate(val info: CertificateInfo, cause: Throwable?, val previousSha256: String? = null) :
    CertificateException("Untrusted server certificate", cause)

/**
 * Trusts what the system trusts, plus exactly one user-approved certificate (by SHA-256 of the leaf).
 * This is how self-signed OMV certificates are supported without ever "trusting all".
 */
class PinningTrustManager(private val pinnedSha256: String?,
    private val system: X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        if (CertUtils.samePin(pinnedSha256, CertUtils.sha256(leaf))) return
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            throw UntrustedServerCertificate(CertUtils.info(leaf), e, pinnedSha256)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    fun sslContext(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinningTrustManager), null) }
}

/**
 * A pinned certificate is accepted for any host name (self-signed OMV certs rarely contain the IP).
 * Otherwise falls back to the standard verifier and remembers the rejected certificate for the UI.
 */
class PinningHostnameVerifier(
    private val pinnedSha256: String?,
    private val delegate: HostnameVerifier,
) : HostnameVerifier {
    @Volatile
    var lastRejected: CertificateInfo? = null
        private set

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        if (leaf != null && CertUtils.samePin(pinnedSha256, CertUtils.sha256(leaf))) return true
        val ok = delegate.verify(hostname, session)
        if (!ok && leaf != null) lastRejected = CertUtils.info(leaf)
        return ok
    }
}
