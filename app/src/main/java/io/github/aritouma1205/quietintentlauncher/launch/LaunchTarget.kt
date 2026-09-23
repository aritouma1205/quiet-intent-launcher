package io.github.aritouma1205.quietintentlauncher.launch

import android.content.ComponentName
import android.os.UserHandle
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget

/**
 * A validated destination for the shared launch path (design 14:
 * app catalog -> Target -> shared launcher, reused by DO / search /
 * Context Slots in later stages).
 *
 * Supported kinds: app activity, public app shortcut and HTTPS link.
 * System-action targets arrive with the system-action stage — the backing
 * accessibility service does not exist yet, so no assignable type is added.
 */
sealed interface LaunchTarget {
    val key: String

    data class AppActivity(
        val component: ComponentName,
        val user: UserHandle,
    ) : LaunchTarget {
        override val key: String get() = "${component.flattenToShortString()}#$user"
    }

    /** A public shortcut of an installed app (design 6). */
    data class AppShortcut(
        val packageName: String,
        val shortcutId: String,
        val user: UserHandle,
    ) : LaunchTarget {
        override val key: String get() = "shortcut:$packageName/$shortcutId#$user"
    }

    /** A validated https:// URI. */
    data class HttpsLink(
        val url: String,
    ) : LaunchTarget {
        override val key: String get() = "https:$url"
    }
}

/**
 * Converts a persisted [StoredTarget] into a runtime [LaunchTarget] for the
 * personal profile. Returns null when a stored component string is corrupt.
 */
fun StoredTarget.toLaunchTarget(user: UserHandle): LaunchTarget? = when (this) {
    is StoredTarget.App -> ComponentName.unflattenFromString(component)
        ?.let { LaunchTarget.AppActivity(it, user) }
    is StoredTarget.Shortcut ->
        LaunchTarget.AppShortcut(packageName, shortcutId, user)
    is StoredTarget.HttpsLink -> LaunchTarget.HttpsLink(url)
}

/** Result of a launch request through [TargetLauncher]. */
sealed interface LaunchResult {
    /** The OS accepted the launch. */
    data object Success : LaunchResult

    /** The same target is still within its launch window. */
    data object Busy : LaunchResult

    /** The target app is gone or disabled. */
    data object NotFound : LaunchResult

    /**
     * The shortcut is revoked or not currently listed, or the HOME role
     * needed to see it is not held (design 6, 15).
     */
    data object ShortcutUnavailable : LaunchResult

    /** No installed app can handle the HTTPS link. */
    data object NoHandler : LaunchResult

    /** The OS refused or failed to start the target. */
    data class Failure(val cause: String) : LaunchResult
}

/**
 * One successful launch, emitted for the future Recent list (design 9).
 * Persistence of the Recent store is a later stage; this is plumbing only.
 */
data class LaunchRecord(
    val targetKey: String,
    val timestampMillis: Long,
)
