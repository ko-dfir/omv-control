package com.omvcompanion.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** A saved OMV server. The password is stored encrypted with an Android Keystore key. */
@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Primary address, e.g. `https://192.168.10.221`. */
    val url: String,
    /** Optional fallback address (VPN/Tailscale or public reverse proxy). */
    val altUrl: String? = null,
    val username: String,
    val encryptedPassword: String = "",
    val sshUsername: String? = null,
    val sshPort: Int = 22,
    val sshFingerprint: String? = null,
    val sshEncryptedPassword: String? = null,
    /** SHA-256 fingerprint of a user-approved (e.g. self-signed) certificate. */
    val pinnedCertSha256: String? = null,
    val allowInsecureHttp: Boolean = false,
) {
    val addresses: List<String> get() = listOfNotNull(url, altUrl?.takeIf { it.isNotBlank() })
}
