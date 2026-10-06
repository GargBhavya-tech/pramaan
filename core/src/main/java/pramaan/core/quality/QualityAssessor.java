/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.quality;

import java.util.EnumMap;
import java.util.Map;
import pramaan.core.detect.FaceBox;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;

/**
 * Face-image quality from the frame and the detector's five landmarks (Build Map #21). Every component
 * maps to [0, 1] with 0.5 exactly at its limit. Pose here is a coarse five-point estimate; head-pose
 * from full landmarks comes with #45. Sharpness and backlight limits are PROVISIONAL until calibrated on
 * the corpus (#65).
 */
public final class QualityAssessor {

    public static final double MIN_IOD_LIVENESS = 60;
    public static final double MIN_IOD_CAPTURE = 90;
    public static final double MAX_YAW_DEG = 15;
    public static final double MAX_PITCH_DEG = 15;
    public static final double MIN_LUMA = 60;
    public static final double MAX_LUMA = 200;
    public static final double MAX_UNIFORMITY = 0.35;
    /** PROVISIONAL: Laplacian variance of a 112x112 face crop at the pass limit. */
    public static final double MIN_SHARPNESS = 60;
    /** PROVISIONAL: the face at least 60% as bright as the whole frame. */
    public static final double MIN_BACKLIGHT_RATIO = 0.6;
    public static final double MIN_CONFIDENCE = 0.8;

    public QualityReport assess(RgbImage frame, FaceBox face, boolean forCapture) {
        Map<QualityComponent, Double> s = new EnumMap<>(QualityComponent.class);
        double iod = face.interOcularDistance();
        s.put(QualityComponent.SIZE, atLeast(iod, forCapture ? MIN_IOD_CAPTURE : MIN_IOD_LIVENESS));
        s.put(QualityComponent.POSE, Math.min(atMost(Math.abs(yawDegrees(face)), MAX_YAW_DEG),
                atMost(Math.abs(pitchDegrees(face)), MAX_PITCH_DEG)));
        s.put(QualityComponent.DETECTOR_CONFIDENCE, atLeast(face.score(), MIN_CONFIDENCE));

        RgbImage crop = ImageOps.resize(ImageOps.crop(frame, Math.round(face.x()), Math.round(face.y()),
                Math.round(face.x() + face.width()), Math.round(face.y() + face.height())), 112, 112);
        float[] gray = ImageOps.luma(crop);
        s.put(QualityComponent.SHARPNESS, atLeast(ImageOps.laplacianVariance(gray, 112, 112), MIN_SHARPNESS));

        double faceMean = ImageOps.meanColumns(gray, 112, 112, 0, 112);
        s.put(QualityComponent.BRIGHTNESS, faceMean < MIN_LUMA ? atLeast(faceMean, MIN_LUMA)
                : faceMean > MAX_LUMA ? atMost(faceMean, MAX_LUMA) : inRange(faceMean, MIN_LUMA, MAX_LUMA));
        double left = ImageOps.meanColumns(gray, 112, 112, 0, 56);
        double right = ImageOps.meanColumns(gray, 112, 112, 56, 112);
        s.put(QualityComponent.UNIFORMITY, atMost(Math.abs(left - right) / Math.max(1, faceMean), MAX_UNIFORMITY));

        float[] frameGray = ImageOps.luma(ImageOps.resize(frame, 160, 120));
        double frameMean = ImageOps.meanColumns(frameGray, 160, 120, 0, 160);
        s.put(QualityComponent.BACKLIGHT, atLeast(faceMean / Math.max(1, frameMean), MIN_BACKLIGHT_RATIO));
        return new QualityReport(s);
    }

    /** Coarse yaw from the nose's horizontal offset from the eye midpoint, relative to eye distance. */
    static double yawDegrees(FaceBox f) {
        double mid = (f.landmark(0, 0) + f.landmark(1, 0)) / 2;
        double ratio = (f.landmark(2, 0) - mid) / Math.max(1, f.interOcularDistance());
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, 2 * ratio))));
    }

    /** Coarse pitch from where the nose sits between the eye line and the mouth line (about 0.55 when level). */
    static double pitchDegrees(FaceBox f) {
        double eyes = (f.landmark(0, 1) + f.landmark(1, 1)) / 2;
        double mouth = (f.landmark(3, 1) + f.landmark(4, 1)) / 2;
        double t = (f.landmark(2, 1) - eyes) / Math.max(1, mouth - eyes);
        return (t - 0.55) * 90;
    }

    /** 0.5 at the limit, rising to 1 at twice the limit. */
    static double atLeast(double value, double limit) {
        return clamp01(0.5 * value / limit);
    }

    /** 0.5 at the limit, 1 at zero. */
    static double atMost(double value, double limit) {
        return value <= 0 ? 1.0 : clamp01(value <= limit ? 1.0 - 0.5 * value / limit : 0.5 * limit / value);
    }

    /** 1 at the centre of the range, 0.5 at its edges. */
    static double inRange(double value, double lo, double hi) {
        double centre = (lo + hi) / 2;
        double half = (hi - lo) / 2;
        return clamp01(1.0 - 0.5 * Math.abs(value - centre) / half);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
