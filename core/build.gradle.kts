// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The XmlPull API and the kXML parser are needed to compile and to test. At runtime :app
    // supplies the parser through :xmlparser (see that module for why).
    compileOnly(libs.kxml2)
    testImplementation(libs.kxml2)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    maxHeapSize = "1g"
    // Tests write artifacts (for example the modem test WAV) here.
    systemProperty("takwave.buildDir", layout.buildDirectory.get().asFile.absolutePath)
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}
