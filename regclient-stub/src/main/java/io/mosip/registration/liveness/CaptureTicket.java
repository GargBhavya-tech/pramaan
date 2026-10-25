/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration.liveness;

/**
 * What a liveness hook hands the client when it clears a capture: the two values the client must put in its rCapture
 * request so the device signs them into the capture (SBI 1.0: {@code transactionId} and the bio element's
 * {@code previousHash}). Either may be {@code null}, which keeps the client's own behaviour for that value.
 */
public final class CaptureTicket {

	private final String transactionId;
	private final String previousHash;

	public CaptureTicket(String transactionId, String previousHash) {
		this.transactionId = transactionId;
		this.previousHash = previousHash;
	}

	/** 4 to 50 characters of letters, digits and hyphens (SBI), or null. */
	public String transactionId() {
		return transactionId;
	}

	/** The hash the capture chains to, or null. */
	public String previousHash() {
		return previousHash;
	}
}
