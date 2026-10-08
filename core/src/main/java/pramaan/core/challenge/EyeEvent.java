/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

/** A completed eye action. */
public final class EyeEvent {

    public enum Type {
        /** Closed for 50-500 ms, then open again. */
        BLINK,
        /** Closed continuously for at least the requested hold. */
        CLOSED_HOLD
    }

    private final Type type;
    private final Onset onset;
    private final long durationMs;

    EyeEvent(Type type, Onset onset, long durationMs) {
        this.type = type;
        this.onset = onset;
        this.durationMs = durationMs;
    }

    public Type type() {
        return type;
    }

    public Onset onset() {
        return onset;
    }

    public long durationMs() {
        return durationMs;
    }
}
