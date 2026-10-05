/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.time.Instant;
import java.util.Objects;

/** Something happened to the device rather than to the face (Bible Part 22 error table). */
public final class DeviceEvent {

    /** Event kinds. Infrastructure problems never consume a person's retry budget (Build Map #49). */
    public enum Type {
        CONNECTED,
        DISCONNECTED,
        ERROR,
        FORMAT_CHANGED,
        /** The consumer fell behind and old frames were dropped (bounded queue, Build Map #13). */
        FRAMES_DROPPED
    }

    private final Type type;
    private final String detail;
    private final Instant at;

    public DeviceEvent(Type type, String detail, Instant at) {
        this.type = Objects.requireNonNull(type, "type");
        this.detail = detail == null ? "" : detail;
        this.at = Objects.requireNonNull(at, "at");
    }

    public Type type() {
        return type;
    }

    public String detail() {
        return detail;
    }

    public Instant at() {
        return at;
    }

    @Override
    public String toString() {
        return type + (detail.isEmpty() ? "" : ": " + detail) + " @ " + at;
    }
}
