package io.github.aritouma1205.quietintentlauncher.apps

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I38-T01 truth table for [canUninstallApp] (design 9.3, Issue #38):
 * package metadata decides whether the long-press dialog may offer an
 * uninstall row. The AppEntry-defaults assertion of the same test ID lives
 * in AppCatalogIntegrationTest — android.jar stubs cannot instantiate
 * ComponentName/UserHandle in local JVM tests.
 */
class AppCatalogTest {

    private companion object {
        const val OWN = "io.example.own"
        const val OTHER = "io.example.other"
    }

    @Test
    fun `null flags stay on the safe side`() {
        assertFalse(canUninstallApp(null, OTHER, OWN))
        assertFalse(canUninstallApp(null, OWN, OWN))
    }

    @Test
    fun `plain user app can be uninstalled`() {
        assertTrue(canUninstallApp(0, OTHER, OWN))
    }

    @Test
    fun `own package is never offered`() {
        assertFalse(canUninstallApp(0, OWN, OWN))
        // Self-exclusion wins even over the updated-system flag.
        assertFalse(
            canUninstallApp(
                ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
                OWN,
                OWN,
            ),
        )
    }

    @Test
    fun `system app cannot be uninstalled`() {
        assertFalse(canUninstallApp(ApplicationInfo.FLAG_SYSTEM, OTHER, OWN))
    }

    @Test
    fun `updated system app counts as uninstallable`() {
        assertTrue(
            canUninstallApp(
                ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
                OTHER,
                OWN,
            ),
        )
    }

    @Test
    fun `unrelated flags keep a user app uninstallable`() {
        assertTrue(
            canUninstallApp(
                ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_ALLOW_BACKUP,
                OTHER,
                OWN,
            ),
        )
    }
}
