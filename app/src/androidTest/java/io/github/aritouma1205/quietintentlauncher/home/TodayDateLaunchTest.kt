package io.github.aritouma1205.quietintentlauncher.home

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.MainActivity
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.calendar.CalendarAccess
import io.github.aritouma1205.quietintentlauncher.settings.InfoSettings
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #48: tapping the TODAY date row hands off to a calendar app's
 * day view via `content://com.android.calendar/time/<dayStart>` — the
 * same emit-path as event rows, with no armed flag. Covers the row's
 * click affordance and OnClick label, both handler branches through the
 * `eventHandlerCheck` seam, the real `startActivity` fire intercepted
 * by a blocking monitor, and GLANCE staying non-interactive.
 */
@RunWith(AndroidJUnit4::class)
class TodayDateLaunchTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() =
        instrumentation.targetContext.applicationContext as QuietLauncherApp
    private val viewModel get() = rule.activity.viewModel

    private fun res(id: Int, vararg args: Any): String =
        rule.activity.getString(id, *args)

    @Before
    fun setUp() {
        runBlocking {
            app.container.settingsStore.state.first { it is SettingsState.Ready }
            app.container.settingsStore.update {
                it.copy(
                    introCompleted = true,
                    info = it.info.copy(
                        glanceDismissSeconds = InfoSettings.GLANCE_DISMISS_NEVER,
                    ),
                )
            }
        }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        app.container.calendarAccess.eventHandlerCheck = null
        rule.runOnUiThread { viewModel.closeOverlay() }
        rule.waitForIdle()
        runBlocking {
            app.container.settingsStore.update {
                it.copy(info = InfoSettings())
            }
        }
    }

    /**
     * Blocking monitor intercepting exactly the day-view fire:
     * ACTION_VIEW on `content://com.android.calendar/…`. A filter-based
     * monitor cannot express this reliably — match() runs resolveType,
     * and the Calendar Provider answers a MIME type for `time/` URIs,
     * which a filter without declared types then rejects.
     */
    private class DayViewMonitor : Instrumentation.ActivityMonitor() {
        var intercepted: Intent? = null
            private set

        override fun onStartActivity(
            intent: Intent,
        ): Instrumentation.ActivityResult? {
            val uri = intent.data
            return if (
                intent.action == Intent.ACTION_VIEW &&
                uri?.scheme == "content" &&
                uri.authority == "com.android.calendar"
            ) {
                intercepted = intent
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent())
            } else {
                null
            }
        }
    }

    /** Opens TODAY through the real UI path and waits for the date line. */
    private fun openTodayPanel() {
        rule.runOnUiThread { viewModel.openTodayPanel() }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.today.value.dateText.isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun dateLine(): String = viewModel.today.value.let {
        "${it.dateText} ${it.weekdayText}"
    }

    private fun dateRow() = rule.onNode(
        hasClickAction() and hasText(dateLine()),
    )

    @Test
    fun dateRowIsClickableAndNamesItsAction() {
        openTodayPanel()

        dateRow().assertIsDisplayed()
        val node = rule.onNodeWithText(dateLine()).fetchSemanticsNode()
        assertEquals(
            res(R.string.today_open_calendar, dateLine()),
            node.config.getOrNull(SemanticsActions.OnClick)?.label,
        )
    }

    @Test
    fun dateTapEmitsDayViewIntent() {
        app.container.calendarAccess.eventHandlerCheck = { true }
        val intents = CopyOnWriteArrayList<Intent>()
        val job = CoroutineScope(Dispatchers.Main).launch {
            viewModel.eventIntents.collect { intents += it }
        }
        // Block the real fire too — the emulator may host a calendar app
        // that would otherwise steal focus mid-assertion.
        val monitor = DayViewMonitor()
        instrumentation.addMonitor(monitor)
        try {
            openTodayPanel()
            dateRow().performClick()
            rule.waitUntil(timeoutMillis = 5_000) { intents.size == 1 }

            val intent = intents.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            val uri = intent.data
            assertEquals("content", uri?.scheme)
            assertEquals("com.android.calendar", uri?.authority)
            val segments = uri?.pathSegments.orEmpty()
            assertEquals(2, segments.size)
            assertEquals("time", segments[0])
            // The appended ms is the device-zone midnight of "now" —
            // idempotent under dayStartMillis and inside today either
            // way the click/measurement order lands at a date boundary.
            val dayStart = segments[1].toLong()
            assertEquals(dayStart, CalendarAccess.dayStartMillis(dayStart))
            assertTrue(abs(System.currentTimeMillis() - dayStart) < 86_400_000L)
        } finally {
            job.cancel()
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun dateTapWithoutHandlerEmitsMessageOnly() {
        app.container.calendarAccess.eventHandlerCheck = { false }
        val intents = CopyOnWriteArrayList<Intent>()
        val messages = CopyOnWriteArrayList<HomeMessage>()
        val jobs = listOf(
            CoroutineScope(Dispatchers.Main).launch {
                viewModel.eventIntents.collect { intents += it }
            },
            CoroutineScope(Dispatchers.Main).launch {
                viewModel.messages.collect { messages += it }
            },
        )
        try {
            openTodayPanel()
            dateRow().performClick()
            rule.waitUntil(timeoutMillis = 5_000) {
                messages.contains(HomeMessage.NoCalendarHandler)
            }
            assertTrue(intents.isEmpty())
        } finally {
            jobs.forEach(Job::cancel)
        }
    }

    @Test
    fun dateTapFiresRealCalendarActivity() {
        // Handler present: the whole chain down to startActivity must
        // run — the blocking monitor counts and captures the real fire.
        app.container.calendarAccess.eventHandlerCheck = { true }
        val monitor = DayViewMonitor()
        instrumentation.addMonitor(monitor)
        try {
            openTodayPanel()
            dateRow().performClick()
            rule.waitUntil(timeoutMillis = 5_000) { monitor.hits >= 1 }
            val segments = monitor.intercepted?.data?.pathSegments.orEmpty()
            assertEquals(2, segments.size)
            assertEquals("time", segments[0])
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun glanceDateRowStaysNonInteractive() {
        rule.runOnUiThread { viewModel.onFreeAreaEvent(FreeAreaEvent.Tap) }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.overlay.value == HomeOverlay.Glance &&
                viewModel.today.value.dateText.isNotEmpty()
        }
        rule.waitForIdle()

        // GLANCE renders weekday-first and must never answer taps —
        // touches pass through to the bars and the free area (design 8.3).
        val t = viewModel.today.value
        rule.onNodeWithText("${t.weekdayText} ${t.dateText}")
            .assert(hasNoClickAction())
        // And no clickable node anywhere on the GLANCE strip.
        rule.onNode(hasClickAction() and hasText(t.dateText))
            .assertDoesNotExist()
    }
}
