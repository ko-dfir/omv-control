package com.omvcompanion.app.security

import com.omvcompanion.app.data.local.*
import com.omvcompanion.app.data.model.ServerProfile
import com.omvcompanion.app.data.repo.*
import com.omvcompanion.app.data.rpc.*
import com.omvcompanion.app.ui.servers.httpConsentError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

class TrustAndCredentialTest {
    @Test fun `pinned LAN certificate does not block a CA trusted fallback chain`() {
        val leaf = mock(X509Certificate::class.java)
        `when`(leaf.encoded).thenReturn(byteArrayOf(3, 2, 1))
        val system = mock(X509TrustManager::class.java)
        PinningTrustManager("LAN-PIN", system).checkServerTrusted(arrayOf(leaf), "RSA")
        verify(system).checkServerTrusted(arrayOf(leaf), "RSA")
        val session = mock(SSLSession::class.java)
        `when`(session.peerCertificates).thenReturn(arrayOf(leaf))
        val delegate = mock(HostnameVerifier::class.java)
        `when`(delegate.verify("public.nas.local", session)).thenReturn(true)
        assertTrue(PinningHostnameVerifier("LAN-PIN", delegate).verify("public.nas.local", session))
        verify(delegate).verify("public.nas.local", session)
    }
    @Test fun `matching LAN pin remains accepted without system trust`() {
        val leaf = mock(X509Certificate::class.java)
        `when`(leaf.encoded).thenReturn(byteArrayOf(1, 2, 3))
        val system = mock(X509TrustManager::class.java)
        PinningTrustManager(CertUtils.sha256(leaf), system).checkServerTrusted(arrayOf(leaf), "RSA")
        verifyNoInteractions(system)
    }
    @Test fun `no saved data differs from unreadable saved data`() {
        assertEquals("", decryptSavedSecret("") { fail("Empty data decrypted"); "unused" })
        try { decryptSavedSecret("ciphertext") { error("Key unavailable") }; fail("Failure became empty password") }
        catch (e: CredentialUnavailableException) { assertTrue(e.message!!.contains("re-enter")) }
        try { decryptSavedSecret("ciphertext") { "" }; fail("Empty plaintext accepted") }
        catch (_: CredentialUnavailableException) { }
        assertEquals("secret", decryptSavedSecret("ciphertext") { "secret" })
    }
    @Test fun `failed decrypt never poisons session cache and subsequent retry succeeds`() = runTest {
        val repo = mock(ServerRepository::class.java)
        val profile = ServerProfile(id = "nas", name = "NAS", url = "https://nas.local", username = "admin", encryptedPassword = "ciphertext")
        `when`(repo.get("nas")).thenReturn(profile)
        `when`(repo.password(profile)).thenAnswer { throw CredentialUnavailableException() }
        val manager = SessionManager(repo)
        try { manager.client("nas"); fail("Unreadable password cached") } catch (_: CredentialUnavailableException) { }
        doReturn("secret").`when`(repo).password(profile)
        val good = manager.client("nas")
        assertSame(good, manager.client("nas"))
        verify(repo, times(2)).password(profile)
    }
    @Test fun `empty decoded or explicit password cannot become a saved server client`() = runTest {
        val repo = mock(ServerRepository::class.java)
        val profile = ServerProfile(name = "NAS", url = "https://nas.local", username = "admin", encryptedPassword = "ciphertext")
        `when`(repo.password(profile)).thenReturn("")
        val manager = SessionManager(repo)
        try { manager.create(profile); fail("Empty decoded password accepted") } catch (_: CredentialUnavailableException) { }
        try { manager.create(profile, ""); fail("Empty explicit password accepted") } catch (_: CredentialUnavailableException) { }
    }
    @Test fun `legacy HTTP consent explanation remains next to checkbox until opted in`() {
        assertTrue(httpConsentError("http://nas.local", "", false, null)!!.contains("checkbox"))
        val error = "Unencrypted HTTP is disabled for this server"
        assertEquals(error, httpConsentError("https://nas.local", "http://backup.local", false, error))
        assertNull(httpConsentError("http://nas.local", "", true, error))
        assertNull(httpConsentError("https://nas.local", "", false, error))
    }
}
