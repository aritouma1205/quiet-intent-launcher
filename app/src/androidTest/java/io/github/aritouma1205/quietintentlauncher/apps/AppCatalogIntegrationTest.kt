package io.github.aritouma1205.quietintentlauncher.apps

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.MainActivity
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I38-T05 real-catalog coverage for [AppEntry.canUninstall] (Issue #38):
 * the flags the OS actually reports decide whether the uninstall row can
 * appear, for both production entry paths — the full query and
 * [AppCatalog.resolveApp] (「最近」 rows). Device-dependent rows are logged,
 * never silently skipped.
 */
@RunWith(AndroidJUnit4::class)
class AppCatalogIntegrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ownPackageName get() = context.packageName
    private val ownComponent get() = ComponentName(context, MainActivity::class.java)

    private fun isSystemPackage(packageName: String): Boolean {
        val flags = context.packageManager
            .getApplicationInfo(packageName, 0).flags
        return flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
    }

    @Test
    fun catalogEntriesCarryRealUninstallFlags() {
        runBlocking {
            val catalog = AppCatalog(context, this)
            catalog.reloadAll()
            val apps = withTimeout(15_000) {
                catalog.apps.filterNotNull().first { it.isNotEmpty() }
            }

            // (a) The launcher's own package never offers uninstall.
            val own = apps.filter { it.packageName == ownPackageName }
            assertTrue(
                "own package missing from launcher activity list",
                own.isNotEmpty(),
            )
            own.forEach { assertFalse(it.canUninstall) }

            // (b) Plain system packages (no updated copy) are protected.
            val system = apps.filter { isSystemPackage(it.packageName) }
            if (system.isEmpty()) {
                Log.i(TAG, "no system package in catalog — row not exercised")
            } else {
                system.forEach { assertFalse(it.canUninstall) }
            }

            // (c) Non-system third-party packages can be uninstalled.
            val thirdParty = apps.filter {
                it.packageName != ownPackageName && !isSystemPackage(it.packageName)
            }
            if (thirdParty.isEmpty()) {
                Log.i(TAG, "no third-party package in catalog — row not exercised")
            } else {
                thirdParty.forEach { assertTrue(it.canUninstall) }
            }
        }
    }

    @Test
    fun resolveAppAppliesTheSameRule() {
        runBlocking {
            // (d) resolveApp (「最近」 path) applies the self-exclusion too.
            val catalog = AppCatalog(context, this)
            val own = withTimeout(15_000) {
                catalog.resolveApp(ownComponent.flattenToShortString())
            }
            assertNotNull("own launchable component did not resolve", own)
            assertFalse(own!!.canUninstall)

            // (e) With the seam pointed elsewhere, the same component is a
            // plain third-party package — proves the constructor wiring
            // reaches resolveApp rather than a hardcoded own-package name.
            val foreign =
                AppCatalog(context, this, ownPackageName = "io.example.other")
            val foreignEntry = withTimeout(15_000) {
                foreign.resolveApp(ownComponent.flattenToShortString())
            }
            assertNotNull(foreignEntry)
            assertTrue(foreignEntry!!.canUninstall)
        }
    }

    @Test
    fun appEntryDefaultsCanUninstallToFalse() {
        // The I38-T01 default-value row: AppEntry construction needs real
        // ComponentName/UserHandle instances, which android.jar stubs
        // cannot produce in local JVM tests — asserted on-device instead.
        val entry = AppEntry(
            component = ownComponent,
            user = android.os.Process.myUserHandle(),
            label = "x",
        )
        assertFalse(entry.canUninstall)
    }

    private companion object {
        const val TAG = "AppCatalogIntegTest"
    }
}
