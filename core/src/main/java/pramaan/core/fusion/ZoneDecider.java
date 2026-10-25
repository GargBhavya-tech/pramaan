/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.fusion;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.DoubleSupplier;
import pramaan.core.policy.OperatingPoint;
import pramaan.core.policy.WorkflowProfile;

/**
 * Turns fused evidence into a zone and tier (Bible Part 9; Build Map #36, #38, #39). In order:
 * <ol>
 *   <li>Any hard veto: RED, without looking at the score.</li>
 *   <li>Score zone: P >= tauAccept accept, P <= tauReject reject, otherwise challenge.</li>
 *   <li>Fewer than {@code minCues} contributing: a would-be accept <em>or</em> reject becomes a challenge.
 *       One model's opinion is not a clear attack, and a challenge unlocks more cues (a turn unlocks
 *       flatness).</li>
 *   <li>Contributing cues disagree by more than the table's logit gap: a would-be accept becomes a
 *       challenge. A reject stays a reject — a challenge is free practice for an attacker.</li>
 *   <li>Random audit: with probability q, a WHITE accept is challenged anyway, so "I got a challenge"
 *       does not tell anyone they nearly failed.</li>
 * </ol>
 * Every verdict carries a random 200-600 ms display delay, so timing does not reveal the path either.
 * The randomness is injected: production passes {@code new SecureRandom()::nextDouble}.
 */
public final class ZoneDecider {

    public static final long MIN_DELAY_MS = 200;
    public static final long MAX_DELAY_MS = 600;

    /**
     * The cues an attacker cannot answer by showing the camera a screen: the flash cue needs the live face to reflect the
     * station's own random light, the stereo cue needs real depth. Names as in the operating-point table.
     */
    public static final Set<String> UNFAKEABLE_CUES = Set.of("flash", "stereo");

    private final DoubleSupplier uniform;
    private final double disagreementLogitGap;

    /**
     * @param uniform draws uniformly from [0, 1); an attacker must not be able to predict it
     * @param disagreementLogitGap from the operating-point table
     */
    public ZoneDecider(DoubleSupplier uniform, double disagreementLogitGap) {
        this.uniform = Objects.requireNonNull(uniform, "uniform");
        this.disagreementLogitGap = disagreementLogitGap;
    }

    /** The first decision of an attempt, audit challenge included. */
    public Decision decide(FusionResult fusion, OperatingPoint point, WorkflowProfile profile, Set<Veto> vetoes) {
        return decide(fusion, point, profile, vetoes, true, false);
    }

    /**
     * As {@link #decide(FusionResult, OperatingPoint, WorkflowProfile, Set)}, for a session whose operator chose an
     * accessibility profile. Such a resident is asked fewer or gentler checks by design (infant, cannot see the screen,
     * limited movement), so the "needs a live vote from a cue the display cannot fake" rule (red-team R-03) stands down for
     * them rather than force a challenge they may not be able to answer: it must never lock out the people the inclusion
     * rules protect.
     */
    public Decision decide(FusionResult fusion, OperatingPoint point, WorkflowProfile profile, Set<Veto> vetoes,
            boolean accessibilityAdjusted) {
        return decide(fusion, point, profile, vetoes, true, accessibilityAdjusted);
    }

    /**
     * The re-decision after a completed challenge (Bible Part 11.6). Never audits again: with audit
     * probability 1 (supervisors) every accept would demand another challenge, forever.
     */
    public Decision decideAfterChallenge(FusionResult fusion, OperatingPoint point, WorkflowProfile profile,
            Set<Veto> vetoes) {
        return decide(fusion, point, profile, vetoes, false, false);
    }

    private static boolean hasUnfakeableLiveVote(FusionResult fusion) {
        for (java.util.Map.Entry<String, Double> c : fusion.contributions().entrySet()) {
            if (UNFAKEABLE_CUES.contains(c.getKey()) && c.getValue() > 0) {
                return true;
            }
        }
        return false;
    }

    private Decision decide(FusionResult fusion, OperatingPoint point, WorkflowProfile profile, Set<Veto> vetoes,
            boolean auditEligible, boolean accessibilityAdjusted) {
        long delay = MIN_DELAY_MS + (long) Math.floor(uniform.getAsDouble() * (MAX_DELAY_MS - MIN_DELAY_MS + 1));
        if (!vetoes.isEmpty()) {
            return new Decision(Zone.REJECT_PAD, Tier.RED, EnumSet.of(Decision.Reason.VETO), vetoes, null, point.name(), delay);
        }
        double p = fusion.pLive();
        Set<Decision.Reason> reasons = EnumSet.of(Decision.Reason.SCORE);
        Zone zone = p >= point.tauAccept() ? Zone.ACCEPT_CANDIDATE : p <= point.tauReject() ? Zone.REJECT_PAD : Zone.CHALLENGE;

        if (zone != Zone.CHALLENGE && fusion.contributing() < profile.minCues()) {
            zone = Zone.CHALLENGE;
            reasons.add(Decision.Reason.TOO_FEW_CUES);
        }
        if (zone == Zone.ACCEPT_CANDIDATE && fusion.logitGap() > disagreementLogitGap) {
            zone = Zone.CHALLENGE;
            reasons.add(Decision.Reason.DISAGREEMENT);
        }
        if (zone == Zone.ACCEPT_CANDIDATE && auditEligible && profile.requireUnfakeableVote() && !accessibilityAdjusted
                && !hasUnfakeableLiveVote(fusion)) {
            // Red-team R-03: scene and moire, both shown by the attacker, can add up to an accept. Ask for a challenge.
            // Not applied after a challenge: passing it is the live vote.
            zone = Zone.CHALLENGE;
            reasons.add(Decision.Reason.NO_DISPLAY_PROOF);
        }
        if (zone == Zone.ACCEPT_CANDIDATE) {
            if (auditEligible && profile.activeEnabled() && uniform.getAsDouble() < profile.auditChallengeProbability()) {
                reasons.add(Decision.Reason.AUDIT);
                return new Decision(Zone.CHALLENGE, Tier.WHITE, reasons, vetoes, fusion, point.name(), delay);
            }
            return new Decision(zone, Tier.WHITE, reasons, vetoes, fusion, point.name(), delay);
        }
        return new Decision(zone, zone == Zone.REJECT_PAD ? Tier.RED : Tier.BLUE, reasons, vetoes, fusion, point.name(), delay);
    }
}
