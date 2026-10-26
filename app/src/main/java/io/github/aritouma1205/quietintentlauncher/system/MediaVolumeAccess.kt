package io.github.aritouma1205.quietintentlauncher.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Snapshot of the media-volume state the TODAY slider renders
 * (Issue #49). Kept outside TodayUi: it is OS state with its own refresh
 * triggers, not part of the weather/events snapshot.
 */
data class VolumeUi(
    val volume: Int,
    val max: Int,
)

/**
 * Media volume bridge (Issue #49): STREAM_MUSIC only — read, write and
 * observe, the TorchController-style thin wrapper over AudioManager.
 *
 * - The launcher never keeps its own copy of the level: every read goes
 *   through [current]/[max], and a [set] is confirmed by re-reading the
 *   OS state, never by trusting the requested index.
 * - External changes (hardware keys, other apps) arrive through the
 *   VOLUME_CHANGED broadcast. [observe] registers that
 *   receiver and hands back the unregister function — the ViewModel
 *   keeps the subscription alive only while the TODAY panel is open
 *   (design 15: no background subscription for a hidden slider).
 */
class MediaVolumeAccess(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    companion object {
        // AudioManager.VOLUME_CHANGED_ACTION and EXTRA_VOLUME_STREAM_TYPE
        // are hidden SDK constants; the strings are stable since they
        // shipped, so the literals are used directly.
        internal const val VOLUME_CHANGED_ACTION =
            "android.media.VOLUME_CHANGED_ACTION"
        internal const val EXTRA_VOLUME_STREAM_TYPE =
            "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }

    // ---- Test seams -------------------------------------------------------
    // Instrumentation replaces the AudioManager surface so the slider path
    // stays deterministic and never moves the device's real volume. The
    // writer sees the raw call so tests can pin stream=MUSIC and flags=0.
    internal var streamVolumeReader: () -> Int = {
        audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
    }
    internal var streamMaxReader: () -> Int = {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
    }
    internal var streamVolumeWriter: (streamType: Int, index: Int, flags: Int) -> Unit =
        { stream, index, flags ->
            audioManager?.setStreamVolume(stream, index, flags)
        }

    /** Test-only: restores every seam to the real AudioManager path. */
    internal fun resetSeams() {
        streamVolumeReader = {
            audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        }
        streamMaxReader = {
            audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        }
        streamVolumeWriter = { stream, index, flags ->
            audioManager?.setStreamVolume(stream, index, flags)
        }
    }

    /** Current STREAM_MUSIC index. */
    fun current(): Int = streamVolumeReader()

    /** Maximum STREAM_MUSIC index. */
    fun max(): Int = streamMaxReader()

    /**
     * Sets the STREAM_MUSIC index with flags=0 — no OS volume UI, no
     * sound effect; the slider must not summon the system sheet while a
     * finger is still on it.
     */
    fun set(index: Int) {
        streamVolumeWriter(AudioManager.STREAM_MUSIC, index, 0)
    }

    private var onVolumeChanged: (() -> Unit)? = null

    /**
     * The registered VOLUME_CHANGED receiver. Internal so instrumentation
     * can drive the same code path the OS broadcast drives — the action
     * is a protected broadcast only the system can send, so tests feed
     * the real intent directly (the same rule as
     * [io.github.aritouma1205.quietintentlauncher.torch.TorchController]'s
     * callback seam).
     */
    internal val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != VOLUME_CHANGED_ACTION) return
            val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (stream != AudioManager.STREAM_MUSIC) return
            onVolumeChanged?.invoke()
        }
    }

    /**
     * Registers the VOLUME_CHANGED receiver filtered to STREAM_MUSIC.
     * Every matching broadcast runs [onChanged]; the caller re-reads
     * [current] there so the UI never trusts payload extras. Returns the
     * unregister function; a registration failure degrades to a no-op
     * subscription — the open/foreground re-reads still cover the
     * display.
     */
    fun observe(onChanged: () -> Unit): () -> Unit {
        onVolumeChanged = onChanged
        return try {
            ContextCompat.registerReceiver(
                appContext,
                volumeReceiver,
                IntentFilter(VOLUME_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            val unregister: () -> Unit = {
                onVolumeChanged = null
                appContext.unregisterReceiver(volumeReceiver)
            }
            unregister
        } catch (e: RuntimeException) {
            // A dead media service must never break the panel.
            onVolumeChanged = null
            {}
        }
    }
}
