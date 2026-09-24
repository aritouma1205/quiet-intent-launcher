package io.github.aritouma1205.quietintentlauncher

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import io.github.aritouma1205.quietintentlauncher.apps.AppCatalog
import io.github.aritouma1205.quietintentlauncher.apps.ShortcutCatalog
import io.github.aritouma1205.quietintentlauncher.home.HomeRole
import io.github.aritouma1205.quietintentlauncher.launch.TargetLauncher
import io.github.aritouma1205.quietintentlauncher.recent.RecentSerializer
import io.github.aritouma1205.quietintentlauncher.recent.RecentStore
import io.github.aritouma1205.quietintentlauncher.search.ExternalSearch
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
    val shortcutCatalog = ShortcutCatalog(context) { homeRole.isHeld() }
    val targetLauncher = TargetLauncher(
        context = context,
        isHomeRoleHeld = { homeRole.isHeld() },
        shortcutCatalog = shortcutCatalog,
    )
    val todayData = TodayDataProvider(context)

    /**
     * 「最近」の履歴ストア（design 9）。設定とは別ファイル — 起動記録は
     * 行動の設定に属さず、記録停止で独立に消せる。検索語は保存しない。
     */
    val recentSerializer = RecentSerializer()
    val recentFile = context.dataStoreFile("quiet_recents.json")
    val recentStore = RecentStore(
        scope = appScope,
        serializer = recentSerializer,
        fileProvider = { recentFile },
    ) { storeScope ->
        DataStoreFactory.create(
            serializer = recentSerializer,
            scope = storeScope,
            produceFile = { recentFile },
        )
    }

    /** External search / share hand-off (design 9.2). */
    val externalSearch = ExternalSearch(context)

    /** String lookup for search-index building off the UI layer. */
    val stringFor: (Int) -> String = { context.getString(it) }

    /** Any accessibility service on (design 8.3: GLANCE must not time out). */
    val isAccessibilityActive: () -> Boolean = {
        val am = context.getSystemService(AccessibilityManager::class.java)
        am != null && am.isEnabled
    }

    fun start() {
        settingsStore.start()
        appCatalog.start()
        recentStore.start()
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
