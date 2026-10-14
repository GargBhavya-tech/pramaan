/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What the injection guard found about one session's capture path (Bible Part 5; Build Map #84). Immutable, written
 * into the evidence so a later review can see which checks ran and which could not.
 *
 * @param boundDenylisted the SBI device's own reported name matched the virtual-camera list
 * @param hostEnumerated whether the operating system's camera list could be read at all
 * @param hostVirtualCameras denylisted products found in that list (empty if none, or if it could not be read)
 * @param identity what the device's digitalId proved
 */
public final class InjectionFindings {

    private final boolean boundDenylisted;
    private final boolean hostEnumerated;
    private final List<String> hostVirtualCameras;
    private final DeviceIdentity identity;

    public InjectionFindings(boolean boundDenylisted, boolean hostEnumerated, List<String> hostVirtualCameras,
            DeviceIdentity identity) {
        this.boundDenylisted = boundDenylisted;
        this.hostEnumerated = hostEnumerated;
        this.hostVirtualCameras = Collections.unmodifiableList(Objects.requireNonNull(hostVirtualCameras));
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    public boolean boundDenylisted() {
        return boundDenylisted;
    }

    public boolean hostEnumerated() {
        return hostEnumerated;
    }

    public List<String> hostVirtualCameras() {
        return hostVirtualCameras;
    }

    public DeviceIdentity identity() {
        return identity;
    }

    public boolean hostVirtualCameraFound() {
        return !hostVirtualCameras.isEmpty();
    }
}
