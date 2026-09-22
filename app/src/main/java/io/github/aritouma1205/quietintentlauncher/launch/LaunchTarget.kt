package io.github.aritouma1205.quietintentlauncher.launch

import android.content.ComponentName
import android.os.UserHandle

/**
 * A validated destination for the shared launch path (design 14:
 * app catalog -> Target -> shared launcher, reused by DO / search /
 * Context Slots in later stages).
 *
 * Stage 1 supports launching installed apps only. Public shortcuts,
 * system actions and HTTPS links are added by later stages.
 */
sealed interface LaunchTarget {
    val key: String

    data class AppActivity(
        val component: ComponentName,
        val user: UserHandle,
    ) : LaunchTarget {
        override val key: String get() = "${component.flattenToShortString()}#$user"
    }
}

/** Result of a launch request through [TargetLauncher]. */
sealed interface LaunchResult {
    /** The OS accepted the launch. */
    data object Success : LaunchResult

    /** The same target is still within its launch window. */
    data object Busy : LaunchResult

    /** The target app is gone or disabled. */
    data object NotFound : LaunchResult

    /** The OS refused or failed to start the target. */
    data class Failure(val cause: String) : LaunchResult
}
