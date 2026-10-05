/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.inference;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * A loaded ONNX model with a single float input and one or more float outputs.
 *
 * <p>This is the only place the engine touches ONNX Runtime directly. Every model PRAMAAN runs
 * (face detection, landmarks, passive liveness, embeddings) goes through it, so version pinning,
 * threading options and native-library loading live in one class (Build Map #2).
 *
 * <p>Instances are not thread-safe for concurrent {@link #run} calls on the same input buffers;
 * create one per worker thread. Close it to free native memory.
 */
public final class OnnxModel implements AutoCloseable {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String outputName;

    private OnnxModel(OrtEnvironment environment, OrtSession session) throws OrtException {
        this.environment = environment;
        this.session = session;
        if (session.getNumInputs() != 1 || session.getNumOutputs() < 1) {
            session.close();
            throw new IllegalArgumentException("expected one input and at least one output, got "
                    + session.getNumInputs() + " inputs and " + session.getNumOutputs() + " outputs");
        }
        this.inputName = session.getInputNames().iterator().next();
        this.outputName = session.getOutputNames().iterator().next();
    }

    /**
     * Loads a model from its serialized bytes, using ONNX Runtime's default CPU execution provider.
     *
     * @throws IllegalStateException if the ONNX Runtime native library cannot be initialised
     */
    public static OnnxModel load(byte[] modelBytes) throws OrtException {
        Objects.requireNonNull(modelBytes, "modelBytes");
        OrtEnvironment environment = environment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            return new OnnxModel(environment, environment.createSession(modelBytes, options));
        }
    }

    /**
     * Obtains the process-wide ONNX Runtime environment, translating a native-load failure into an
     * actionable message. Liveness must fail closed (Bible Part 22), so this is thrown, never swallowed.
     */
    private static OrtEnvironment environment() {
        try {
            return OrtEnvironment.getEnvironment();
        } catch (UnsatisfiedLinkError | ExceptionInInitializerError | NoClassDefFoundError e) {
            throw new IllegalStateException("ONNX Runtime's native library failed to initialise. On Windows this"
                    + " usually means the Java runtime loaded an older Visual C++ runtime (msvcp140.dll below"
                    + " 14.40) before ONNX Runtime did. ONNX Runtime 1.21 and later require 14.40; PRAMAAN pins"
                    + " 1.20.0 for this reason (see tools/versions.md).", e);
        }
    }

    public String inputName() {
        return inputName;
    }

    public String outputName() {
        return outputName;
    }

    /** The declared input shape; dynamic dimensions are reported as -1. */
    public long[] inputShape() throws OrtException {
        return shapeOf(session.getInputInfo().get(inputName));
    }

    /**
     * Runs one inference.
     *
     * @param data  input values in row-major order
     * @param shape input shape; its element count must equal {@code data.length}
     */
    public Output run(float[] data, long[] shape) throws OrtException {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(shape, "shape");
        long elements = 1;
        for (long dim : shape) {
            if (dim <= 0) {
                throw new IllegalArgumentException("shape must be fully positive, got " + Arrays.toString(shape));
            }
            elements *= dim;
        }
        if (elements != data.length) {
            throw new IllegalArgumentException("shape " + Arrays.toString(shape) + " holds " + elements
                    + " values but data has " + data.length);
        }

        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape);
                OrtSession.Result result = session.run(Map.of(inputName, input))) {
            OnnxValue value = result.get(0);
            if (!(value instanceof OnnxTensor)) {
                throw new IllegalStateException("output '" + outputName + "' is not a tensor");
            }
            OnnxTensor tensor = (OnnxTensor) value;
            FloatBuffer buffer = tensor.getFloatBuffer();
            float[] values = new float[buffer.remaining()];
            buffer.get(values);
            return new Output(values, tensor.getInfo().getShape());
        }
    }

    /** Runs one inference and returns every output by name (YuNet has twelve). */
    public java.util.Map<String, Output> runAll(float[] data, long[] shape) throws OrtException {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(shape, "shape");
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape);
                OrtSession.Result result = session.run(Map.of(inputName, input))) {
            java.util.Map<String, Output> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, OnnxValue> e : result) {
                if (!(e.getValue() instanceof OnnxTensor)) {
                    throw new IllegalStateException("output '" + e.getKey() + "' is not a tensor");
                }
                OnnxTensor tensor = (OnnxTensor) e.getValue();
                FloatBuffer buffer = tensor.getFloatBuffer();
                float[] values = new float[buffer.remaining()];
                buffer.get(values);
                out.put(e.getKey(), new Output(values, tensor.getInfo().getShape()));
            }
            return out;
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }

    private static long[] shapeOf(NodeInfo node) {
        if (!(node.getInfo() instanceof TensorInfo)) {
            throw new IllegalStateException("node '" + node.getName() + "' is not a tensor");
        }
        return ((TensorInfo) node.getInfo()).getShape();
    }

    /** A model output: values in row-major order plus the shape they were produced in. */
    public static final class Output {

        private final float[] values;
        private final long[] shape;

        Output(float[] values, long[] shape) {
            this.values = values;
            this.shape = shape;
        }

        public float[] values() {
            return values.clone();
        }

        public long[] shape() {
            return shape.clone();
        }
    }
}
