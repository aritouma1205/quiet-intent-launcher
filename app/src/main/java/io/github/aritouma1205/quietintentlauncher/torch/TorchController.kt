package io.github.aritouma1205.quietintentlauncher.torch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observed torch state, always sourced from the OS callback (design 7). */
enum class TorchState {
    /** No camera with a usable flash exists. */
    NoFlash,

    /** The flash exists and is currently off. */
    Off,

    /** The flash exists and is currently on. */
    On,

    /** The flash exists but is unavailable right now (camera in use). */
    Unavailable,
}

/** Result of a toggle request; never a guessed state. */
enum class TorchResult {
    Toggled,

    /** CAMERA permission is required and not granted. */
    PermissionMissing,

    /** No camera with a usable flash exists. */
    NoFlash,

    /** Camera busy / in use / at the concurrent-use limit. */
    Busy,

    /** Any other failure (camera error, disconnected, runtime failure). */
    Failed,
}

/**
 * Torch control via CameraManager (design 7, 13).
 *
 * - The displayed state comes ONLY from the OS torch callback: a successful
 *   [setTorch] call updates nothing by itself; the callback does. That keeps
 *   the row honest when another app owns the camera or turns the torch off.
 * - No persistent service is created. The callback is registered only while
 *   the launcher UI is foregrounded via [setObserving].
 * - CAMERA permission is requested at first use (design 13); [setTorch]
 *   refuses without it.
 */
class TorchController(context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(resolveInitialState())
    val state: StateFlow<TorchState> = _state.asStateFlow()

    private var observing = false

    // ---- Test seams -------------------------------------------------------
    // The emulator image may or may not report a flash unit; tests replace
    // these to exercise every branch deterministically.
    internal var flashCameraIdProvider: () -> String? = { findFlashCameraId() }
    internal var torchModeWriter: (String, Boolean) -> Unit = { id, on ->
        cameraManager.setTorchMode(id, on)
    }
    internal var permissionCheck: (() -> Boolean)? = null
    internal var callbackRegistration: ((Boolean) -> Unit)? = null

    /** Test-only: restores every seam and re-resolves the coarse state. */
    internal fun resetSeams() {
        flashCameraIdProvider = { findFlashCameraId() }
        torchModeWriter = { id, on -> cameraManager.setTorchMode(id, on) }
        permissionCheck = null
        callbackRegistration = null
        _state.value = resolveInitialState()
    }

    // Internal so instrumentation tests can drive the same code path the OS
    // callback drives — the visible state must never come from anywhere else.
    internal val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            val id = flashCameraIdProvider()
            if (cameraId == id) {
                _state.value = if (enabled) TorchState.On else TorchState.Off
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            val id = flashCameraIdProvider()
            if (cameraId == id) {
                _state.value = TorchState.Unavailable
            }
        }
    }

    fun hasPermission(): Boolean =
        permissionCheck?.invoke() ?: (
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            )

    /**
     * Registers/unregisters the OS torch callback (design 15: display
     * subscriptions stop while backgrounded). Registration immediately
     * reports the current torch state, so re-subscribing also re-syncs the
     * row after another app used the camera while we were away.
     */
    fun setObserving(observe: Boolean) {
        if (observe == observing) return
        observing = observe
        val registration = callbackRegistration
        if (registration != null) {
            registration(observe)
            if (observe) refreshStateGuess()
            return
        }
        try {
            if (observe) {
                refreshStateGuess()
                cameraManager.registerTorchCallback(torchCallback, mainHandler)
            } else {
                cameraManager.unregisterTorchCallback(torchCallback)
            }
        } catch (e: RuntimeException) {
            // A dead/absent camera service must never break the launcher.
            if (observe) _state.value = TorchState.Unavailable
        }
    }

    /**
     * Requests the torch transition. The result classifies the immediate
     * outcome; the visible state still arrives through the OS callback.
     */
    fun setTorch(on: Boolean): TorchResult {
        if (!hasPermission()) return TorchResult.PermissionMissing
        val cameraId = flashCameraIdProvider() ?: run {
            _state.value = TorchState.NoFlash
            return TorchResult.NoFlash
        }
        return try {
            torchModeWriter(cameraId, on)
            TorchResult.Toggled
        } catch (e: CameraAccessException) {
            when (e.reason) {
                CameraAccessException.CAMERA_IN_USE,
                CameraAccessException.MAX_CAMERAS_IN_USE,
                -> TorchResult.Busy
                else -> TorchResult.Failed
            }
        } catch (e: IllegalStateException) {
            // "Torch is busy" surfaces as IllegalStateException on some
            // devices when another app owns the camera (design 7).
            TorchResult.Busy
        } catch (e: SecurityException) {
            TorchResult.PermissionMissing
        } catch (e: RuntimeException) {
            TorchResult.Failed
        }
    }

    /** Recompute the coarse state when (re)subscribing or on start. */
    private fun refreshStateGuess() {
        if (_state.value == TorchState.NoFlash || _state.value == TorchState.Unavailable) {
            _state.value = resolveInitialState()
        }
    }

    private fun resolveInitialState(): TorchState =
        // Deliberately not via flashCameraIdProvider: [_state] is built while
        // the constructor runs, before the test seams exist, and the initial
        // value should describe the real hardware anyway.
        if (findFlashCameraId() == null) TorchState.NoFlash else TorchState.Off

    private fun findFlashCameraId(): String? {
        val manager = cameraManager ?: return null
        val ids = try {
            manager.cameraIdList
        } catch (e: CameraAccessException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        var fallback: String? = null
        for (id in ids) {
            val characteristics = try {
                manager.getCameraCharacteristics(id)
            } catch (e: CameraAccessException) {
                continue
            } catch (e: RuntimeException) {
                continue
            }
            val hasFlash = characteristics
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasFlash) continue
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }
}
