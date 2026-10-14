/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.MoireCue;

/**
 * An automated red team (Bible Part 45): a black-box search over the screen-recapture simulator's parameters for the
 * attack that the moiré cue likes best. Where the search finds a hole, that hole is real in the cue, not in the
 * simulator: a tuned attacker could find it too. It is how the weak spots of a hand-set threshold are found before an
 * attacker finds them, and what a combination of cues has to cover.
 *
 * <p>An attack is four numbers: how many display pixels fall on one sensor pixel (the aliasing pitch), how much light a
 * lit sub-pixel leaks into its neighbours, the dark gap between pixels, and an optical blur the attacker adds (a
 * defocused or low-pass filtered capture). The search is a small (mu + lambda) evolution strategy with a 1/5-success
 * step rule; nothing about the cue is assumed, it is only asked for a score.
 *
 * <p>SYNTHETIC: it shows how the cue behaves on simulated recaptures of a smooth picture. It does not say how a real
 * phone, a real camera or a real face behaves, and finding no hole here is not evidence there is none.
 *
 * <pre>./gradlew :harness:redTeam [--args="--seed 7 --generations 40"]</pre>
 */
public final class RedTeamFuzz {

    /** One attack: the four numbers the search varies. */
    public static final class Attack {
        public final double pitch;
        public final double leak;
        public final double gap;
        public final double blur;

        Attack(double[] g) {
            this.pitch = g[0];
            this.leak = g[1];
            this.gap = g[2];
            this.blur = g[3];
        }

        @Override
        public String toString() {
            return String.format("pitch %.3f  leak %.2f  gap %.2f  blur %.2f px", pitch, leak, gap, blur);
        }
    }

    /** How the cue scored one attack. */
    public static final class Outcome {
        public final double peakiness;
        public final double pLive;
        public final boolean abstained;
        /** Sharpness of the attack's face crop relative to the source's: what the evasion cost. */
        public final double sharpnessRatio;

        Outcome(double peakiness, double pLive, boolean abstained, double sharpnessRatio) {
            this.peakiness = peakiness;
            this.pLive = pLive;
            this.abstained = abstained;
            this.sharpnessRatio = sharpnessRatio;
        }
    }

    /** What a search found. */
    public static final class Result {
        public final Attack best;
        public final Outcome bestOutcome;
        public final Attack naive;
        public final Outcome naiveOutcome;
        public final int evaluations;
        public final double randomEvasionShare;
        public final double randomEvasionShareWithoutBlur;

        Result(Attack best, Outcome bestOutcome, Attack naive, Outcome naiveOutcome, int evaluations, double randomShare,
                double randomShareNoBlur) {
            this.best = best;
            this.bestOutcome = bestOutcome;
            this.naive = naive;
            this.naiveOutcome = naiveOutcome;
            this.evaluations = evaluations;
            this.randomEvasionShare = randomShare;
            this.randomEvasionShareWithoutBlur = randomShareNoBlur;
        }
    }

    private static final double[] LO = {0.72, 0.0, 0.0, 0.0};
    private static final double[] HI = {2.40, 0.50, 0.30, 3.0};
    private static final int SOURCE = 200;
    private static final int MU = 4;
    private static final int LAMBDA = 12;

    private final RgbImage source;
    private final double sourceSharpness;

    public RedTeamFuzz() {
        this.source = face(SOURCE);
        this.sourceSharpness = sharpness(source);
    }

    /** A smooth, lightly textured stand-in for a face: slowly varying tone and a little grain, as skin gives the lens. */
    static RgbImage face(int size) {
        byte[] px = new byte[size * size * 3];
        Random r = new Random(5);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double v = 140 + 30 * Math.sin(x / 17.0) * Math.cos(y / 23.0) + r.nextGaussian() * 2.5;
                for (int c = 0; c < 3; c++) {
                    px[(y * size + x) * 3 + c] = (byte) Math.max(0, Math.min(255, Math.round(v - 8 * c)));
                }
            }
        }
        return RgbImage.of(size, size, px);
    }

    /** Scores the attack: renders the recapture, applies the attacker's blur, and asks the moiré cue. */
    public Outcome evaluate(Attack a) {
        ScreenRecapture.Params p = new ScreenRecapture.Params();
        p.displayPitchPx = a.pitch;
        p.leak = a.leak;
        p.gap = a.gap;
        p.glare = 0;
        p.ripple = 0;
        p.supersample = 3; // the hole is about the pixel grid, not the sub-pixel shape; 3 is 4x faster than the default
        RgbImage shot = ScreenRecapture.render(source, p);
        if (a.blur > 0.05) {
            shot = blur(shot, a.blur);
        }
        int w = shot.width();
        int h = shot.height();
        RgbImage crop = ImageOps.crop(shot, Math.max(0, w / 2 - 80), Math.max(0, h / 2 - 80), Math.min(w, w / 2 + 80) - 1,
                Math.min(h, h / 2 + 80) - 1);
        CueResult r = new MoireCue().evaluate(crop);
        double sharp = sharpness(crop) / sourceSharpness;
        if (r.abstained()) {
            return new Outcome(Double.NaN, Double.NaN, true, sharp);
        }
        return new Outcome(r.rawValue(), r.pLive(), false, sharp);
    }

    private static double sharpness(RgbImage img) {
        return Math.max(1e-9, ImageOps.laplacianVariance(ImageOps.luma(img), img.width(), img.height()));
    }

    /** The search: {@code generations} of {@value #LAMBDA} candidates each, from a naive attacker's starting point. */
    public Result search(long seed, int generations) {
        return search(seed, generations, 150);
    }

    /** @param samples how many uniform random attackers to also try, for the share of the space the cue is fooled by (0 for none) */
    public Result search(long seed, int generations, int samples) {
        Random rnd = new Random(seed);
        double[] naive = {1.15, 0.12, 0.08, 0.0}; // a phone pointed at a screen, no thought given
        List<double[]> parents = new ArrayList<>();
        List<Double> parentFit = new ArrayList<>();
        parents.add(naive);
        parentFit.add(fitness(evaluate(new Attack(naive))));
        int evaluations = 1;
        double[] step = new double[4];
        for (int i = 0; i < 4; i++) {
            step[i] = 0.15 * (HI[i] - LO[i]);
        }
        double sigma = 1.0;
        for (int g = 0; g < generations; g++) {
            List<double[]> pool = new ArrayList<>(parents);
            List<Double> fits = new ArrayList<>(parentFit);
            int parentCount = parents.size();
            for (int k = 0; k < LAMBDA; k++) {
                double[] parent = parents.get(rnd.nextInt(parents.size()));
                double[] child = new double[4];
                for (int i = 0; i < 4; i++) {
                    child[i] = clamp(parent[i] + rnd.nextGaussian() * step[i] * sigma, LO[i], HI[i]);
                }
                pool.add(child);
                fits.add(fitness(evaluate(new Attack(child))));
                evaluations++;
            }
            Integer[] order = new Integer[pool.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            Arrays.sort(order, Comparator.comparingDouble(fits::get));
            parents = new ArrayList<>();
            parentFit = new ArrayList<>();
            int childWins = 0;
            for (int i = 0; i < Math.min(MU, order.length); i++) {
                parents.add(pool.get(order[i]));
                parentFit.add(fits.get(order[i]));
                childWins += order[i] >= parentCount ? 1 : 0;
            }
            // 1/5 rule: if fewer than a fifth of the survivors are new children, search more locally; otherwise wider.
            sigma *= childWins * 5 < MU ? 0.82 : 1.22;
            sigma = Math.max(0.05, Math.min(3, sigma));
        }
        double[] best = parents.get(0);
        // How much of the whole attack space the cue is fooled by, sampled uniformly, with and without the blur.
        Random sample = new Random(seed + 1);
        int n = Math.max(1, samples);
        int evade = 0;
        int evadeNoBlur = 0;
        for (int i = 0; i < (samples == 0 ? 0 : n); i++) {
            double[] g = new double[4];
            for (int d = 0; d < 4; d++) {
                g[d] = LO[d] + sample.nextDouble() * (HI[d] - LO[d]);
            }
            Outcome o = evaluate(new Attack(g));
            evade += !o.abstained && o.pLive > 0.5 ? 1 : 0;
            double[] noBlur = g.clone();
            noBlur[3] = 0;
            Outcome o2 = evaluate(new Attack(noBlur));
            evadeNoBlur += !o2.abstained && o2.pLive > 0.5 ? 1 : 0;
        }
        Attack naiveAttack = new Attack(naive);
        return new Result(new Attack(best), evaluate(new Attack(best)), naiveAttack, evaluate(naiveAttack), evaluations,
                samples == 0 ? Double.NaN : (double) evade / n, samples == 0 ? Double.NaN : (double) evadeNoBlur / n);
    }

    /**
     * The cue's blind spots, mapped: pLive for a clean recapture (no leak, a 15% gap, no blur) at every pitch from
     * {@code from} to {@code to}. Where it is high the cue calls a recapture live.
     */
    public List<double[]> sweep(double from, double to, double stepBy) {
        List<double[]> out = new ArrayList<>();
        for (double pitch = from; pitch <= to + 1e-9; pitch += stepBy) {
            Outcome o = evaluate(new Attack(new double[] {pitch, 0.0, 0.15, 0.0}));
            out.add(new double[] {pitch, o.abstained ? Double.NaN : o.pLive, o.abstained ? Double.NaN : o.peakiness});
        }
        return out;
    }

    /** Smaller is a better attack: the peakiness the cue sees; an abstention is not a win, it is the worst result. */
    private static double fitness(Outcome o) {
        return o.abstained ? 1e9 : o.peakiness;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Separable Gaussian blur, standard deviation {@code sigma} pixels, edges clamped. */
    static RgbImage blur(RgbImage img, double sigma) {
        int radius = (int) Math.ceil(3 * sigma);
        double[] k = new double[2 * radius + 1];
        double sum = 0;
        for (int i = -radius; i <= radius; i++) {
            k[i + radius] = Math.exp(-i * i / (2 * sigma * sigma));
            sum += k[i + radius];
        }
        for (int i = 0; i < k.length; i++) {
            k[i] /= sum;
        }
        int w = img.width();
        int h = img.height();
        double[] tmp = new double[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    double acc = 0;
                    for (int i = -radius; i <= radius; i++) {
                        acc += k[i + radius] * img.get(Math.max(0, Math.min(w - 1, x + i)), y, c);
                    }
                    tmp[(y * w + x) * 3 + c] = acc;
                }
            }
        }
        byte[] out = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    double acc = 0;
                    for (int i = -radius; i <= radius; i++) {
                        acc += k[i + radius] * tmp[(Math.max(0, Math.min(h - 1, y + i)) * w + x) * 3 + c];
                    }
                    out[(y * w + x) * 3 + c] = (byte) Math.max(0, Math.min(255, Math.round(acc)));
                }
            }
        }
        return RgbImage.of(w, h, out);
    }

    // ------------------------------------------------------------------------------------------ the command

    public static void main(String[] args) {
        long seed = 7;
        int generations = 40;
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].equals("--map")) {
                RedTeamFuzz f = new RedTeamFuzz();
                System.out.println("pitch  pLive  peakiness   (SYNTHETIC; # = the cue calls the recapture live)");
                for (double[] row : f.sweep(0.72, 2.4, Double.parseDouble(args[i + 1]))) {
                    System.out.printf("%.3f  %.3f  %6.1f  %s%n", row[0], row[1], row[2], row[1] > 0.5 ? "#" : "");
                }
                return;
            }
            if (args[i].equals("--seed")) {
                seed = Long.parseLong(args[i + 1]);
            } else if (args[i].equals("--generations")) {
                generations = Integer.parseInt(args[i + 1]);
            } else {
                throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        RedTeamFuzz fuzz = new RedTeamFuzz();
        Result r = fuzz.search(seed, generations);
        System.out.println("SYNTHETIC red team against the moire cue (threshold peakiness "
                + MoireCue.PROVISIONAL_THRESHOLD + ": below it the cue says live)");
        System.out.printf("naive attacker  %s -> peakiness %.1f, pLive %.3f, sharpness x%.2f%n", r.naive,
                r.naiveOutcome.peakiness, r.naiveOutcome.pLive, r.naiveOutcome.sharpnessRatio);
        System.out.printf("best found      %s -> peakiness %.1f, pLive %.3f, sharpness x%.2f  (%d evaluations)%n", r.best,
                r.bestOutcome.peakiness, r.bestOutcome.pLive, r.bestOutcome.sharpnessRatio, r.evaluations);
        System.out.printf("random attackers the cue calls live: %.0f%% of the space, %.0f%% when blur is not allowed%n",
                100 * r.randomEvasionShare, 100 * r.randomEvasionShareWithoutBlur);
    }
}
