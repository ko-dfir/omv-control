package com.omvcompanion.app

import android.content.Context
import com.omvcompanion.app.data.local.CredentialCipher
import com.omvcompanion.app.data.repo.ServerRepository
import com.omvcompanion.app.data.repo.SessionManager
import com.omvcompanion.app.data.repo.SystemRepository
import com.omvcompanion.app.data.repo.serverDataStore
import kotlinx.serialization.json.Json

/** Manual dependency container (kept simple on purpose; can be swapped for Hilt later). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val serverRepository = ServerRepository(appContext.serverDataStore, json, CredentialCipher())
    val sessionManager = SessionManager(serverRepository) { sshRunner.disconnect(it) }
    val sshRunner = com.omvcompanion.app.data.repo.SshRunner(serverRepository)
    val performanceRepository = com.omvcompanion.app.data.repo.PerformanceRepository(appContext, serverRepository, sshRunner)
    val systemRepository = SystemRepository(sessionManager) { id, info -> performanceRepository.recordLater(id, info) }
    val storageRepository = com.omvcompanion.app.data.repo.StorageRepository(sessionManager)
    val fileBrowserRepository = com.omvcompanion.app.data.repo.FileBrowserRepository(serverRepository, storageRepository, sshRunner)
    val containerEditorRepository = com.omvcompanion.app.data.repo.ContainerEditorRepository(appContext, sessionManager, sshRunner)
    val servicesRepository = com.omvcompanion.app.data.repo.ServicesRepository(sessionManager, sshRunner)
    val managementRepository = com.omvcompanion.app.data.repo.ManagementRepository(sessionManager)
    val softwareRepository = com.omvcompanion.app.data.repo.SoftwareRepository(sessionManager)
    val hardwareMonitoringRepository = com.omvcompanion.app.data.repo.HardwareMonitoringRepository(appContext, serverRepository, sshRunner, softwareRepository)
}
