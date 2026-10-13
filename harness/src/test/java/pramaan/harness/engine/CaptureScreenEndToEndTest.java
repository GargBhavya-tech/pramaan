/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.engine.EngineListener;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.quality.Guidance;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionContext;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;
import pramaan.core.source.FaceFrameSource;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.SignedCapture;
import pramaan.core.testing.TestClock;
import pramaan.desktop.capture.CaptureScreenModel;
import pramaan.desktop.capture.CaptureSession;
import pramaan.desktop.capture.Messages;
import pramaan.desktop.capture.Text;
import pramaan.harness.mock.MockFrameSource;

/**
 * Build Map #75's test: a full resident capture runs end to end — device search, stream, measured frame rate,
 * engine, prompts, capture, binding — through the scaffold's {@link CaptureSession} and the screen model, and no
 * engine internal ever reaches the screen. The mock device signs for real; the resident is simulated.
 */
class CaptureScreenEndToEndTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 9, 8, 7};
    /** What a resident must never read (Bible Part 19): scores, zones, cues, causes of rejection, decimals. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)score|probab|confiden|zone|cue|threshold|moir|spoof|fake|attack|replay|tau|p_?live|logit|%|\\d+\\.\\d+");
    private static EngineBundle bundle;
    private final Messages en = Messages.forLocale(Locale.ROOT);

    @BeforeAll
    static void loadBundle() throws Exception {
        byte[] policy = Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml"));
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        bundle = EngineBundle.load(policy, bound.getBytes(StandardCharsets.UTF_8), Map.of("smoke.onnx", model));
    }

    private static final FrameSink NOWHERE = new FrameSink() {
        @Override
        public void onFrame(Frame frame) {
        }

        @Override
        public void onDeviceEvent(DeviceEvent event) {
        }
    };

    /** One scan on the scaffold's own capture session, with the resident answering whatever the screen asks. */
    private final class Scan {
        final TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        final SimulatedResident resident = new SimulatedResident(clock);
        final List<CaptureScreenModel.Screen> screens = new ArrayList<>();
        final CaptureScreenModel model = new CaptureScreenModel(screens::add);
        final List<Frame> previewed = new ArrayList<>();
        final List<Exception> problems = new ArrayList<>();
        final MockFrameSource source;
        final LivenessSession session;
        final CaptureSession capture;

        Scan() throws GeneralSecurityException {
            source = MockFrameSource.builder(clock).delayer(clock::advance).fps(10).neutral(List.of(JPEG)).build();
            session = bundle.newSession(Workflow.RESIDENT, "R1", "OP1", "mock", "c1", new SessionRegistry(), e -> { },
                    clock, new SecureRandom());
            EngineListener screenAndResident = new EngineListener() {
                @Override
                public void stateChanged(SessionState state) {
                    model.stateChanged(state);
                }

                @Override
                public void guidance(Guidance g) {
                    model.guidance(g);
                }

                @Override
                public void message(String messageId) {
                    model.message(messageId);
                }

                @Override
                public void prompt(ChallengePrompt prompt, int step, int steps) {
                    model.prompt(prompt, step, steps);
                    resident.respond(prompt); // the resident reads the screen and acts
                }

                @Override
                public void stepCompleted(int step, int steps) {
                    model.stepCompleted(step, steps);
                }

                @Override
                public void attempt(int attempt, int max) {
                    model.attempt(attempt, max);
                }
            };
            FrameSink preview = new FrameSink() {
                @Override
                public void onFrame(Frame frame) {
                    previewed.add(frame);
                }

                @Override
                public void onDeviceEvent(DeviceEvent event) {
                }
            };
            capture = new CaptureSession(source, (caps, device) -> LivenessEngine.builder(bundle, session)
                    .device(source, source.trust(), device.device().level())
                    .analysers(resident, c -> SimulatedResident.RESIDENT)
                    .capabilities(caps)
                    .random(new SecureRandom())
                    .listener(screenAndResident)
                    .requestedScore(40)
                    .build(), preview, new CaptureSession.Problems() {
                        @Override
                        public void deviceUnavailable(Exception cause) {
                            problems.add(cause);
                            model.deviceUnavailable();
                        }

                        @Override
                        public void engineUnavailable(Exception cause) {
                            problems.add(cause);
                            model.engineUnavailable();
                        }
                    });
        }

        CaptureScreenModel.Stage run(int maxFrames) {
            assertTrue(capture.start(), String.valueOf(problems));
            for (int i = 0; i < maxFrames; i++) {
                CaptureScreenModel.Stage s = stage();
                if (s == CaptureScreenModel.Stage.SUCCESS || s == CaptureScreenModel.Stage.STOPPED) {
                    break;
                }
                source.step();
            }
            return stage();
        }

        CaptureScreenModel.Stage stage() {
            return screens.isEmpty() ? CaptureScreenModel.Stage.CONNECTING : screens.get(screens.size() - 1).stage();
        }

        List<String> everythingShown() {
            List<String> out = new ArrayList<>();
            for (CaptureScreenModel.Screen s : screens) {
                for (Text t : s.texts()) {
                    out.add(en.resolve(t));
                }
            }
            return out;
        }
    }

    private static void assertNothingInternal(List<String> shown) {
        for (String s : shown) {
            assertFalse(FORBIDDEN.matcher(s).find(), "shown to the resident: '" + s + "'");
            assertFalse(s.startsWith("pramaan."), "an untranslated id reached the screen: " + s);
        }
    }

    @Test
    void aResidentCaptureRunsEndToEndAndTheScreenShowsNoEngineInternals() throws Exception {
        Scan scan = new Scan();
        scan.resident.passive = 0.97;
        scan.resident.moire = 0.90;
        assertEquals(CaptureScreenModel.Stage.SUCCESS, scan.run(600), String.valueOf(scan.screens));
        assertTrue(scan.capture.acceptedCapture().isPresent(), "the signed capture the binder accepted");
        assertTrue(scan.session.evidenceFrames() > 0);
        assertTrue(scan.session.evidenceFrames() < scan.previewed.size(),
                "the first second only measured the frame rate: shown, never scored or hashed");
        List<String> shown = scan.everythingShown();
        assertTrue(shown.contains("Checking face liveness…"), String.valueOf(shown));
        assertEquals("Good, face captured successfully", shown.get(shown.size() - 1));
        assertNothingInternal(shown);
    }

    @Test
    void aChallengeIsShownAsWordsAndAnimationAndItsCompletionIsAcknowledged() throws Exception {
        Scan scan = new Scan(); // borderline passive score: the engine asks for a challenge
        scan.run(1500);
        List<CaptureScreenModel.Screen> prompts = new ArrayList<>();
        for (CaptureScreenModel.Screen s : scan.screens) {
            if (s.stage() == CaptureScreenModel.Stage.CHALLENGE && s.animation() != null) {
                prompts.add(s);
            }
        }
        assertFalse(prompts.isEmpty(), String.valueOf(scan.screens));
        for (CaptureScreenModel.Screen p : prompts) {
            assertTrue(en.resolve(p.headline()).startsWith("Please "), p.toString());
        }
        List<String> shown = scan.everythingShown();
        assertTrue(shown.contains("Action detected"), String.valueOf(shown));
        assertNothingInternal(shown);
    }

    @Test
    void noFaceDeviceMeansNoSessionAndTheOperatorIsTold() {
        List<CaptureScreenModel.Screen> screens = new ArrayList<>();
        CaptureScreenModel model = new CaptureScreenModel(screens::add);
        FaceFrameSource absent = new FaceFrameSource() {
            @Override
            public DeviceCapabilities discover() throws DeviceException {
                throw new DeviceException(DeviceException.Reason.UNAVAILABLE, "no SBI device service answered");
            }

            @Override
            public void start(SessionContext session, FrameSink sink) {
                throw new AssertionError("never started without a device");
            }

            @Override
            public SignedCapture capture(CaptureRequest request) {
                throw new AssertionError("never captured without a device");
            }

            @Override
            public void stop() {
            }
        };
        CaptureSession cs = new CaptureSession(absent, (caps, device) -> {
            throw new AssertionError("no engine without a device");
        }, NOWHERE, new CaptureSession.Problems() {
            @Override
            public void deviceUnavailable(Exception cause) {
                model.deviceUnavailable();
            }

            @Override
            public void engineUnavailable(Exception cause) {
                model.engineUnavailable();
            }
        });
        assertFalse(cs.start());
        assertEquals(CaptureScreenModel.Stage.STOPPED, screens.get(screens.size() - 1).stage());
        assertEquals("Camera not connected. Please check the device.", en.resolve(screens.get(screens.size() - 1).headline()));
    }
}
