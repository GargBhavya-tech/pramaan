/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import pramaan.core.Workflow;

/**
 * The complete, validated liveness policy (Bible Part 17.1; Build Map #9). Immutable. Every knob the
 * engine uses comes from here; nothing security-relevant is hardcoded in {@code core}.
 */
public final class LivenessPolicy {

    private final int policyVersion;
    private final boolean enabled;
    private final int stalePolicyMaxAgeDays;
    private final boolean diagnosticMode;
    private final Map<Workflow, WorkflowProfile> profiles;
    private final List<AccessibilityProfile> accessibilityProfiles;
    private final int exceptionMaxPerOperatorPerDay;
    private final int exceptionMaxPerCentrePerDay;
    private final boolean exceptionRequiresSupervisor;
    private final int evidenceSampleFrames;
    private final int evidenceCropSize;
    private final int virtualCameraDenylistVersion;
    private final boolean vetoOnBoundVirtualCamera;
    private final SessionTimeouts sessionTimeouts;
    private final RateLimits rateLimits;
    private final java.time.Duration supervisorVerdictMaxAge;

    private LivenessPolicy(Builder b) {
        this.policyVersion = b.policyVersion;
        this.enabled = b.enabled;
        this.stalePolicyMaxAgeDays = b.stalePolicyMaxAgeDays;
        this.diagnosticMode = b.diagnosticMode;
        this.profiles = Collections.unmodifiableMap(b.profiles);
        this.accessibilityProfiles = Collections.unmodifiableList(b.accessibilityProfiles);
        this.exceptionMaxPerOperatorPerDay = b.exceptionMaxPerOperatorPerDay;
        this.exceptionMaxPerCentrePerDay = b.exceptionMaxPerCentrePerDay;
        this.exceptionRequiresSupervisor = b.exceptionRequiresSupervisor;
        this.evidenceSampleFrames = b.evidenceSampleFrames;
        this.evidenceCropSize = b.evidenceCropSize;
        this.virtualCameraDenylistVersion = b.virtualCameraDenylistVersion;
        this.vetoOnBoundVirtualCamera = b.vetoOnBoundVirtualCamera;
        this.sessionTimeouts = b.sessionTimeouts;
        this.rateLimits = b.rateLimits;
        this.supervisorVerdictMaxAge = b.supervisorVerdictMaxAge;
    }

    /** Parses and validates a policy tree whose root holds a single {@code pramaan} object. */
    static LivenessPolicy fromTree(JsonNode root) {
        StrictNode top = new StrictNode(root, "$");
        StrictNode n = top.object("pramaan");
        top.finish();

        Builder b = new Builder();
        b.policyVersion = n.integer("policyVersion");
        b.enabled = n.bool("enabled");
        b.stalePolicyMaxAgeDays = n.integer("stalePolicyMaxAgeDays");
        b.diagnosticMode = n.bool("diagnosticMode");

        StrictNode profilesNode = n.object("profiles");
        for (Workflow workflow : Workflow.values()) {
            b.profiles.put(workflow, WorkflowProfile.read(profilesNode.object(workflow.name())));
        }
        profilesNode.finish();

        b.accessibilityProfiles = n.enumList("accessibilityProfiles", AccessibilityProfile.class);

        StrictNode exceptionFlow = n.object("exceptionFlow");
        b.exceptionRequiresSupervisor = exceptionFlow.bool("requireSupervisor");
        b.exceptionMaxPerOperatorPerDay = exceptionFlow.integer("maxPerOperatorPerDay");
        b.exceptionMaxPerCentrePerDay = exceptionFlow.integer("maxPerCentrePerDay");
        b.supervisorVerdictMaxAge = java.time.Duration.ofMillis(exceptionFlow.integer("supervisorVerdictMaxAgeMs"));
        exceptionFlow.finish();

        b.sessionTimeouts = SessionTimeouts.read(n.object("session"));
        b.rateLimits = RateLimits.read(n.object("rateLimits"));

        StrictNode injection = n.object("injection");
        b.virtualCameraDenylistVersion = injection.integer("virtualCameraDenylistVersion");
        b.vetoOnBoundVirtualCamera = injection.bool("vetoOnBoundVirtualCamera");
        injection.finish();

        StrictNode evidence = n.object("evidence");
        b.evidenceSampleFrames = evidence.integer("sampleFrames");
        b.evidenceCropSize = evidence.integer("cropSize");
        evidence.finish();

        n.finish();
        b.validate();
        return new LivenessPolicy(b);
    }

    public int policyVersion() {
        return policyVersion;
    }

    public boolean enabled() {
        return enabled;
    }

    public int stalePolicyMaxAgeDays() {
        return stalePolicyMaxAgeDays;
    }

    public boolean diagnosticMode() {
        return diagnosticMode;
    }

    public WorkflowProfile profile(Workflow workflow) {
        return profiles.get(Objects.requireNonNull(workflow, "workflow"));
    }

    public List<AccessibilityProfile> accessibilityProfiles() {
        return accessibilityProfiles;
    }

    public boolean exceptionRequiresSupervisor() {
        return exceptionRequiresSupervisor;
    }

    public int exceptionMaxPerOperatorPerDay() {
        return exceptionMaxPerOperatorPerDay;
    }

    public int exceptionMaxPerCentrePerDay() {
        return exceptionMaxPerCentrePerDay;
    }

    public int evidenceSampleFrames() {
        return evidenceSampleFrames;
    }

    public int evidenceCropSize() {
        return evidenceCropSize;
    }

    public int virtualCameraDenylistVersion() {
        return virtualCameraDenylistVersion;
    }

    /** Per-state limits for the session state machine (Build Map #48). */
    public SessionTimeouts sessionTimeouts() {
        return sessionTimeouts;
    }

    /** Attempts allowed per rolling hour (Build Map #49). */
    public RateLimits rateLimits() {
        return rateLimits;
    }

    /** How old a supervisor's passing session may be when it approves an exception (Build Map #50). */
    public java.time.Duration supervisorVerdictMaxAge() {
        return supervisorVerdictMaxAge;
    }

    public boolean vetoOnBoundVirtualCamera() {
        return vetoOnBoundVirtualCamera;
    }

    private static final class Builder {
        int policyVersion;
        boolean enabled;
        int stalePolicyMaxAgeDays;
        boolean diagnosticMode;
        final Map<Workflow, WorkflowProfile> profiles = new EnumMap<>(Workflow.class);
        List<AccessibilityProfile> accessibilityProfiles;
        boolean exceptionRequiresSupervisor;
        int exceptionMaxPerOperatorPerDay;
        int exceptionMaxPerCentrePerDay;
        int virtualCameraDenylistVersion;
        boolean vetoOnBoundVirtualCamera;
        int evidenceSampleFrames;
        int evidenceCropSize;
        SessionTimeouts sessionTimeouts;
        RateLimits rateLimits;
        java.time.Duration supervisorVerdictMaxAge;

        void validate() {
            require(exceptionRequiresSupervisor, "pramaan.exceptionFlow.requireSupervisor must be true: the exception"
                    + " path is dual-controlled by design (Bible Part 12) and cannot be switched off");
            require(supervisorVerdictMaxAge.toMinutes() >= 1 && supervisorVerdictMaxAge.toMinutes() <= 60,
                    "pramaan.exceptionFlow.supervisorVerdictMaxAgeMs must be 1..60 minutes");
            require(policyVersion >= 1, "pramaan.policyVersion must be at least 1");
            require(stalePolicyMaxAgeDays >= 1, "pramaan.stalePolicyMaxAgeDays must be at least 1");
            require(exceptionMaxPerOperatorPerDay >= 0, "pramaan.exceptionFlow.maxPerOperatorPerDay must not be negative");
            require(exceptionMaxPerCentrePerDay >= exceptionMaxPerOperatorPerDay,
                    "pramaan.exceptionFlow.maxPerCentrePerDay must be >= maxPerOperatorPerDay");
            require(evidenceSampleFrames >= 1 && evidenceSampleFrames <= 10, "pramaan.evidence.sampleFrames must be 1..10");
            require(evidenceCropSize >= 64 && evidenceCropSize <= 512, "pramaan.evidence.cropSize must be 64..512");
            require(virtualCameraDenylistVersion >= 1, "pramaan.injection.virtualCameraDenylistVersion must be at least 1");
            // The Bible's tiering (Part 15.1): supervisors are never less strict than operators, operators never
            // less strict than residents, on the knobs that make a policy stricter.
            WorkflowProfile r = profiles.get(Workflow.RESIDENT);
            WorkflowProfile o = profiles.get(Workflow.OPERATOR);
            WorkflowProfile s = profiles.get(Workflow.SUPERVISOR);
            require(r.minCues() <= o.minCues() && o.minCues() <= s.minCues(),
                    "profiles: minCues must not decrease from RESIDENT to OPERATOR to SUPERVISOR");
            require(r.auditChallengeProbability() <= o.auditChallengeProbability()
                            && o.auditChallengeProbability() <= s.auditChallengeProbability(),
                    "profiles: auditChallengeProbability must not decrease from RESIDENT to OPERATOR to SUPERVISOR");
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new InvalidPolicyException(message);
            }
        }
    }
}
