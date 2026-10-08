/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Bible Part 5; Build Map #84: the software-only tier of the injection guard. */
class InjectionGuardTest {

    private static DeviceMetadata device(String make, String model, String code) {
        return new DeviceMetadata("d1", "0", code, DeviceLevel.L1, make, model, "SN-1");
    }

    // ---------------------------------------------------------------- denylistMatch
    @Test
    void aKnownVirtualCameraProductInAnyFieldIsCaught() {
        assertEquals("OBS Virtual Camera", InjectionGuard.denylistMatch(device("OBS Virtual Camera", "x", "x")).orElseThrow());
        assertEquals("ManyCam", InjectionGuard.denylistMatch(device("Acme", "ManyCam 7.0", "x")).orElseThrow());
        assertEquals("v4l2loopback", InjectionGuard.denylistMatch(device("Acme", "x", "v4l2loopback0")).orElseThrow());
        // Case-insensitive, substring.
        assertTrue(InjectionGuard.denylistMatch(device("obs virtual camera device", "x", "x")).isPresent());
    }

    @Test
    void aRealDeviceNameIsNotFlagged() {
        assertTrue(InjectionGuard.denylistMatch(device("Logitech", "BRIO 4K", "USB-CAM-01")).isEmpty());
        assertTrue(InjectionGuard.denylistMatch(device(null, null, null)).isEmpty());
    }

    @Test
    void aCustomDenylistIsHonoured() {
        DeviceMetadata d = device("SuspiciousCam Pro", "x", "x");
        assertTrue(InjectionGuard.denylistMatch(d, List.of("SuspiciousCam")).isPresent());
        assertTrue(InjectionGuard.denylistMatch(d, List.of("SomethingElse")).isEmpty());
        assertTrue(InjectionGuard.denylistMatch(d, List.of()).isEmpty());
    }

    // ---------------------------------------------------------------- timingCoefficientOfVariation
    @Test
    void perfectlyRegularTimestampsHaveZeroVariation() {
        long[] t = new long[60];
        for (int i = 0; i < t.length; i++) {
            t[i] = i * 33_333_333L;
        }
        assertEquals(0.0, InjectionGuard.timingCoefficientOfVariation(t), 1e-12);
        assertTrue(InjectionGuard.lowVarianceTiming(t, InjectionGuard.DEFAULT_CV_THRESHOLD));
    }

    @Test
    void jitteredTimestampsAreNotFlagged() {
        // +/- 15% jitter around 33.3 ms, deterministic (no RNG), well above the 0.002 threshold.
        long[] t = new long[60];
        long tCursor = 0;
        for (int i = 0; i < t.length; i++) {
            t[i] = tCursor;
            double jitter = 1 + 0.15 * Math.sin(i * 1.7);
            tCursor += (long) (33_333_333L * jitter);
        }
        double cv = InjectionGuard.timingCoefficientOfVariation(t);
        assertTrue(cv > InjectionGuard.DEFAULT_CV_THRESHOLD, "cv=" + cv);
        assertFalse(InjectionGuard.lowVarianceTiming(t, InjectionGuard.DEFAULT_CV_THRESHOLD));
    }

    @Test
    void tooFewTimestampsIsUndecided() {
        assertTrue(Double.isNaN(InjectionGuard.timingCoefficientOfVariation(new long[] {0, 1})));
        assertFalse(InjectionGuard.lowVarianceTiming(new long[] {0, 1}, 1.0));
    }

    // ---------------------------------------------------------------- duplicateFrame
    @Test
    void identicalFramesAreDuplicatesDifferentOnesAreNot() {
        byte[] a = {1, 2, 3};
        byte[] b = {1, 2, 3};
        byte[] c = {1, 2, 4};
        assertTrue(InjectionGuard.duplicateFrame(a, b));
        assertFalse(InjectionGuard.duplicateFrame(a, c));
        assertFalse(InjectionGuard.duplicateFrame(a, null));
        assertFalse(InjectionGuard.duplicateFrame(null, null));
    }
}
