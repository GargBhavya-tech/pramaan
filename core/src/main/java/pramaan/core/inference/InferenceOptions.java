/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.inference;

import java.util.Map;
import java.util.Objects;

/**
 * How ONNX Runtime should execute the models: threads and an optional hardware accelerator (PS04 bonus: optimise for
 * low-resource Android devices, support hardware acceleration where available).
 *
 * <p>An accelerator is a request, not a promise. If it is not compiled into the runtime on this device, or the device
 * has no driver for it, {@link OnnxModel} runs the same model on the CPU and records why ({@link OnnxModel#provider()},
 * {@link OnnxModel#fallbackReason()}). Falling back is safe for security (the same model and the same decision logic
 * run) but not necessarily numerically identical: an accelerator may use half precision. The operating points in
 * {@code config/operating-points.json} were calibrated on the CPU path, so before an accelerator is enabled in
 * production run {@link AcceleratorParity} on that device and re-run the evaluation harness with it on.
 */
public final class InferenceOptions {

    /** The execution providers PRAMAAN can ask for. */
    public enum Accelerator {
        /** ONNX Runtime's default CPU provider. Always available; the path the operating points were calibrated on. */
        CPU,
        /** Android Neural Networks API (GPU, DSP or NPU through the vendor driver). Android builds of the runtime only. */
        NNAPI,
        /** XNNPACK: hand-tuned ARM/x86 CPU kernels. Often faster than the default on low-end phones, same precision. */
        XNNPACK,
        /** Windows DirectML (any DirectX 12 GPU). */
        DIRECTML,
        /** Apple Core ML. */
        COREML
    }

    private static final InferenceOptions CPU = new InferenceOptions(Accelerator.CPU, 0, 0, false);

    private final Accelerator accelerator;
    private final int intraOpThreads;
    private final int interOpThreads;
    private final boolean sequential;

    private InferenceOptions(Accelerator accelerator, int intraOpThreads, int interOpThreads, boolean sequential) {
        this.accelerator = Objects.requireNonNull(accelerator, "accelerator");
        if (intraOpThreads < 0 || interOpThreads < 0) {
            throw new IllegalArgumentException("thread counts must be zero (runtime default) or positive");
        }
        this.intraOpThreads = intraOpThreads;
        this.interOpThreads = interOpThreads;
        this.sequential = sequential;
    }

    /** The default: CPU, runtime-chosen thread counts. */
    public static InferenceOptions cpu() {
        return CPU;
    }

    public static InferenceOptions of(Accelerator accelerator, int intraOpThreads, int interOpThreads,
            boolean sequential) {
        return new InferenceOptions(accelerator, intraOpThreads, interOpThreads, sequential);
    }

    /**
     * A profile for a low-resource phone: few threads so the camera and the UI keep their cores, sequential operator
     * execution (no inter-op pool, less memory), and a request for {@code accelerator}.
     *
     * @param cores the device's core count ({@code Runtime.availableProcessors()})
     */
    public static InferenceOptions lowResource(int cores, Accelerator accelerator) {
        int intra = Math.max(1, Math.min(2, cores / 2));
        return new InferenceOptions(accelerator, intra, 1, true);
    }

    public Accelerator accelerator() {
        return accelerator;
    }

    public int intraOpThreads() {
        return intraOpThreads;
    }

    public int interOpThreads() {
        return interOpThreads;
    }

    public boolean sequential() {
        return sequential;
    }

    /** The XNNPACK provider option naming its thread pool, kept here so callers do not repeat the key. */
    Map<String, String> xnnpackOptions() {
        return intraOpThreads > 0 ? Map.of("intra_op_num_threads", Integer.toString(intraOpThreads)) : Map.of();
    }

    @Override
    public String toString() {
        return "InferenceOptions{" + accelerator + ", intra=" + intraOpThreads + ", inter=" + interOpThreads
                + (sequential ? ", sequential" : "") + "}";
    }
}
