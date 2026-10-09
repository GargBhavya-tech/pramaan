/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import java.util.Collections;
import java.util.List;

/**
 * The binding verdict (Build Map #57). No public constructor: only {@link CaptureBinder} makes one, and a
 * session accepts it only for the nonce and evidence head it was computed against, so ACCEPT cannot be
 * reached with a result forged, reused, or taken from another attempt.
 */
public final class BindingResult {

    private final String nonce;
    private final String evidenceHead;
    private final List<LockOutcome> outcomes;
    private final String finalHash;

    BindingResult(String nonce, String evidenceHead, List<LockOutcome> outcomes, String finalHash) {
        this.nonce = nonce;
        this.evidenceHead = evidenceHead;
        this.outcomes = Collections.unmodifiableList(outcomes);
        this.finalHash = finalHash;
    }

    public boolean passed() {
        return outcomes.stream().allMatch(LockOutcome::passed);
    }

    public String nonce() {
        return nonce;
    }

    public String evidenceHead() {
        return evidenceHead;
    }

    public List<LockOutcome> outcomes() {
        return outcomes;
    }

    /** H_final binding our frame chain to MOSIP's capture; null unless the chain lock passed. */
    public String finalHash() {
        return finalHash;
    }

    /** "nonce=ok,chain=ok,identity=identity_mismatch,time=ok" for the audit log. */
    public String summary() {
        return Locks.describe(outcomes);
    }
}
