/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.inference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import pramaan.core.inference.InferenceOptions.Accelerator;

/** PS04 bonus: low-resource tuning and hardware acceleration, with a safe CPU fallback and a parity check. */
class InferenceOptionsTest {

    private static byte[] smokeModel() throws IOException {
        try (InputStream in = InferenceOptionsTest.class.getResourceAsStream("/models/smoke_affine.onnx")) {
            if (in == null) {
                throw new IllegalStateException("missing fixture smoke_affine.onnx");
            }
            return in.readAllBytes();
        }
    }

    @Test
    void lowResourceUsesFewThreadsAndSequentialExecution() {
        InferenceOptions o = InferenceOptions.lowResource(8, Accelerator.NNAPI);
        assertEquals(2, o.intraOpThreads());
        assertEquals(1, o.interOpThreads());
        assertTrue(o.sequential());
        assertEquals(Accelerator.NNAPI, o.accelerator());
        assertEquals(1, InferenceOptions.lowResource(1, Accelerator.CPU).intraOpThreads(), "never zero threads");
        assertEquals(1, InferenceOptions.lowResource(2, Accelerator.CPU).intraOpThreads());
    }

    @Test
    void negativeThreadCountsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> InferenceOptions.of(Accelerator.CPU, -1, 0, false));
    }

    @Test
    void aTunedCpuModelGivesTheSameAnswerAsTheDefault() throws Exception {
        try (OnnxModel model = OnnxModel.load(smokeModel(), InferenceOptions.lowResource(4, Accelerator.CPU))) {
            assertEquals(Accelerator.CPU, model.provider());
            assertNull(model.fallbackReason());
            assertArrayEquals(new float[] {3f, 5f, 7f, 0f},
                    model.run(new float[] {1f, 2f, 3f, -0.5f}, new long[] {1, 4}).values(), 1e-6f);
        }
    }

    @Test
    void anUnavailableAcceleratorFallsBackToTheCpuAndSaysWhy() throws Exception {
        // The desktop runtime has no NNAPI: the request must not fail the load, and must not pretend it was honoured.
        try (OnnxModel model = OnnxModel.load(smokeModel(), InferenceOptions.of(Accelerator.NNAPI, 0, 0, false))) {
            assertEquals(Accelerator.CPU, model.provider());
            assertNotNull(model.fallbackReason());
            assertTrue(model.fallbackReason().contains("NNAPI"));
            assertArrayEquals(new float[] {3f, 5f, 7f, 0f},
                    model.run(new float[] {1f, 2f, 3f, -0.5f}, new long[] {1, 4}).values(), 1e-6f);
        }
    }

    @Test
    void theDefaultOptionsApplyToPlainLoadsAndCanBeRestored() throws Exception {
        try {
            OnnxModel.setDefaultOptions(InferenceOptions.of(Accelerator.NNAPI, 0, 0, false));
            try (OnnxModel model = OnnxModel.load(smokeModel())) {
                assertNotNull(model.fallbackReason(), "the plain load must have asked for the default accelerator");
            }
        } finally {
            OnnxModel.setDefaultOptions(InferenceOptions.cpu());
        }
        try (OnnxModel model = OnnxModel.load(smokeModel())) {
            assertNull(model.fallbackReason());
        }
    }

    @Test
    void parityBetweenTheCpuAndItselfIsExactlyZero() throws Exception {
        AcceleratorParity.Result r = AcceleratorParity.compare(smokeModel(), InferenceOptions.cpu(),
                new float[] {1f, 2f, 3f, -0.5f}, new long[] {1, 4});
        assertEquals(0.0, r.maxAbsDifference(), 0.0);
        assertTrue(r.within(0.0));
        assertEquals(Accelerator.CPU, r.provider());
    }

    @Test
    void parityReportsTheFallbackSoACpuResultIsNotMistakenForAnAcceleratedOne() throws Exception {
        AcceleratorParity.Result r = AcceleratorParity.compare(smokeModel(),
                InferenceOptions.of(Accelerator.XNNPACK, 2, 1, true), new float[] {0f, 1f, 2f, 3f}, new long[] {1, 4});
        assertTrue(r.within(1e-5));
        if (r.provider() == Accelerator.CPU) {
            assertNotNull(r.fallbackReason(), "a CPU result for an accelerator request must say it fell back");
        }
    }
}
