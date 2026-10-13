package com.omvcompanion.app.data.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Body of a POST to `/rpc.php`, exactly as sent by the OMV web UI. */
@Serializable
internal data class RpcRequest(
    val service: String,
    val method: String,
    val params: JsonElement = JsonObject(emptyMap()),
    val options: JsonElement? = null,
)

/** Every `/rpc.php` reply has this shape; exactly one of [response]/[error] is meaningful. */
@Serializable
internal data class RpcEnvelope(
    val response: JsonElement? = null,
    val error: RpcErrorBody? = null,
)

@Serializable
internal data class RpcErrorBody(
    val code: Int = 0,
    val message: String = "",
    val trace: String? = null,
)

/** Details of a server certificate shown to the user before pinning it. */
data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val sha256: String,
    val notBefore: Long,
    val notAfter: Long,
)

open class OmvException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server returned an RPC error for a specific call. */
class OmvRpcException(
    val code: Int,
    message: String,
    val httpStatus: Int,
    val trace: String? = null,
) : OmvException(message)

/** Wrong credentials, or the session could not be (re-)established. */
open class OmvAuthException(message: String) : OmvException(message)
class OmvTotpRequiredException : OmvAuthException("Session requires verification. Sign in again with your authenticator code.")

/** Network level failure (host unreachable, timeout, not an OMV server, ...). */
class OmvConnectionException(message: String, cause: Throwable? = null) : OmvException(message, cause)

/** TLS certificate is not trusted by the system and not pinned by the user. */
class UntrustedCertificateException(
    val info: CertificateInfo,
    val hostnameMismatch: Boolean,
    val previousSha256: String? = null,
    cause: Throwable? = null,
) : OmvException(
    if (hostnameMismatch) "The server certificate does not match the host name."
    else "The server certificate is not trusted (self-signed or unknown issuer).",
    cause,
)
