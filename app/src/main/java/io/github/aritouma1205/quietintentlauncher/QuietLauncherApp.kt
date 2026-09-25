package io.github.aritouma1205.quietintentlauncher

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import io.github.aritouma1205.quietintentlauncher.apps.AppCatalog
import io.github.aritouma1205.quietintentlauncher.apps.ShortcutCatalog
import io.github.aritouma1205.quietintentlauncher.calendar.CalendarAccess
import io.github.aritouma1205.quietintentlauncher.home.HomeRole
import io.github.aritouma1205.quietintentlauncher.launch.TargetLauncher
import io.github.aritouma1205.quietintentlauncher.recent.RecentSerializer
import io.github.aritouma1205.quietintentlauncher.recent.RecentStore
import io.github.aritouma1205.quietintentlauncher.search.ExternalSearch
import io.github.aritouma1205.quietintentlauncher.settings.SettingsData
import io.github.aritouma1205.quietintentlauncher.settings.SettingsSerializer
import io.github.aritouma1205.quietintentlauncher.settings.SettingsStore
import io.github.aritouma1205.quietintentlauncher.system.AssistiveServiceTracker
import io.github.aritouma1205.quietintentlauncher.system.QuietSystemService
import io.github.aritouma1205.quietintentlauncher.system.SystemActions
import io.github.aritouma1205.quietintentlauncher.system.ToolLauncher
import io.github.aritouma1205.quietintentlauncher.today.TodayDataProvider
import io.github.aritouma1205.quietintentlauncher.torch.TorchController
import io.github.aritouma1205.quietintentlauncher.weather.WeatherCacheSerializer
import io.github.aritouma1205.quietintentlauncher.weather.WeatherCacheStore
import io.github.aritouma1205.quietintentlauncher.weather.WeatherService
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

    /**
     * Opt-in weather (design 8.2): the cache lives in its own disposable
     * file, deleted together with the region setting when weather is
     * disabled. No clock skew survives in the age check — the snapshot
     * carries the boot marker alongside the fetch times.
     */
    val weatherSerializer = WeatherCacheSerializer()
    val weatherFile = context.dataStoreFile("quiet_weather.json")
    val weatherCacheStore = WeatherCacheStore(
        scope = appScope,
        serializer = weatherSerializer,
        fileProvider = { weatherFile },
    ) { storeScope ->
        DataStoreFactory.create(
            serializer = weatherSerializer,
            scope = storeScope,
            produceFile = { weatherFile },
        )
    }
    val weatherService = WeatherService(appScope, settingsStore, weatherCacheStore)

    /** Read-only Calendar Provider bridge (design 8.1); events stay in memory. */
    val calendarAccess = CalendarAccess(context)

    /**
     * Torch control (design 7, 13): OS-callback state, foreground-only
     * subscription, CAMERA requested at first use. No resident service.
     */
    val torch = TorchController(context)

    /**
     * Optional system operations (design 13): the facade the service binds
     * into; every call re-checks enabled+connected state so a revoked
     * service fails one operation only.
     */
    val systemActions = SystemActions(context)

    /** Timer-list hand-off for the TOOLS row (design 7). */
    val toolLauncher = ToolLauncher(context)

    /** Monotonic + wall clocks, injectable for weather-age tests. */
    val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() }
    val wallClock: () -> Long = System::currentTimeMillis

    /** String lookup for search-index building off the UI layer. */
    val stringFor: (Int) -> String = { context.getString(it) }

    private val accessibilityManager =
        context.getSystemService(AccessibilityManager::class.java)

    private val quietSystemServiceComponent = ComponentName(
        context.packageName,
        QuietSystemService::class.java.name,
    )

    /**
     * Observer-driven cache for the external-assistive-service check —
     * the GLANCE tick must not run a binder RPC every 50ms.
     */
    internal val assistiveTracker = AssistiveServiceTracker(
        context,
        quietSystemServiceComponent,
    )

    /**
     * Touch exploration is on — a TalkBack-style reader is interpreting
     * taps (design 12). While it is, the free-area double tap is suspended
     * so the home gesture never steals the assistive double tap.
     * QuietSystemService requests no accessibility flags, so enabling the
     * launcher's own service alone does NOT turn this on. A failed read
     * resolves to the conservative side (exploration on) so the gesture
     * can never steal an assistive double tap through a broken query.
     */
    internal var isTouchExplorationActive: () -> Boolean = {
        runCatching {
            accessibilityManager?.isTouchExplorationEnabled == true
        }.getOrElse { true }
    }

    /**
     * An assistive service OTHER than QuietSystemService is enabled
     * (design 8.3): such services may be reading or operating the screen,
     * so GLANCE must not time out. Our own service reads nothing and
     * requests nothing — enabling it alone must not pause the timer,
     * which is why the enabled-service list is filtered by component.
     * Reads the observer-maintained cache, not the framework, so the
     * GLANCE tick costs a volatile load instead of a binder RPC; a query
     * failure inside the tracker resolves to true (conservative: keep
     * GLANCE on screen).
     */
    internal var isAssistiveServiceActive: () -> Boolean = {
        assistiveTracker.active()
    }

    fun start() {
        settingsStore.start()
        appCatalog.start()
        recentStore.start()
        weatherCacheStore.start()
        weatherService.start()
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
