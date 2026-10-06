/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.gate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import pramaan.core.detect.FaceBox;

/**
 * Exactly one usable face, or a debounced reason why not (Bible Part 6; Build Map #19). Faces smaller
 * than 40% of the main face's inter-ocular distance (an operator in the background) are ignored, and an
 * error is reported only after it has persisted for 0.5 s, so a passer-by does not reset the session.
 */
public final class FaceGate {

    /** What the gate sees. */
    public enum Status {
        NO_FACE,
        ONE_FACE,
        MULTIPLE_FACES
    }

    public static final Duration DEBOUNCE = Duration.ofMillis(500);
    public static final double BACKGROUND_FACE_RATIO = 0.4;

    private Status reported = Status.NO_FACE;
    private Status candidate;
    private Instant candidateSince;

    /** Result of one update: the debounced status and, when there is one, the main face. */
    public static final class Result {
        private final Status status;
        private final FaceBox primary;

        Result(Status status, FaceBox primary) {
            this.status = status;
            this.primary = primary;
        }

        public Status status() {
            return status;
        }

        /** The largest face, or null when none was detected this frame. */
        public FaceBox primary() {
            return primary;
        }
    }

    public Result update(List<FaceBox> faces, Instant at) {
        FaceBox primary = null;
        for (FaceBox f : faces) {
            if (primary == null || f.interOcularDistance() > primary.interOcularDistance()) {
                primary = f;
            }
        }
        int significant = 0;
        for (FaceBox f : faces) {
            if (f.interOcularDistance() >= BACKGROUND_FACE_RATIO * primary.interOcularDistance()) {
                significant++;
            }
        }
        Status raw = faces.isEmpty() ? Status.NO_FACE : significant > 1 ? Status.MULTIPLE_FACES : Status.ONE_FACE;

        if (raw == Status.ONE_FACE) {
            reported = Status.ONE_FACE;
            candidate = null;
        } else if (raw != reported) {
            if (candidate != raw) {
                candidate = raw;
                candidateSince = at;
            } else if (Duration.between(candidateSince, at).compareTo(DEBOUNCE) >= 0) {
                reported = raw;
                candidate = null;
            }
        } else {
            candidate = null;
        }
        return new Result(reported, primary);
    }
}
