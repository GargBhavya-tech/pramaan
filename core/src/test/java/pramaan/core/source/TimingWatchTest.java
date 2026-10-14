/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Bible Part 5.3: timing and duplicate frames are counted for the record. */
class TimingWatchTest {

    private static final DeviceMetadata DEVICE = new DeviceMetadata("d", "0", "c", DeviceLevel.L1, "Acme", "Cam", "1");

    private static Frame frame(int index, long sensorNanos, byte content) {
        return new Frame(new byte[] {content, (byte) index}, PixelFormat.JPEG, 0, 0, sensorNanos,
                Instant.ofEpochMilli(1_000L + index * 100L), index, DEVICE);
    }

    @Test
    void aMetronomeHasZeroVariationAndASensorWithJitterDoesNot() {
        TimingWatch steady = new TimingWatch();
        TimingWatch jittery = new TimingWatch();
        long[] jitter = {0, 33, 70, 99, 140, 165, 205, 232, 270};
        for (int i = 0; i < jitter.length; i++) {
            steady.observe(frame(i, i * 33_000_000L, (byte) i));
            jittery.observe(frame(i, jitter[i] * 1_000_000L, (byte) i));
        }
        assertEquals(0.0, steady.coefficientOfVariation(), 1e-9);
        assertTrue(jittery.coefficientOfVariation() > 0.1);
        assertTrue(steady.usesSensorTimestamps());
    }

    @Test
    void withoutSensorTimestampsHostArrivalIsUsedAndSaidSo() {
        TimingWatch w = new TimingWatch();
        for (int i = 0; i < 5; i++) {
            w.observe(frame(i, Frame.NO_SENSOR_TIMESTAMP, (byte) i));
        }
        assertFalse(w.usesSensorTimestamps());
        assertEquals(0.0, w.coefficientOfVariation(), 1e-9);
    }

    @Test
    void aFrameIdenticalToThePreviousOneIsCounted() {
        TimingWatch w = new TimingWatch();
        byte[] same = {1, 2, 3};
        for (int i = 0; i < 3; i++) {
            w.observe(new Frame(same, PixelFormat.JPEG, 0, 0, i * 1_000_000L, Instant.ofEpochMilli(i), i, DEVICE));
        }
        w.observe(frame(3, 4_000_000L, (byte) 9));
        assertEquals(2, w.duplicateFrames());
        assertEquals(4, w.frames());
    }

    @Test
    void tooFewFramesGiveNoNumber() {
        TimingWatch w = new TimingWatch();
        w.observe(frame(0, 0, (byte) 0));
        assertTrue(Double.isNaN(w.coefficientOfVariation()));
    }
}
