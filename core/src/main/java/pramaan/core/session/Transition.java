/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import java.time.Instant;

/** One recorded state change. */
public final class Transition {

    private final SessionState from;
    private final SessionState to;
    private final Instant at;
    private final String why;

    Transition(SessionState from, SessionState to, Instant at, String why) {
        this.from = from;
        this.to = to;
        this.at = at;
        this.why = why;
    }

    public SessionState from() {
        return from;
    }

    public SessionState to() {
        return to;
    }

    public Instant at() {
        return at;
    }

    public String why() {
        return why;
    }

    @Override
    public String toString() {
        return from + "->" + to + " (" + why + ")";
    }
}
