/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Physics as a free labeller (Bible Part 8.6, 24): at a station with a light, the flash cue answers "is a 3-D thing that
 * reflects my random colours here, now?" without anyone staging an attack. That makes a labelled training set cheap, for
 * a small model that sees only plain frames and so can run at a station with no light and on the Android build.
 *
 * <p>A teacher is only as good as its blind spots, so this class does three things and refuses a fourth:
 * <ol>
 *   <li><b>labels</b> a session LIVE or ATTACK only when the flash vote is confident (p at least
 *       {@value #CONFIDENT} or at most {@code 1 - CONFIDENT}), and UNSURE otherwise: no label beats a wrong one;</li>
 *   <li><b>queues for a human</b> every session where another cue disagrees sharply with the teacher (the sessions most
 *       likely to be the teacher's blind spots: a projected mask the flash cannot see), plus a random sample of the
 *       rest;</li>
 *   <li><b>estimates the teacher's own error</b> from what the human decided, with a 95% Clopper-Pearson interval, so the
 *       label noise a student would inherit is stated, not assumed;</li>
 *   <li><b>refuses</b> to call a set trainable until the audited error is below a stated bound.</li>
 * </ol>
 *
 * <p>Honest status: this is the labelling and auditing machinery. No student was trained, and none can be without a
 * consented corpus of frames; the test uses synthetic votes.
 */
public final class PseudoLabeler {

    public enum Label { LIVE, ATTACK, UNSURE }

    /** The flash vote at or beyond which the teacher is trusted to label. */
    public static final double CONFIDENT = 0.9;
    /** Another cue this far on the other side of 0.5 marks a session for a human. */
    public static final double DISAGREE = 0.25;

    /** One session: the cue votes (absent = abstained) and, once a human has looked, the truth. */
    public static final class Session {
        final String id;
        final Map<String, Double> votes;
        /** True live, false attack, null if no human has decided. */
        final Boolean humanVerdict;

        public Session(String id, Map<String, Double> votes, Boolean humanVerdict) {
            this.id = id;
            this.votes = Map.copyOf(votes);
            this.humanVerdict = humanVerdict;
        }
    }

    /** What labelling a pile of sessions produced. */
    public static final class Result {
        public final List<String> live = new ArrayList<>();
        public final List<String> attack = new ArrayList<>();
        public final List<String> unsure = new ArrayList<>();
        /** Sessions a person should look at: teacher and another cue disagree, plus a random audit sample. */
        public final List<String> reviewQueue = new ArrayList<>();
    }

    private PseudoLabeler() {
    }

    /** The teacher's label for one session; UNSURE when the flash cue abstained or is not confident. */
    public static Label label(Session s) {
        Double flash = s.votes.get("flash");
        if (flash == null) {
            return Label.UNSURE;
        }
        return flash >= CONFIDENT ? Label.LIVE : flash <= 1 - CONFIDENT ? Label.ATTACK : Label.UNSURE;
    }

    /** True if some other voting cue is clearly on the other side of the teacher's label. */
    static boolean disagrees(Session s, Label l) {
        for (Map.Entry<String, Double> v : s.votes.entrySet()) {
            if (v.getKey().equals("flash")) {
                continue;
            }
            if (l == Label.LIVE && v.getValue() < 0.5 - DISAGREE) {
                return true;
            }
            if (l == Label.ATTACK && v.getValue() > 0.5 + DISAGREE) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param auditShare the fraction of agreeing, labelled sessions also sent to a human, to catch a blind spot the
     *     other cues share with the teacher
     */
    public static Result run(List<Session> sessions, double auditShare, long seed) {
        Random r = new Random(seed);
        Result out = new Result();
        for (Session s : sessions) {
            Label l = label(s);
            switch (l) {
                case LIVE:
                    out.live.add(s.id);
                    break;
                case ATTACK:
                    out.attack.add(s.id);
                    break;
                default:
                    out.unsure.add(s.id);
            }
            if (l != Label.UNSURE && (disagrees(s, l) || r.nextDouble() < auditShare)) {
                out.reviewQueue.add(s.id);
            }
        }
        return out;
    }

    /**
     * The teacher's audited error on the sessions a human decided: labelled ones only, with a 95% interval.
     *
     * <p>Pass <b>only the random audit sample</b> for an unbiased estimate. The disagreement queue is selected for being
     * suspicious, so its error rate overstates the teacher's; use it to find blind spots, not to measure them.
     */
    public static Binomial.Rate teacherError(List<Session> sessions) {
        long wrong = 0;
        long n = 0;
        for (Session s : sessions) {
            Label l = label(s);
            if (l == Label.UNSURE || s.humanVerdict == null) {
                continue;
            }
            n++;
            wrong += (l == Label.LIVE) != s.humanVerdict ? 1 : 0;
        }
        return Binomial.clopperPearson(wrong, n, 0.95);
    }

    /**
     * True only if enough sessions were audited and the upper end of the teacher's error interval is below
     * {@code maxError}: the label noise a student would learn is then bounded, and the bound is stated.
     */
    public static boolean trainable(List<Session> sessions, double maxError, int minAudited) {
        Binomial.Rate e = teacherError(sessions);
        return e.n() >= minAudited && e.upper() <= maxError;
    }
}
