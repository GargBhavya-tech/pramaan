/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.evidence;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import pramaan.core.binding.BindingResult;
import pramaan.core.binding.LockOutcome;
import pramaan.core.challenge.Challenge;
import pramaan.core.challenge.ChallengePlan;
import pramaan.core.fusion.Decision;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.AccessibilityProfile;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.LivenessSession;
import pramaan.core.source.DeviceMetadata;

/**
 * Assembles an evidence record from what the engine actually used (Bible Part 14; Build Map #59). The
 * session supplies its own facts — nonce, outcome, attempts, cue results, frame-chain head, binding hash —
 * so they cannot drift from what was decided; the bundle supplies the exact model, policy and
 * operating-point hashes. Personal data is limited to ids.
 */
public final class EvidenceBuilder {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private final ObjectNode root = F.objectNode();

    private EvidenceBuilder() {
    }

    public static EvidenceBuilder forSession(LivenessSession s, EngineBundle bundle) {
        EvidenceBuilder b = new EvidenceBuilder();
        ObjectNode r = b.root;
        r.put("schema", EvidenceRecord.SCHEMA);
        r.put("sessionId", s.sessionId());
        r.put("sessionNonce", s.nonce().value());
        r.put("workflow", s.workflow().name());
        r.put("outcome", s.state().name());
        r.put("attempt", s.attempt());
        r.put("startedAt", s.startedAt().toString());
        r.put("hashChainHead", s.evidenceHead());
        ObjectNode models = r.putObject("models");
        bundle.table().models().forEach(models::put);
        ObjectNode policy = r.putObject("policy");
        policy.put("version", bundle.policy().policyVersion());
        policy.put("sha256", bundle.policySha256());
        policy.put("operatingPoint", bundle.policy().profile(s.workflow()).operatingPoint());
        policy.put("operatingPointsSha256", bundle.operatingPointsSha256());
        policy.put("provisionalThresholds", bundle.table().provisional());
        // Bible Part 17.2 and 15.5: was the bundle signed, which version, and was it past its age when used.
        ObjectNode signedBundle = policy.putObject("bundle");
        signedBundle.put("signed", bundle.signed());
        signedBundle.put("version", bundle.bundleVersion());
        bundle.issuedAt().ifPresent(t -> signedBundle.put("issuedAt", t.toString()));
        policy.put("stale", bundle.stale(s.startedAt()));
        // The injection guard (#84): denylist match is built (Part 5, points 1-2 remain undone — see InjectionGuard's
        // own class doc); injectionGuard(...) below fills in "detected" once the engine's own verdict is known.
        ObjectNode injection = policy.putObject("injection");
        injection.put("denylistVersion", bundle.policy().virtualCameraDenylistVersion());
        injection.put("vetoOnBoundVirtualCamera", bundle.policy().vetoOnBoundVirtualCamera());
        injection.put("requireSignedStream", bundle.policy().requireSignedStream());
        injection.put("guard", "denylist");
        Map<String, CueResult> last = new LinkedHashMap<>();
        for (CueResult c : s.evidenceCues()) {
            last.put(c.cue(), c);
        }
        ObjectNode cues = r.putObject("cues");
        last.forEach((name, c) -> {
            ObjectNode o = cues.putObject(name);
            if (c.abstained()) {
                o.put("abstain", c.abstainReason().name());
            } else {
                o.put("pLive", c.pLive());
            }
        });
        if (s.bindingFinalHash() != null) {
            r.putObject("binding").put("finalHash", s.bindingFinalHash());
        }
        return b;
    }

    public EvidenceBuilder device(DeviceMetadata d) {
        ObjectNode o = root.putObject("device");
        o.put("deviceId", d.deviceId());
        o.put("deviceCode", d.deviceCode());
        o.put("level", d.level().name());
        o.put("make", d.make());
        o.put("model", d.model());
        o.put("serialNo", d.serialNo());
        return this;
    }

    public EvidenceBuilder fusion(Decision d) {
        ObjectNode o = root.putObject("fusion");
        o.put("zone", d.zone().name());
        o.put("tier", d.tier().name());
        ArrayNode reasons = o.putArray("reasons");
        d.reasons().forEach(x -> reasons.add(x.name()));
        if (d.fusion() != null) {
            o.put("pLive", d.fusion().pLive());
            o.put("logOdds", d.fusion().logOdds());
            o.put("contributing", d.fusion().contributing());
        }
        return this;
    }

    /** The plan and, now that the session is over, the seed that produced it (the server regenerates it). */
    public EvidenceBuilder challenges(ChallengePlan plan, byte[] revealedSeed) {
        ObjectNode o = root.putObject("challenges");
        o.put("seedCommitment", plan.seedCommitment());
        StringBuilder hex = new StringBuilder();
        for (byte x : revealedSeed) {
            hex.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        o.put("seed", hex.toString());
        o.put("actionBits", plan.actionBits());
        o.put("flashBits", plan.flashBits());
        o.put("lowEntropy", plan.lowEntropy());
        if (!plan.unlockingKinds().isEmpty()) {
            ArrayNode unlocking = o.putArray("unlocking");
            plan.unlockingKinds().forEach(k -> unlocking.add(k.name()));
        }
        ArrayNode steps = o.putArray("steps");
        for (Challenge c : plan.steps()) {
            ObjectNode st = steps.addObject();
            st.put("kind", c.kind().name());
            if (c.direction() != null) {
                st.put("direction", c.direction().name());
                st.put("targetDeg", c.targetDeg());
            }
            st.put("count", c.count());
            st.put("holdMs", c.holdMs());
            st.put("gazeCell", c.gazeCell());
            st.put("onsetDelayMs", c.onsetDelayMs());
        }
        o.put("flash", plan.flash().isPresent());
        // What the plan was drawn from, so the server can draw it again from the seed and compare (Bible Part 10).
        ArrayNode pool = o.putArray("pool");
        plan.policyPool().forEach(k -> pool.add(k.name()));
        o.put("minSteps", plan.minSteps());
        o.put("maxSteps", plan.maxSteps());
        o.put("fps", plan.fps());
        ArrayNode caps = o.putArray("capabilities");
        plan.capabilities().stream().map(Enum::name).sorted().forEach(caps::add);
        ArrayNode profiles = o.putArray("accessibility");
        plan.adjustments().profiles().stream().map(Enum::name).sorted().forEach(profiles::add);
        return this;
    }

    public EvidenceBuilder binding(BindingResult r) {
        ObjectNode o = root.putObject("binding");
        o.put("level", r.level().name()); // HEURISTIC or CRYPTOGRAPHIC: the server weights the evidence by it
        if (r.finalHash() != null) {
            o.put("finalHash", r.finalHash());
        }
        ArrayNode locks = o.putArray("locks");
        for (LockOutcome l : r.outcomes()) {
            ObjectNode x = locks.addObject();
            x.put("lock", l.lock());
            x.put("code", l.code());
            if (!Double.isNaN(l.value())) {
                x.put("value", l.value());
            }
        }
        return this;
    }

    /** Whether the device bound to this capture path matched the virtual-camera denylist (Bible Part 5, Build Map #84). */
    public EvidenceBuilder injectionGuard(boolean virtualCameraDetected) {
        ((ObjectNode) root.path("policy").path("injection")).put("detected", virtualCameraDetected);
        return this;
    }

    public EvidenceBuilder accessibility(Set<AccessibilityProfile> profiles) {
        ArrayNode a = root.putArray("accessibilityProfiles");
        profiles.stream().map(Enum::name).sorted().forEach(a::add);
        return this;
    }

    public EvidenceBuilder operator(String operatorId, String centreId) {
        root.put("operatorId", operatorId);
        root.put("centreId", centreId);
        return this;
    }

    /** Encrypted sample crops (Build Map #61). */
    public EvidenceBuilder samples(ObjectNode encrypted) {
        root.set("samples", encrypted.deepCopy());
        return this;
    }

    public EvidenceRecord build(Instant endedAt) {
        root.put("endedAt", endedAt.toString());
        return new EvidenceRecord(root);
    }
}
