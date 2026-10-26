/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;

/**
 * Found on a real phone: a test thread polling {@code session.state()} while the engine's thread did the same made the session
 * believe the clock had run backwards (one thread read its time first, another stored a later one, and the first then compared its
 * older time with the newer stored one), so a healthy session failed closed as UNAVAILABLE after two minutes. {@code state()} and
 * {@code tick()} are called from timers and screens as well as the engine, so they must be safe from any thread.
 */
class SessionThreadSafetyTest {

    @Test
    void manyThreadsReadingTheStateNeverMakeTheClockLookLikeItRanBackwards() throws Exception {
        SessionFixtures fx = new SessionFixtures();
        LivenessSession session = new LivenessSession(SessionContext.start(Workflow.RESIDENT, fx.policy, Clock.systemUTC(),
                new SecureRandom()), "resident-1", "operator-1", "device-1", "centre-1", fx.registry, fx.audit::add, new SecureRandom());
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        List<java.util.concurrent.Future<?>> running = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            running.add(pool.submit(() -> {
                go.await();
                while (System.nanoTime() < deadline) {
                    session.state();
                    session.tick();
                }
                return null;
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<?> f : running) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertNotEquals(SessionState.UNAVAILABLE, session.state(), "a healthy clock must not be reported as running backwards: "
                + session.history());
        assertEquals(SessionState.IDLE, session.state());
        assertTrue(fx.audit.stream().noneMatch(e -> e.type().contains("ERROR") || e.type().contains("CLOCK")),
                "no clock anomaly was audited: " + fx.audit);
    }
}
