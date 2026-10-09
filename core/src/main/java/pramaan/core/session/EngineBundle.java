/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import ai.onnxruntime.OrtException;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.inference.OnnxModel;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.policy.BundleManifest;
import pramaan.core.policy.InvalidPolicyException;
import pramaan.core.policy.RollbackGuard;
import pramaan.core.policy.LivenessPolicy;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.policy.YamlPolicySource;

/**
 * The verified set a session runs on — policy, operating-point table, models — and the only public way to
 * create a {@link LivenessSession} (Build Map #51). If any part is missing, malformed, bound to different
 * models, or a model will not load, {@link #load} throws and no session can exist: liveness fails closed,
 * it is never silently skipped (Bible Part 22).
 */
public final class EngineBundle implements AutoCloseable {

    private final LivenessPolicy policy;
    private final OperatingPointTable table;
    private final Map<String, OnnxModel> models;
    private final String policySha256;
    private final String operatingPointsSha256;
    private BundleManifest manifest;

    private EngineBundle(LivenessPolicy policy, OperatingPointTable table, Map<String, OnnxModel> models, String policySha256,
            String operatingPointsSha256) {
        this.policy = policy;
        this.table = table;
        this.models = Collections.unmodifiableMap(models);
        this.policySha256 = policySha256;
        this.operatingPointsSha256 = operatingPointsSha256;
    }

    /**
     * A bundle whose signed manifest names exactly these bytes (Bible Part 17.2): the signature is checked first,
     * then every file's hash, then the version against the highest this client has run, so a tampered, partial or
     * older bundle never loads. The version is recorded only once everything, models included, has loaded.
     */
    public static EngineBundle loadSigned(String manifestJws, JwsTrust bundleTrust, byte[] policyYaml,
            byte[] operatingPointsJson, Map<String, byte[]> modelBytes, RollbackGuard rollback)
            throws EngineUnavailableException {
        BundleManifest m;
        try {
            m = BundleManifest.verify(manifestJws, bundleTrust, policyYaml, operatingPointsJson, modelBytes);
            long highest = rollback.highestSeen();
            if (m.bundleVersion() < highest) {
                throw new InvalidPolicyException("bundle version " + m.bundleVersion() + " is older than version " + highest
                        + " already run here: refusing a rollback");
            }
        } catch (InvalidPolicyException | IOException e) {
            throw new EngineUnavailableException(LivenessError.BUNDLE_INVALID, e.getMessage(), e);
        }
        EngineBundle b = load(policyYaml, operatingPointsJson, modelBytes);
        try {
            rollback.record(m.bundleVersion());
        } catch (IOException e) {
            b.close();
            throw new EngineUnavailableException(LivenessError.BUNDLE_INVALID, "cannot record the bundle version: " + e.getMessage(), e);
        }
        b.manifest = m;
        return b;
    }

    /** True if this bundle came with a verified signed manifest; false for an unsigned development directory. */
    public boolean signed() {
        return manifest != null;
    }

    /** The signed manifest's version; 0 for an unsigned bundle. */
    public long bundleVersion() {
        return manifest == null ? 0 : manifest.bundleVersion();
    }

    /** When the signed bundle was issued; empty for an unsigned bundle, whose age cannot be known. */
    public Optional<Instant> issuedAt() {
        return manifest == null ? Optional.empty() : Optional.of(manifest.issuedAt());
    }

    /**
     * Older than the policy's {@code stalePolicyMaxAgeDays} (Bible Part 15.5): the client keeps working, the screen
     * says so, and the evidence is marked for the server to weigh. An unsigned bundle has no trusted age: never stale.
     */
    public boolean stale(Instant now) {
        return issuedAt().map(t -> now.isAfter(t.plus(Duration.ofDays(policy.stalePolicyMaxAgeDays())))).orElse(false);
    }

    /**
     * @param modelBytes model file name to the exact bytes that will run
     */
    public static EngineBundle load(byte[] policyYaml, byte[] operatingPointsJson, Map<String, byte[]> modelBytes)
            throws EngineUnavailableException {
        LivenessPolicy policy;
        OperatingPointTable table;
        try {
            policy = YamlPolicySource.of(policyYaml, "policy").load();
            if (!policy.enabled()) {
                // PRAMAAN never skips liveness (Bible Part 22): a policy that switches it off yields no engine at all,
                // and the host must refuse face capture rather than capture without liveness.
                throw new InvalidPolicyException("pramaan.enabled is false: face liveness is disabled by policy");
            }
            Map<String, String> hashes = new TreeMap<>();
            modelBytes.forEach((name, bytes) -> hashes.put(name, OperatingPointTable.sha256Hex(bytes)));
            table = OperatingPointTable.load(operatingPointsJson, "operating-points", hashes);
            table.requireCovers(policy);
        } catch (InvalidPolicyException | IOException e) {
            throw new EngineUnavailableException(LivenessError.BUNDLE_INVALID, e.getMessage(), e);
        }
        Map<String, OnnxModel> loaded = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, byte[]> m : new TreeMap<>(modelBytes).entrySet()) {
                loaded.put(m.getKey(), OnnxModel.load(m.getValue()));
            }
        } catch (OrtException | IllegalArgumentException e) {
            closeAll(loaded.values());
            throw new EngineUnavailableException(LivenessError.BUNDLE_INVALID, "a model will not load: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            closeAll(loaded.values());
            throw new EngineUnavailableException(LivenessError.ENGINE_ERROR, e.getMessage(), e);
        }
        return new EngineBundle(policy, table, loaded, OperatingPointTable.sha256Hex(policyYaml),
                OperatingPointTable.sha256Hex(operatingPointsJson));
    }

    public LivenessPolicy policy() {
        return policy;
    }

    public OperatingPointTable table() {
        return table;
    }

    /** SHA-256 of the exact policy bytes this bundle runs: what the evidence records and the server allow-lists. */
    public String policySha256() {
        return policySha256;
    }

    public String operatingPointsSha256() {
        return operatingPointsSha256;
    }

    public Map<String, OnnxModel> models() {
        return models;
    }

    /** A new resident or operator session on this bundle. Supervisors use {@link #newSupervisorSession}. */
    public LivenessSession newSession(Workflow workflow, String subjectId, String operatorId, String deviceId,
            String centreId, SessionRegistry registry, Consumer<AuditEvent> audit, Clock clock, SecureRandom random) {
        if (Objects.requireNonNull(workflow, "workflow") == Workflow.SUPERVISOR) {
            throw new IllegalArgumentException("a supervisor session must name what it approves: use newSupervisorSession");
        }
        SessionContext ctx = SessionContext.start(workflow, policy, clock, random);
        return new LivenessSession(ctx, subjectId, operatorId, deviceId, centreId, registry, audit, random);
    }

    /**
     * A supervisor strict liveness session for one approval (Build Map #77): {@code approves} — a packet id, or
     * the resident session of an exception — is fixed before the face is checked, so the verdict cannot be
     * carried to anything else. A supervisor login is not an approval.
     */
    public LivenessSession newSupervisorSession(String supervisorId, String approves, String deviceId, String centreId,
            SessionRegistry registry, Consumer<AuditEvent> audit, Clock clock, SecureRandom random) {
        if (approves == null || approves.isBlank()) {
            throw new IllegalArgumentException("a supervisor session must name what it approves");
        }
        SessionContext ctx = SessionContext.start(Workflow.SUPERVISOR, policy, clock, random);
        audit.accept(AuditEvent.of("SUPERVISOR_SESSION_STARTED").put("sessionId", ctx.sessionId())
                .put("supervisorId", supervisorId).put("approves", approves).build());
        return new LivenessSession(ctx, supervisorId, supervisorId, deviceId, centreId, registry, audit, random, approves);
    }

    @Override
    public void close() {
        closeAll(models.values());
    }

    private static void closeAll(Iterable<OnnxModel> ms) {
        List<OnnxModel> copy = new ArrayList<>();
        ms.forEach(copy::add);
        for (OnnxModel m : copy) {
            try {
                m.close();
            } catch (OrtException ignored) {
                // Closing is best effort; the bundle is being discarded either way.
            }
        }
    }
}
