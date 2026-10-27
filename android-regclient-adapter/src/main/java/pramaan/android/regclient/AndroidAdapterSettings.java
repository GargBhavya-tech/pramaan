/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android.regclient;

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
import pramaan.core.evidence.EvidenceSigner;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.SessionRegistry;

/**
 * Settings supplied by the Android Registration Client host at application startup via
 * {@link PramaanAndroidLivenessHook#configure(AndroidAdapterSettings)}.
 */
public final class AndroidAdapterSettings {

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

    final EngineBundle bundle;
    final JwsTrust deviceTrust;
    final EvidenceSigner signer;
    final Clock clock;
    final SecureRandom random;
    final Supplier<Subject> subject;
    final Duration clearanceTimeout;
    final SessionRegistry registry;
    final EngineListener listener;
    final Consumer<AuditEvent> audit;

    private AndroidAdapterSettings(Builder b) {
        this.bundle = b.bundle;
        this.deviceTrust = b.deviceTrust;
        this.signer = b.signer;
        this.clock = b.clock;
        this.random = b.random;
        this.subject = b.subject;
        this.clearanceTimeout = b.clearanceTimeout;
        this.registry = b.registry;
        this.listener = b.listener;
        this.audit = b.audit;
    }

    public static Builder builder(EngineBundle bundle, JwsTrust deviceTrust, EvidenceSigner signer, Clock clock,
            SecureRandom random, Supplier<Subject> subject) {
        return new Builder(bundle, deviceTrust, signer, clock, random, subject);
    }

    public static final class Builder {
        private final EngineBundle bundle;
        private final JwsTrust deviceTrust;
        private final EvidenceSigner signer;
        private final Clock clock;
        private final SecureRandom random;
        private final Supplier<Subject> subject;
        private Duration clearanceTimeout = Duration.ofSeconds(30);
        private SessionRegistry registry = new SessionRegistry();
        private EngineListener listener = EngineListener.NONE;
        private Consumer<AuditEvent> audit = e -> {};

        private Builder(EngineBundle bundle, JwsTrust deviceTrust, EvidenceSigner signer, Clock clock, SecureRandom random,
                Supplier<Subject> subject) {
            this.bundle = Objects.requireNonNull(bundle, "bundle");
            this.deviceTrust = Objects.requireNonNull(deviceTrust, "deviceTrust");
            this.signer = Objects.requireNonNull(signer, "signer");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.random = Objects.requireNonNull(random, "random");
            this.subject = Objects.requireNonNull(subject, "subject");
        }

        public Builder clearanceTimeout(Duration timeout) {
            this.clearanceTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder listener(EngineListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        public Builder audit(Consumer<AuditEvent> audit) {
            this.audit = Objects.requireNonNull(audit, "audit");
            return this;
        }

        public Builder registry(SessionRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
            return this;
        }

        public AndroidAdapterSettings build() {
            return new AndroidAdapterSettings(this);
        }
    }
}
