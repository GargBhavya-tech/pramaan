/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.ChallengeGenerator;
import pramaan.core.challenge.ChallengePlan;
import pramaan.core.fusion.Decision;
import pramaan.core.fusion.FusionResult;
import pramaan.core.fusion.RiskFusion;
import pramaan.core.fusion.Veto;
import pramaan.core.fusion.ZoneDecider;
import pramaan.core.fusion.Zone;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.AccessibilityProfile;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.policy.LivenessPolicy;
import pramaan.core.policy.OperatingPoint;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.policy.YamlPolicySource;
import pramaan.core.source.Capability;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.SessionCapabilities;
import pramaan.core.testing.TestClock;
import pramaan.core.binding.BindingResult;
import pramaan.core.binding.CaptureBinder;
import pramaan.core.binding.SbiCaptureChain;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.source.SignedCapture;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/** Real policy, real table, real fusion: the decisions a session sees in tests are the ones production makes. */
final class SessionFixtures {

    final LivenessPolicy policy;
    final OperatingPointTable table;
    final TestClock clock = TestClock.at("2026-09-23T09:00:00Z");
    final SessionRegistry registry = new SessionRegistry();
    final List<AuditEvent> audit = new ArrayList<>();
    private final ZoneDecider noAudit;
    private static KeyPair deviceKey;
    static final float[] FACE = face(0);
    static final float[] STRANGER = face(64);
    private final ZoneDecider alwaysAudit;

    SessionFixtures() throws Exception {
        policy = YamlPolicySource.of(Path.of("..", "config", "pramaan-policy.yaml")).load();
        byte[] json = Files.readAllBytes(Path.of("..", "config", "operating-points.json"));
        Map<String, String> models = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = new ObjectMapper().readTree(json).get("models").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            models.put(e.getKey(), e.getValue().textValue());
        }
        table = OperatingPointTable.load(json, "operating-points.json", models);
        noAudit = new ZoneDecider(() -> 0.999, table.disagreementLogitGap());
        synchronized (SessionFixtures.class) {
            if (deviceKey == null) {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(2048);
                deviceKey = g.generateKeyPair();
            }
        }
        alwaysAudit = new ZoneDecider(() -> 0.0, table.disagreementLogitGap());
    }

    LivenessSession session(Workflow w, String subject, String operator) {
        SessionContext ctx = SessionContext.start(w, policy, clock, new SecureRandom());
        return new LivenessSession(ctx, subject, operator, "device-1", "centre-1", registry, audit::add, new SecureRandom());
    }

    private Decision decide(Workflow w, boolean afterChallenge, boolean audited, CueResult... cues) {
        FusionResult fused = new RiskFusion(table).fuse(List.of(cues), w, DeviceLevel.L1);
        OperatingPoint point = table.operatingPoint(policy.profile(w).operatingPoint(), "default");
        ZoneDecider d = audited ? alwaysAudit : noAudit;
        return afterChallenge ? d.decideAfterChallenge(fused, point, policy.profile(w), EnumSet.noneOf(Veto.class))
                : d.decide(fused, point, policy.profile(w), EnumSet.noneOf(Veto.class));
    }

    private static CueResult v(String cue, double p) {
        return CueResult.vote(cue, p, Double.NaN);
    }

    /** Strong live evidence from three cues: accepts under every profile when not audited. */
    Decision accept(Workflow w) {
        return decide(w, false, false, v("passive", 0.99), v("moire", 0.99), v("flatness", 0.99));
    }

    Decision acceptAfterChallenge(Workflow w) {
        return decide(w, true, false, v("passive", 0.99), v("moire", 0.99), v("flatness", 0.99));
    }

    /** The same strong evidence, but the random audit fires. */
    Decision audited(Workflow w) {
        return decide(w, false, true, v("passive", 0.99), v("moire", 0.99), v("flatness", 0.99));
    }

    Decision reject(Workflow w) {
        return decide(w, false, false, v("passive", 0.1), v("moire", 0.02), v("flatness", 0.05));
    }

    /** A clear accept, but with a hard veto: fusion is bypassed. */
    Decision vetoed(Workflow w, Veto veto) {
        FusionResult fused = new RiskFusion(table).fuse(List.of(v("passive", 0.99)), w, DeviceLevel.L1);
        OperatingPoint point = table.operatingPoint(policy.profile(w).operatingPoint(), "default");
        Decision d = noAudit.decide(fused, point, policy.profile(w), EnumSet.of(veto));
        if (d.zone() != Zone.REJECT_PAD) {
            throw new AssertionError("a veto must reject");
        }
        return d;
    }

    /** One analysed frame on track 1 with its face embedding, as the pipeline would deliver it. */
    void feed(LivenessSession s) {
        clock.advanceMillis(33);
        s.frameAnalysed(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) clock.millis()}, clock.millis(), 1);
        s.recordEmbedding(FACE);
    }

    static float[] face(int at) {
        float[] v = new float[128];
        java.util.Arrays.fill(v, 0.01f);
        v[at] = 1;
        return pramaan.core.binding.Embeddings.normalize(v);
    }

    /**
     * A real capture signed by the test device and bound by the real binder: {@code sameFace} decides whether
     * the captured face matches the attempt's frames. Moves the session from CAPTURING through BINDING.
     */
    BindingResult bind(LivenessSession s, boolean sameFace) {
        return bindAs(s, sameFace ? FACE : STRANGER);
    }

    /** As {@link #bind(LivenessSession, boolean)}, with an arbitrary embedding as the signed capture's face. */
    BindingResult bindAs(LivenessSession s, float[] capturedFace) {
        try {
            String ts = java.time.format.DateTimeFormatter.ISO_INSTANT.format(clock.instant());
            byte[] iso = {1, 2, 3, (byte) clock.millis()};
            String payload = "{\"transactionId\":\"" + s.nonce().value() + "\",\"timestamp\":\"" + ts
                    + "\",\"bioValue\":\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(iso) + "\"}";
            String jws = Jws.signRs256(payload.getBytes(StandardCharsets.UTF_8), "test-device", deviceKey.getPrivate());
            SignedCapture capture = new SignedCapture(jws, SbiCaptureChain.expectedHash("", iso), "", s.nonce().value(), ts,
                    "test-device", new DeviceMetadata("3", "0", "code", DeviceLevel.L1, "mock", "face", "1"), clock.instant());
            s.captured();
            CaptureBinder binder = new CaptureBinder(JwsTrust.builder(clock).key("test-device", deviceKey.getPublic()).build(),
                    table.thetaSame(), policy.sessionTimeouts().bindingWindow(), policy.sessionTimeouts().captureMaxSkew());
            BindingResult r = binder.bind(s.bindingInput(capture, capturedFace, 1, List.of()));
            s.bindingVerified(r);
            return r;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Bible Part 9 worked example: P = 0.70, a challenge for a resident. */
    Decision challenge(Workflow w) {
        return decide(w, false, false, v("passive", 0.62), v("colorTexture", 0.40), v("moire", 0.70));
    }

    /** A plan with exactly {@code steps} action steps and no flash (no resident-facing display). */
    ChallengePlan plan(int steps) {
        SessionCapabilities caps = SessionCapabilities.of(30, new DeviceCapabilities(
                new DeviceMetadata("3", "0", "code", DeviceLevel.L1, "mock", "face", "1"), EnumSet.noneOf(Capability.class)));
        return ChallengeGenerator.fresh(new SecureRandom()).plan(List.of(ChallengeKind.TURN, ChallengeKind.BLINK), steps, steps,
                caps, AccessibilityAdjustments.NONE);
    }

    /** An infant at a desk with no resident-facing light: nothing to ask, nothing to flash. */
    ChallengePlan infantPlan() {
        SessionCapabilities caps = SessionCapabilities.of(3, new DeviceCapabilities(
                new DeviceMetadata("3", "0", "code", DeviceLevel.L1, "mock", "face", "1"), EnumSet.noneOf(Capability.class)));
        return ChallengeGenerator.fresh(new SecureRandom()).plan(policy.profile(Workflow.RESIDENT).challenge(), caps,
                AccessibilityAdjustments.of(EnumSet.of(AccessibilityProfile.INFANT)));
    }

    /** Drives a session from IDLE (or ACQUIRING) through the passive window. */
    void toPassiveDue(LivenessSession s) {
        if (s.state() == SessionState.IDLE) {
            s.start();
        }
        s.faceAcquired();
        feed(s);
        clock.advance(policy.sessionTimeouts().passiveWindow());
    }

    /**
     * A supervisor who really passed a strict session (supervisors are always audited: q = 1), started for one
     * approval: {@code approves} is a packet id or the resident session of an exception (Build Map #77).
     */
    SupervisorVerdict supervisorVerdict(String supervisorId, String approves) {
        SessionContext ctx = SessionContext.start(Workflow.SUPERVISOR, policy, clock, new SecureRandom());
        LivenessSession s = new LivenessSession(ctx, supervisorId, supervisorId, "device-1", "centre-1", registry,
                audit::add, new SecureRandom(), approves);
        toPassiveDue(s);
        s.passiveDecided(audited(Workflow.SUPERVISOR), plan(3));
        for (int i = 0; i < 3; i++) {
            s.challengeStepPassed();
        }
        feed(s);
        s.challengesDecided(acceptAfterChallenge(Workflow.SUPERVISOR));
        bind(s, true);
        return s.supervisorVerdict().orElseThrow();
    }
}
