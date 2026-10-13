package com.omvcompanion.app.security

import com.omvcompanion.app.data.model.ServerProfile
import com.omvcompanion.app.data.repo.*
import com.omvcompanion.app.data.rpc.*
import com.omvcompanion.app.ui.authorizedNavigation
import com.omvcompanion.app.ui.servers.ServerEditViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityBehaviorTest {
    @Test fun `changed certificate carries previous identity and fails closed`() {
        val leaf = mock(X509Certificate::class.java)
        `when`(leaf.encoded).thenReturn(byteArrayOf(1, 2, 3))
        `when`(leaf.subjectX500Principal).thenReturn(X500Principal("CN=NAS"))
        `when`(leaf.issuerX500Principal).thenReturn(X500Principal("CN=NAS"))
        `when`(leaf.notBefore).thenReturn(Date(0))
        `when`(leaf.notAfter).thenReturn(Date(Long.MAX_VALUE))
        val system = mock(javax.net.ssl.X509TrustManager::class.java)
        doThrow(java.security.cert.CertificateException("Untrusted")).`when`(system).checkServerTrusted(arrayOf(leaf), "RSA")
        try { PinningTrustManager("AA:BB", system).checkServerTrusted(arrayOf(leaf), "RSA"); fail("Changed pin accepted") }
        catch (e: UntrustedServerCertificate) {
            assertEquals("AA:BB", e.previousSha256)
            assertEquals(CertUtils.sha256(leaf), e.info.sha256)
        }
    }

    @Test fun `saved password never enters state and blank save retains ciphertext`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mock(ServerRepository::class.java)
            val sessions = mock(SessionManager::class.java)
            val system = mock(SystemRepository::class.java)
            val profile = ServerProfile(id = "saved", name = "NAS", url = "https://nas.local", username = "admin", encryptedPassword = "ciphertext")
            `when`(repo.get("saved")).thenReturn(profile)
            val vm = ServerEditViewModel("saved", repo, sessions, system, StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            assertEquals("", vm.state.value.password)
            assertTrue(vm.state.value.hasSavedPassword)
            verify(repo, never()).password(profile)
            vm.save(); advanceUntilIdle()
            verify(repo).upsert(profile)
            verify(repo, never()).encryptPassword(anyString())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `trusting a changed pin updates form without creating connection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mock(ServerRepository::class.java)
            val sessions = mock(SessionManager::class.java)
            val system = mock(SystemRepository::class.java)
            val rpc = mock(OmvRpcClient::class.java)
            val profile = ServerProfile(id = "saved", name = "NAS", url = "https://nas.local", username = "admin", encryptedPassword = "ciphertext", pinnedCertSha256 = "OLD")
            `when`(repo.get("saved")).thenReturn(profile)
            `when`(repo.password(profile)).thenReturn("secret")
            `when`(sessions.create(profile, "secret")).thenReturn(rpc)
            val certificate = CertificateInfo("CN=NAS", "CN=NAS", "NEW", 0, 1)
            `when`(rpc.login()).thenAnswer { throw UntrustedCertificateException(certificate, false, previousSha256 = "OLD") }
            val vm = ServerEditViewModel("saved", repo, sessions, system, StandardTestDispatcher(testScheduler))
            advanceUntilIdle(); vm.signIn(); advanceUntilIdle()
            assertEquals("OLD", vm.state.value.previousSha256)
            clearInvocations(sessions, rpc)
            vm.trustCertificate(); advanceUntilIdle()
            assertEquals("NEW", vm.state.value.pinnedCertSha256)
            assertNull(vm.state.value.untrustedCert)
            assertFalse(vm.state.value.testing)
            verifyNoInteractions(sessions, rpc)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `navigation requires install token and saved server`() {
        assertTrue(authorizedNavigation("secret", "secret", "saved", setOf("saved")))
        assertFalse(authorizedNavigation(null, "secret", "saved", setOf("saved")))
        assertFalse(authorizedNavigation("forged", "secret", "saved", setOf("saved")))
        assertFalse(authorizedNavigation("secret", "secret", "foreign", setOf("saved")))
    }
}
