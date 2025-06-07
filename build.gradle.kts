// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false

    id("com.google.gms.google-services") version "4.4.2" apply false
}
allprojects {
    afterEvaluate {
        if (plugins.hasPlugin("com.android.application") || plugins.hasPlugin("com.android.library")) {
            extensions.findByName("android")?.let { androidExt ->
                val android = androidExt as com.android.build.gradle.BaseExtension
                android.lintOptions.apply {
                    isAbortOnError = false
                    isCheckReleaseBuilds = false
                }
            }
        }
    }
}