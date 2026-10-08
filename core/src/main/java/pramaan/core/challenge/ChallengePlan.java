/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A generated challenge sequence plus the facts the evidence package needs: the seed commitment, the
 * entropy the sequence carries, and whether the accessibility filter left no action at all.
 */
public final class ChallengePlan {

    private final String seedCommitment;
    private final List<Challenge> steps;
    private final FlashSequence flash;
    private final int actionOptions;
    private final double actionBits;
    private final double flashBits;
    private final AccessibilityAdjustments adjustments;
    private final java.util.Set<pramaan.core.policy.ChallengeKind> unlocking;

    private final List<pramaan.core.policy.ChallengeKind> policyPool;
    private final int minSteps;
    private final int maxSteps;
    private final double fps;
    private final java.util.Set<pramaan.core.source.Capability> capabilities;

    ChallengePlan(String seedCommitment, List<Challenge> steps, FlashSequence flash, int actionOptions, double actionBits,
            double flashBits, AccessibilityAdjustments adjustments, java.util.Set<pramaan.core.policy.ChallengeKind> unlocking,
            List<pramaan.core.policy.ChallengeKind> policyPool, int minSteps, int maxSteps, double fps,
            java.util.Set<pramaan.core.source.Capability> capabilities) {
        this.policyPool = Collections.unmodifiableList(new java.util.ArrayList<>(policyPool));
        this.minSteps = minSteps;
        this.maxSteps = maxSteps;
        this.fps = fps;
        this.capabilities = Collections.unmodifiableSet(capabilities.isEmpty()
                ? java.util.EnumSet.noneOf(pramaan.core.source.Capability.class) : java.util.EnumSet.copyOf(capabilities));
        this.seedCommitment = seedCommitment;
        this.steps = Collections.unmodifiableList(steps);
        this.flash = flash;
        this.actionOptions = actionOptions;
        this.actionBits = actionBits;
        this.flashBits = flashBits;
        this.adjustments = adjustments;
        this.unlocking = java.util.Collections.unmodifiableSet(unlocking.isEmpty()
                ? java.util.EnumSet.noneOf(pramaan.core.policy.ChallengeKind.class) : java.util.EnumSet.copyOf(unlocking));
    }

    /** Lowercase hex SHA-256 of the seed; the seed itself is revealed only after the session. */
    public String seedCommitment() {
        return seedCommitment;
    }

    public List<Challenge> steps() {
        return steps;
    }

    public Optional<FlashSequence> flash() {
        return Optional.ofNullable(flash);
    }

    /** How many (kind, parameter) choices each action step was drawn from. */
    public int actionOptions() {
        return actionOptions;
    }

    /**
     * Shannon entropy of the <em>verifiable</em> outcome of each step, times the number of steps. A turn past
     * the target satisfies every smaller target, three blinks satisfy "blink once", a long closure satisfies
     * a short hold: so a turn counts only its direction, and blink counts and hold lengths count nothing.
     * Onset-delay randomness is not counted either: at 3 fps it cannot be resolved.
     */
    public double actionBits() {
        return actionBits;
    }

    /** colours x log2(6). Duration randomness is not counted. */
    public double flashBits() {
        return flashBits;
    }

    /**
     * Below {@link ChallengeGenerator#MIN_BITS}: a pre-recorded clip library is not infeasible. The session
     * must compensate (raise minCues, prefer no-action physics) and flag the packet for server re-check.
     */
    public boolean lowEntropy() {
        return actionBits + flashBits < ChallengeGenerator.MIN_BITS;
    }

    /**
     * Nothing to ask the resident to do: continue on no-action cues and the multimodal path (Bible Part 9
     * step-up ladder, rungs 1, 2 and 4). This is never a reason to reject.
     */
    public boolean needsFallback() {
        return steps.isEmpty() && flash == null;
    }

    public AccessibilityAdjustments adjustments() {
        return adjustments;
    }

    /**
     * The kinds of which the plan had to contain at least one step (Bible Part 10, cue-unlocking): part of the
     * plan's parameters, so the server regenerates the same plan from the revealed seed. Empty when none was
     * required.
     */
    public java.util.Set<pramaan.core.policy.ChallengeKind> unlockingKinds() {
        return unlocking;
    }

    /**
     * Everything else the plan was drawn from, recorded so a server holding the revealed seed can draw it again and
     * compare, step for step (Bible Part 10): the pool as given, the step bounds, the measured frame rate and the
     * device capabilities (which decide BLINK vs EYES_CLOSED and whether FLASH or GAZE exist).
     */
    public List<pramaan.core.policy.ChallengeKind> policyPool() {
        return policyPool;
    }

    public int minSteps() {
        return minSteps;
    }

    public int maxSteps() {
        return maxSteps;
    }

    public double fps() {
        return fps;
    }

    public java.util.Set<pramaan.core.source.Capability> capabilities() {
        return capabilities;
    }
}
