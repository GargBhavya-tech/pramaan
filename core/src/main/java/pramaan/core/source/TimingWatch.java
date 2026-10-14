/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Frame-timing and duplicate-frame statistics for one session (Bible Part 5.3), collected for the evidence record
 * and never used to reject anyone. A live sensor jitters and never repeats a frame byte for byte; a looped file or a
 * naive virtual camera can do either. The false-positive rate against real device jitter is unmeasured (it needs a
 * timing corpus, Build Map #65), so the numbers are <strong>reported, not acted on</strong>.
 *
 * <p>Sensor timestamps are used when the device gives them; host arrival times are a weaker fallback because the
 * SBI stream is buffered over loopback HTTP, and the report says which it used.
 */
public final class TimingWatch {

    private static final int KEEP = 600;

    private final long[] times = new long[KEEP];
    private int count;
    private boolean sensorTimes = true;
    private byte[] previousDigest;
    private int duplicates;
    private int frames;
    private final MessageDigest digest;

    public TimingWatch() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void observe(Frame frame) {
        frames++;
        long t;
        if (frame.sensorTimestampNanos() != Frame.NO_SENSOR_TIMESTAMP) {
            t = frame.sensorTimestampNanos();
        } else {
            sensorTimes = false;
            t = frame.hostArrival().getEpochSecond() * 1_000_000_000L + frame.hostArrival().getNano();
        }
        times[count % KEEP] = t;
        count++;
        digest.reset();
        digest.update(frame.bytes().duplicate());
        byte[] now = digest.digest();
        if (previousDigest != null && Arrays.equals(previousDigest, now)) {
            duplicates++;
        }
        previousDigest = now;
    }

    public synchronized void reset() {
        count = 0;
        frames = 0;
        duplicates = 0;
        previousDigest = null;
        sensorTimes = true;
    }

    public synchronized int frames() {
        return frames;
    }

    public synchronized int duplicateFrames() {
        return duplicates;
    }

    /** True if the intervals come from sensor timestamps rather than host arrival. */
    public synchronized boolean usesSensorTimestamps() {
        return sensorTimes;
    }

    /** Coefficient of variation of the last {@value #KEEP} frame gaps, or NaN with fewer than three frames. */
    public synchronized double coefficientOfVariation() {
        int n = Math.min(count, KEEP);
        long[] window = new long[n];
        for (int i = 0; i < n; i++) {
            window[i] = times[(count - n + i) % KEEP];
        }
        return InjectionGuard.timingCoefficientOfVariation(window);
    }
}
