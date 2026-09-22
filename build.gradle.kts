buildscript {
    dependencies {
        // AGP 9 bundles KGP 2.2.10 for built-in Kotlin. Override it so the
        // Kotlin compiler version matches the Compose compiler plugin and the
        // serialization plugin below.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
