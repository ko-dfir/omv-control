package com.omvcompanion.app.security

import org.junit.Assert.*
import org.junit.Test

class SshFingerprintTest {
    @Test fun unknownAndChangedKeysAreRejectedWhileMatchingKeysPass() {
        val key = "known-server-key".toByteArray()
        val first = CheckingHostKeys("nas", null)
        assertEquals(com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED, first.check("nas", key))
        assertFalse(first.rejected!!.changed)
        val pinned = CheckingHostKeys("nas", sshFingerprint(key))
        assertEquals(com.jcraft.jsch.HostKeyRepository.OK, pinned.check("nas", key))
        assertNull(pinned.rejected)
        assertEquals(com.jcraft.jsch.HostKeyRepository.CHANGED, pinned.check("nas", "other-key".toByteArray()))
        assertTrue(pinned.rejected!!.changed)
    }

    @Test fun producesOpenSshSha256FingerprintWithoutPadding() {
        assertEquals("SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0", sshFingerprint("abc".toByteArray()))
        assertNotEquals(sshFingerprint("server-key".toByteArray()), sshFingerprint("changed-key".toByteArray()))
    }
}
