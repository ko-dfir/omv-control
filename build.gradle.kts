// Top-level build file. Plugins are declared here (not applied) so all modules share one version.
plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 has built-in Kotlin support; declaring KGP here only pins the Kotlin version on the classpath.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
