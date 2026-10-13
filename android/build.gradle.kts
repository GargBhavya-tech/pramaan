/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// AGP 8.5.2: the newest release already in this machine's Gradle cache, and inside the 8.x line MOSIP's Android
// mock SBI builds with (8.2.2), so an app on that line can take the AAR without an upgrade.
plugins {
    id("com.android.library") version "8.5.2" apply false
}
