/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

rootProject.name = "pramaan"

// core    — the liveness engine: no UI, no device I/O, no HTTP (Build Map #1)
// sbi     — MOSIP SBI device client (discovery, stream, rcapture)
// desktop — Registration Client integration, deployment Mode C (Phase 11)
// harness — evaluation: corpus replay, APCER/BPCER, reports (Phase 10)
// The Android module (Mode D, Phase 12) is built via android/settings.gradle.kts.
// Android Registration Client integration (Mode C): stub and adapter.
include("core", "sbi", "desktop", "harness", "regclient-stub", "regclient-adapter",
        "android-regclient-stub", "android-regclient-adapter")

