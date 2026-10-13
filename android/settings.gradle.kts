/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// The Android build (Build Map Phase 12, deployment Mode D). A separate Gradle build on purpose: the main build
// stays free of the Android Gradle Plugin and the SDK, so everyone else builds and tests without them.
//   ./gradlew -p android :pramaan-android:assembleRelease :pramaan-android:testDebugUnitTest
// The engine comes from the main build as an included build: the same core jar the desktop runs.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pramaan-android-build"
includeBuild("..")
include(":pramaan-android")
