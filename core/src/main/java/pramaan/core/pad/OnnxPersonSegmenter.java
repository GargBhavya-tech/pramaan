/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import ai.onnxruntime.OrtException;
import java.util.Objects;
import pramaan.core.image.ImageOps;
import pramaan.core.image.RgbImage;
import pramaan.core.inference.OnnxModel;

/**
 * Route B, with a model (Bible Part 43): a person-segmentation network says, per pixel, how likely it is that a person
 * is there. Unlike {@link BackdropSubtractionSegmenter} it needs no stored picture of the room and it finds a person
 * whose clothes match the wall, but it is a model: a file to ship, license, pin by hash and run, and it can be wrong
 * about people it was not trained on.
 *
 * <p>Written to fit common person/portrait segmenters rather than one file: {@link Spec} says what size and layout the
 * network wants, how to normalise the picture, and where the answer is. The frame is stretched to the network's input
 * (no letterbox), so the output grid maps back to the frame by plain scaling. The probability is thresholded, cleaned
 * the same way Route A's is, and joined with the face (grown by 15%), which is always a person.
 */
public final class OnnxPersonSegmenter implements PersonSegmenter {

    /** Where the network's input and output are, and how to prepare the picture. */
    public static final class Spec {
        final int inputWidth;
        final int inputHeight;
        final boolean channelsFirst;
        final float[] mean;
        final float[] std;
        final int personChannel;
        final boolean applySigmoid;
        final boolean minMaxNormalise;
        final float threshold;

        /**
         * @param channelsFirst true for NCHW, false for NHWC (both the input and the output)
         * @param mean per-channel mean subtracted after scaling the pixel to 0..1 (zeros for none)
         * @param std per-channel divisor (ones for none)
         * @param personChannel which output channel is "person" (0 for a single-channel mask)
         * @param applySigmoid true if the output is logits rather than probabilities
         * @param minMaxNormalise true if the output must be stretched to 0..1 first (U2-Net style)
         */
        public Spec(int inputWidth, int inputHeight, boolean channelsFirst, float[] mean, float[] std, int personChannel,
                boolean applySigmoid, boolean minMaxNormalise, float threshold) {
            this.inputWidth = inputWidth;
            this.inputHeight = inputHeight;
            this.channelsFirst = channelsFirst;
            this.mean = mean.clone();
            this.std = std.clone();
            this.personChannel = personChannel;
            this.applySigmoid = applySigmoid;
            this.minMaxNormalise = minMaxNormalise;
            this.threshold = threshold;
        }

        /** A 256 x 256 NHWC selfie-style network: pixels 0..1, one probability channel. */
        public static Spec selfie256() {
            return new Spec(256, 256, false, new float[3], new float[] {1, 1, 1}, 0, false, false, 0.5f);
        }

        /** A 320 x 320 NCHW U2-Net-style network: ImageNet mean and std, one saliency channel stretched to 0..1. */
        public static Spec u2net320() {
            return new Spec(320, 320, true, new float[] {0.485f, 0.456f, 0.406f}, new float[] {0.229f, 0.224f, 0.225f}, 0,
                    false, true, 0.5f);
        }
    }

    /** What running the network gives back. */
    public static final class Result {
        final float[] values;
        final long[] shape;

        public Result(float[] values, long[] shape) {
            this.values = values;
            this.shape = shape;
        }
    }

    /** Runs the network; the real one wraps an {@link OnnxModel}, tests pass a fake. */
    public interface Runner {
        Result run(float[] input, long[] shape) throws Exception;
    }

    private static final int MIN_REGION = 40;
    private static final double FACE_GROWTH = 0.15;

    private final Runner runner;
    private final Spec spec;

    public OnnxPersonSegmenter(Runner runner, Spec spec) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    /** The usual way: a loaded ONNX model. */
    public static OnnxPersonSegmenter of(OnnxModel model, Spec spec) {
        return new OnnxPersonSegmenter((input, shape) -> {
            try {
                OnnxModel.Output out = model.run(input, shape);
                return new Result(out.values(), out.shape());
            } catch (OrtException e) {
                throw new IllegalStateException("the segmentation model failed: " + e.getMessage(), e);
            }
        }, spec);
    }

    @Override
    public PersonMask segment(RgbImage frame, int[] faceBox, SceneFingerprint stored) {
        PersonMask faceOnly = PersonMask.ofBox(frame.width(), frame.height(), growBox(faceBox, frame));
        try {
            Result r = runner.run(input(frame), spec.channelsFirst
                    ? new long[] {1, 3, spec.inputHeight, spec.inputWidth}
                    : new long[] {1, spec.inputHeight, spec.inputWidth, 3});
            return mask(r).union(faceOnly);
        } catch (Exception e) {
            // A failed network must not hide or invent a person: fall back to what is certain (the face and the column
            // under it, which is what the rectangle used to be) rather than to nothing.
            return BackdropSubtractionSegmenter.prior(frame, faceBox);
        }
    }

    private static int[] growBox(int[] box, RgbImage frame) {
        int dx = (int) ((box[2] - box[0]) * FACE_GROWTH);
        int dy = (int) ((box[3] - box[1]) * FACE_GROWTH);
        return new int[] {Math.max(0, box[0] - dx), Math.max(0, box[1] - dy), Math.min(frame.width(), box[2] + dx),
            Math.min(frame.height(), box[3] + dy)};
    }

    private float[] input(RgbImage frame) {
        RgbImage in = ImageOps.resize(frame, spec.inputWidth, spec.inputHeight);
        float[] data = new float[3 * spec.inputWidth * spec.inputHeight];
        int plane = spec.inputWidth * spec.inputHeight;
        for (int y = 0; y < spec.inputHeight; y++) {
            for (int x = 0; x < spec.inputWidth; x++) {
                for (int c = 0; c < 3; c++) {
                    float v = (in.get(x, y, c) / 255f - spec.mean[c]) / spec.std[c];
                    if (spec.channelsFirst) {
                        data[c * plane + y * spec.inputWidth + x] = v;
                    } else {
                        data[(y * spec.inputWidth + x) * 3 + c] = v;
                    }
                }
            }
        }
        return data;
    }

    private PersonMask mask(Result r) {
        int rank = r.shape.length;
        int channels;
        int h;
        int w;
        if (rank == 3) {
            channels = 1;
            h = (int) r.shape[1];
            w = (int) r.shape[2];
        } else if (spec.channelsFirst) {
            channels = (int) r.shape[1];
            h = (int) r.shape[2];
            w = (int) r.shape[3];
        } else {
            h = (int) r.shape[1];
            w = (int) r.shape[2];
            channels = (int) r.shape[3];
        }
        float[] p = new float[h * w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = spec.channelsFirst || rank == 3
                        ? spec.personChannel * h * w + y * w + x
                        : (y * w + x) * channels + spec.personChannel;
                p[y * w + x] = r.values[at];
            }
        }
        if (spec.applySigmoid) {
            for (int i = 0; i < p.length; i++) {
                p[i] = (float) (1 / (1 + Math.exp(-p[i])));
            }
        }
        if (spec.minMaxNormalise) {
            float lo = Float.MAX_VALUE;
            float hi = -Float.MAX_VALUE;
            for (float v : p) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            float range = Math.max(1e-6f, hi - lo);
            for (int i = 0; i < p.length; i++) {
                p[i] = (p[i] - lo) / range;
            }
        }
        boolean[] person = new boolean[PersonMask.W * PersonMask.H];
        for (int y = 0; y < PersonMask.H; y++) {
            int sy = Math.min(h - 1, (y * h) / PersonMask.H + h / (2 * PersonMask.H));
            for (int x = 0; x < PersonMask.W; x++) {
                int sx = Math.min(w - 1, (x * w) / PersonMask.W + w / (2 * PersonMask.W));
                person[y * PersonMask.W + x] = p[sy * w + sx] >= spec.threshold;
            }
        }
        return PersonMask.of(person).open(1).close(2).withoutSpecks(MIN_REGION);
    }
}
