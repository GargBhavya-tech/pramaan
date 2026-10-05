/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import java.time.Duration;

/** Per-state limits for the session state machine (Bible Part 12; Build Map #48). */
public final class SessionTimeouts {

    private final Duration acquire;
    private final Duration passiveWindow;
    private final Duration decisionGrace;
    private final Duration capture;
    private final Duration binding;
    private final Duration bindingWindow;
    private final Duration captureMaxSkew;

    SessionTimeouts(Duration acquire, Duration passiveWindow, Duration decisionGrace, Duration capture, Duration binding,
            Duration bindingWindow, Duration captureMaxSkew) {
        this.acquire = acquire;
        this.passiveWindow = passiveWindow;
        this.decisionGrace = decisionGrace;
        this.capture = capture;
        this.binding = binding;
        this.bindingWindow = bindingWindow;
        this.captureMaxSkew = captureMaxSkew;
    }

    static SessionTimeouts read(StrictNode n) {
        int acquire = n.integer("acquireTimeoutMs");
        int passive = n.integer("passiveWindowMs");
        int grace = n.integer("decisionGraceMs");
        int capture = n.integer("captureTimeoutMs");
        int binding = n.integer("bindingTimeoutMs");
        int window = n.integer("bindingWindowMs");
        int skew = n.integer("captureMaxSkewMs");
        n.finish();
        if (window < 100 || window > 10_000 || skew < 1_000 || skew > 300_000) {
            throw new InvalidPolicyException(n.path() + ": bindingWindowMs must be 100..10000 and captureMaxSkewMs 1000..300000");
        }
        if (acquire < 1_000 || passive < 500 || grace < 100 || capture < 1_000 || binding < 100) {
            throw new InvalidPolicyException(n.path() + ": timeouts too short (acquire >= 1000, passiveWindow >= 500,"
                    + " decisionGrace >= 100, capture >= 1000, binding >= 100 ms)");
        }
        return new SessionTimeouts(Duration.ofMillis(acquire), Duration.ofMillis(passive), Duration.ofMillis(grace),
                Duration.ofMillis(capture), Duration.ofMillis(binding), Duration.ofMillis(window), Duration.ofMillis(skew));
    }

    /** ACQUIRING: time to find one stable, quality-passing face. */
    public Duration acquire() {
        return acquire;
    }

    /** PASSIVE: how long cues accumulate before the first decision. */
    public Duration passiveWindow() {
        return passiveWindow;
    }

    /** How long the engine may take to deliver a decision once it is due; past it is an engine timeout. */
    public Duration decisionGrace() {
        return decisionGrace;
    }

    public Duration capture() {
        return capture;
    }

    public Duration binding() {
        return binding;
    }

    /** Lock 4: longest allowed gap from the last verified frame to the capture request. */
    public Duration bindingWindow() {
        return bindingWindow;
    }

    /** Lock 1: largest allowed difference between the device's signed timestamp and receipt. */
    public Duration captureMaxSkew() {
        return captureMaxSkew;
    }
}
