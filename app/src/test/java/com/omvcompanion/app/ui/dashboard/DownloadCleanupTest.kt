package com.omvcompanion.app.ui.dashboard

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DownloadCleanupTest {
    @Test fun `failed ZIP is removed and failure explained`() = runTest {
        var removed = false
        try { cleanupIncompleteDownload(true, { error("Lost connection") }, { removed = true; true }); fail("Failure hidden") }
        catch (e: IOException) { assertTrue(e.message!!.contains("Download failed; the incomplete ZIP was removed.")) }
        assertTrue(removed)
    }
    @Test fun `cancelled file attempts cleanup without claiming deletion if provider refuses`() = runTest {
        var attempted = false
        try { cleanupIncompleteDownload(false, { throw CancellationException("cancelled") }, { attempted = true; false }); fail("Cancellation hidden") }
        catch (e: CancellationException) { assertTrue(e.message!!.contains("could not remove")) }
        assertTrue(attempted)
    }
    @Test fun `successful download keeps destination`() = runTest {
        cleanupIncompleteDownload(true, {}, { fail("Successful download deleted"); false })
    }
}
