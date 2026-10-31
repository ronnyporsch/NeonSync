rootProject.name = "NeonSync"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.3.20"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

/** Shared logic and Compose UI for all platforms. */
include(":shared")
/** Linux desktop app (KDE Plasma / Wayland): screen and audio capture, tray icon. */
include(":desktopApp")
include(":androidApp")
include(":webApp")
