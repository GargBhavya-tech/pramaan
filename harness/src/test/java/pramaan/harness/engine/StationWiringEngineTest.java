/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.binding.BindingLevel;
import pramaan.core.binding.EmissionLog;
import pramaan.core.binding.StreamKeyAgreement;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.challenge.FlashColour;
import pramaan.core.challenge.FlashSequence;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.engine.CaptureAnalyser;
import pramaan.core.engine.EngineListener;
import pramaan.core.engine.FrameAnalyser;
import pramaan.core.engine.FrameObservation;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.gate.FaceGate;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.StereoCalibration;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceException;
import pramaan.core.source.DeviceIlluminator;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.FlashLight;
import pramaan.core.source.Frame;
import pramaan.core.source.PupilSource;
import pramaan.core.source.SessionCapabilities;
import pramaan.core.source.StereoSource;
import pramaan.core.testing.TestClock;
import pramaan.harness.mock.MockFrameSource;
import pramaan.harness.mock.MockLivenessProfile;

/**
 * Build Map #85, #86, #90, #100: the station extras are wired into the real engine — a resident-facing light plays
 * the flash challenge and its cue votes; a second camera adds stereo votes; an iris camera adds the pupil-reflex
 * phase; and a device that speaks the SBI Liveness Profile gets the fifth binding lock, the level
 * {@code CRYPTOGRAPHIC}, and an injected frame, a foreign stream head or a hidden profile all fail.
 */
class StationWiringEngineTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 9, 8, 7};
    private static final double[] AMBIENT = {120, 90, 80};
    private static EngineBundle bundle;
    private static EngineBundle strictBundle;

    @BeforeAll
    static void loadBundles() throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] strictPolicy = new String(policy, StandardCharsets.UTF_8).replace("requireSignedStream: false",
                "requireSignedStream: true").getBytes(StandardCharsets.UTF_8);
        assertFalse(java.util.Arrays.equals(policy, strictPolicy), "the shipped policy has the flag set to false");
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        byte[] bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        bundle = EngineBundle.load(policy, bound, Map.of("smoke.onnx", model));
        strictBundle = EngineBundle.load(strictPolicy, bound, Map.of("smoke.onnx", model));
    }

    // ------------------------------------------------------------------------------------------ fakes

    /** Records what the resident-facing light was told, and what it shows now. */
    private static final class FakeLight implements FlashLight {
        volatile FlashColour current;
        int shows;

        @Override
        public void show(FlashColour colour) {
            current = colour;
            shows++;
        }

        @Override
        public void off() {
            current = null;
        }
    }

    /**
     * The simulated resident, plus a face that answers the light: its colour is the ambient plus a share of whatever
     * the light showed when the frame was taken, and the nose answers more strongly than the cheeks.
     */
    private static final class ResponsiveResident implements FrameAnalyser {
        final SimulatedResident inner;
        final java.util.function.Supplier<FlashColour> light;
        double gain;
        private final Random noise = new Random(3);

        ResponsiveResident(SimulatedResident inner, java.util.function.Supplier<FlashColour> light, double gain) {
            this.inner = inner;
            this.light = light;
            this.gain = gain;
        }

        @Override
        public FrameObservation analyse(Frame frame) {
            FrameObservation base = inner.analyse(frame);
            if (base.gate() != FaceGate.Status.ONE_FACE) {
                return base;
            }
            FlashColour c = light.get();
            double[] e = c == null ? new double[3] : new double[] {c.r(), c.g(), c.b()};
            return base.withRegions(region(e, 1.0), region(e, 1.5), region(e, 1.0));
        }

        private double[] region(double[] emitted, double strength) {
            double[] out = new double[3];
            for (int k = 0; k < 3; k++) {
                out[k] = AMBIENT[k] + gain * strength * emitted[k] * (AMBIENT[k] / 100) + noise.nextGaussian() * 0.3;
            }
            return out;
        }
    }

    /**
     * The device's own light (Liveness Profile, addition 5): it signs a log of what it showed, starting a little after
     * the request, and the face answers what it really showed, which a lying device can make differ from the log.
     */
    private static final class FakeDeviceLight implements DeviceIlluminator {
        static final String TXN = "0123456789abcdef0123456789abcdef";
        final TestClock clock;
        final MockLivenessProfile profile;
        final JwsTrust trust;
        long startDelayMs = 100;
        boolean lieInLog;
        boolean fail;
        int calls;
        private long firstEmissionMs = -1;
        private List<long[]> actual = List.of();
        private List<FlashColour> actualColours = List.of();

        FakeDeviceLight(TestClock clock) throws Exception {
            this.clock = clock;
            java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            java.security.KeyPair key = gen.generateKeyPair();
            this.profile = new MockLivenessProfile(key.getPrivate(), "dev");
            this.trust = JwsTrust.builder(clock).key("dev", key.getPublic()).build();
            profile.answerKeyAgreement(TXN, StreamKeyAgreement.newEphemeral().getPublic().getEncoded());
        }

        @Override
        public java.util.concurrent.CompletableFuture<EmissionLog> illuminate(FlashSequence sequence) {
            calls++;
            if (fail) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new DeviceException(DeviceException.Reason.DEVICE_ERROR, "the illuminator is not responding"));
            }
            firstEmissionMs = clock.millis() + startDelayMs;
            actualColours = sequence.colours();
            List<MockLivenessProfile.Emission> logged = new ArrayList<>();
            List<long[]> shown = new ArrayList<>();
            long at = 0;
            for (int i = 0; i < actualColours.size(); i++) {
                long d = sequence.durationsMs()[i];
                shown.add(new long[] {at, d});
                FlashColour claimed = lieInLog ? FlashColour.values()[(actualColours.get(i).ordinal() + 1) % 6]
                        : actualColours.get(i);
                logged.add(new MockLivenessProfile.Emission(claimed.name(), at, d));
                at += d;
            }
            actual = shown;
            try {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        EmissionLog.verify(profile.emissionLog(logged), trust, TXN));
            } catch (JwsVerificationException e) {
                return java.util.concurrent.CompletableFuture.failedFuture(e);
            }
        }

        /** What the light is really showing now, by the simulated clock. */
        FlashColour showing() {
            if (firstEmissionMs < 0) {
                return null;
            }
            long ms = clock.millis() - firstEmissionMs;
            for (int i = 0; i < actual.size(); i++) {
                if (ms >= actual.get(i)[0] && ms < actual.get(i)[0] + actual.get(i)[1]) {
                    return actualColours.get(i);
                }
            }
            return null;
        }
    }

    /** An iris camera: it records when it was stimulated and synthesises the pupil that would have followed. */
    private static final class FakePupil implements PupilSource {
        final TestClock clock;
        final List<Long> stimuli = new ArrayList<>();
        double amplitude = 0.26;

        FakePupil(TestClock clock) {
            this.clock = clock;
        }

        @Override
        public void stimulate() {
            stimuli.add(clock.millis());
        }

        @Override
        public double[][] radii(long from, long to) {
            List<double[]> rows = new ArrayList<>();
            Random noise = new Random(7);
            for (long t = from; t <= to; t += 33) {
                double drop = 0;
                for (long s : stimuli) {
                    double rel = t - s - 280;
                    if (rel >= 0) {
                        drop = Math.max(drop, amplitude * (1 - Math.exp(-rel / 300.0))
                                * (rel > 1000 ? Math.exp(-(rel - 1000) / 500.0) : 1));
                    }
                }
                rows.add(new double[] {t, 42 * (1 - drop) + noise.nextGaussian() * 0.25});
            }
            return rows.toArray(new double[0][]);
        }
    }

    /** A second camera over a synthetic face with the given nose relief, projected through the Bible's cameras. */
    private static final class FakeStereo implements StereoSource {
        static final StereoCalibration CAL = new StereoCalibration(900, 80, 640, 360);
        final double reliefMm;

        FakeStereo(double reliefMm) {
            this.reliefMm = reliefMm;
        }

        @Override
        public Optional<Pair> pairFor(long t) {
            int n = 30;
            double[] left = new double[2 * n];
            double[] right = new double[2 * n];
            Random r = new Random(11);
            for (int i = 0; i < n; i++) {
                double x;
                double y;
                double z;
                if (i == NOSE_TIP) {
                    x = 0;
                    y = 10;
                    z = 500 - reliefMm;
                } else if (i == LEFT_CHEEK || i == RIGHT_CHEEK) {
                    x = i == LEFT_CHEEK ? -55 : 55;
                    y = 15;
                    z = 500;
                } else {
                    x = (r.nextDouble() - 0.5) * 120;
                    y = (r.nextDouble() - 0.5) * 140;
                    z = 500 + 30 * (x * x + y * y) / (70.0 * 70.0);
                }
                left[2 * i] = CAL.cx() + CAL.focalPx() * x / z;
                left[2 * i + 1] = CAL.cy() + CAL.focalPx() * y / z;
                right[2 * i] = CAL.cx() + CAL.focalPx() * (x - CAL.baselineMm()) / z;
                right[2 * i + 1] = CAL.cy() + CAL.focalPx() * y / z;
            }
            return Optional.of(new Pair(CAL, left, right, t, t + 5));
        }
    }

    // ------------------------------------------------------------------------------------------ the rig

    private static final class Rig {
        final TestClock clock;
        final MockFrameSource source;
        final LivenessSession session;
        final LivenessEngine engine;
        final SimulatedResident resident;
        final FakeLight light = new FakeLight();
        final List<String> audits = new ArrayList<>();

        Rig(TestClock clock, EngineBundle bundle, MockFrameSource.Builder mock, boolean withLight, PupilSource pupil,
                StereoSource stereo, double flashGain, Capability... declared) {
            this(clock, bundle, mock, withLight, pupil, stereo, flashGain, null, declared);
        }

        Rig(TestClock clock, EngineBundle bundle, MockFrameSource.Builder mock, boolean withLight, PupilSource pupil,
                StereoSource stereo, double flashGain, FakeDeviceLight deviceLight, Capability... declared) {
            this.clock = clock;
            this.resident = new SimulatedResident(clock);
            for (Capability c : declared) {
                mock.capability(c);
            }
            try {
                source = mock.fps(10).delayer(clock::advance).neutral(List.of(JPEG)).build();
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
            DeviceCapabilities device = source.discover();
            session = bundle.newSession(Workflow.RESIDENT, "R1", "OP1", "mock", "c1", new SessionRegistry(), e -> audits.add(e.type() + " " + e.fields()),
                    clock, new SecureRandom());
            resident.passive = 0.62;
            resident.moire = 0.70;
            CaptureAnalyser captures = c -> SimulatedResident.RESIDENT;
            EngineListener listener = new EngineListener() {
                @Override
                public void prompt(ChallengePrompt prompt, int step, int steps) {
                    resident.respond(prompt);
                }
            };
            LivenessEngine.Builder b = LivenessEngine.builder(bundle, session)
                    .device(source, source.trust(), DeviceLevel.L1)
                    .analysers(new ResponsiveResident(resident, deviceLight != null ? deviceLight::showing : () -> light.current, flashGain), captures)
                    .capabilities(SessionCapabilities.of(10, device))
                    .random(new SecureRandom())
                    .listener(listener)
                    .requestedScore(40);
            if (withLight) {
                b.flashLight(light);
            }
            if (deviceLight != null) {
                b.deviceIlluminator(deviceLight);
            }
            if (pupil != null) {
                b.pupil(pupil);
            }
            if (stereo != null) {
                b.stereo(stereo);
            }
            engine = b.build();
            source.start(null, engine);
            engine.start();
        }

        void run(int maxFrames) {
            for (int i = 0; i < maxFrames && !settled(); i++) {
                source.step();
            }
        }

        boolean settled() {
            SessionState s = session.state();
            return s.terminal() || s == SessionState.RETRY_WAIT || s == SessionState.LOCKED_OUT
                    || s == SessionState.EXCEPTION_FLOW || s == SessionState.FAILED;
        }

        CueResult cue(String name) {
            return session.evidenceCues().stream().filter(c -> c.cue().equals(name)).findFirst().orElse(null);
        }

        String why() {
            List<String> tail = audits.subList(Math.max(0, audits.size() - 12), audits.size());
            return String.join(" ; ", tail) + "\n"
                    + session.history().stream().map(Object::toString).collect(Collectors.joining(" | ")) + "\nstate "
                    + session.state() + " failure " + session.lastFailure().orElse(null) + " cues "
                    + session.evidenceCues().stream().map(c -> c.cue() + (c.abstained() ? ":" + c.abstainReason()
                    : ":" + String.format("%.2f", c.pLive()))).distinct().collect(Collectors.joining(","));
        }
    }

    private static TestClock newClock() {
        return TestClock.at("2026-10-14T10:00:00Z");
    }

    // ------------------------------------------------------------------------------------------ flash

    @Test
    void aLiveFaceAnswersTheFlashTheEnginePlaysAndTheCueVotes() {
        TestClock clock = newClock();
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), true, null, null, 25);
        rig.run(600);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.why());
        assertTrue(rig.engine.challengePlan().orElseThrow().flash().isPresent(), "the plan drew a flash sequence");
        assertTrue(rig.light.shows >= 5, "the light was driven: " + rig.light.shows);
        assertNull(rig.light.current, "and left off");
        CueResult flash = rig.cue("flash");
        assertNotNull(flash, rig.why());
        assertFalse(flash.abstained(), rig.why());
        assertTrue(flash.pLive() > 0.8, "flash p " + flash.pLive());
    }

    @Test
    void anInjectedStreamThatIgnoresTheLightVotesAgainstItself() {
        TestClock clock = newClock();
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), true, null, null, 0);
        rig.run(600);
        CueResult flash = rig.cue("flash");
        assertNotNull(flash, rig.why());
        assertFalse(flash.abstained(), "no response in normal light is evidence, not an abstention: " + rig.why());
        assertTrue(flash.pLive() < 0.5, "flash p " + flash.pLive());
        assertNull(rig.light.current, "the light is off once the attempt is over");
    }

    @Test
    void withoutALightTheFlashIsNeverDrawnAndTheCueAbstains() {
        TestClock clock = newClock();
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), false, null, null, 25,
                Capability.RESIDENT_DISPLAY);
        rig.run(600);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.why());
        assertTrue(rig.engine.challengePlan().orElseThrow().flash().isEmpty());
        assertEquals(0, rig.light.shows);
        CueResult flash = rig.cue("flash");
        assertNotNull(flash);
        assertTrue(flash.abstained());
        assertEquals(CueResult.AbstainReason.NOT_APPLICABLE, flash.abstainReason(),
                "the device claims a display but this engine has no light to drive");
    }

    // ------------------------------------------------------------------------------------------ the device's own light

    @Test
    void aLiveFaceAnswersTheDevicesLightAndTheCueIsJudgedAgainstItsSignedLog() throws Exception {
        TestClock clock = newClock();
        FakeDeviceLight device = new FakeDeviceLight(clock);
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), false, null, null, 25, device);
        rig.run(600);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.why());
        assertEquals(1, device.calls, "the schedule was sent once");
        assertEquals(0, rig.light.shows, "and the station's own light was never used");
        CueResult flash = rig.cue("flash");
        assertNotNull(flash, rig.why());
        assertFalse(flash.abstained(), rig.why());
        assertTrue(flash.pLive() > 0.8, "flash p " + flash.pLive());
    }

    @Test
    void theDeviceLightIsPreferredWhenTheStationHasOneToo() throws Exception {
        TestClock clock = newClock();
        FakeDeviceLight device = new FakeDeviceLight(clock);
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), true, null, null, 25, device);
        rig.run(600);
        assertEquals(1, device.calls);
        assertEquals(0, rig.light.shows);
    }

    @Test
    void anInjectedStreamThatIgnoresTheDevicesLightVotesAgainstItself() throws Exception {
        TestClock clock = newClock();
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), false, null, null, 0, new FakeDeviceLight(clock));
        rig.run(600);
        CueResult flash = rig.cue("flash");
        assertNotNull(flash, rig.why());
        assertFalse(flash.abstained(), rig.why());
        assertTrue(flash.pLive() < 0.5, "flash p " + flash.pLive());
    }

    @Test
    void aDeviceWhoseLogDiffersFromTheScheduleAbstainsAndIsAuditedNotHeldAgainstTheResident() throws Exception {
        TestClock clock = newClock();
        FakeDeviceLight device = new FakeDeviceLight(clock);
        device.lieInLog = true;
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), false, null, null, 25, device);
        rig.run(600);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.why());
        CueResult flash = rig.cue("flash");
        assertNotNull(flash, rig.why());
        assertTrue(flash.abstained(), rig.why());
        assertTrue(rig.audits.stream().anyMatch(a -> a.contains("FLASH_DEVICE_LOG_PROBLEM")), rig.audits.toString());
    }

    @Test
    void aDeviceThatCannotIlluminateAbstainsAndIsAudited() throws Exception {
        TestClock clock = newClock();
        FakeDeviceLight device = new FakeDeviceLight(clock);
        device.fail = true;
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), false, null, null, 25, device);
        rig.run(600);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.why());
        assertTrue(rig.cue("flash").abstained());
        assertTrue(rig.audits.stream().anyMatch(a -> a.contains("FLASH_DEVICE_LOG_PROBLEM")), rig.audits.toString());
    }

    // ------------------------------------------------------------------------------------------ stereo, pupil

    @Test
    void aSecondCameraAddsStereoVotesForARealFaceAndAgainstAFlatOne() {
        TestClock c1 = newClock();
        Rig real = new Rig(c1, bundle, MockFrameSource.builder(c1), false, null, new FakeStereo(25), 0);
        real.run(600);
        CueResult stereoReal = real.cue("stereo");
        assertNotNull(stereoReal, real.why());
        assertFalse(stereoReal.abstained());
        assertTrue(stereoReal.pLive() > 0.9, real.why());

        TestClock c2 = newClock();
        Rig flat = new Rig(c2, bundle, MockFrameSource.builder(c2), false, null, new FakeStereo(0), 0);
        flat.run(600);
        CueResult stereoFlat = flat.cue("stereo");
        assertNotNull(stereoFlat, flat.why());
        assertTrue(stereoFlat.pLive() < 0.1, flat.why());
    }

    @Test
    void anIrisCameraAddsThePupilReflexPhase() {
        TestClock clock = newClock();
        FakePupil pupil = new FakePupil(clock);
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), false, pupil, null, 0);
        rig.run(900);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.why());
        assertEquals(3, pupil.stimuli.size(), "three random flashes");
        long g1 = pupil.stimuli.get(1) - pupil.stimuli.get(0);
        long g2 = pupil.stimuli.get(2) - pupil.stimuli.get(1);
        assertTrue(g1 >= 2000 && g1 <= 3700 && g2 >= 2000 && g2 <= 3700, "gaps " + g1 + ", " + g2);
        CueResult plr = rig.cue("plr");
        assertNotNull(plr, rig.why());
        assertFalse(plr.abstained(), rig.why());
        assertTrue(plr.pLive() > 0.9);
    }

    @Test
    void aPrintedIrisAbstainsAndNeverCountsAgainstTheResident() {
        TestClock clock = newClock();
        FakePupil pupil = new FakePupil(clock);
        pupil.amplitude = 0;
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), false, pupil, null, 0);
        rig.run(900);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), "no reflex is not a reason to reject: " + rig.why());
        CueResult plr = rig.cue("plr");
        assertNotNull(plr);
        assertTrue(plr.abstained());
    }

    @Test
    void stationExtrasThatAreMissingShowAsCapabilityAbstentions() {
        TestClock clock = newClock();
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), false, null, null, 0);
        rig.run(600);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.why());
        for (String name : new String[] {"stereo", "plr", "scene", "lightDirection", "whitePoint"}) {
            CueResult c = rig.cue(name);
            assertNotNull(c, name + " should be recorded: " + rig.why());
            assertTrue(c.abstained());
            assertEquals(CueResult.AbstainReason.CAPABILITY_MISSING, c.abstainReason(), name);
        }
    }

    // ------------------------------------------------------------------------------------------ the profile

    @Test
    void aDeviceThatSpeaksTheProfileGetsCryptographicBinding() {
        TestClock clock = newClock();
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock).livenessProfile(true), false, null, null, 0);
        rig.run(600);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.why());
        var binding = rig.engine.bindingResult().orElseThrow();
        assertEquals(BindingLevel.CRYPTOGRAPHIC, binding.level());
        assertTrue(binding.summary().contains("signedStream=ok"), binding.summary());
    }

    @Test
    void anInjectedFrameBreaksTheChainAndTheBindingFails() {
        TestClock clock = newClock();
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock).livenessProfile(true).injectFromFrame(20),
                false, null, null, 0);
        rig.run(600);
        var binding = rig.engine.bindingResult();
        assertTrue(binding.isPresent(), "the session got as far as binding: " + rig.why());
        assertFalse(binding.get().passed());
        assertTrue(binding.get().summary().contains("stream_tag_mismatch"), binding.get().summary());
        assertEquals(BindingLevel.HEURISTIC, binding.get().level());
    }

    @Test
    void aCaptureNamingAnotherStreamIsRefused() {
        TestClock clock = newClock();
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock).livenessProfile(true).wrongStreamHead(true),
                false, null, null, 0);
        rig.run(600);
        var binding = rig.engine.bindingResult().orElseThrow();
        assertFalse(binding.passed());
        assertTrue(binding.summary().contains("stream_head_mismatch"), binding.summary());
    }

    @Test
    void hidingTheProfileFailsClosedWhenPolicyRequiresIt() {
        TestClock clock = newClock();
        Rig rig = new Rig(clock, strictBundle, MockFrameSource.builder(clock), false, null, null, 0);
        rig.run(600);
        var binding = rig.engine.bindingResult().orElseThrow();
        assertFalse(binding.passed(), "a plain device cannot satisfy requireSignedStream");
        assertTrue(binding.summary().contains("stream_required"), binding.summary());
        assertEquals(BindingLevel.HEURISTIC, binding.level());
    }

    @Test
    void aPlainDeviceStaysHeuristicAndAcceptedWhenPolicyAllowsIt() {
        TestClock clock = newClock();
        Rig rig = new Rig(clock, bundle, MockFrameSource.builder(clock), false, null, null, 0);
        rig.run(600);
        assertEquals(SessionState.ACCEPTED, rig.session.state(), rig.why());
        assertEquals(BindingLevel.HEURISTIC, rig.engine.bindingResult().orElseThrow().level());
    }
}
