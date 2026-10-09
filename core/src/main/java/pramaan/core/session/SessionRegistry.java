/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import pramaan.core.policy.RateLimits;

/**
 * State that must outlive a single session object (Build Map #49, #50): consumed attempts and lockouts per
 * subject — otherwise closing and reopening the window would reset them — hourly rate limits per subject,
 * operator and device, exception counts per operator and centre over a rolling 24 hours, and the
 * supervisor verdicts already spent. One instance per client, shared by every session; thread-safe.
 */
public final class SessionRegistry {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofDays(1);

    /** A lock with no end: until an administrator intervenes, or until the exception flow resolves it. */
    static final Instant INDEFINITE = Instant.MAX;

    private final Map<String, Deque<Instant>> windows = new HashMap<>();
    private final Map<String, Integer> consumed = new HashMap<>();
    private final Map<String, Instant> lockedUntil = new HashMap<>();
    private final Set<String> spentVerdicts = new HashSet<>();

    /** Records an attempt if every hourly limit allows it; records nothing otherwise. */
    public synchronized boolean tryStartAttempt(String subject, String operator, String device, RateLimits limits, Instant now) {
        String[] keys = {"subject:" + subject, "operator:" + operator, "device:" + device};
        int[] caps = {limits.perSubjectPerHour(), limits.perOperatorPerHour(), limits.perDevicePerHour()};
        for (int i = 0; i < keys.length; i++) {
            if (recent(keys[i], now, HOUR) >= caps[i]) {
                return false;
            }
        }
        for (String k : keys) {
            windows.computeIfAbsent(k, x -> new ArrayDeque<>()).addLast(now);
        }
        return true;
    }

    /** Reserves one exception if both rolling-24-hour limits allow it. */
    public synchronized boolean tryReserveException(String operator, String centre, int maxPerOperator, int maxPerCentre,
            Instant now) {
        String op = "exception-operator:" + operator;
        String ce = "exception-centre:" + centre;
        if (recent(op, now, DAY) >= maxPerOperator || recent(ce, now, DAY) >= maxPerCentre) {
            return false;
        }
        windows.computeIfAbsent(op, x -> new ArrayDeque<>()).addLast(now);
        windows.computeIfAbsent(ce, x -> new ArrayDeque<>()).addLast(now);
        return true;
    }

    /** False if this supervisor verdict was already used: one supervisor session approves one exception. */
    public synchronized boolean spendVerdict(String supervisorSessionId) {
        return spentVerdicts.add(supervisorSessionId);
    }

    public synchronized int consumedAttempts(String subject) {
        return consumed.getOrDefault(subject, 0);
    }

    synchronized int recordConsumed(String subject) {
        return consumed.merge(subject, 1, Integer::sum);
    }

    synchronized void resetAttempts(String subject) {
        consumed.remove(subject);
    }

    synchronized void lock(String subject, Instant until) {
        lockedUntil.put(subject, until);
    }

    synchronized void unlock(String subject) {
        lockedUntil.remove(subject);
        consumed.remove(subject);
    }

    /** The lock's end if the subject is locked now, else null. */
    synchronized Instant lockedUntil(String subject, Instant now) {
        Instant until = lockedUntil.get(subject);
        return until != null && now.isBefore(until) ? until : null;
    }

    private int recent(String key, Instant now, Duration window) {
        Deque<Instant> q = windows.get(key);
        if (q == null) {
            return 0;
        }
        Instant cutoff = now.minus(window);
        while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
            q.removeFirst();
        }
        return q.size();
    }
}
