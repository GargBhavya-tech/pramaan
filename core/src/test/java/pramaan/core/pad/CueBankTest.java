/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pramaan.core.audit.AuditEvent;
import pramaan.core.image.RgbImage;
import pramaan.core.source.Capability;

/** Build Map #29-#34 on synthetic inputs with known answers. Real-data thresholds come from the corpus (#65). */
class CueBankTest {

    // ---------------------------------------------------------------- #29
    @Test
    void cuesWithoutTheirCapabilityOrFrameRateAbstainAndNeverVote() {
        List<CueRegistry.Spec> cues = List.of(
                new CueRegistry.Spec("passive", Set.of(), 1),
                new CueRegistry.Spec("flash", Set.of(Capability.RESIDENT_DISPLAY), 3),
                new CueRegistry.Spec("rppg", Set.of(Capability.UNCOMPRESSED_FRAMES), 10));
        CueRegistry.Plan plan = CueRegistry.plan(cues, EnumSet.noneOf(Capability.class), 3);
        assertEquals(List.of("passive"), plan.runnable());
        assertEquals(2, plan.abstentions().size());
        for (CueResult r : plan.abstentions()) {
            assertTrue(r.abstained() && Double.isNaN(r.pLive()), "abstention must not carry a probability");
        }
        CueRegistry.Plan slow = CueRegistry.plan(cues, EnumSet.of(Capability.UNCOMPRESSED_FRAMES), 5);
        assertEquals(CueResult.AbstainReason.FRAME_RATE_TOO_LOW, slow.abstentions().stream()
                .filter(r -> r.cue().equals("rppg")).findFirst().orElseThrow().abstainReason());
    }

    @Test
    void aVoteMustBeAProperProbability() {
        assertThrows(IllegalArgumentException.class, () -> CueResult.vote("x", 0.0, 1));
        assertThrows(IllegalArgumentException.class, () -> CueResult.vote("x", Double.NaN, 1));
    }

    // ---------------------------------------------------------------- #30
    @Test
    void uniformLbpHasFiftyEightUniformBinsAndHistogramsSumToOne() {
        long uniform = java.util.Arrays.stream(ColorTextureCue.UNIFORM_BIN).filter(b -> b < 58).count();
        assertEquals(58, uniform);
        double[] f = ColorTextureCue.features(noise(80, 80, 3));
        assertEquals(ColorTextureCue.FEATURES, f.length);
        for (int c = 0; c < 6; c++) {
            double sum = 0;
            for (int i = 0; i < ColorTextureCue.BINS; i++) {
                sum += f[c * ColorTextureCue.BINS + i];
            }
            assertEquals(1.0, sum, 1e-9);
        }
    }

    @Test
    void anUntrainedColorTextureCueAbstainsInsteadOfGuessing() {
        CueResult r = new ColorTextureCue(null, 0).evaluate(noise(80, 80, 1));
        assertEquals(CueResult.AbstainReason.UNTRAINED, r.abstainReason());
    }

    // ---------------------------------------------------------------- #31
    @Test
    void anOffAxisGratingIsPeakyButAxisBlocksAndNaturalTextureAreNot() {
        // One seeded stream per image: new Random(seed) for neighbouring seeds gives correlated first values,
        // which is itself a spectral pattern.
        Random r1 = new Random(1);
        Random r2 = new Random(2);
        Random r3 = new Random(3);
        double grating = MoireCue.peakiness(gray(128, (x, y) -> 128 + 40 * Math.sin(2 * Math.PI * (0.19 * x + 0.13 * y))
                + 10 * (r1.nextDouble() - 0.5)));
        // 8x8 block edges at an arbitrary phase, including where they cross (energy off the axes).
        double blocks = MoireCue.peakiness(gray(128, (x, y) -> (((x + 3) % 8 == 0) || ((y + 5) % 8 == 0) ? 160 : 120)
                + 10 * (r2.nextDouble() - 0.5)));
        double natural = MoireCue.peakiness(gray(128, (x, y) -> 128 + 30 * r3.nextGaussian()));
        assertTrue(grating > 15, "screen-like grating: " + grating);
        assertTrue(blocks < MoireCue.PROVISIONAL_THRESHOLD, "JPEG-style block edges must not look like a screen: " + blocks);
        assertTrue(natural < MoireCue.PROVISIONAL_THRESHOLD, "natural texture: " + natural);
        assertEquals(CueResult.AbstainReason.INPUT_TOO_SMALL, new MoireCue().evaluate(noise(100, 140, 2)).abstainReason(),
                "a face too small for a native window must abstain, not be upscaled");
        assertFalse(new MoireCue().evaluate(noise(160, 200, 2)).abstained());
    }

    // ---------------------------------------------------------------- #32
    @Test
    void aTurnedPlaneIsFlatAndATurnedFaceIsNot() {
        double[][] face3d = faceModel();
        double[][] plane = new double[face3d.length][];
        for (int i = 0; i < face3d.length; i++) {
            plane[i] = new double[] {face3d[i][0], face3d[i][1], 0};
        }
        double iod = 100;
        double eFace = FlatnessCue.residual(project(face3d, 0), project(face3d, 20)) / iod;
        double ePlane = FlatnessCue.residual(project(plane, 0), project(plane, 20)) / iod;
        assertTrue(ePlane < 0.012, "planar residual " + ePlane);
        assertTrue(eFace > 0.03, "3-D face residual " + eFace);

        FlatnessCue cue = new FlatnessCue();
        assertTrue(cue.evaluate(project(plane, 0), project(plane, 20), 20, iod).pLive() < 0.1);
        assertTrue(cue.evaluate(project(face3d, 0), project(face3d, 20), 20, iod).pLive() > 0.9);
        assertEquals(CueResult.AbstainReason.NOT_ENOUGH_MOTION, cue.evaluate(project(face3d, 0), project(face3d, 4), 4, iod).abstainReason());
    }

    // ---------------------------------------------------------------- #33
    @Test
    void aRealSmileDeformsButARigidlyMovedPhotoDoesNot() {
        double[] rigid0 = {-50, 0, 50, 0, -20, 0, 20, 0, 0, 20};
        double[] mouth0 = {-30, 60, 30, 60};
        double ang = Math.toRadians(8);
        double s = 1.05;
        double[] rigid1 = transform(rigid0, s, ang, 12, -5);
        double[] mouthRigid = transform(mouth0, s, ang, 12, -5);
        double[] mouthSmile = mouthRigid.clone();
        mouthSmile[0] -= 9;
        mouthSmile[1] -= 6;
        mouthSmile[2] += 9;
        mouthSmile[3] -= 6;
        DeformationCue cue = new DeformationCue();
        double iod = 100;
        CueResult photo = cue.evaluate(rigid0, rigid1, mouth0, mouthRigid, iod);
        CueResult smile = cue.evaluate(rigid0, rigid1, mouth0, mouthSmile, iod);
        assertTrue(photo.rawValue() < 1e-9, "rigid motion leaves no residual: " + photo.rawValue());
        assertTrue(smile.rawValue() > 0.1 && smile.pLive() > 0.9, "smile residual " + smile.rawValue());
        assertTrue(photo.pLive() < 0.05);
    }

    // ---------------------------------------------------------------- #34
    @Test
    void diagnosticsBecomeAuditEventsWithNumbersOnly() {
        CueDiagnostics d = new CueDiagnostics("s-01");
        d.record(CueResult.vote(MoireCue.NAME, 0.2, 18.5), 1_850);
        d.record(CueResult.abstain(FlatnessCue.NAME, CueResult.AbstainReason.NOT_ENOUGH_MOTION));
        List<AuditEvent> events = d.toAuditEvents();
        assertEquals(2, events.size());
        assertEquals(18.5, events.get(0).fields().get("raw"));
        assertEquals(1_850L, events.get(0).fields().get("micros"));
        assertFalse(events.get(1).fields().containsKey("micros"));
        assertEquals("NOT_ENOUGH_MOTION", events.get(1).fields().get("reason"));
        assertFalse(events.get(1).fields().containsKey("pLive"));
    }

    // ---------------------------------------------------------------- helpers
    interface Pixel {
        double at(int x, int y);
    }

    private static float[] gray(int n, Pixel p) {
        float[] g = new float[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                g[y * n + x] = (float) Math.max(0, Math.min(255, p.at(x, y)));
            }
        }
        return g;
    }

    private static RgbImage noise(int w, int h, long seed) {
        Random r = new Random(seed);
        byte[] b = new byte[w * h * 3];
        r.nextBytes(b);
        return RgbImage.of(w, h, b);
    }

    /** A face-like 3-D point set: an ellipsoid of points with a protruding nose ridge (units: px at 100 px IOD). */
    private static double[][] faceModel() {
        List<double[]> pts = new java.util.ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double t = 2 * Math.PI * i / 24;
            double x = 90 * Math.cos(t);
            double y = 110 * Math.sin(t);
            pts.add(new double[] {x, y, -30 * (1 - Math.abs(Math.cos(t)))});
        }
        for (int k = 0; k < 6; k++) {
            pts.add(new double[] {0, -30 + 12 * k, 20 + 5 * k});
        }
        pts.add(new double[] {-50, -30, 5});
        pts.add(new double[] {50, -30, 5});
        return pts.toArray(new double[0][]);
    }

    private static double[] project(double[][] pts, double yawDeg) {
        double c = Math.cos(Math.toRadians(yawDeg));
        double s = Math.sin(Math.toRadians(yawDeg));
        double f = 900;
        double dist = 600;
        double[] out = new double[pts.length * 2];
        for (int i = 0; i < pts.length; i++) {
            double x = c * pts[i][0] + s * pts[i][2];
            double z = -s * pts[i][0] + c * pts[i][2];
            out[2 * i] = 320 + f * x / (dist - z);
            out[2 * i + 1] = 240 + f * pts[i][1] / (dist - z);
        }
        return out;
    }

    private static double[] transform(double[] p, double scale, double ang, double tx, double ty) {
        double[] out = new double[p.length];
        for (int i = 0; i < p.length / 2; i++) {
            out[2 * i] = scale * (Math.cos(ang) * p[2 * i] - Math.sin(ang) * p[2 * i + 1]) + tx;
            out[2 * i + 1] = scale * (Math.sin(ang) * p[2 * i] + Math.cos(ang) * p[2 * i + 1]) + ty;
        }
        return out;
    }
}
