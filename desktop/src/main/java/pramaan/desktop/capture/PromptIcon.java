/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.util.Optional;

/**
 * The icon for a challenge prompt's animation hint (Build Map #91): residents who cannot read the words still see
 * what is asked. Headless, so every hint the engine can send is checked to have one.
 */
public enum PromptIcon {
    TURN_LEFT("anim:head-turn-left"),
    TURN_RIGHT("anim:head-turn-right"),
    BLINK("anim:blink"),
    EYES_CLOSED("anim:eyes-closed"),
    SMILE("anim:smile"),
    GAZE("anim:gaze-dot"),
    LOOK_AT_SCREEN("anim:look-at-screen");

    private final String hint;

    PromptIcon(String hint) {
        this.hint = hint;
    }

    public String hint() {
        return hint;
    }

    /** The icon for an animation hint; empty for a hint no icon exists for (a test fails on any such hint). */
    public static Optional<PromptIcon> of(String animationHint) {
        for (PromptIcon i : values()) {
            if (i.hint.equals(animationHint)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }
}
