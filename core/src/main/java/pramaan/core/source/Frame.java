/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Objects;

/**
 * One frame from a {@link FaceFrameSource}. Immutable. The pixel bytes are exposed read-only rather
 * than copied, because frames are large and arrive many times a second.
 */
public final class Frame {

    /** Marker for "the device did not report a sensor timestamp". */
    public static final long NO_SENSOR_TIMESTAMP = -1;

    private final byte[] data;
    private final PixelFormat format;
    private final int width;
    private final int height;
    private final long sensorTimestampNanos;
    private final Instant hostArrival;
    private final long index;
    private final DeviceMetadata device;
    private final byte[] streamTag;
    private final long streamTimeMillis;
    private final String streamCheckpoint;

    /**
     * @param data                 encoded or raw pixels; ownership passes to the frame (do not modify afterwards)
     * @param width                pixels, or 0 when unknown until decoded (JPEG)
     * @param height               pixels, or 0 when unknown until decoded (JPEG)
     * @param sensorTimestampNanos device clock, or {@link #NO_SENSOR_TIMESTAMP}
     * @param hostArrival          when the host received it, from the session's clock
     */
    public Frame(byte[] data, PixelFormat format, int width, int height, long sensorTimestampNanos, Instant hostArrival,
            long index, DeviceMetadata device) {
        this(data, format, width, height, sensorTimestampNanos, hostArrival, index, device, null, -1, null);
    }

    /**
     * A frame from a device that implements the SBI Liveness Profile (Bible Part 41): the authenticated-stream tag it
     * sent with this frame, the sensor time that tag covers (milliseconds), and, every N frames, the device-signed
     * checkpoint (a compact JWS). All three are null or -1 for a plain SBI device.
     */
    public Frame(byte[] data, PixelFormat format, int width, int height, long sensorTimestampNanos, Instant hostArrival,
            long index, DeviceMetadata device, byte[] streamTag, long streamTimeMillis, String streamCheckpoint) {
        this.streamTag = streamTag == null ? null : streamTag.clone();
        this.streamTimeMillis = streamTimeMillis;
        this.streamCheckpoint = streamCheckpoint;
        this.data = Objects.requireNonNull(data, "data");
        this.format = Objects.requireNonNull(format, "format");
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("negative frame size " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.sensorTimestampNanos = sensorTimestampNanos;
        this.hostArrival = Objects.requireNonNull(hostArrival, "hostArrival");
        this.index = index;
        this.device = device;
    }

    /** Read-only view of the pixel bytes. */
    public ByteBuffer bytes() {
        return ByteBuffer.wrap(data).asReadOnlyBuffer();
    }

    /** A private copy of the pixel bytes, for code that needs an array. */
    public byte[] copyBytes() {
        return data.clone();
    }

    public int length() {
        return data.length;
    }

    public PixelFormat format() {
        return format;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long sensorTimestampNanos() {
        return sensorTimestampNanos;
    }

    public Instant hostArrival() {
        return hostArrival;
    }

    public long index() {
        return index;
    }

    public DeviceMetadata device() {
        return device;
    }

    /** The profile tag the device sent with this frame, or null on a plain SBI stream. */
    public byte[] streamTag() {
        return streamTag == null ? null : streamTag.clone();
    }

    /** The sensor time covered by {@link #streamTag()}, in milliseconds, or -1. */
    public long streamTimeMillis() {
        return streamTimeMillis;
    }

    /** The device-signed checkpoint carried by this frame, or null. */
    public String streamCheckpoint() {
        return streamCheckpoint;
    }
}
