plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    application
}

group = "neonsync"
version = "1.0.0"

dependencies {
    implementation(project(":shared"))
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport)
    implementation(libs.gstreamer.java)
    implementation(libs.jna)
    implementation(libs.slf4j.simple)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
}

// Build with whatever JDK runs Gradle (>= 21), producing Java 21 bytecode
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("neonsync.MainKt")
    applicationName = "neonsync"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx512m",
        "-XX:+UseSerialGC",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=info",
        "-Dorg.slf4j.simpleLogger.log.org.freedesktop.dbus=warn",
        "-Dorg.slf4j.simpleLogger.showDateTime=true",
        "-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS",
        // Lets main() set the X11 WM_CLASS, so KDE matches the window to neonsync.desktop
        "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
    )
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
