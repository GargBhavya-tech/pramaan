/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Compile-time stand-ins for the classes the Registration Client patch adds (integration/registration-client). They exist so
// the adapter and its tests build here without MOSIP's client; they are NEVER shipped, because the real client has the real
// classes. StubMatchesPatchTest fails if a stand-in drifts from the patch.
