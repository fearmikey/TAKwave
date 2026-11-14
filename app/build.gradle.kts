// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing material lives outside the repository (see README, "Release signing").
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.fearmikey.takwave"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.fearmikey.takwave"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Keep local Git metadata out of the APK so F-Droid can reproduce the build.
            vcsInfo {
                include = false
            }
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = false
    }
    dependenciesInfo {
        // The dependency metadata block is encrypted with a Google key; F-Droid rejects it.
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources {
            excludes += setOf("META-INF/*.version", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "DebugProbesKt.bin")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":usbserial"))
    implementation(project(":xmlparser"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
}

/**
 * FOSS guard: fails the build when a Google Play Services, Firebase, ML Kit or Play Billing
 * artifact appears anywhere in a resolved runtime classpath. Required by AGENTS.md.
 */
abstract class ForbiddenDependencyCheck : DefaultTask() {
    @get:Input
    abstract val coordinates: ListProperty<String>

    @get:Input
    abstract val forbiddenPrefixes: ListProperty<String>

    @TaskAction
    fun check() {
        val bad = coordinates.get().filter { c -> forbiddenPrefixes.get().any { c.startsWith(it) } }.distinct()
        if (bad.isNotEmpty()) {
            throw GradleException(
                "Forbidden non-FOSS dependencies in the resolved graph:\n" + bad.joinToString("\n") { "  $it" },
            )
        }
        logger.lifecycle("FOSS dependency check passed (${coordinates.get().size} artifacts inspected).")
    }
}

val checkFossDependencies = tasks.register<ForbiddenDependencyCheck>("checkFossDependencies") {
    group = "verification"
    description = "Fails if any Google Play Services / Firebase / ML Kit artifact is in the dependency graph."
    forbiddenPrefixes.set(
        listOf(
            "com.google.android.gms:",
            "com.google.firebase:",
            "com.google.mlkit:",
            "com.android.billingclient:",
            "com.google.android.play:",
            "com.android.installreferrer:",
        ),
    )
    coordinates.set(
        provider {
            configurations
                .filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath") }
                .flatMap { cfg ->
                    cfg.incoming.resolutionResult.allComponents.mapNotNull { c ->
                        c.moduleVersion?.let { "${it.group}:${it.name}:${it.version}" }
                    }
                }
                .distinct()
                .sorted()
        },
    )
}

tasks.named("preBuild") {
    dependsOn(checkFossDependencies)
}
