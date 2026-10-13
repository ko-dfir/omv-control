package com.omvcompanion.app.data.rpc

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class RedirectPolicyTest {
    @Test fun `allows same host secure upgrade`() {
        requireSafeRedirect("http://nas.local/rpc.php".toHttpUrl(), "https://nas.local/rpc.php".toHttpUrl())
    }
    @Test fun `rejects password forwarding to other hosts and downgrade`() {
        listOf("https://other.local/rpc.php", "http://nas.local/rpc.php", "https://user:secret@nas.local/rpc.php").forEach {
            try { requireSafeRedirect("https://nas.local/rpc.php".toHttpUrl(), it.toHttpUrl()); fail("Allowed $it") }
            catch (_: OmvConnectionException) { }
        }
    }
}
