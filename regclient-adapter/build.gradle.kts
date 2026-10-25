/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// The PRAMAAN side of the Registration Client's LivenessHook (integration/registration-client): a class the client loads with
// java.util.ServiceLoader that drives the engine through the client's own capture. The hook interface comes from the
// client (the patch adds it); here it is compile-only, from regclient-stub.
dependencies {
    api(project(":core"))
    api(project(":sbi"))
    api(project(":desktop"))
    compileOnly(project(":regclient-stub"))
}
