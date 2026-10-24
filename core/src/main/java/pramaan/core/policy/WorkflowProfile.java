/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import java.util.regex.Pattern;

/**
 * Liveness settings for one workflow. Note what is absent: there are no score thresholds here. The
 * profile names an <em>operating point</em>; the thresholds live in the harness-generated table bound
 * to a model hash (Bible Part 9; Build Map #37). A raw threshold in config would drift silently.
 */
public final class WorkflowProfile {

    private static final Pattern OPERATING_POINT_NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final String operatingPoint;
    private final boolean activeEnabled;
    private final double auditChallengeProbability;
    private final int minCues;
    private final ChallengePolicy challenge;
    private final RetryPolicy retries;
    private final LockoutAction onLockout;
    private final boolean requireUnfakeableVote;

    private WorkflowProfile(String operatingPoint, boolean activeEnabled, double auditChallengeProbability, int minCues,
            ChallengePolicy challenge, RetryPolicy retries, LockoutAction onLockout, boolean requireUnfakeableVote) {
        this.requireUnfakeableVote = requireUnfakeableVote;
        this.operatingPoint = operatingPoint;
        this.activeEnabled = activeEnabled;
        this.auditChallengeProbability = auditChallengeProbability;
        this.minCues = minCues;
        this.challenge = challenge;
        this.retries = retries;
        this.onLockout = onLockout;
    }

    static WorkflowProfile read(StrictNode n) {
        String operatingPoint = n.text("operatingPoint");
        boolean activeEnabled = n.bool("activeEnabled");
        double audit = n.number("auditChallengeProbability");
        int minCues = n.integer("minCues");
        ChallengePolicy challenge = ChallengePolicy.read(n.object("challenge"));
        RetryPolicy retries = RetryPolicy.read(n.object("retries"));
        LockoutAction onLockout = n.enumValue("onLockout", LockoutAction.class);
        boolean requireUnfakeableVote = n.boolOr("requireUnfakeableVote", false);
        n.finish();

        String p = n.path();
        if (!OPERATING_POINT_NAME.matcher(operatingPoint).matches()) {
            throw new InvalidPolicyException(p + ".operatingPoint: must be a lower-case name like 'resident-default', got '"
                    + operatingPoint + "'");
        }
        if (!(audit >= 0.0 && audit <= 1.0)) {
            throw new InvalidPolicyException(p + ".auditChallengeProbability: must be in [0, 1], got " + audit);
        }
        if (minCues < 1) {
            throw new InvalidPolicyException(p + ".minCues: must be at least 1, got " + minCues);
        }
        return new WorkflowProfile(operatingPoint, activeEnabled, audit, minCues, challenge, retries, onLockout,
                requireUnfakeableVote);
    }

    public String operatingPoint() {
        return operatingPoint;
    }

    /**
     * Red-team finding R-03: an ACCEPT on the first decision needs at least one live vote from a cue the screen cannot
     * fake (flash reflection or stereo depth). Without one the session goes to the challenge instead, and a passed
     * challenge is the live vote. Optional, default false; the shipped policy turns it on for operators and supervisors.
     */
    public boolean requireUnfakeableVote() {
        return requireUnfakeableVote;
    }

    public boolean activeEnabled() {
        return activeEnabled;
    }

    /** Probability of a challenge even when passive evidence is strong — the anti-oracle measure. */
    public double auditChallengeProbability() {
        return auditChallengeProbability;
    }

    public int minCues() {
        return minCues;
    }

    public ChallengePolicy challenge() {
        return challenge;
    }

    public RetryPolicy retries() {
        return retries;
    }

    public LockoutAction onLockout() {
        return onLockout;
    }
}
