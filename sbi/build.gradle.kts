/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// MOSIP Secure Biometric Interface client: discovery, device info, M-JPEG stream, rcapture.
dependencies {
    api(project(":core"))
    implementation(libs.jackson.databind)
}

// Shared test helpers such as TestClock (Build Map #8).
dependencies {
    testImplementation(testFixtures(project(":core")))
}
