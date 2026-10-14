/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import pramaan.core.session.SessionContext;

/**
 * The device abstraction (Bible Part 4.1; Build Map #11). Real SBI devices and the replay mock both
 * implement it, so the evaluation harness exercises exactly the production code path.
 *
 * <p>{@link #capture} lives on the same interface as the stream on purpose: the signed capture and the
 * preview frames must come from the same source in the same session, or the capture-swap hole
 * (Bible Part 13) is built in.
 */
public interface FaceFrameSource extends AutoCloseable {

    /** Finds the face device and reports what it can do. */
    DeviceCapabilities discover() throws DeviceException;

    /** Starts delivering frames and device events for one session. */
    void start(SessionContext session, FrameSink sink) throws DeviceException;

    /** Requests one signed face capture and verifies its signature before returning it. */
    SignedCapture capture(CaptureRequest request) throws DeviceException;

    /** Stops delivery. Safe to call more than once. */
    void stop();

    /**
     * The authenticated-stream lock of this session when the device implements the SBI Liveness Profile (Bible
     * Part 41), fed with every frame the source reads; empty for a plain SBI device. The capture binder checks it
     * against the signed capture, so the binding level becomes {@code CRYPTOGRAPHIC} instead of {@code HEURISTIC}.
     */
    default java.util.Optional<pramaan.core.binding.SignedStreamLock> streamLock() {
        return java.util.Optional.empty();
    }

    /**
     * The device's own illuminator, when its profile offers one (SBI Liveness Profile, addition 5; Bible Part 41).
     * Empty for a plain SBI device. The flash challenge prefers it to the station's light because the device signs
     * what it showed and when.
     */
    default java.util.Optional<DeviceIlluminator> illuminator() {
        return java.util.Optional.empty();
    }

    @Override
    default void close() {
        stop();
    }
}
