package io.github.aritouma1205.quietintentlauncher

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import io.github.aritouma1205.quietintentlauncher.apps.AppCatalog
import io.github.aritouma1205.quietintentlauncher.home.HomeRole
import io.github.aritouma1205.quietintentlauncher.launch.TargetLauncher
import io.github.aritouma1205.quietintentlauncher.settings.SettingsData
import io.github.aritouma1205.quietintentlauncher.settings.SettingsSerializer
import io.github.aritouma1205.quietintentlauncher.settings.SettingsStore
import io.github.aritouma1205.quietintentlauncher.today.TodayDataProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-wired service holder (design 14: no DI framework). One instance per
 * process, owned by [QuietLauncherApp].
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsSerializer = SettingsSerializer()
    val settingsFile = context.dataStoreFile("quiet_settings.json")
    val settingsStore = SettingsStore(
        scope = appScope,
        serializer = settingsSerializer,
        fileProvider = { settingsFile },
    ) { storeScope ->
        DataStoreFactory.create(
            serializer = settingsSerializer,
            scope = storeScope,
            produceFile = { settingsFile },
        )
    }

    val homeRole = HomeRole(context)
    val appCatalog = AppCatalog(context, appScope)
    val targetLauncher = TargetLauncher(context)
    val todayData = TodayDataProvider(context)

    /** Any accessibility service on (design 8.3: GLANCE must not time out). */
    val isAccessibilityActive: () -> Boolean = {
        val am = context.getSystemService(AccessibilityManager::class.java)
        am != null && am.isEnabled
    }

    fun start() {
        settingsStore.start()
        appCatalog.start()
    }
}

class QuietLauncherApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
