/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.policy.ChallengePolicy;
import pramaan.core.source.SessionCapabilities;

/**
 * Draws a challenge sequence nobody could have prepared for (Bible Part 10; Build Map #41).
 *
 * <p>A fresh 32-byte seed comes from {@link SecureRandom}; everything else is derived from it with
 * HMAC-SHA256, so the server can regenerate the sequence from the seed, which the evidence package reveals
 * after the session, and check it against the SHA-256 commitment recorded before it.
 *
 * <p>Each action step is drawn uniformly from the union of (kind, parameter) options the session allows
 * (24 with every kind). Parameters vary the experience, but entropy is counted only over outcomes a
 * verifier can tell apart: 3.52 bits per step with every kind, 1.86 on a 3 fps desktop without a
 * resident-facing display. Only the flash sequence (~20.7 bits) clears {@link #MIN_BITS} on its own. The
 * number of steps is drawn
 * uniformly from [minSteps, maxSteps] and is deliberately <strong>not</strong> scaled by P(live): a step
 * count that tracked the score would tell an attacker how close they came (Bible 47.8).
 */
public final class ChallengeGenerator {

    /** Below this, a clip library for every sequence stops being infeasible (Bible Part 10). */
    public static final double MIN_BITS = 13.0;
    static final int[] BLINK_COUNTS = {1, 2, 3};
    static final int[] EYES_CLOSED_HOLDS_MS = {1000, 1500, 2000};
    static final int GAZE_CELLS = 9;
    static final int ONSET_DELAY_MIN_MS = 300;
    static final int ONSET_DELAY_STEP_MS = 100;
    static final int ONSET_DELAY_CHOICES = 10;
    static final int FLASH_DURATION_STEP_MS = 50;

    private final byte[] seed;

    private ChallengeGenerator(byte[] seed) {
        if (seed.length != 32) {
            throw new IllegalArgumentException("seed must be 32 bytes");
        }
        this.seed = seed.clone();
    }

    /** A generator with a new secret seed. */
    public static ChallengeGenerator fresh(SecureRandom random) {
        byte[] s = new byte[32];
        Objects.requireNonNull(random, "random").nextBytes(s);
        return new ChallengeGenerator(s);
    }

    /** Rebuilds a generator from a revealed seed, to verify a session's sequence. */
    public static ChallengeGenerator replay(byte[] seed) {
        return new ChallengeGenerator(Objects.requireNonNull(seed, "seed"));
    }

    public String seedCommitment() {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(seed);
            StringBuilder s = new StringBuilder(64);
            for (byte b : d) {
                s.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    /** The seed, for the signed evidence package once the session is over. Never show it earlier. */
    public byte[] revealSeed() {
        return seed.clone();
    }

    public ChallengePlan plan(ChallengePolicy policy, SessionCapabilities caps, AccessibilityAdjustments adjustments) {
        return plan(policy.pool(), policy.minSteps(), policy.maxSteps(), caps, adjustments);
    }

    /** Deterministic in the seed and the arguments: the same inputs always give the same plan. */
    public ChallengePlan plan(List<ChallengeKind> policyPool, int minSteps, int maxSteps, SessionCapabilities caps,
            AccessibilityAdjustments adjustments) {
        return plan(policyPool, minSteps, maxSteps, caps, adjustments, java.util.EnumSet.noneOf(ChallengeKind.class));
    }

    /**
     * As {@link #plan(List, int, int, SessionCapabilities, AccessibilityAdjustments)}, but when any option of an
     * {@code unlocking} kind is allowed, one step — at a uniformly drawn position — is drawn from those options
     * only (Bible Part 10: prefer challenges that unlock abstaining cues; a turn unlocks flatness, a smile
     * deformation). A plan of blinks alone adds no PAD evidence, so re-fusion after it can never move the score.
     * The forced step's entropy is counted from its own, smaller option set.
     */
    public ChallengePlan plan(List<ChallengeKind> policyPool, int minSteps, int maxSteps, SessionCapabilities caps,
            AccessibilityAdjustments adjustments, java.util.Set<ChallengeKind> unlocking) {
        List<ChallengeKind> pool = new ArrayList<>();
        for (ChallengeKind k : caps.effectivePool(policyPool)) {
            if (adjustments.allows(k)) {
                pool.add(k);
            }
        }
        List<Challenge> options = options(pool, adjustments);
        List<Challenge> unlockOptions = new ArrayList<>();
        for (Challenge c : options) {
            if (unlocking.contains(c.kind())) {
                unlockOptions.add(c);
            }
        }
        DeterministicStream stream = new DeterministicStream(seed);

        List<Challenge> steps = new ArrayList<>();
        int forced = -1;
        if (!options.isEmpty()) {
            int count = minSteps + stream.uniformInt(maxSteps - minSteps + 1);
            forced = unlockOptions.isEmpty() ? -1 : stream.uniformInt(count);
            for (int i = 0; i < count; i++) {
                int delay = ONSET_DELAY_MIN_MS + ONSET_DELAY_STEP_MS * stream.uniformInt(ONSET_DELAY_CHOICES);
                List<Challenge> from = i == forced ? unlockOptions : options;
                steps.add(from.get(stream.uniformInt(from.size())).withOnsetDelay(delay));
            }
        }

        FlashSequence flash = null;
        double flashBits = 0;
        if (pool.contains(ChallengeKind.FLASH)) {
            FlashColour[] palette = FlashColour.values();
            int k = caps.flashSteps();
            int min = (int) caps.flashMinStep().toMillis();
            int choices = ((int) caps.flashMaxStep().toMillis() - min) / FLASH_DURATION_STEP_MS + 1;
            List<FlashColour> colours = new ArrayList<>();
            int[] durations = new int[k];
            for (int i = 0; i < k; i++) {
                colours.add(palette[stream.uniformInt(palette.length)]);
                durations[i] = min + FLASH_DURATION_STEP_MS * stream.uniformInt(choices);
            }
            flash = new FlashSequence(colours, durations);
            flashBits = k * log2(palette.length);
        }
        double actionBits = steps.isEmpty() ? 0 : forced < 0 ? steps.size() * outcomeEntropyBits(options)
                : (steps.size() - 1) * outcomeEntropyBits(options) + outcomeEntropyBits(unlockOptions);
        return new ChallengePlan(seedCommitment(), steps, flash, options.size(), actionBits, flashBits, adjustments,
                unlocking, policyPool, minSteps, maxSteps, caps.fps(), caps.device().capabilities());
    }

    /** Every (kind, parameter) combination the session allows, in a fixed order. FLASH is drawn separately. */
    static List<Challenge> options(List<ChallengeKind> pool, AccessibilityAdjustments adjustments) {
        List<Challenge> out = new ArrayList<>();
        for (ChallengeKind kind : pool) {
            switch (kind) {
                case TURN:
                    for (TurnDirection d : TurnDirection.values()) {
                        for (int deg : adjustments.turnTargetsDeg()) {
                            out.add(Challenge.turn(d, deg));
                        }
                    }
                    break;
                case BLINK:
                    for (int n : BLINK_COUNTS) {
                        out.add(Challenge.blink(n));
                    }
                    break;
                case EYES_CLOSED:
                    for (int ms : EYES_CLOSED_HOLDS_MS) {
                        out.add(Challenge.eyesClosed(ms));
                    }
                    break;
                case SMILE:
                    out.add(Challenge.smile());
                    break;
                case GAZE:
                    for (int cell = 0; cell < GAZE_CELLS; cell++) {
                        out.add(Challenge.gaze(cell));
                    }
                    break;
                case FLASH:
                    break;
                default:
                    throw new IllegalStateException("unhandled challenge kind " + kind);
            }
        }
        return out;
    }

    /**
     * What a replayed clip must match to pass a step: the direction of a turn, the cell of a gaze, and only
     * the kind for blink, eyes-closed and smile. See {@link ChallengePlan#actionBits()}.
     */
    static String verifiableOutcome(Challenge c) {
        switch (c.kind()) {
            case TURN:
                return "TURN:" + c.direction();
            case GAZE:
                return "GAZE:" + c.gazeCell();
            default:
                return c.kind().name();
        }
    }

    /** Entropy per step of the verifiable outcome when options are drawn uniformly. */
    static double outcomeEntropyBits(List<Challenge> options) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (Challenge c : options) {
            counts.merge(verifiableOutcome(c), 1, Integer::sum);
        }
        double h = 0;
        for (int n : counts.values()) {
            double p = (double) n / options.size();
            h -= p * log2(p);
        }
        return h;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
