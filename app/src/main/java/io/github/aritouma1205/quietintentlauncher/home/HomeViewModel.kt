package io.github.aritouma1205.quietintentlauncher.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.aritouma1205.quietintentlauncher.AppContainer
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.launch.LaunchResult
import io.github.aritouma1205.quietintentlauncher.launch.LaunchTarget
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One-shot UI events carrying a string resource id. */
enum class HomeMessage {
    LaunchFailed,
    LaunchBusy,
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val nav = HomeNavigation()
    val screen: StateFlow<HomeScreen> = nav.screen

    val settingsState: StateFlow<SettingsState> = container.settingsStore.state
    val apps: StateFlow<List<AppEntry>?> = container.appCatalog.apps

    private val _isDefaultHome = MutableStateFlow(container.homeRole.isHeld())
    val isDefaultHome: StateFlow<Boolean> = _isDefaultHome.asStateFlow()

    private val _recoveryDismissed = MutableStateFlow(false)
    val recoveryDismissed: StateFlow<Boolean> = _recoveryDismissed.asStateFlow()

    private val _messages = MutableSharedFlow<HomeMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<HomeMessage> = _messages.asSharedFlow()

    private var introDecided = false

    init {
        viewModelScope.launch {
            settingsState.collect { state ->
                when (state) {
                    is SettingsState.Ready -> {
                        _recoveryDismissed.value = false
                        if (!introDecided) {
                            introDecided = true
                            if (!state.data.introCompleted) {
                                nav.navigateTo(HomeScreen.Intro)
                            }
                        }
                    }
                    is SettingsState.Degraded -> _recoveryDismissed.value = false
                    SettingsState.Loading -> Unit
                }
            }
        }
    }

    fun refreshHomeRole() {
        _isDefaultHome.value = container.homeRole.isHeld()
    }

    fun completeIntro() {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(introCompleted = true) }
        }
        nav.resetToQuiet()
    }

    fun launchApp(entry: AppEntry) {
        val result = container.targetLauncher.launch(
            LaunchTarget.AppActivity(entry.component, entry.user),
        )
        when (result) {
            LaunchResult.Success -> nav.resetToQuiet()
            LaunchResult.Busy -> _messages.tryEmit(HomeMessage.LaunchBusy)
            LaunchResult.NotFound -> {
                _messages.tryEmit(HomeMessage.LaunchFailed)
                container.appCatalog.reloadAll()
            }
            is LaunchResult.Failure -> _messages.tryEmit(HomeMessage.LaunchFailed)
        }
    }

    fun dismissRecovery() {
        _recoveryDismissed.value = true
    }

    fun retrySettings() {
        container.settingsStore.retry()
    }

    fun resetSettings() {
        viewModelScope.launch {
            if (container.settingsStore.resetToDefaults()) {
                _recoveryDismissed.value = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}
