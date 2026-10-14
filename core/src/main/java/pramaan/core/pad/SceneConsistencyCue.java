/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import pramaan.core.image.RgbImage;

/**
 * Scene-fingerprint cue, part A of Bible Part 43 (Build Map #87): is the background behind the resident the room this
 * device was installed in? An injected video recorded elsewhere shows the wrong room and scores low.
 *
 * <pre>S_bg = alpha * HistCorr + (1 - alpha) * (matched keypoints / fingerprint keypoints)</pre>
 *
 * <p>Abstains ({@code CAPABILITY_MISSING}) when no fingerprint was ever captured — the Bible's "no backdrop
 * fingerprint" — and ({@code INPUT_TOO_SMALL}) when the resident hides so much of the frame that too little
 * background remains to compare. When the matches have moved together by a consistent offset, the camera has been
 * bumped, not the room swapped (the Bible's FOV proxy): the cue abstains ({@code NOT_APPLICABLE}) and reports
 * {@link Analysis#cameraMoved} so the caller can ask for an <em>audited re-fingerprint</em> instead of rejecting
 * residents. {@link #PROVISIONAL_SCORE} is a placeholder until the centre calibration statistics (#65) exist.
 */
public final class SceneConsistencyCue {

    public static final String NAME = "scene";
    public static final double ALPHA = 0.5;
    /**
     * PROVISIONAL: S_bg at which the vote crosses 0.5. Above the 0.45 that a room of similar colours scores on its
     * histogram alone (correlation about 0.9 with no keypoint matching at all), so that a room that merely looks
     * alike does not sit on the fence.
     */
    public static final double PROVISIONAL_SCORE = 0.55;
    private static final double SLOPE = 0.08;
    /** Visible background below this fraction of the frame is not enough to judge. */
    public static final double MIN_VISIBLE_FRACTION = 0.25;
    /** Fingerprints with fewer keypoints than this (a blank wall) cannot support the match term. */
    public static final int MIN_FINGERPRINT_KEYPOINTS = 12;

    /** Everything measured, for diagnostics and the evidence record. */
    public static final class Analysis {
        public final CueResult result;
        public final double score;
        public final boolean cameraMoved;
        public final SceneFingerprint.Comparison comparison;

        Analysis(CueResult result, double score, boolean cameraMoved, SceneFingerprint.Comparison comparison) {
            this.result = result;
            this.score = score;
            this.cameraMoved = cameraMoved;
            this.comparison = comparison;
        }
    }

    /**
     * @param stored the installation-time fingerprint, or null
     * @param frame the live frame
     * @param residentBox {@code {x0, y0, x1, y1}} of the resident (face box grown to the body) in frame pixels
     */
    public CueResult evaluate(SceneFingerprint stored, RgbImage frame, int[] residentBox) {
        return analyse(stored, frame, residentBox).result;
    }

    public Analysis analyse(SceneFingerprint stored, RgbImage frame, int[] residentBox) {
        if (stored == null || stored.keypointCount() < MIN_FINGERPRINT_KEYPOINTS) {
            return new Analysis(CueResult.abstain(NAME, CueResult.AbstainReason.CAPABILITY_MISSING), Double.NaN, false,
                    null);
        }
        return analyseLive(stored, SceneFingerprint.capture(frame, residentBox));
    }

    /** As above, leaving out exactly the pixels a segmenter found to be people instead of a rectangle round them. */
    public Analysis analyse(SceneFingerprint stored, RgbImage frame, PersonMask people) {
        if (stored == null || stored.keypointCount() < MIN_FINGERPRINT_KEYPOINTS) {
            return new Analysis(CueResult.abstain(NAME, CueResult.AbstainReason.CAPABILITY_MISSING), Double.NaN, false,
                    null);
        }
        return analyseLive(stored, SceneFingerprint.capture(frame, people));
    }

    private Analysis analyseLive(SceneFingerprint stored, SceneFingerprint live) {
        SceneFingerprint.Comparison cmp = stored.compare(live);
        if (cmp.visibleFraction < MIN_VISIBLE_FRACTION) {
            return new Analysis(CueResult.abstain(NAME, CueResult.AbstainReason.INPUT_TOO_SMALL), Double.NaN, false,
                    cmp);
        }
        if (cmp.cameraMoved) {
            return new Analysis(CueResult.abstain(NAME, CueResult.AbstainReason.NOT_APPLICABLE), cmp.score(ALPHA), true,
                    cmp);
        }
        double s = cmp.score(ALPHA);
        double p = 1 / (1 + Math.exp(-(s - PROVISIONAL_SCORE) / SLOPE));
        return new Analysis(CueResult.vote(NAME, Math.max(1e-6, Math.min(1 - 1e-6, p)), s), s, false, cmp);
    }
}
