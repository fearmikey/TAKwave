// SPDX-License-Identifier: GPL-3.0-or-later
// Build script for the vendored usb-serial-for-android sources (MIT, see LICENSE.txt and VENDORED.md).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hoho.android.usbserial"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        // Upstream drivers reference BuildConfig.DEBUG. The generated class holds only constants.
        buildConfig = true
    }
    lint {
        // Third-party code: not ours to restyle.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}
