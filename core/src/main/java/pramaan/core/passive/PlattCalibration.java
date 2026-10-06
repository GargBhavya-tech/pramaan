/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.passive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

/**
 * Platt scaling, p = sigmoid(a * logit + b), fitted per model version and device class (Build Map #26).
 * Bound to the model's SHA-256: a calibration for another model is refused, never silently applied.
 * {@link #IDENTITY} is explicitly provisional — raw scores are not probabilities until fitted on corpus data.
 */
public final class PlattCalibration {

    public static final PlattCalibration IDENTITY = new PlattCalibration(1, 0, "*", "*", true);

    private final double a;
    private final double b;
    private final String modelSha256;
    private final String deviceClass;
    private final boolean provisional;

    public PlattCalibration(double a, double b, String modelSha256, String deviceClass, boolean provisional) {
        this.a = a;
        this.b = b;
        this.modelSha256 = Objects.requireNonNull(modelSha256, "modelSha256");
        this.deviceClass = Objects.requireNonNull(deviceClass, "deviceClass");
        this.provisional = provisional;
    }

    /** Reads {"model": sha256, "deviceClass": ..., "a": ..., "b": ..., "provisional": bool}. */
    public static PlattCalibration fromJson(byte[] json, String expectedModelSha256) throws IOException {
        JsonNode n = new ObjectMapper().readTree(json);
        String model = n.path("model").asText("");
        if (!model.equalsIgnoreCase(expectedModelSha256)) {
            throw new IOException("calibration is for model " + model + ", not the loaded " + expectedModelSha256);
        }
        return new PlattCalibration(n.path("a").asDouble(1), n.path("b").asDouble(0), model,
                n.path("deviceClass").asText("*"), n.path("provisional").asBoolean(true));
    }

    public double probability(double rawLogit) {
        return Logits.sigmoid(a * rawLogit + b);
    }

    public boolean provisional() {
        return provisional;
    }

    public double a() {
        return a;
    }

    public double b() {
        return b;
    }

    /**
     * Fits a and b by Newton's method with backtracking line search on the log-loss (Lin, Lin and Weng,
     * 2007), starting from a = 0 and the prior log-odds so the sigmoid never starts saturated. Uses
     * Platt's smoothed targets ((N+ + 1)/(N+ + 2) and 1/(N- + 2)) to avoid over-confidence on small sets.
     */
    public static PlattCalibration fit(double[] logits, boolean[] live, String modelSha256, String deviceClass) {
        if (logits.length != live.length || logits.length == 0) {
            throw new IllegalArgumentException("need equal, non-empty score and label arrays");
        }
        int pos = 0;
        for (boolean l : live) {
            pos += l ? 1 : 0;
        }
        int neg = live.length - pos;
        double hiT = (pos + 1.0) / (pos + 2.0);
        double loT = 1.0 / (neg + 2.0);
        double[] t = new double[live.length];
        for (int i = 0; i < t.length; i++) {
            t[i] = live[i] ? hiT : loT;
        }
        double a = 0;
        double b = Math.log((pos + 1.0) / (neg + 1.0));
        double loss = loss(logits, t, a, b);
        for (int iter = 0; iter < 200; iter++) {
            double g1 = 0;
            double g2 = 0;
            double h11 = 1e-12;
            double h22 = 1e-12;
            double h21 = 0;
            for (int i = 0; i < logits.length; i++) {
                double p = Logits.sigmoid(a * logits[i] + b);
                double d = p - t[i];
                double w = p * (1 - p);
                g1 += d * logits[i];
                g2 += d;
                h11 += w * logits[i] * logits[i];
                h22 += w;
                h21 += w * logits[i];
            }
            if (Math.abs(g1) < 1e-9 && Math.abs(g2) < 1e-9) {
                break;
            }
            double det = h11 * h22 - h21 * h21;
            double da = (h22 * g1 - h21 * g2) / det;
            double db = (h11 * g2 - h21 * g1) / det;
            double slope = g1 * da + g2 * db;
            double step = 1;
            boolean improved = false;
            while (step >= 1e-10) {
                double na = a - step * da;
                double nb = b - step * db;
                double nl = loss(logits, t, na, nb);
                if (nl <= loss - 1e-4 * step * slope) {
                    a = na;
                    b = nb;
                    loss = nl;
                    improved = true;
                    break;
                }
                step /= 2;
            }
            if (!improved) {
                break;
            }
        }
        return new PlattCalibration(a, b, modelSha256, deviceClass, false);
    }

    /** Cross-entropy of targets t under p = sigmoid(a x + b), computed without overflow. */
    private static double loss(double[] x, double[] t, double a, double b) {
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            double f = a * x[i] + b;
            // -[t log sigmoid(f) + (1-t) log(1 - sigmoid(f))] = log(1 + e^f) - t f, stably
            double softplus = f > 0 ? f + Math.log1p(Math.exp(-f)) : Math.log1p(Math.exp(f));
            sum += softplus - t[i] * f;
        }
        return sum;
    }
}
