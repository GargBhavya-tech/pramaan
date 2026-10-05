/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A request for one signed face capture. The {@code transactionId} is the session nonce: the device
 * signs it into the capture, which is what ties the capture to this session (Build Map #18, #54).
 */
public final class CaptureRequest {

    /** SBI: 4-50 characters, letters, digits and hyphens only. */
    public static final Pattern TRANSACTION_ID = Pattern.compile("[A-Za-z0-9-]{4,50}");
    private static final Pattern PREVIOUS_HASH = Pattern.compile("([0-9A-Fa-f]{64})?");

    private final String transactionId;
    private final String previousHash;
    private final int requestedScore;
    private final Duration timeout;

    /**
     * @param previousHash the previous capture's chained hash in this session, or "" for the first capture
     */
    public CaptureRequest(String transactionId, String previousHash, int requestedScore, Duration timeout) {
        if (transactionId == null || !TRANSACTION_ID.matcher(transactionId).matches()) {
            throw new IllegalArgumentException("transactionId must be 4-50 letters, digits or hyphens: " + transactionId);
        }
        if (previousHash == null || !PREVIOUS_HASH.matcher(previousHash).matches()) {
            throw new IllegalArgumentException("previousHash must be empty or 64 hex characters");
        }
        if (requestedScore < 0 || requestedScore > 100) {
            throw new IllegalArgumentException("requestedScore must be 0..100, got " + requestedScore);
        }
        this.transactionId = transactionId;
        this.previousHash = previousHash;
        this.requestedScore = requestedScore;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public String transactionId() {
        return transactionId;
    }

    public String previousHash() {
        return previousHash;
    }

    public int requestedScore() {
        return requestedScore;
    }

    public Duration timeout() {
        return timeout;
    }
}
