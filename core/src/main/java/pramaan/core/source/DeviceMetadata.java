/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.util.Objects;

/** Identity of the capture device as reported by the device service. Informational until a signature proves it. */
public final class DeviceMetadata {

    private final String deviceId;
    private final String deviceSubId;
    private final String deviceCode;
    private final DeviceLevel level;
    private final String make;
    private final String model;
    private final String serialNo;
    private final DeviceIdentity identity;

    public DeviceMetadata(String deviceId, String deviceSubId, String deviceCode, DeviceLevel level, String make,
            String model, String serialNo) {
        this(deviceId, deviceSubId, deviceCode, level, make, model, serialNo, DeviceIdentity.UNCHECKED);
    }

    public DeviceMetadata(String deviceId, String deviceSubId, String deviceCode, DeviceLevel level, String make,
            String model, String serialNo, DeviceIdentity identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.deviceSubId = Objects.requireNonNull(deviceSubId, "deviceSubId");
        this.deviceCode = deviceCode;
        this.level = Objects.requireNonNull(level, "level");
        this.make = make;
        this.model = model;
        this.serialNo = serialNo;
    }

    public String deviceId() {
        return deviceId;
    }

    public String deviceSubId() {
        return deviceSubId;
    }

    public String deviceCode() {
        return deviceCode;
    }

    public DeviceLevel level() {
        return level;
    }

    public String make() {
        return make;
    }

    public String model() {
        return model;
    }

    public String serialNo() {
        return serialNo;
    }

    /** Whether the device's digitalId proved its maker (Bible Part 5, point 1). */
    public DeviceIdentity identity() {
        return identity;
    }

    /** The same device with the outcome of an identity check. */
    public DeviceMetadata withIdentity(DeviceIdentity checked) {
        return new DeviceMetadata(deviceId, deviceSubId, deviceCode, level, make, model, serialNo, checked);
    }

    @Override
    public String toString() {
        return "device " + deviceId + "/" + deviceSubId + " (" + level + ", " + make + " " + model + ")";
    }
}
