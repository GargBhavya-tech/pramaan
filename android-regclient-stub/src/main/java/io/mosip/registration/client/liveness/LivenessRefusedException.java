/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration.client.liveness;

/**
 * Thrown by a {@link LivenessHook} to stop a face capture: liveness could not be established, or the signed capture is
 * not the face that was checked. The client shows its generic capture-failure message and stores nothing; the reason
 * belongs in the hook's own audit log, not on the screen.
 */
public class LivenessRefusedException extends Exception {

	private static final long serialVersionUID = 1L;

	public LivenessRefusedException(String message) {
		super(message);
	}
}
