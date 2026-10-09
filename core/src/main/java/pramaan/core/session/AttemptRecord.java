/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pramaan.core.Workflow;
import pramaan.core.policy.AccessibilityProfile;

/**
 * One ended attempt, pass or fail, captured at the moment it ended (Bible Part 42; Build Map #64, #88): ids,
 * the attempt's own nonce and frame-chain head, and how it ended. Hashes and ids only, never biometric data. The
 * session hands it to {@link LivenessSession#onAttemptEnded} so failed attempts reach the transparency log too.
 */
public final class AttemptRecord {

    /** The outcome of an accepted attempt; failures use their {@link FailureReason} name. */
    public static final String ACCEPTED = "ACCEPTED";
    /** A resident with no action path, handed to the supervised exception flow (not a failure). */
    public static final String EXCEPTION_FLOW = "EXCEPTION_FLOW";

    private final String sessionId;
    private final Workflow workflow;
    private final int attempt;
    private final String nonce;
    private final String outcome;
    private final FailureReason failure;
    private final String evidenceHead;
    private final String bindingFinalHash;
    private final String subjectId;
    private final String operatorId;
    private final String centreId;
    private final String deviceId;
    private final Set<AccessibilityProfile> accessibility;

    AttemptRecord(String sessionId, Workflow workflow, int attempt, String nonce, String outcome, FailureReason failure,
            String evidenceHead, String bindingFinalHash, String subjectId, String operatorId, String centreId,
            String deviceId, Set<AccessibilityProfile> accessibility) {
        this.sessionId = sessionId;
        this.workflow = workflow;
        this.attempt = attempt;
        this.nonce = nonce;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.failure = failure;
        this.evidenceHead = evidenceHead;
        this.bindingFinalHash = bindingFinalHash;
        this.subjectId = subjectId;
        this.operatorId = operatorId;
        this.centreId = centreId;
        this.deviceId = deviceId;
        this.accessibility = Collections.unmodifiableSet(accessibility.isEmpty()
                ? EnumSet.noneOf(AccessibilityProfile.class) : EnumSet.copyOf(accessibility));
    }

    public String sessionId() {
        return sessionId;
    }

    public Workflow workflow() {
        return workflow;
    }

    public int attempt() {
        return attempt;
    }

    /** This attempt's capture nonce, as sent in {@code transactionId}. */
    public String nonce() {
        return nonce;
    }

    /** {@link #ACCEPTED}, {@link #EXCEPTION_FLOW} or a {@link FailureReason} name. */
    public String outcome() {
        return outcome;
    }

    public Optional<FailureReason> failure() {
        return Optional.ofNullable(failure);
    }

    /** Head of the attempt's frame hash chain when it ended. */
    public String evidenceHead() {
        return evidenceHead;
    }

    /** H_final for an accepted attempt; empty otherwise. */
    public Optional<String> bindingFinalHash() {
        return Optional.ofNullable(bindingFinalHash);
    }

    public String subjectId() {
        return subjectId;
    }

    public String operatorId() {
        return operatorId;
    }

    public String centreId() {
        return centreId;
    }

    public String deviceId() {
        return deviceId;
    }

    public Set<AccessibilityProfile> accessibility() {
        return accessibility;
    }
}
