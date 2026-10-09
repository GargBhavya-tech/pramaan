/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

/** One lock's verdict, with a reason code for the audit log and, where it has one, the measured value. */
public final class LockOutcome {

    private final String lock;
    private final boolean passed;
    private final String code;
    private final double value;

    private LockOutcome(String lock, boolean passed, String code, double value) {
        this.lock = lock;
        this.passed = passed;
        this.code = code;
        this.value = value;
    }

    static LockOutcome pass(String lock, double value) {
        return new LockOutcome(lock, true, "OK", value);
    }

    static LockOutcome fail(String lock, String code, double value) {
        return new LockOutcome(lock, false, code, value);
    }

    public String lock() {
        return lock;
    }

    public boolean passed() {
        return passed;
    }

    /** OK, or why it failed (NONCE_MISMATCH, SIGNATURE_INVALID, STALE_TIMESTAMP, CHAIN_HASH_MISMATCH, ...). */
    public String code() {
        return code;
    }

    /** The measured value (similarity, gap in ms, ...); NaN when there is none. */
    public double value() {
        return value;
    }

    @Override
    public String toString() {
        return lock + ":" + code + (Double.isNaN(value) ? "" : "(" + value + ")");
    }
}
