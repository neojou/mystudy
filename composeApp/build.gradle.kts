// KMP : Desktop + WasmJs

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val appGroup = providers.gradleProperty("app.group").get()
val appRootName = providers.gradleProperty("app.rootName").get()
val appDisplayName = providers.gradleProperty("app.displayName").get()
val appVersion = providers.gradleProperty("app.version").get()

group = appGroup
version = appVersion

kotlin {
    jvm("desktop")
    jvmToolchain(25)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "$appGroup.MainKt"

        // The macOS menu bar name is fixed when the JVM starts. Gradle `run` does not
        // package an app, so the dock name has to be passed as a launcher argument.
        if (System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) {
            jvmArgs += listOf(
                "-Dapple.awt.application.name=$appDisplayName",
                "-Xdock:name=$appDisplayName",
            )
        }

        nativeDistributions {
            packageName = appRootName
            packageVersion = appVersion
            macOS {
                dockName = appDisplayName
                bundleID = appGroup
            }
        }
    }
}

compose.resources {
    packageOfResClass = appGroup
}
