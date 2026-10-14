/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceIdentity;
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
    private final JsonNode livenessProfile;
    private DeviceIdentity identity = DeviceIdentity.UNCHECKED;

    DiscoveredDevice(int port, String deviceId, List<String> deviceSubIds, String status, String purpose, DeviceLevel level,
            String type, String deviceCode, String make, String model, String serialNo, List<String> specVersions) {
        this(port, deviceId, deviceSubIds, status, purpose, level, type, deviceCode, make, model, serialNo, specVersions,
                null);
    }

    /** @param livenessProfile the device-info {@code livenessProfile} object (Bible Part 41), or null for plain SBI */
    DiscoveredDevice(int port, String deviceId, List<String> deviceSubIds, String status, String purpose, DeviceLevel level,
            String type, String deviceCode, String make, String model, String serialNo, List<String> specVersions,
            JsonNode livenessProfile) {
        this.livenessProfile = livenessProfile != null && livenessProfile.isObject() ? livenessProfile : null;
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

    /** The SBI Liveness Profile version the device advertises (Bible Part 41), or null for a plain SBI device. */
    public String livenessProfileVersion() {
        return livenessProfile == null ? null : livenessProfile.path("version").asText(null);
    }

    /** True if the device authenticates its preview stream (profile addition 2). */
    public boolean signedStream() {
        return livenessProfile != null && livenessProfile.path("signedStream").asBoolean(false);
    }

    /** The optional capabilities the device's profile block declares; empty for a plain SBI device. */
    public Set<Capability> profileCapabilities() {
        Set<Capability> out = EnumSet.noneOf(Capability.class);
        if (livenessProfile == null) {
            return out;
        }
        if (signedStream()) {
            out.add(Capability.SIGNED_STREAM);
        }
        if (livenessProfile.path("captureBurst").asBoolean(false)) {
            out.add(Capability.CAPTURE_BURST);
        }
        if (livenessProfile.path("sensorTimestamps").asBoolean(false)) {
            out.add(Capability.SENSOR_TIMESTAMPS);
        }
        if (livenessProfile.path("focusControl").asBoolean(false)) {
            out.add(Capability.FOCUS_CONTROL);
        }
        for (JsonNode light : livenessProfile.path("illuminatorControl")) {
            String kind = light.asText("");
            if (kind.startsWith("VISIBLE")) {
                out.add(Capability.RESIDENT_DISPLAY); // the device's own light faces the resident
            } else if (kind.startsWith("NIR")) {
                out.add(Capability.NIR_ILLUMINATOR_CONTROL);
            }
        }
        return out;
    }

    /** Records what the digitalId proved; set by discovery when the client has a trust set to check it against. */
    DiscoveredDevice withIdentity(DeviceIdentity checked) {
        this.identity = checked;
        return this;
    }

    public DeviceIdentity identity() {
        return identity;
    }

    /** True if the device's profile offers a visible-light illuminator the client may drive (addition 5). */
    public boolean canIlluminate() {
        if (livenessProfile == null) {
            return false;
        }
        for (JsonNode light : livenessProfile.path("illuminatorControl")) {
            if (light.asText("").startsWith("VISIBLE")) {
                return true;
            }
        }
        return false;
    }

    public boolean isFace() {
        return "Face".equalsIgnoreCase(type);
    }

    public boolean isReady() {
        return "Ready".equalsIgnoreCase(status);
    }

    public DeviceMetadata metadata(String subId) {
        return new DeviceMetadata(deviceId, subId, deviceCode, level, make, model, serialNo, identity);
    }
}
