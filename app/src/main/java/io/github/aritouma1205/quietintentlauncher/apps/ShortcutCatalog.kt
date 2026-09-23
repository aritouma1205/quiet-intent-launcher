package io.github.aritouma1205.quietintentlauncher.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One currently listed public shortcut of the personal profile. */
data class ShortcutEntry(
    val packageName: String,
    val id: String,
    val label: String,
)

/**
 * Public app shortcuts, resolved against live OS state (design 6, 13).
 * Listing and launching shortcuts requires the HOME role; without it every
 * query answers "unavailable" instead of throwing.
 */
class ShortcutCatalog(
    context: Context,
    private val isHomeRoleHeld: () -> Boolean,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userHandle: UserHandle = Process.myUserHandle()

    private val queryFlags =
        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED

    /**
     * Enabled public shortcuts of [packageName] visible to us right now.
     * Synchronous: callers are the launch path (already a binder call) and
     * [list], which moves it off the UI thread.
     */
    fun listNow(packageName: String): List<ShortcutEntry> {
        if (!isHomeRoleHeld()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setQueryFlags(queryFlags)
        return try {
            launcherApps.getShortcuts(query, userHandle).orEmpty()
                .filter { it.isEnabled }
                .map { info ->
                    ShortcutEntry(
                        packageName = info.`package`,
                        id = info.id,
                        label = info.shortLabel?.toString()
                            ?: info.longLabel?.toString()
                            ?: info.id,
                    )
                }
        } catch (e: SecurityException) {
            // Role lost between the check and the query.
            emptyList()
        }
    }

    suspend fun list(packageName: String): List<ShortcutEntry> =
        withContext(Dispatchers.IO) { listNow(packageName) }

    fun isAvailableNow(packageName: String, shortcutId: String): Boolean =
        listNow(packageName).any { it.id == shortcutId }
}
