package io.github.aritouma1205.quietintentlauncher.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One launchable activity of the personal profile. [category] carries the
 * declaring application's [android.content.pm.ApplicationInfo.category] for
 * the design-9.3 grid; it is package metadata shared by every launchable
 * activity of the package.
 */
data class AppEntry(
    val component: ComponentName,
    val user: UserHandle,
    val label: String,
    val category: AppCategory = AppCategory.Other,
) {
    val packageName: String get() = component.packageName
    val key: String get() = component.flattenToShortString()
}

/**
 * Launchable apps of the personal profile, kept in memory and maintained by
 * OS change callbacks plus a consistency refresh on foreground (design 15).
 *
 * A null [apps] value means the first load has not finished yet; callers must
 * keep unrelated entries (e.g. settings) reachable while it loads (9.3).
 */
class AppCatalog(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userHandle: UserHandle = Process.myUserHandle()
    private val densityDpi: Int = context.resources.displayMetrics.densityDpi

    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps: StateFlow<List<AppEntry>?> = _apps.asStateFlow()

    private val infoByComponent = ConcurrentHashMap<ComponentName, LauncherActivityInfo>()
    private val listLock = Mutex()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            if (user == userHandle) refreshPackage(packageName)
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {
            if (user == userHandle) refreshPackage(packageName)
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            if (user == userHandle) refreshPackage(packageName)
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            if (user == userHandle) packageNames.forEach(::refreshPackage)
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            if (user == userHandle) packageNames.forEach(::refreshPackage)
        }

        override fun onPackageLoadingProgressChanged(
            packageName: String,
            user: UserHandle,
            progress: Float,
        ) = Unit

        override fun onShortcutsChanged(
            packageName: String,
            shortcuts: MutableList<android.content.pm.ShortcutInfo>,
            user: UserHandle,
        ) = Unit
    }

    fun start() {
        launcherApps.registerCallback(callback)
        reloadAll()
    }

    /** Full re-query; also used as the foreground consistency check (15). */
    fun reloadAll() {
        scope.launch { _apps.value = query(null) }
    }

    /**
     * Test seam: replaces the cached list as if the OS reported a change,
     * so catalog load/update reactions can be exercised deterministically.
     */
    internal fun replaceAppsForTest(entries: List<AppEntry>?) {
        _apps.value = entries
    }

    /** Incremental update for a single package (design 15: 差分更新). */
    private fun refreshPackage(packageName: String) {
        scope.launch {
            listLock.withLock {
                val current = _apps.value ?: return@withLock reloadAll()
                val fresh = query(packageName)
                _apps.value = sorted(current.filter { it.packageName != packageName } + fresh)
            }
        }
    }

    private suspend fun query(packageName: String?): List<AppEntry> =
        withContext(Dispatchers.IO) {
            val infos = launcherApps
                .getActivityList(packageName, userHandle)
                .orEmpty()
            if (packageName == null) {
                infoByComponent.clear()
            } else {
                infoByComponent.keys.removeIf { it.packageName == packageName }
            }
            infos.forEach { infoByComponent[it.componentName] = it }
            val entries = infos.map { info ->
                AppEntry(
                    component = info.componentName,
                    user = info.user,
                    label = info.label?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: info.componentName.packageName,
                    category = AppCategories.fromOsCategory(
                        info.applicationInfo?.category
                            ?: ApplicationInfo.CATEGORY_UNDEFINED,
                    ),
                )
            }
            sorted(entries)
        }

    private fun sorted(entries: List<AppEntry>): List<AppEntry> = AppSort.sorted(entries) {
        AppSortKey(it.label, it.packageName, it.component.className)
    }

    fun loadIcon(entry: AppEntry): Drawable? =
        infoByComponent[entry.component]?.getIcon(densityDpi)

    /** The personal-profile user every stored target launches as. */
    val currentUser: UserHandle get() = userHandle

    /**
     * Resolves a stored flattened component against live OS state (design 6:
     * a deleted or disabled app reads as unavailable without touching the
     * saved configuration). Returns null when the component string is
     * malformed or the activity is not currently launchable.
     */
    suspend fun resolveApp(flattenedComponent: String): AppEntry? =
        withContext(Dispatchers.IO) {
            val component = ComponentName.unflattenFromString(flattenedComponent)
                ?: return@withContext null
            launcherApps.getActivityList(component.packageName, userHandle)
                .firstOrNull { it.componentName == component }
                ?.let { info ->
                    AppEntry(
                        component = info.componentName,
                        user = info.user,
                        label = info.label?.toString()
                            ?.takeIf { it.isNotBlank() }
                            ?: info.componentName.packageName,
                        category = AppCategories.fromOsCategory(
                            info.applicationInfo?.category
                                ?: ApplicationInfo.CATEGORY_UNDEFINED,
                        ),
                    )
                }
        }
}
