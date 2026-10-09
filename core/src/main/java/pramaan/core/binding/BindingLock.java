/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import java.util.List;

/**
 * One of the four capture-binding locks (Bible Part 13; Build Map #57). Each is checked independently and
 * reported separately; any failure rejects the capture.
 */
public interface BindingLock {

    /**
     * @param current the capture being bound, already signature-verified by the binder; null if verification failed
     * @param earlier the session's earlier captures, verified; null if any failed verification
     */
    LockOutcome check(BindingInput input, VerifiedCapture current, List<VerifiedCapture> earlier);
}
