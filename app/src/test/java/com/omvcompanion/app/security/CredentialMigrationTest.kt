package com.omvcompanion.app.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.omvcompanion.app.data.local.*
import com.omvcompanion.app.data.model.ServerProfile
import com.omvcompanion.app.data.repo.*
import com.omvcompanion.app.data.rpc.*
import com.omvcompanion.app.notifications.monitoringSignInAlert
import com.omvcompanion.app.ui.captureProtectionText
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class CredentialMigrationTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        val value = MutableStateFlow(initial)
        override val data: Flow<Preferences> = value
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(value.value).also { value.value = it }
    }
    private val json = Json { encodeDefaults = true }
    private val serializer = ListSerializer(ServerProfile.serializer())
    private val key = stringPreferencesKey("servers_json")
    private fun store(vararg profiles: ServerProfile) = MemoryStore(mutablePreferencesOf(key to json.encodeToString(serializer, profiles.toList())))
    private fun cipher(): CredentialCipher = mock(CredentialCipher::class.java).also { cipher ->
        `when`(cipher.decrypt(anyString())).thenAnswer { invocation ->
            val text = invocation.getArgument<String>(0)
            if (text == "locked") throw CredentialUnavailableException()
            if (text.startsWith("v2:")) text.removePrefix("v2:") else "decrypted-$text"
        }
        `when`(cipher.encrypt(anyString())).thenAnswer { "v2:${it.getArgument<String>(0)}" }
    }

    @Test fun `credential failure triggers sign in alert without converting network failures`() {
        val alert = monitoringSignInAlert(CredentialUnavailableException())!!
        assertEquals("sign-in", alert.key)
        assertEquals("Monitoring needs sign-in", alert.title)
        assertTrue(alert.message.contains("re-enter"))
        assertNotNull(monitoringSignInAlert(OmvAuthException("expired")))
        assertNotNull(monitoringSignInAlert(UntrustedCertificateException(CertificateInfo("nas", "nas", "pin", 0, 1), false)))
        assertNull(monitoringSignInAlert(OmvConnectionException("offline")))
    }
    @Test fun `only nonempty legacy secrets need migration`() {
        assertFalse(needsSecretMigration(null))
        assertFalse(needsSecretMigration(""))
        assertFalse(needsSecretMigration("v2:ciphertext"))
        assertTrue(needsSecretMigration("legacy-ciphertext"))
    }
    @Test fun `admin and SSH migrate atomically preserving profile fields before key deletion`() = runTest {
        val profile = ServerProfile(id = "nas", name = "NAS", url = "https://nas.local", altUrl = "https://vpn.local", username = "admin", encryptedPassword = "admin", sshUsername = "operator", sshFingerprint = "verified", sshEncryptedPassword = "ssh", pinnedCertSha256 = "pinned")
        val store = store(profile); val cipher = cipher()
        doAnswer {
            val saved = json.decodeFromString(serializer, store.value.value[key]!!).single()
            assertFalse(hasLegacySecrets(saved)); null
        }.`when`(cipher).deleteLegacyKey()
        val repo = ServerRepository(store, json, cipher)
        assertEquals("decrypted-admin", repo.password(profile))
        assertEquals(profile.copy(encryptedPassword = "v2:decrypted-admin", sshEncryptedPassword = "v2:decrypted-ssh"), repo.get("nas"))
        verify(cipher).deleteLegacyKey()
        repo.upsert(profile.copy(name = "Renamed"))
        val renamed = repo.get("nas")!!
        assertEquals("Renamed", renamed.name)
        assertEquals("v2:decrypted-admin", renamed.encryptedPassword)
        assertEquals("v2:decrypted-ssh", renamed.sshEncryptedPassword)
    }
    @Test fun `unreadable legacy secret is retained and prevents key deletion until retry succeeds`() = runTest {
        val first = ServerProfile(id = "a", name = "A", url = "https://a.local", username = "admin", encryptedPassword = "admin")
        val locked = first.copy(id = "b", name = "B", encryptedPassword = "locked")
        val store = store(first, locked); val cipher = cipher(); val repo = ServerRepository(store, json, cipher)
        repo.migrateLegacySecrets(first)
        assertEquals("locked", repo.migrateLegacySecrets(locked).encryptedPassword)
        verify(cipher, never()).deleteLegacyKey()
        doReturn("recovered").`when`(cipher).decrypt("locked")
        assertEquals("v2:recovered", repo.migrateLegacySecrets(locked).encryptedPassword)
        verify(cipher).deleteLegacyKey()
    }
    @Test fun `already migrated values and missing SSH password are not reencrypted`() {
        val profile = ServerProfile(name = "NAS", url = "https://nas.local", username = "admin", encryptedPassword = "v2:secret")
        assertEquals(profile, migrateProfileSecrets(profile, { fail("Decrypted unnecessarily"); "" }, { fail("Encrypted unnecessarily"); "" }))
    }
    @Test fun `session cache rebuilds after migration and then reuses migrated client`() = runTest {
        val profile = ServerProfile(id = "nas", name = "NAS", url = "https://nas.local", username = "admin", encryptedPassword = "admin")
        val repo = ServerRepository(store(profile), json, cipher())
        val sessions = SessionManager(repo)
        val before = sessions.client("nas")
        val after = sessions.client("nas")
        assertNotSame(before, after)
        assertSame(after, sessions.client("nas"))
        assertTrue(repo.get("nas")!!.encryptedPassword.startsWith("v2:"))
    }
    @Test fun `privacy text reflects both settings and mandatory sensitive screen protection`() {
        for ((biometric, hidden) in listOf(true to true, true to false, false to true)) {
            assertEquals("Screen captures, screen mirroring and Recents thumbnails are hidden.", captureProtectionText(biometric, hidden))
        }
        assertEquals("Screen captures are allowed; the SSH and sign-in screens are always hidden.", captureProtectionText(false, false))
    }
}
