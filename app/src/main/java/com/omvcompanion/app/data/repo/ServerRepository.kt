package com.omvcompanion.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omvcompanion.app.data.local.CredentialCipher
import com.omvcompanion.app.data.model.ServerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.omvcompanion.app.data.local.CredentialUnavailableException

val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(name = "servers")

/** Persists server profiles (as JSON in DataStore) with Keystore-encrypted passwords. */
class ServerRepository(
    private val store: DataStore<Preferences>,
    private val json: Json,
    private val cipher: CredentialCipher,
) {
    private val secretWrites = Mutex()
    private val serializer = ListSerializer(ServerProfile.serializer())

    val servers: Flow<List<ServerProfile>> = store.data.map { decode(it) }

    val selectedServerId: Flow<String?> = store.data.map { it[KEY_SELECTED] }
    suspend fun select(id: String) { store.edit { it[KEY_SELECTED] = id } }

    suspend fun get(id: String): ServerProfile? = if (id == "demo") ServerProfile(id = "demo", name = "Demo server", url = "http://demo.invalid", username = "demo") else servers.first().find { it.id == id }

    suspend fun upsert(profile: ServerProfile) = secretWrites.withLock {
        store.edit { prefs ->
            val list = decode(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == profile.id }
            val retained = retainMigratedSecrets(profile, list.getOrNull(index))
            if (index >= 0) list[index] = retained else list.add(retained)
            prefs[KEY_SERVERS] = json.encodeToString(serializer, list)
        }
    }

    suspend fun delete(id: String) = secretWrites.withLock {
        store.edit { prefs ->
            prefs[KEY_SERVERS] = json.encodeToString(serializer, decode(prefs).filterNot { it.id == id })
        }
    }

    fun encryptPassword(plain: String): String = cipher.encrypt(plain)

    suspend fun password(profile: ServerProfile): String = cipher.decrypt(migrateLegacySecrets(profile).encryptedPassword)

    suspend fun sshPassword(profile: ServerProfile): String? = migrateLegacySecrets(profile).sshEncryptedPassword?.takeIf { it.isNotEmpty() }?.let { cipher.decrypt(it) }

    suspend fun migrateLegacySecrets(profile: ServerProfile): ServerProfile = secretWrites.withLock {
        var migrated = profile
        var canDeleteLegacyKey = false
        store.edit { prefs ->
            val list = decode(prefs).toMutableList()
            val index = list.indexOfFirst { it.id == profile.id }
            val current = list.getOrNull(index)
            migrated = migrateProfileSecrets(retainMigratedSecrets(profile, current), cipher::decrypt, cipher::encrypt)
            if (current != null) {
                // A caller may be editing other fields or hold an older credential snapshot.
                // Update only secrets that still match that snapshot; preserve concurrent edits.
                list[index] = current.copy(
                    encryptedPassword = if (current.encryptedPassword == profile.encryptedPassword) migrated.encryptedPassword else current.encryptedPassword,
                    sshEncryptedPassword = if (current.sshEncryptedPassword == profile.sshEncryptedPassword) migrated.sshEncryptedPassword else current.sshEncryptedPassword,
                )
                prefs[KEY_SERVERS] = json.encodeToString(serializer, list)
            }
            canDeleteLegacyKey = !hasLegacySecrets(migrated) && list.none(::hasLegacySecrets)
        }
        // Persist before deleting the old key. All repository writes share this mutex.
        if (canDeleteLegacyKey) runCatching { cipher.deleteLegacyKey() }
        migrated
    }

    suspend fun saveSshCredentials(id: String, username: String, pass: String) {
        val serversList = servers.first()
        val server = serversList.find { it.id == id } ?: return
        val updated = server.copy(
            sshUsername = username,
            sshEncryptedPassword = encryptPassword(pass)
        )
        upsert(updated)
    }

    private fun decode(prefs: Preferences): List<ServerProfile> = decodeServerProfiles(json, prefs[KEY_SERVERS])

    private companion object {
        val KEY_SELECTED = stringPreferencesKey("selected_server")
        val KEY_SERVERS = stringPreferencesKey("servers_json")
    }
}

internal fun decodeServerProfiles(json: Json, raw: String?): List<ServerProfile> {
    if (raw == null) return emptyList()
    return try { json.decodeFromString(ListSerializer(ServerProfile.serializer()), raw) }
    catch (e: kotlinx.serialization.SerializationException) {
        throw IllegalStateException("Saved server settings are unreadable. They have been preserved; restore a valid settings file before saving changes.", e)
    }
}

internal fun needsSecretMigration(value: String?): Boolean = !value.isNullOrEmpty() && !value.startsWith("v2:")
internal fun hasLegacySecrets(profile: ServerProfile): Boolean = needsSecretMigration(profile.encryptedPassword) || needsSecretMigration(profile.sshEncryptedPassword)
internal fun retainMigratedSecrets(incoming: ServerProfile, current: ServerProfile?): ServerProfile = incoming.copy(
    encryptedPassword = if (needsSecretMigration(incoming.encryptedPassword) && current?.encryptedPassword?.startsWith("v2:") == true) current.encryptedPassword else incoming.encryptedPassword,
    sshEncryptedPassword = if (needsSecretMigration(incoming.sshEncryptedPassword) && current?.sshEncryptedPassword?.startsWith("v2:") == true) current.sshEncryptedPassword else incoming.sshEncryptedPassword,
)
internal fun migrateProfileSecrets(profile: ServerProfile, decrypt: (String) -> String, encrypt: (String) -> String): ServerProfile {
    fun migrate(value: String?): String? {
        if (!needsSecretMigration(value)) return value
        return try { encrypt(decrypt(value!!)).also { require(it.startsWith("v2:")) { "Migration did not produce v2 ciphertext" } } }
        catch (_: CredentialUnavailableException) { value }
    }
    return profile.copy(encryptedPassword = migrate(profile.encryptedPassword).orEmpty(), sshEncryptedPassword = migrate(profile.sshEncryptedPassword))
}
