package com.omvcompanion.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class LogRedactionTest {
    @Test fun removesCredentialsAndUrlUserInfo() {
        val result = redactLog("password=private cookie=session123 https://admin:secret@nas.local token:abc")
        assertFalse(result.contains("private")); assertFalse(result.contains("session123")); assertFalse(result.contains("admin:secret")); assertFalse(result.contains("abc"))
    }
}
