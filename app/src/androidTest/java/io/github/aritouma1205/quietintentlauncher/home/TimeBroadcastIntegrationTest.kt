package io.github.aritouma1205.quietintentlauncher.home

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.MainActivity
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I6-T02 real-path coverage (design 8.1): MainActivity registers a receiver
 * for TIME_SET / DATE_CHANGED / TIMEZONE_CHANGED while started and forwards
 * it into HomeViewModel.onTimeChanged. Protected broadcasts cannot be sent
 * from a test app — and on API 30+ not even from the shell — so the test
 * asserts the registered filter's actions and drives the actual receiver
 * instance, which is the full in-app route the OS delivery lands on.
 */
@RunWith(AndroidJUnit4::class)
class TimeBroadcastIntegrationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() =
        instrumentation.targetContext.applicationContext as QuietLauncherApp

    @Before
    fun setUp() {
        runBlocking {
            app.container.settingsStore.state.first { it is SettingsState.Ready }
            app.container.settingsStore.update { it.copy(introCompleted = true) }
        }
    }

    @Test
    fun registeredReceiverForwardsTimeChangesToTheViewModel() {
        val activity = rule.activity

        // The same filter object is registered in onStart — its actions are
        // the declared subscription for the time-change route.
        assertTrue(
            activity.timeChangeFilter.hasAction(Intent.ACTION_TIME_CHANGED),
        )
        assertTrue(
            activity.timeChangeFilter.hasAction(Intent.ACTION_DATE_CHANGED),
        )
        assertTrue(
            activity.timeChangeFilter.hasAction(Intent.ACTION_TIMEZONE_CHANGED),
        )

        val calls = AtomicInteger(0)
        rule.runOnUiThread {
            activity.viewModel.timeChangedProbe = { calls.incrementAndGet() }
        }
        // Drive the registered receiver directly: onReceive → onTimeChanged
        // → refresh + probe is the whole in-app route a real broadcast takes.
        rule.runOnUiThread {
            activity.timeChangeReceiver.onReceive(
                activity,
                Intent(Intent.ACTION_TIME_CHANGED),
            )
        }
        rule.waitUntil(timeoutMillis = 10_000) { calls.get() > 0 }
    }
}
