/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import java.util.Collections;
import java.util.List;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;

/** One device as reported by MOSIPDISC. Descriptive only: nothing here is signed yet. */
public final class DiscoveredDevice {

    private final int port;
    private final String deviceId;
    private final List<String> deviceSubIds;
    private final String status;
    private final String purpose;
    private final DeviceLevel level;
    private final String type;
    private final String deviceCode;
    private final String make;
    private final String model;
    private final String serialNo;
    private final List<String> specVersions;

    DiscoveredDevice(int port, String deviceId, List<String> deviceSubIds, String status, String purpose, DeviceLevel level,
            String type, String deviceCode, String make, String model, String serialNo, List<String> specVersions) {
        this.port = port;
        this.deviceId = deviceId;
        this.deviceSubIds = Collections.unmodifiableList(deviceSubIds);
        this.status = status;
        this.purpose = purpose;
        this.level = level;
        this.type = type;
        this.deviceCode = deviceCode;
        this.make = make;
        this.model = model;
        this.serialNo = serialNo;
        this.specVersions = Collections.unmodifiableList(specVersions);
    }

    public int port() {
        return port;
    }

    public String deviceId() {
        return deviceId;
    }

    public List<String> deviceSubIds() {
        return deviceSubIds;
    }

    public String status() {
        return status;
    }

    public String purpose() {
        return purpose;
    }

    public DeviceLevel level() {
        return level;
    }

    /** From the digitalId: "Face", "Finger", "Iris". */
    public String type() {
        return type;
    }

    public List<String> specVersions() {
        return specVersions;
    }

    public boolean isFace() {
        return "Face".equalsIgnoreCase(type);
    }

    public boolean isReady() {
        return "Ready".equalsIgnoreCase(status);
    }

    public DeviceMetadata metadata(String subId) {
        return new DeviceMetadata(deviceId, subId, deviceCode, level, make, model, serialNo);
    }
}
