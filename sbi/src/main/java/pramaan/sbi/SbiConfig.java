/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import pramaan.core.crypto.JwsTrust;

/** How to reach and trust the device service. */
public final class SbiConfig {

    private final String host;
    private final int firstPort;
    private final int lastPort;
    private final Duration probeTimeout;
    private final Duration streamTimeout;
    private final String env;
    private final JwsTrust trust;
    private final Clock clock;
    private final int queueCapacity;

    private SbiConfig(Builder b) {
        this.host = b.host;
        this.firstPort = b.firstPort;
        this.lastPort = b.lastPort;
        this.probeTimeout = b.probeTimeout;
        this.streamTimeout = b.streamTimeout;
        this.env = b.env;
        this.trust = Objects.requireNonNull(b.trust, "trust is required: an SBI client that trusts nothing is useless");
        this.clock = Objects.requireNonNull(b.clock, "clock");
        this.queueCapacity = b.queueCapacity;
    }

    public static Builder builder(JwsTrust trust, Clock clock) {
        Builder b = new Builder();
        b.trust = trust;
        b.clock = clock;
        return b;
    }

    String host() {
        return host;
    }

    int firstPort() {
        return firstPort;
    }

    int lastPort() {
        return lastPort;
    }

    Duration probeTimeout() {
        return probeTimeout;
    }

    Duration streamTimeout() {
        return streamTimeout;
    }

    String env() {
        return env;
    }

    JwsTrust trust() {
        return trust;
    }

    Clock clock() {
        return clock;
    }

    int queueCapacity() {
        return queueCapacity;
    }

    /** Builder with the specification's defaults. */
    public static final class Builder {

        private String host = SbiConstants.HOST;
        private int firstPort = SbiConstants.FIRST_PORT;
        private int lastPort = SbiConstants.LAST_PORT;
        private Duration probeTimeout = Duration.ofMillis(800);
        private Duration streamTimeout = Duration.ofMinutes(5);
        private String env = "Staging";
        private JwsTrust trust;
        private Clock clock;
        private int queueCapacity = 4;

        public Builder ports(int first, int last) {
            if (first < 1 || last < first || last > 65535) {
                throw new IllegalArgumentException("bad port range " + first + ".." + last);
            }
            this.firstPort = first;
            this.lastPort = last;
            return this;
        }

        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        public Builder probeTimeout(Duration timeout) {
            this.probeTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder env(String env) {
            this.env = Objects.requireNonNull(env, "env");
            return this;
        }

        public Builder queueCapacity(int capacity) {
            this.queueCapacity = capacity;
            return this;
        }

        public SbiConfig build() {
            return new SbiConfig(this);
        }
    }
}
