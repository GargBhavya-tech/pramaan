/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// The PRAMAAN side of the Android Registration Client's LivenessHook (integration/android-registration-client).
// Loaded via java.util.ServiceLoader. Hook interfaces come from android-regclient-stub.
dependencies {
    api(project(":core"))
    api(project(":sbi"))
    compileOnly(project(":android-regclient-stub"))

    testImplementation(project(":android-regclient-stub"))
    testImplementation(testFixtures(project(":core")))
}
