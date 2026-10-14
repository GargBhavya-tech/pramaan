/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

/**
 * Flash-reflection cue (Bible Part 8.6; Build Map #85): a resident-facing light shows a random colour sequence and
 * the face must answer it, in time, the way a lit 3-D object does.
 *
 * <ol>
 *   <li><b>Correlation.</b> Per-frame normalised-rgb chroma of the face skin, against the emitted colours, both
 *       centred; the normalised cross-correlation is searched over camera lag 0..{@value #MAX_PASS_LAG} frames.
 *       Passing needs lag at most {@value #MAX_PASS_LAG}: a response that only lines up later is replay latency, so
 *       lags beyond that are never searched and a late response scores low.
 *   <li><b>Shading.</b> The gain of the nose bridge against the gain of the cheeks. A nose stands proud of the
 *       face and catches a frontal light differently; a flat print gains uniformly (ratio 1).
 *   <li><b>Spectral skin check</b> (reported, <em>not</em> fused): the blue-to-red gain ratio of the response.
 *       Skin absorbs short wavelengths; a pale silicone or resin mask does not. It needs the corpus (#65) and a
 *       fixed white balance before it can vote, so it is only recorded in {@link Analysis#blueToRedGain}.
 * </ol>
 *
 * <p>Abstains with {@code CAPABILITY_MISSING} when there was no resident-facing light, {@code INPUT_TOO_SMALL} when
 * the trace is too short, and {@code NOT_APPLICABLE} when the ambient light drowns the flash or the light never
 * changed. A weak response in normal ambient light is <em>not</em> an abstention: that is exactly what an injected
 * stream looks like.
 *
 * <p>Thresholds come from the Bible's worked example (live rho 0.83, phone replay 0.21, injection 0.07), not from
 * measurement: {@link #PROVISIONAL_RHO} is where the vote crosses 0.5. Honest limit (Part 8.6): the flash proves
 * "a 3-D object is here now", not "live skin" — projection onto a mask passes this cue.
 */
public final class FlashReflectionCue {

    public static final String NAME = "flash";
    public static final int MAX_PASS_LAG = 2;
    /** Bible Part 8.6: correlation needed to pass. */
    public static final double PASS_RHO = 0.6;
    /** PROVISIONAL: where the vote crosses 0.5 (between the worked examples 0.21 and 0.83). */
    public static final double PROVISIONAL_RHO = 0.45;
    private static final double RHO_SLOPE = 0.07;
    /** Frames needed to say anything. */
    public static final int MIN_FRAMES = 12;
    /** Mean face brightness (0-255) above which a screen flash is lost in the ambient light. */
    public static final double AMBIENT_TOO_STRONG = 200;
    /** |ln(nose gain / cheek gain)| at which the shading is considered fully three-dimensional. */
    static final double SHADING_FULL = 0.15;
    /** PROVISIONAL: largest logit shift from a fully 3-D (+) or fully uniform (-) shading result. */
    private static final double SHADING_LOGIT = 0.5;

    /** Everything measured, for diagnostics and the evidence record. */
    public static final class Analysis {
        public final CueResult result;
        public final double rho;
        public final int lag;
        public final double noseToCheekGain;
        public final double blueToRedGain;

        Analysis(CueResult result, double rho, int lag, double noseToCheekGain, double blueToRedGain) {
            this.result = result;
            this.rho = rho;
            this.lag = lag;
            this.noseToCheekGain = noseToCheekGain;
            this.blueToRedGain = blueToRedGain;
        }
    }

    /** @param trace null means the device has no resident-facing light */
    public CueResult evaluate(FlashTrace trace) {
        return analyse(trace).result;
    }

    public Analysis analyse(FlashTrace trace) {
        if (trace == null) {
            return abstained(CueResult.AbstainReason.CAPABILITY_MISSING);
        }
        if (trace.frames() < MIN_FRAMES) {
            return abstained(CueResult.AbstainReason.INPUT_TOO_SMALL);
        }
        double[][] emitted = trace.emitted();
        double[][] face = trace.face();
        if (meanBrightness(face) > AMBIENT_TOO_STRONG) {
            return abstained(CueResult.AbstainReason.NOT_APPLICABLE);
        }
        double[][] e = centred(chromatic(emitted));
        if (energy(e) == 0) {
            return abstained(CueResult.AbstainReason.NOT_APPLICABLE); // the light never changed: nothing to correlate
        }
        double[][] f = centred(chroma(face));
        int bestLag = 0;
        double best = -2;
        for (int lag = 0; lag <= MAX_PASS_LAG; lag++) {
            double rho = ncc(e, f, lag);
            if (rho > best) {
                best = rho;
                bestLag = lag;
            }
        }
        double noseGain = gain(centred(chroma(trace.nose())), e, bestLag);
        double cheekGain = gain(centred(chroma(trace.cheek())), e, bestLag);
        double ratio = cheekGain <= 1e-12 || noseGain <= 1e-12 ? 1 : noseGain / cheekGain;
        double shading = Math.min(1, Math.abs(Math.log(ratio)) / SHADING_FULL); // 0 = flat, 1 = clearly 3-D

        double logit = (best - PROVISIONAL_RHO) / RHO_SLOPE + (shading - 0.5) * 2 * SHADING_LOGIT;
        double p = 1 / (1 + Math.exp(-logit));
        CueResult vote = CueResult.vote(NAME, Math.max(1e-6, Math.min(1 - 1e-6, p)), best);
        return new Analysis(vote, best, bestLag, ratio, blueToRed(face, emitted, bestLag));
    }

    private static Analysis abstained(CueResult.AbstainReason r) {
        return new Analysis(CueResult.abstain(NAME, r), Double.NaN, -1, Double.NaN, Double.NaN);
    }

    private static double meanBrightness(double[][] rgb) {
        double s = 0;
        for (double[] p : rgb) {
            s += (p[0] + p[1] + p[2]) / 3;
        }
        return s / rgb.length;
    }

    /** Normalised rgb: each channel over the sum of the three. */
    static double[][] chroma(double[][] rgb) {
        double[][] out = new double[rgb.length][3];
        for (int i = 0; i < rgb.length; i++) {
            double s = rgb[i][0] + rgb[i][1] + rgb[i][2];
            for (int c = 0; c < 3; c++) {
                out[i][c] = s <= 0 ? 1.0 / 3 : rgb[i][c] / s;
            }
        }
        return out;
    }

    static double[][] centred(double[][] x) {
        double[] mean = new double[3];
        for (double[] p : x) {
            for (int c = 0; c < 3; c++) {
                mean[c] += p[c] / x.length;
            }
        }
        double[][] out = new double[x.length][3];
        for (int i = 0; i < x.length; i++) {
            for (int c = 0; c < 3; c++) {
                out[i][c] = x[i][c] - mean[c];
            }
        }
        return out;
    }

    /** The colour part of the emitted light: its grey (equal-channel) component removed, since white shifts no chroma. */
    static double[][] chromatic(double[][] rgb) {
        double[][] out = new double[rgb.length][3];
        for (int i = 0; i < rgb.length; i++) {
            double m = (rgb[i][0] + rgb[i][1] + rgb[i][2]) / 3;
            for (int c = 0; c < 3; c++) {
                out[i][c] = rgb[i][c] - m;
            }
        }
        return out;
    }

    private static double energy(double[][] x) {
        double s = 0;
        for (double[] p : x) {
            for (double v : p) {
                s += v * v;
            }
        }
        return s;
    }

    /** Normalised cross-correlation of emitted[t] with response[t + lag], pooled over the three channels. */
    static double ncc(double[][] emitted, double[][] response, int lag) {
        int n = emitted.length - lag;
        double se = 0;
        double sr = 0;
        double dot = 0;
        for (int t = 0; t < n; t++) {
            for (int c = 0; c < 3; c++) {
                double a = emitted[t][c];
                double b = response[t + lag][c];
                se += a * a;
                sr += b * b;
                dot += a * b;
            }
        }
        return se <= 0 || sr <= 0 ? 0 : dot / Math.sqrt(se * sr);
    }

    /** Least-squares slope magnitude of the response on the emitted signal at the given lag. */
    private static double gain(double[][] response, double[][] emitted, int lag) {
        int n = emitted.length - lag;
        double dot = 0;
        double ee = 0;
        for (int t = 0; t < n; t++) {
            for (int c = 0; c < 3; c++) {
                dot += emitted[t][c] * response[t + lag][c];
                ee += emitted[t][c] * emitted[t][c];
            }
        }
        return ee <= 0 ? 0 : Math.abs(dot / ee);
    }

    /** Per-channel gain of the raw (not chroma) face response to its own emitted channel: blue over red. */
    private static double blueToRed(double[][] faceRgb, double[][] emitted, int lag) {
        double[][] f = centred(faceRgb);
        double[][] e = centred(emitted);
        double gr = channelGain(f, e, 0, lag);
        double gb = channelGain(f, e, 2, lag);
        return gr <= 1e-12 ? Double.NaN : gb / gr;
    }

    private static double channelGain(double[][] f, double[][] e, int c, int lag) {
        int n = e.length - lag;
        double dot = 0;
        double ee = 0;
        for (int t = 0; t < n; t++) {
            dot += e[t][c] * f[t + lag][c];
            ee += e[t][c] * e[t][c];
        }
        return ee <= 0 ? 0 : dot / ee;
    }
}
