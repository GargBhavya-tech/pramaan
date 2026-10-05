/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import pramaan.core.inference.OnnxModel;

/**
 * Build Map #2 "done when": measures ONNX Runtime cold-start and warm latency on this machine.
 *
 * <p>Usage: {@code ./gradlew :harness:onnxSmoke} (defaults to the smoke fixture), or pass a model
 * path with {@code --args="path/to/model.onnx"}. Only single-input float models with a fully static
 * input shape are supported; the input is filled with zeros.
 */
public final class OnnxSmokeCheck {

    private static final int WARM_RUNS = 200;

    private OnnxSmokeCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path modelPath = Path.of(args.length > 0 ? args[0] : "core/src/test/resources/models/smoke_affine.onnx");
        byte[] bytes = Files.readAllBytes(modelPath);

        long coldStart = System.nanoTime();
        try (OnnxModel model = OnnxModel.load(bytes)) {
            long[] shape = model.inputShape();
            int elements = Math.toIntExact(Arrays.stream(shape).reduce(1, (a, b) -> a * b));
            float[] input = new float[elements];

            OnnxModel.Output first = model.run(input, shape);
            double coldMs = (System.nanoTime() - coldStart) / 1e6;

            double[] warmMs = new double[WARM_RUNS];
            for (int i = 0; i < WARM_RUNS; i++) {
                long t = System.nanoTime();
                model.run(input, shape);
                warmMs[i] = (System.nanoTime() - t) / 1e6;
            }
            Arrays.sort(warmMs);

            System.out.println("model          : " + modelPath);
            System.out.println("ONNX Runtime   : " + ai.onnxruntime.OrtEnvironment.getEnvironment().getVersion());
            System.out.println("java           : " + System.getProperty("java.version") + " (" + System.getProperty("os.name") + ")");
            System.out.println("input shape    : " + Arrays.toString(shape));
            System.out.println("output shape   : " + Arrays.toString(first.shape()));
            System.out.printf("cold start     : %.2f ms (load + first inference)%n", coldMs);
            System.out.printf("warm p50 / p95 : %.3f / %.3f ms over %d runs%n",
                    warmMs[WARM_RUNS / 2], warmMs[(int) (WARM_RUNS * 0.95)], WARM_RUNS);
        }
    }
}
