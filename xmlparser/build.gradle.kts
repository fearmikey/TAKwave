// SPDX-License-Identifier: GPL-3.0-or-later
//
// Repackages the kXML 2 parser (BSD license, from Maven Central) for use on Android.
//
// The upstream jar bundles a copy of the org.xmlpull.v1 API. Android already ships that API in
// the platform, and R8 refuses to build an app that redefines it ("Library class ... implements
// program class org.xmlpull.v1.XmlPullParser"). This module's jar is the upstream jar minus
// org/xmlpull/**, so :app gets exactly the parser implementation that the :core tests run
// against, compiled against the platform's XmlPull interfaces. No source is modified.
plugins {
    `java-library`
}

val upstream: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}

dependencies {
    upstream(libs.kxml2)
}

tasks.jar {
    from({ upstream.map { zipTree(it) } }) {
        exclude("org/xmlpull/**")
        exclude("META-INF/**")
    }
    // Reproducible output for F-Droid.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
