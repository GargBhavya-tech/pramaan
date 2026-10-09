/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

/** Liveness cannot run. The caller must show the error's message and must not proceed without liveness. */
public final class EngineUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    private final LivenessError error;

    public EngineUnavailableException(LivenessError error, String detail, Throwable cause) {
        super(error + ": " + detail, cause);
        this.error = error;
    }

    public LivenessError error() {
        return error;
    }
}
