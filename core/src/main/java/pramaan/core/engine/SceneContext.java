/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import pramaan.core.detect.FaceBox;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.LightDirectionConsistencyCue;
import pramaan.core.pad.SceneConsistencyCue;
import pramaan.core.pad.SceneFingerprint;
import pramaan.core.pad.WhitePointConsistencyCue;

/**
 * What a station knows about its own room (Bible Part 43; Build Map #87): the stored fingerprint of the empty
 * backdrop, and the three cues that compare each frame's visible background and the resident's face with it — the
 * scene fingerprint, the white point and the light direction.
 *
 * <p>The resident is excluded from the background by a box grown from the face box (the Bible suggests a
 * segmentation model; the box loses some background rather than ever counting the resident as scenery). The backdrop
 * crop for the two illumination cues is the wider side margin outside that box. Fingerprinting costs real time, so
 * only every {@value #EVERY_NTH_FRAME}th frame is evaluated: the engine averages cue votes over the attempt anyway.
 *
 * <p>When the stored fingerprint matches the live frame at a consistent offset, the camera has been bumped, not the
 * room swapped: {@link #cameraMoved()} turns true so the station can ask for an <em>audited re-fingerprint</em>
 * instead of rejecting residents.
 */
public final class SceneContext {

    /** Fingerprint on one frame in this many. */
    public static final int EVERY_NTH_FRAME = 5;
    private static final int MIN_MARGIN_PX = 24;

    private final SceneFingerprint stored;
    private final SceneConsistencyCue scene = new SceneConsistencyCue();
    private final WhitePointConsistencyCue whitePoint = new WhitePointConsistencyCue();
    private final LightDirectionConsistencyCue lightDirection = new LightDirectionConsistencyCue();
    private int frames;
    private volatile boolean cameraMoved;

    public SceneContext(SceneFingerprint stored) {
        this.stored = Objects.requireNonNull(stored, "stored");
    }

    /** True once a frame matched the stored backdrop at a consistent offset: re-fingerprint, audited. */
    public boolean cameraMoved() {
        return cameraMoved;
    }

    /** Cue results for this frame, or none on a frame that is skipped to save time. */
    List<CueResult> evaluate(RgbImage image, FaceBox face, RgbImage nativeFaceCrop) {
        if (frames++ % EVERY_NTH_FRAME != 0) {
            return List.of();
        }
        int x0 = Math.max(0, Math.round(face.x() - 0.6f * face.width()));
        int x1 = Math.min(image.width(), Math.round(face.x() + 1.6f * face.width()));
        int y0 = Math.max(0, Math.round(face.y() - 0.4f * face.height()));
        int[] resident = {x0, y0, x1, image.height()};

        List<CueResult> out = new ArrayList<>();
        SceneConsistencyCue.Analysis a = scene.analyse(stored, image, resident);
        if (a.cameraMoved) {
            cameraMoved = true;
        }
        out.add(a.result);
        RgbImage backdrop = backdrop(image, x0, x1);
        out.add(whitePoint.evaluate(backdrop, nativeFaceCrop));
        out.add(lightDirection.evaluate(nativeFaceCrop, backdrop));
        return out;
    }

    /** The wider of the two side margins outside the resident box, or null if neither is wide enough to judge. */
    static RgbImage backdrop(RgbImage image, int residentX0, int residentX1) {
        int left = residentX0;
        int right = image.width() - residentX1;
        if (Math.max(left, right) < MIN_MARGIN_PX || image.height() < MIN_MARGIN_PX) {
            return null;
        }
        return left >= right
                ? ImageOps.crop(image, 0, 0, left - 1, image.height() - 1)
                : ImageOps.crop(image, residentX1, 0, image.width() - 1, image.height() - 1);
    }
}
