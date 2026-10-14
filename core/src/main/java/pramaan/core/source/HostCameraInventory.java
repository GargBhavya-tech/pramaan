/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.util.List;
import java.util.Optional;

/**
 * The cameras the operating system itself lists (Bible Part 5, point 2): the mechanism behind "enumerate the host's
 * video devices", as opposed to trusting the name the SBI device reports for itself. Core cannot enumerate devices
 * (it is headless, with no native code), so each platform supplies one.
 */
public interface HostCameraInventory {

    /**
     * The friendly names of the host's camera devices, or empty if the platform could not be asked. Empty means
     * "unknown", never "none": a station that cannot enumerate is not accused of anything.
     */
    Optional<List<String>> cameraNames();
}
