/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.MoireCue;

/** Bible Part 45 and Build Map #89: MoireSimulationTest, HalftoneTest, the KS test and the sim-to-real report. */
class SyntheticAttackFactoryTest {

    /** A smooth, skin-like source: slow variations only, so any fine structure in the output comes from the attack. */
    private static RgbImage smooth(int w, int h) {
        byte[] px = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = 140 + 30 * Math.sin(x / 17.0) * Math.cos(y / 23.0);
                for (int c = 0; c < 3; c++) {
                    px[(y * w + x) * 3 + c] = (byte) Math.round(v - 8 * c);
                }
            }
        }
        return RgbImage.of(w, h, px);
    }

    private static RgbImage grey(int w, int h, int level) {
        byte[] px = new byte[w * h * 3];
        Arrays.fill(px, (byte) level);
        return RgbImage.of(w, h, px);
    }

    /** Mean power spectrum of the rows of a channel, indexed by bin (cycles per pixel = bin / width). */
    private static double[] rowPower(RgbImage img, int channel) {
        int w = img.width();
        double[] power = new double[w / 2];
        for (int y = 0; y < img.height(); y++) {
            double mean = 0;
            for (int x = 0; x < w; x++) {
                mean += img.get(x, y, channel) / (double) w;
            }
            for (int k = 1; k < w / 2; k++) {
                double re = 0;
                double im = 0;
                for (int x = 0; x < w; x++) {
                    double v = img.get(x, y, channel) - mean;
                    re += v * Math.cos(2 * Math.PI * k * x / w);
                    im -= v * Math.sin(2 * Math.PI * k * x / w);
                }
                power[k] += (re * re + im * im) / img.height();
            }
        }
        return power;
    }

    private static int argmax(double[] a, int from, int to) {
        int best = from;
        for (int i = from; i < to; i++) {
            if (a[i] > a[best]) {
                best = i;
            }
        }
        return best;
    }

    private static double median(double[] a, int from, int to) {
        double[] s = Arrays.copyOfRange(a, from, to);
        Arrays.sort(s);
        return s[s.length / 2];
    }

    // --- screen recapture ------------------------------------------------------------------------------------

    @Test
    void thePredictedBeatIsTheAliasOfTheDisplayGrid() {
        assertEquals(0.0741, ScreenRecapture.predictedBeat(1.08), 1e-3);
        assertEquals(0.1304, ScreenRecapture.predictedBeat(1.15), 1e-3);
        assertEquals(0.0, ScreenRecapture.predictedBeat(0.5), 1e-9, "a grid at exactly half a pixel does not alias");
    }

    @Test
    void aSimulatedRecaptureShowsASpectralPeakNearThePredictedBeat() {
        ScreenRecapture.Params p = new ScreenRecapture.Params();
        p.displayPitchPx = 1.08;
        p.glare = 0;
        p.ripple = 0;
        RgbImage out = ScreenRecapture.render(smooth(200, 100), p);
        double[] power = rowPower(out, 1);
        int w = out.width();
        double beat = ScreenRecapture.predictedBeat(p.displayPitchPx);
        int expectedBin = (int) Math.round(beat * w);
        int lo = Math.max(2, expectedBin - 3);
        int peak = argmax(power, lo, expectedBin + 4);
        assertTrue(Math.abs((double) peak / w - beat) < 0.01, "peak at " + (double) peak / w + " expected " + beat);
        double floor = median(power, 12, w / 2);
        assertTrue(power[peak] > 50 * floor, "peak " + power[peak] + " vs median floor " + floor);
    }

    @Test
    void theExistingMoireCueScoresARecaptureAboveItsBonaFideSource() {
        RgbImage source = smooth(160, 160);
        ScreenRecapture.Params p = new ScreenRecapture.Params();
        p.displayPitchPx = 1.15;
        p.glare = 0;
        p.ripple = 0;
        RgbImage recapture = ScreenRecapture.render(source, p);
        CueResult bona = new MoireCue().evaluate(ImageOps.crop(source, 16, 16, 144, 144));
        CueResult attack = new MoireCue().evaluate(ImageOps.crop(recapture, 16, 16, 144, 144));
        assertTrue(!bona.abstained() && !attack.abstained());
        assertTrue(attack.rawValue() > 3 * bona.rawValue(),
                "peakiness attack " + attack.rawValue() + " vs bona fide " + bona.rawValue());
        assertTrue(attack.pLive() < bona.pLive());
    }

    @Test
    void refreshBandingVanishesWhenTheExposureSpansWholeRipplePeriods() {
        ScreenRecapture.Params whole = new ScreenRecapture.Params();
        whole.exposureMs = 1000.0 / whole.rippleHz; // exactly one ripple period
        assertTrue(ScreenRecapture.bandingAmplitude(whole) < 1e-9);
        ScreenRecapture.Params partial = new ScreenRecapture.Params();
        partial.exposureMs = 2.0;
        assertTrue(ScreenRecapture.bandingAmplitude(partial) > 0.04);

        // And it shows in the rows: bands appear only with the partial exposure.
        partial.displayPitchPx = 1.0;
        partial.glare = 0;
        whole.displayPitchPx = 1.0;
        whole.glare = 0;
        double bandy = rowMeanSpread(ScreenRecapture.render(grey(60, 240, 128), partial));
        double flat = rowMeanSpread(ScreenRecapture.render(grey(60, 240, 128), whole));
        assertTrue(bandy > 4 * flat + 0.5, "banded " + bandy + " flat " + flat);
    }

    private static double rowMeanSpread(RgbImage img) {
        double lo = 1e9;
        double hi = -1e9;
        for (int y = 0; y < img.height(); y++) {
            double m = 0;
            for (int x = 0; x < img.width(); x++) {
                m += img.get(x, y, 1) / (double) img.width();
            }
            lo = Math.min(lo, m);
            hi = Math.max(hi, m);
        }
        return hi - lo;
    }

    @Test
    void aRecaptureIsReproducibleFromItsSeedAndDiffersAcrossSeeds() {
        RgbImage source = smooth(60, 60);
        ScreenRecapture.Params a = new ScreenRecapture.Params();
        ScreenRecapture.Params b = new ScreenRecapture.Params();
        b.seed = 2;
        RgbImage one = ScreenRecapture.render(source, a);
        RgbImage again = ScreenRecapture.render(source, a);
        RgbImage other = ScreenRecapture.render(source, b);
        assertTrue(same(one, again));
        assertTrue(!same(one, other), "a different seed moves the glass highlight");
    }

    private static boolean same(RgbImage a, RgbImage b) {
        if (a.width() != b.width() || a.height() != b.height()) {
            return false;
        }
        for (int y = 0; y < a.height(); y++) {
            for (int x = 0; x < a.width(); x++) {
                for (int c = 0; c < 3; c++) {
                    if (a.get(x, y, c) != b.get(x, y, c)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Test
    void aRecaptureHasTheCoolGamutShift() {
        ScreenRecapture.Params p = new ScreenRecapture.Params();
        p.glare = 0;
        p.ripple = 0;
        p.displayPitchPx = 1.0;
        RgbImage out = ScreenRecapture.render(grey(40, 40, 128), p);
        double r = 0;
        double b = 0;
        for (int y = 0; y < out.height(); y++) {
            for (int x = 0; x < out.width(); x++) {
                r += out.get(x, y, 0);
                b += out.get(x, y, 2);
            }
        }
        assertTrue(b > r * 1.05, "blue " + b + " vs red " + r);
    }

    // --- print -----------------------------------------------------------------------------------------------

    @Test
    void aHalftonedMidGreyHasAScreenPeriodOfCellTimesRootTwo() {
        PrintSimulator.Params p = new PrintSimulator.Params();
        p.cellPx = 6;
        p.tooth = 0;
        p.gloss = 0;
        RgbImage out = PrintSimulator.render(grey(192, 96, 128), p);
        double[] power = rowPower(out, 1);
        int w = out.width();
        double expected = 1.0 / PrintSimulator.blackScreenPeriod(p.cellPx);
        int peak = argmax(power, 3, w / 2);
        assertTrue(Math.abs((double) peak / w - expected) < 0.01,
                "peak at " + (double) peak / w + " expected " + expected);
    }

    @Test
    void halftoneCoverageTracksTheInkAmount() {
        PrintSimulator.Params p = new PrintSimulator.Params();
        p.tooth = 0;
        p.gloss = 0;
        RgbImage mid = PrintSimulator.render(grey(120, 120, 128), p);
        RgbImage dark = PrintSimulator.render(grey(120, 120, 40), p);
        RgbImage light = PrintSimulator.render(grey(120, 120, 220), p);
        assertTrue(meanGreen(dark) < meanGreen(mid) && meanGreen(mid) < meanGreen(light));
        assertEquals(0.93 * 0.5 * 255, meanGreen(mid), 0.1 * 255, "a 50% grey prints about half coverage");
    }

    private static double meanGreen(RgbImage img) {
        double s = 0;
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                s += img.get(x, y, 1);
            }
        }
        return s / (img.width() * img.height());
    }

    @Test
    void aBentPrintMovesTheEdgesButNotTheCentre() {
        byte[] px = new byte[100 * 20 * 3];
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 100; x++) {
                for (int c = 0; c < 3; c++) {
                    px[(y * 100 + x) * 3 + c] = (byte) (x * 2);
                }
            }
        }
        RgbImage ramp = RgbImage.of(100, 20, px);
        PrintSimulator.Params flat = new PrintSimulator.Params();
        PrintSimulator.Params bent = new PrintSimulator.Params();
        bent.bend = 0.3;
        double centreFlat = column(PrintSimulator.render(ramp, flat), 50);
        double centreBent = column(PrintSimulator.render(ramp, bent), 50);
        double edgeFlat = column(PrintSimulator.render(ramp, flat), 92);
        double edgeBent = column(PrintSimulator.render(ramp, bent), 92);
        assertEquals(centreFlat, centreBent, 10);
        assertTrue(Math.abs(edgeFlat - edgeBent) > 5, "edge flat " + edgeFlat + " bent " + edgeBent);
    }

    private static double column(RgbImage img, int x) {
        double s = 0;
        for (int y = 0; y < img.height(); y++) {
            for (int dx = -3; dx <= 3; dx++) {
                s += img.get(x + dx, y, 1) / (img.height() * 7.0);
            }
        }
        return s;
    }

    // --- sim-to-real -----------------------------------------------------------------------------------------

    private static double[] gaussian(int n, double mean, double sd, long seed) {
        Random r = new Random(seed);
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = mean + sd * r.nextGaussian();
        }
        return v;
    }

    @Test
    void ksCannotTellTwoSamplesOfTheSameDistributionApart() {
        double p = TwoSampleKs.pValue(gaussian(200, 10, 3, 1), gaussian(200, 10, 3, 2));
        assertTrue(p > 0.05, "p " + p);
    }

    @Test
    void ksSeparatesAShiftedDistribution() {
        double p = TwoSampleKs.pValue(gaussian(200, 10, 3, 1), gaussian(200, 13, 3, 2));
        assertTrue(p < 0.001, "p " + p);
    }

    @Test
    void ksStatisticOfDisjointSamplesIsOne() {
        assertEquals(1.0, TwoSampleKs.statistic(new double[] {1, 2, 3}, new double[] {10, 11, 12}), 1e-12);
        assertEquals(0.0, TwoSampleKs.statistic(new double[] {1, 2, 3}, new double[] {1, 2, 3}), 1e-12);
    }

    @Test
    void theReportLabelsTheSimulatorMatchedOrDiffersPerCue() {
        SimToRealReport report = new SimToRealReport()
                .add("moire", gaussian(200, 10, 3, 1), gaussian(200, 10, 3, 2))
                .add("colorTexture", gaussian(150, 0.4, 0.1, 5), gaussian(150, 0.6, 0.1, 6));
        assertTrue(report.rows().get(0).matched());
        assertTrue(!report.rows().get(1).matched());
        assertEquals(0.5, report.matchedFraction(), 1e-12);
        String md = report.markdown();
        assertTrue(md.contains("SYNTHETIC"));
        assertTrue(md.contains("DIFFERS"));
        assertTrue(md.contains("headline APCER uses real presentations only"));
    }
}
