/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.util.Objects;

/** A device-side failure, classified so the session can map it to Bible Part 22 without string matching. */
public final class DeviceException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why the device call failed. */
    public enum Reason {
        /** Nothing answered, or no suitable device was found. */
        UNAVAILABLE,
        /** The device reported itself busy or not ready. */
        BUSY,
        /** The device answered but not in the expected shape. */
        PROTOCOL,
        /** The device reported an error of its own. */
        DEVICE_ERROR,
        TIMEOUT,
        /** The response's signature could not be verified. Never retried silently. */
        UNTRUSTED
    }

    private final Reason reason;

    public DeviceException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public DeviceException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
