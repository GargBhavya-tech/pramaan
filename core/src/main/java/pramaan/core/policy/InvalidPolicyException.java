/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

/**
 * A policy file that cannot be trusted as written. Always names the offending path, e.g.
 * {@code pramaan.profiles.RESIDENT.challenge.minSteps}. The engine refuses to start on one: silently
 * defaulting a security setting is worse than not running (Bible Part 22, "fail closed").
 */
public final class InvalidPolicyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidPolicyException(String message) {
        super(message);
    }

    public InvalidPolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
