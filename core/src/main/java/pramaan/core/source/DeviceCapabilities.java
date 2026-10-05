/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** What a discovered device (and station) can do. Missing capabilities make cues abstain; they never fail anyone. */
public final class DeviceCapabilities {

    private final DeviceMetadata device;
    private final Set<Capability> capabilities;

    public DeviceCapabilities(DeviceMetadata device, Set<Capability> capabilities) {
        this.device = Objects.requireNonNull(device, "device");
        EnumSet<Capability> copy = EnumSet.noneOf(Capability.class);
        copy.addAll(capabilities);
        this.capabilities = Collections.unmodifiableSet(copy);
    }

    public DeviceMetadata device() {
        return device;
    }

    public Set<Capability> capabilities() {
        return capabilities;
    }

    public boolean has(Capability capability) {
        return capabilities.contains(capability);
    }
}
