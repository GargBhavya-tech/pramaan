/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import pramaan.core.gate.FaceTracker;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;
import pramaan.core.source.Frame;

/**
 * Turns what the device and the decoder report into session transitions, row by row from Bible Part 22
 * (Build Map #51, #52). One router per session.
 *
 * <ul>
 *   <li>Disconnect or device error: DEVICE_ERROR, attempt not consumed, evidence discarded.</li>
 *   <li>An undecodable frame: skipped, never hashed or scored. More than 20% of at least 5 frames in the
 *       last 2 s: DEVICE_ERROR.</li>
 *   <li>No frame for longer than the track gap (300 ms, or 1.5 frame intervals on a slow stream): the face is treated as lost and the attempt's
 *       evidence restarts — a gap is exactly where a swap would hide.</li>
 *   <li>Capture failure: DEVICE_ERROR (or DEVICE_TIMEOUT). Engine exception: ENGINE_ERROR, fail closed.</li>
 * </ul>
 */
public final class SessionEventRouter {

    public static final Duration BAD_FRAME_WINDOW = Duration.ofSeconds(2);
    public static final double BAD_FRAME_RATIO = 0.2;
    public static final int BAD_FRAME_MIN_FRAMES = 5;

    private final LivenessSession session;
    private final Duration maxGap;
    private final Deque<Instant> good = new ArrayDeque<>();
    private final Deque<Instant> bad = new ArrayDeque<>();
    private Instant lastArrival;

    public SessionEventRouter(LivenessSession session) {
        this(session, FaceTracker.MAX_GAP);
    }

    /** @param maxGap the frame gap that counts as a lost face: the face tracker's, {@link FaceTracker#gapFor} */
    public SessionEventRouter(LivenessSession session, Duration maxGap) {
        this.session = Objects.requireNonNull(session, "session");
        this.maxGap = Objects.requireNonNull(maxGap, "maxGap");
    }

    public void onDeviceEvent(DeviceEvent e) {
        switch (e.type()) {
            case DISCONNECTED:
            case ERROR:
                session.infrastructureFailure(FailureReason.DEVICE_ERROR);
                break;
            default:
                // CONNECTED, FORMAT_CHANGED and FRAMES_DROPPED change nothing by themselves; each frame is judged on its own.
                break;
        }
    }

    /**
     * One frame from the device.
     *
     * @param decoded the bytes the engine will analyse, or null if the frame could not be decoded
     * @param trackId the face tracker's current track (0 when no face)
     */
    public void onFrame(Frame frame, byte[] decoded, long trackId) {
        Instant t = frame.hostArrival();
        if (lastArrival != null && Duration.between(lastArrival, t).compareTo(maxGap) > 0 && collecting()) {
            session.faceLost();
        }
        lastArrival = t;
        (decoded == null ? bad : good).addLast(t);
        prune(t);
        int total = good.size() + bad.size();
        if (total >= BAD_FRAME_MIN_FRAMES && bad.size() > BAD_FRAME_RATIO * total) {
            good.clear();
            bad.clear();
            session.infrastructureFailure(FailureReason.DEVICE_ERROR);
            return;
        }
        if (decoded != null && collecting()) {
            session.frameAnalysed(decoded, t.toEpochMilli(), trackId);
        }
    }

    /** The face tracker ended a track: the same reset as a gap. */
    public void onTrack(FaceTracker.Update update) {
        if (update.previousTrackLost()) {
            onTrackLost();
        }
    }

    /** The same as an {@link #onTrack} update that ended a track, for callers that see only the flag. */
    public void onTrackLost() {
        if (collecting()) {
            session.faceLost();
        }
    }

    public void onCaptureFailed(DeviceException e) {
        session.infrastructureFailure(e.reason() == DeviceException.Reason.TIMEOUT
                ? FailureReason.DEVICE_TIMEOUT : FailureReason.DEVICE_ERROR);
    }

    /** Any exception from the engine: fail the attempt closed. Never proceed without liveness. */
    public void onEngineError(Throwable t) {
        session.infrastructureFailure(FailureReason.ENGINE_ERROR);
    }

    private boolean collecting() {
        SessionState s = session.state();
        return s == SessionState.PASSIVE || s == SessionState.CHALLENGING || s == SessionState.CAPTURING;
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(BAD_FRAME_WINDOW);
        while (!good.isEmpty() && good.peekFirst().isBefore(cutoff)) {
            good.removeFirst();
        }
        while (!bad.isEmpty() && bad.peekFirst().isBefore(cutoff)) {
            bad.removeFirst();
        }
    }
}
