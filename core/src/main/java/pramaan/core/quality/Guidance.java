/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.quality;

import pramaan.core.gate.FaceGate;

/**
 * Message ids for the person in front of the camera (Build Map #22). Quality problems get actionable
 * guidance and never become a PAD verdict — poor lighting must not look like an attack (Bible Part 6).
 * The UI owns the wording, in every language; core only names the message.
 */
public enum Guidance {
    LOOK_AT_THE_CAMERA("pramaan.guidance.look-at-camera"),
    ONLY_ONE_PERSON("pramaan.guidance.one-person"),
    MOVE_CLOSER("pramaan.guidance.move-closer"),
    FACE_THE_CAMERA("pramaan.guidance.face-camera"),
    HOLD_STILL("pramaan.guidance.hold-still"),
    MORE_LIGHT("pramaan.guidance.more-light"),
    LESS_LIGHT("pramaan.guidance.less-light"),
    EVEN_LIGHTING("pramaan.guidance.even-light"),
    MOVE_AWAY_FROM_LIGHT_BEHIND_YOU("pramaan.guidance.light-behind"),
    NONE(null);

    private final String messageId;

    Guidance(String messageId) {
        this.messageId = messageId;
    }

    /** The Bible Part 19 message id a screen translates; empty for {@link #NONE}, when nothing is shown. */
    public java.util.Optional<String> messageId() {
        return java.util.Optional.ofNullable(messageId);
    }

    public static Guidance forGate(FaceGate.Status status) {
        switch (status) {
            case NO_FACE:
                return LOOK_AT_THE_CAMERA;
            case MULTIPLE_FACES:
                return ONLY_ONE_PERSON;
            default:
                return NONE;
        }
    }

    public static Guidance forQuality(QualityReport report, double faceBrightnessScoreSide) {
        if (report.passes()) {
            return NONE;
        }
        switch (report.worst()) {
            case SIZE:
                return MOVE_CLOSER;
            case POSE:
                return FACE_THE_CAMERA;
            case SHARPNESS:
                return HOLD_STILL;
            case BRIGHTNESS:
                return faceBrightnessScoreSide < 0 ? MORE_LIGHT : LESS_LIGHT;
            case UNIFORMITY:
                return EVEN_LIGHTING;
            case BACKLIGHT:
                return MOVE_AWAY_FROM_LIGHT_BEHIND_YOU;
            case DETECTOR_CONFIDENCE:
            default:
                return FACE_THE_CAMERA;
        }
    }
}
