/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.gate;

import java.time.Duration;
import java.time.Instant;
import pramaan.core.detect.FaceBox;

/**
 * Gives the main face a stable track id (Build Map #20). A gap longer than 300 ms (longer on a slow stream,
 * see {@link #gapFor}), or a jump to a box
 * that does not overlap the last one, starts a new track and reports that the old one was lost — and a
 * lost track resets the session's evidence (Build Map #52), so evidence can never span a face swap.
 */
public final class FaceTracker {

    public static final Duration MAX_GAP = Duration.ofMillis(300);
    public static final double MIN_IOU = 0.3;
    /** The gap may not be shorter than this many frame intervals, or a slow stream loses its track every frame. */
    public static final double GAP_FRAME_INTERVALS = 1.5;

    private final Duration maxGap;
    private long nextId = 1;
    private long trackId;
    private FaceBox last;
    private Instant lastSeen;

    /** One tracker update. */
    public static final class Update {
        private final long trackId;
        private final boolean previousTrackLost;

        Update(long trackId, boolean previousTrackLost) {
            this.trackId = trackId;
            this.previousTrackLost = previousTrackLost;
        }

        /** Current track id, or 0 when no face is being tracked. */
        public long trackId() {
            return trackId;
        }

        /** True when this update ended an earlier track: session evidence must be reset. */
        public boolean previousTrackLost() {
            return previousTrackLost;
        }
    }

    /** A tracker with the 300 ms gap, for streams of 5 fps and faster. */
    public FaceTracker() {
        this(MAX_GAP);
    }

    /** @param maxGap the longest time without the face before its track ends; see {@link #gapFor} */
    public FaceTracker(Duration maxGap) {
        if (maxGap.isNegative() || maxGap.isZero()) {
            throw new IllegalArgumentException("maxGap must be positive");
        }
        this.maxGap = maxGap;
    }

    /**
     * The track gap for a measured frame rate: 300 ms, or 1.5 frame intervals when that is longer. At the
     * 3 fps an SBI stream guarantees, frames arrive 333 ms apart, and a fixed 300 ms gap would end the track
     * on every frame. 1.5 intervals (500 ms at 3 fps) absorbs jitter but not a dropped frame: a missing frame
     * still restarts the evidence, which is the safe side.
     */
    public static Duration gapFor(double measuredFps) {
        if (!(measuredFps > 0)) {
            throw new IllegalArgumentException("measured fps must be positive, got " + measuredFps);
        }
        long intervals = (long) Math.ceil(GAP_FRAME_INTERVALS * 1000.0 / measuredFps);
        return Duration.ofMillis(Math.max(MAX_GAP.toMillis(), intervals));
    }

    public Duration maxGap() {
        return maxGap;
    }

    public Update update(FaceBox face, Instant at) {
        boolean lost = false;
        if (trackId != 0 && Duration.between(lastSeen, at).compareTo(maxGap) > 0) {
            trackId = 0;
            lost = true;
        }
        if (face == null) {
            return new Update(trackId, lost);
        }
        if (trackId == 0 || last.iou(face) < MIN_IOU) {
            lost = lost || trackId != 0;
            trackId = nextId++;
        }
        last = face;
        lastSeen = at;
        return new Update(trackId, lost);
    }
}
