/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.regclient;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.engine.EngineListener;
import pramaan.core.evidence.EvidencePackager;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionRegistry;
import pramaan.core.source.FaceFrameSource;
import pramaan.desktop.capture.CaptureSession;
import pramaan.desktop.capture.DesktopEngines;

/**
 * Everything the host (the Registration Client's start-up code) must give the PRAMAAN hook, once, with
 * {@link PramaanLivenessHook#configure}. Nothing here has a default that skips liveness: without settings the hook refuses
 * every face scan.
 */
public final class AdapterSettings {

    /** Who the liveness session is for. The hook interface does not carry this, so the host supplies it per scan. */
    public static final class Subject {
        private final Workflow workflow;
        private final String subjectId;
        private final String operatorId;
        private final String centreId;

        public Subject(Workflow workflow, String subjectId, String operatorId, String centreId) {
            this.workflow = Objects.requireNonNull(workflow, "workflow");
            this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
            this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
            this.centreId = Objects.requireNonNull(centreId, "centreId");
        }

        public Workflow workflow() {
            return workflow;
        }

        public String subjectId() {
            return subjectId;
        }

        public String operatorId() {
            return operatorId;
        }

        public String centreId() {
            return centreId;
        }
    }

    /** Builds the engine once the capture session has measured the frame rate. The default builds the production engine. */
    public interface EngineFactoryProvider {
        CaptureSession.EngineFactory create(LivenessSession session, FaceFrameSource source, EngineListener listener,
                int requestedScore);
    }

    final EngineBundle bundle;
    final JwsTrust deviceTrust;
    final EvidencePackager packager;
    final Clock clock;
    final SecureRandom random;
    final Supplier<Subject> subject;
    final String sbiHost;
    final int firstPort;
    final int lastPort;
    final int requestedScore;
    final Duration clearanceTimeout;
    final Duration verdictTimeout;
    final SessionRegistry registry;
    final EngineListener listener;
    final Consumer<AuditEvent> audit;
    final EngineFactoryProvider engineFactory;

    private AdapterSettings(Builder b) {
        this.bundle = b.bundle;
        this.deviceTrust = b.deviceTrust;
        this.packager = b.packager;
        this.clock = b.clock;
        this.random = b.random;
        this.subject = b.subject;
        this.sbiHost = b.sbiHost;
        this.firstPort = b.firstPort;
        this.lastPort = b.lastPort;
        this.requestedScore = b.requestedScore;
        this.clearanceTimeout = b.clearanceTimeout;
        this.verdictTimeout = b.verdictTimeout;
        this.registry = b.registry;
        this.listener = b.listener;
        this.audit = b.audit;
        this.engineFactory = b.engineFactory != null ? b.engineFactory : defaultFactory(b.bundle, b.deviceTrust, b.random);
    }

    private static EngineFactoryProvider defaultFactory(EngineBundle bundle, JwsTrust trust, SecureRandom random) {
        DesktopEngines engines = new DesktopEngines(bundle, trust, random);
        return (session, source, listener, score) -> engines.factory(session, source, listener, score);
    }

    /**
     * @param bundle the verified policy, operating-point table and models (the engine fails closed without it)
     * @param deviceTrust the device CA the signed capture is verified against
     * @param packager signs the evidence that goes into the face BIR
     * @param subject who the next scan is for
     */
    public static Builder builder(EngineBundle bundle, JwsTrust deviceTrust, EvidencePackager packager, Clock clock,
            SecureRandom random, Supplier<Subject> subject) {
        return new Builder(bundle, deviceTrust, packager, clock, random, subject);
    }

    /** Optional settings have safe defaults. */
    public static final class Builder {
        private final EngineBundle bundle;
        private final JwsTrust deviceTrust;
        private final EvidencePackager packager;
        private final Clock clock;
        private final SecureRandom random;
        private final Supplier<Subject> subject;
        private String sbiHost = "127.0.0.1";
        private int firstPort = 4501;
        private int lastPort = 4600;
        private int requestedScore = 40;
        private Duration clearanceTimeout = Duration.ofSeconds(60);
        private Duration verdictTimeout = Duration.ofSeconds(15);
        private SessionRegistry registry = new SessionRegistry();
        private EngineListener listener = EngineListener.NONE;
        private Consumer<AuditEvent> audit = e -> { };
        private EngineFactoryProvider engineFactory;

        private Builder(EngineBundle bundle, JwsTrust deviceTrust, EvidencePackager packager, Clock clock, SecureRandom random,
                Supplier<Subject> subject) {
            this.bundle = Objects.requireNonNull(bundle, "bundle");
            this.deviceTrust = Objects.requireNonNull(deviceTrust, "deviceTrust");
            this.packager = Objects.requireNonNull(packager, "packager");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.random = Objects.requireNonNull(random, "random");
            this.subject = Objects.requireNonNull(subject, "subject");
        }

        /** Where the SBI device service listens; the defaults are MOSIP's 127.0.0.1, 4501 to 4600. */
        public Builder sbi(String host, int firstPort, int lastPort) {
            this.sbiHost = Objects.requireNonNull(host, "host");
            this.firstPort = firstPort;
            this.lastPort = lastPort;
            return this;
        }

        public Builder requestedScore(int score) {
            this.requestedScore = score;
            return this;
        }

        /** How long the capture thread waits for the engine to reach its capture step before the scan is refused. */
        public Builder clearanceTimeout(Duration timeout) {
            this.clearanceTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** How long, after the client hands over the signed capture, the engine has to bind it and decide. */
        public Builder verdictTimeout(Duration timeout) {
            this.verdictTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Attempts and lockouts are counted here across scans; share one registry for the life of the station. */
        public Builder registry(SessionRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
            return this;
        }

        /**
         * Receives the engine's prompts and guidance ("please turn your head"). The client has no screen for these: the host
         * must show them (the capture screen of the scaffold does), or a challenge can never be answered.
         */
        public Builder listener(EngineListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        public Builder audit(Consumer<AuditEvent> audit) {
            this.audit = Objects.requireNonNull(audit, "audit");
            return this;
        }

        /** Replaces the production engine, for tests. */
        public Builder engineFactory(EngineFactoryProvider factory) {
            this.engineFactory = Objects.requireNonNull(factory, "factory");
            return this;
        }

        public AdapterSettings build() {
            return new AdapterSettings(this);
        }
    }
}
