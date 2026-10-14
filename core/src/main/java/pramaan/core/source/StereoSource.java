/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.util.Optional;
import pramaan.core.pad.StereoCalibration;

/**
 * The auxiliary camera of a second-camera station (Bible Part 38): the {@link Capability#SECOND_CAMERA} capability.
 * The primary SBI device stays the signed source; this is unsigned and only ever adds evidence.
 *
 * <p>An implementation detects the face in both rectified views and returns the matching landmarks. The landmark
 * order is fixed so the engine need not know the detector: index {@link #NOSE_TIP}, then {@link #LEFT_CHEEK} and
 * {@link #RIGHT_CHEEK}, then at least five more (8 or more in all).
 */
public interface StereoSource {

    int NOSE_TIP = 0;
    int LEFT_CHEEK = 1;
    int RIGHT_CHEEK = 2;

    /** One synchronised pair. Coordinates are x0,y0,x1,y1,... in pixels of the rectified images. */
    final class Pair {
        private final StereoCalibration calibration;
        private final double[] left;
        private final double[] right;
        private final double leftMillis;
        private final double rightMillis;

        public Pair(StereoCalibration calibration, double[] left, double[] right, double leftMillis, double rightMillis) {
            this.calibration = calibration;
            this.left = left.clone();
            this.right = right.clone();
            this.leftMillis = leftMillis;
            this.rightMillis = rightMillis;
        }

        public StereoCalibration calibration() {
            return calibration;
        }

        public double[] left() {
            return left.clone();
        }

        public double[] right() {
            return right.clone();
        }

        public double leftMillis() {
            return leftMillis;
        }

        public double rightMillis() {
            return rightMillis;
        }
    }

    /** The latest pair for a primary frame captured at this time, or empty if the second camera saw no usable face. */
    Optional<Pair> pairFor(long primaryFrameMillis);
}
