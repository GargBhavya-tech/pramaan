/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

/**
 * When an action began, bracketed by frames: it started after the last frame without it
 * ({@link #earliestMs()}) and by the first frame with it ({@link #latestMs()}). At 30 fps the bracket is
 * 33 ms wide; at 3 fps it is 333 ms, which is why the early-onset rule switches off at low frame rates.
 */
public final class Onset {

    private final long earliestMs;
    private final long latestMs;

    public Onset(long earliestMs, long latestMs) {
        if (earliestMs > latestMs) {
            throw new IllegalArgumentException("earliest after latest");
        }
        this.earliestMs = earliestMs;
        this.latestMs = latestMs;
    }

    public long earliestMs() {
        return earliestMs;
    }

    public long latestMs() {
        return latestMs;
    }
}
