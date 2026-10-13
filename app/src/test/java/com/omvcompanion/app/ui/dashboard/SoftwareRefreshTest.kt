package com.omvcompanion.app.ui.dashboard

import com.omvcompanion.app.data.repo.*
import com.omvcompanion.app.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SoftwareRefreshTest {
    private class Source : SoftwareSource {
        var cache = listOf(AptPackage("test-package", "2", "stable"))
        var installed = false
        var failRefresh = false
        val calls = mutableListOf<String>()
        override suspend fun getUpdatablePackages(serverId: String) = cache.also { calls.add("list") }
        override suspend fun getPlugins(serverId: String) = emptyList<PluginPackage>()
        override suspend fun installUpdates(serverId: String, packages: List<String>, onOutput: (String) -> Unit) { calls.add("install"); installed = true }
        override suspend fun installPlugin(serverId: String, packageName: String, onOutput: (String) -> Unit) { installed = true }
        override suspend fun checkUpdates(serverId: String, onOutput: (String) -> Unit) {
            calls.add("refresh")
            if (failRefresh) error("Index unavailable")
            if (installed) cache = emptyList()
        }
    }
    @Test fun `installation refreshes stale index before reading packages`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source(); val vm = SoftwareViewModel("server", source)
            advanceUntilIdle(); source.calls.clear()
            vm.install(listOf("test-package")); vm.retry(); advanceUntilIdle()
            assertEquals(listOf("install", "refresh", "list"), source.calls)
            assertTrue((vm.uiState.value as UiState.Success).data.updates.isEmpty())
            assertFalse(vm.task.value.busy)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `manual refresh resyncs rather than rereading cached list`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source(); val vm = SoftwareViewModel("server", source)
            advanceUntilIdle(); source.installed = true; source.calls.clear()
            vm.retry(); advanceUntilIdle()
            assertEquals(listOf("refresh", "list"), source.calls)
            assertTrue((vm.uiState.value as UiState.Success).data.updates.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `post install refresh failure preserves installation success and can retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source(); val vm = SoftwareViewModel("server", source)
            advanceUntilIdle(); source.failRefresh = true
            vm.install(listOf("test-package")); advanceUntilIdle()
            assertEquals("Updates installed", vm.task.value.title)
            assertTrue(vm.task.value.error!!.contains("refreshing the package list failed"))
            source.failRefresh = false; vm.retry(); advanceUntilIdle()
            assertNull(vm.task.value.error)
            assertTrue((vm.uiState.value as UiState.Success).data.updates.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
}
