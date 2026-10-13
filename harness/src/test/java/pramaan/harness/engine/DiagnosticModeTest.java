/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.engine.DiagnosticGrant;
import pramaan.core.engine.DiagnosticSink;
import pramaan.core.engine.EngineListener;
import pramaan.core.engine.FrameObservation;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.fusion.Decision;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.quality.Guidance;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.SessionCapabilities;
import pramaan.core.testing.TestClock;
import pramaan.desktop.capture.CaptureScreenModel;
import pramaan.desktop.capture.ConfigurationSource;
import pramaan.desktop.capture.DesktopEngines;
import pramaan.desktop.capture.Messages;
import pramaan.desktop.capture.Text;
import pramaan.harness.mock.MockFrameSource;

/**
 * Build Map #78: policy is read at run time through a configuration source, so changing a value changes
 * behaviour without a rebuild; diagnostic mode is off by default, its activation is audited, and what it shows
 * goes to an administrator channel the resident screen never sees.
 */
class DiagnosticModeTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 9, 8, 7};
    private final List<AuditEvent> audit = new ArrayList<>();

    /** The checked-in configuration, with the table bound to the smoke model and one policy line optionally changed. */
    private static ConfigurationSource config(Path models, boolean diagnosticMode) throws Exception {
        byte[] model = Files.readAllBytes(Path.of("..", "core", "src", "test", "resources", "models", "smoke_affine.onnx"));
        Files.write(models.resolve("smoke.onnx"), model);
        String policy = new String(Files.readAllBytes(Path.of("..", "config", "pramaan-policy.yaml")), StandardCharsets.UTF_8);
        assertTrue(policy.contains("diagnosticMode: false"), "diagnostic mode must be off in the shipped policy");
        String edited = diagnosticMode ? policy.replace("diagnosticMode: false", "diagnosticMode: true") : policy;
        String table = new String(Files.readAllBytes(Path.of("..", "config", "operating-points.json")), StandardCharsets.UTF_8);
        String models0 = table.substring(table.indexOf("\"models\""), table.indexOf("},", table.indexOf("\"models\"")) + 1);
        String bound = table.replace(models0, "\"models\": {\"smoke.onnx\": \"" + OperatingPointTable.sha256Hex(model) + "\"}");
        return new ConfigurationSource() {
            @Override
            public byte[] policyYaml() {
                return edited.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public byte[] operatingPointsJson() {
                return bound.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String describe() {
                return "test configuration, diagnosticMode " + diagnosticMode;
            }
        };
    }

    @Test
    void diagnosticModeIsOffByDefaultAndEvenTheRefusalIsAudited(@TempDir Path models) throws Exception {
        try (EngineBundle shipped = DesktopEngines.loadBundle(config(models, false), models)) {
            assertFalse(shipped.policy().diagnosticMode());
            assertThrows(IllegalStateException.class,
                    () -> DiagnosticGrant.activate(shipped, "ADMIN1", "centre lighting", Instant.EPOCH, audit::add));
            assertEquals("DIAGNOSTIC_MODE_REFUSED", audit.get(audit.size() - 1).type());
            assertEquals("ADMIN1", audit.get(audit.size() - 1).fields().get("adminId"));
        }
    }

    @Test
    void changingThePolicyValueTurnsItOnWithoutARebuildAndTheResidentScreenStillSeesNothing(@TempDir Path models)
            throws Exception {
        try (EngineBundle edited = DesktopEngines.loadBundle(config(models, true), models)) {
            DiagnosticGrant grant = DiagnosticGrant.activate(edited, "ADMIN1", "centre lighting", Instant.EPOCH, audit::add);
            assertEquals("DIAGNOSTIC_MODE_ACTIVATED", audit.get(audit.size() - 1).type());

            List<String> shownToAdmin = new ArrayList<>();
            DiagnosticSink admin = new DiagnosticSink() {
                @Override
                public void capabilities(SessionCapabilities caps) {
                    shownToAdmin.add("caps " + caps.fps());
                }

                @Override
                public void frame(long t, FrameObservation o) {
                    shownToAdmin.add("frame passive " + o.passive());
                }

                @Override
                public void decision(String phase, List<CueResult> cues, Decision d) {
                    shownToAdmin.add("decision " + phase + " " + d.zone() + " " + cues.size() + " cues");
                }
            };
            List<CaptureScreenModel.Screen> screens = new ArrayList<>();
            CaptureScreenModel resident = new CaptureScreenModel(screens::add);
            SessionState end = run(edited, admin, grant, resident);

            assertEquals(SessionState.ACCEPTED, end);
            assertTrue(shownToAdmin.get(0).startsWith("caps "), String.valueOf(shownToAdmin));
            assertTrue(shownToAdmin.stream().anyMatch(s -> s.startsWith("frame passive 0.97")));
            assertTrue(shownToAdmin.stream().anyMatch(s -> s.startsWith("decision passive")));
            assertTrue(audit.stream().anyMatch(e -> e.type().equals("DIAGNOSTICS_ATTACHED")
                    && "ADMIN1".equals(e.fields().get("adminId"))));

            Messages en = Messages.forLocale(Locale.ROOT);
            for (CaptureScreenModel.Screen s : screens) {
                for (Text t : s.texts()) {
                    String shown = en.resolve(t);
                    assertFalse(shown.matches("(?i).*(score|passive|zone|cue|\\d+\\.\\d+).*"), "resident saw: " + shown);
                }
            }
        }
    }

    @Test
    void aGrantUnderOnePolicyDoesNotOpenAnother(@TempDir Path a, @TempDir Path b) throws Exception {
        try (EngineBundle on = DesktopEngines.loadBundle(config(a, true), a);
                EngineBundle shipped = DesktopEngines.loadBundle(config(b, false), b)) {
            DiagnosticGrant grant = DiagnosticGrant.activate(on, "ADMIN1", "test", Instant.EPOCH, audit::add);
            assertThrows(IllegalArgumentException.class,
                    () -> run(shipped, new DiagnosticSink() { }, grant, new CaptureScreenModel(s -> { })));
        }
    }

    private SessionState run(EngineBundle bundle, DiagnosticSink sink, DiagnosticGrant grant, CaptureScreenModel screen)
            throws Exception {
        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        SimulatedResident person = new SimulatedResident(clock);
        person.passive = 0.97;
        person.moire = 0.90;
        MockFrameSource source = MockFrameSource.builder(clock).delayer(clock::advance).fps(10).neutral(List.of(JPEG)).build();
        LivenessSession session = bundle.newSession(Workflow.RESIDENT, "R1", "OP1", "mock", "c1", new SessionRegistry(),
                audit::add, clock, new SecureRandom());
        LivenessEngine engine = LivenessEngine.builder(bundle, session)
                .device(source, source.trust(), DeviceLevel.L1)
                .analysers(person, c -> SimulatedResident.RESIDENT)
                .capabilities(SessionCapabilities.of(10, source.discover()))
                .random(new SecureRandom())
                .listener(new EngineListener() {
                    @Override
                    public void stateChanged(SessionState s) {
                        screen.stateChanged(s);
                    }

                    @Override
                    public void guidance(Guidance g) {
                        screen.guidance(g);
                    }

                    @Override
                    public void message(String id) {
                        screen.message(id);
                    }

                    @Override
                    public void prompt(ChallengePrompt p, int step, int steps) {
                        screen.prompt(p, step, steps);
                        person.respond(p);
                    }
                })
                .diagnostics(sink, grant)
                .requestedScore(40)
                .build();
        source.start(null, engine);
        engine.start();
        for (int i = 0; i < 400 && session.state() != SessionState.ACCEPTED && session.state() != SessionState.RETRY_WAIT; i++) {
            source.step();
        }
        return session.state();
    }
}
