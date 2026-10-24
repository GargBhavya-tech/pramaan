/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.inference;

import ai.onnxruntime.OrtException;

/**
 * Compares a model's output on the CPU with its output under a requested accelerator, on the same input.
 *
 * <p>The operating points were calibrated on the CPU path. An accelerator that computes in half precision can move a
 * score by a little; this tells you by how much on this device, so enabling it is a measured decision. Run it on the
 * phone with a few real crops from the harness; it is not a substitute for re-running the evaluation with the
 * accelerator on (docs/android-low-resource.md).
 */
public final class AcceleratorParity {

    private AcceleratorParity() {
    }

    /** What the comparison found. */
    public static final class Result {
        private final InferenceOptions.Accelerator provider;
        private final String fallbackReason;
        private final double maxAbsDifference;

        Result(InferenceOptions.Accelerator provider, String fallbackReason, double maxAbsDifference) {
            this.provider = provider;
            this.fallbackReason = fallbackReason;
            this.maxAbsDifference = maxAbsDifference;
        }

        /** The provider that really ran the candidate: the CPU if the accelerator was unavailable. */
        public InferenceOptions.Accelerator provider() {
            return provider;
        }

        public String fallbackReason() {
            return fallbackReason;
        }

        /** The largest absolute difference over every output value. */
        public double maxAbsDifference() {
            return maxAbsDifference;
        }

        public boolean within(double tolerance) {
            return maxAbsDifference <= tolerance;
        }

        @Override
        public String toString() {
            return "ran on " + provider + (fallbackReason == null ? "" : " (" + fallbackReason + ")")
                    + ", max |difference| from the CPU result = " + maxAbsDifference;
        }
    }

    public static Result compare(byte[] modelBytes, InferenceOptions candidate, float[] input, long[] shape)
            throws OrtException {
        try (OnnxModel cpu = OnnxModel.load(modelBytes, InferenceOptions.cpu());
                OnnxModel other = OnnxModel.load(modelBytes, candidate)) {
            float[] a = cpu.run(input, shape).values();
            float[] b = other.run(input, shape).values();
            if (a.length != b.length) {
                throw new IllegalStateException("outputs differ in length: " + a.length + " and " + b.length);
            }
            double max = 0;
            for (int i = 0; i < a.length; i++) {
                max = Math.max(max, Math.abs((double) a[i] - b[i]));
            }
            return new Result(other.provider(), other.fallbackReason(), max);
        }
    }
}
