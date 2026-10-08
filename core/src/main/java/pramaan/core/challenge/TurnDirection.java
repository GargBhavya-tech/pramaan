/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

/**
 * The resident's <em>own</em> left or right. In the camera's (unmirrored) image, a turn to the resident's
 * left moves the nose towards the image's right, which {@link HeadPoseEstimator} reports as positive yaw.
 */
public enum TurnDirection {
    LEFT(1),
    RIGHT(-1);

    private final int yawSign;

    TurnDirection(int yawSign) {
        this.yawSign = yawSign;
    }

    /** +1 if this turn makes yaw positive, -1 if negative. */
    public int yawSign() {
        return yawSign;
    }
}
