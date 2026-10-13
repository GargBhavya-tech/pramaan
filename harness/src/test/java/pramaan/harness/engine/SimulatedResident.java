/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.engine;

import pramaan.core.binding.Embeddings;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.challenge.TurnDirection;
import pramaan.core.engine.FrameAnalyser;
import pramaan.core.engine.FrameObservation;
import pramaan.core.gate.FaceGate;
import pramaan.core.landmarks.FaceLandmarks;
import pramaan.core.pad.CueResult;
import pramaan.core.quality.Guidance;
import pramaan.core.source.Frame;
import pramaan.core.testing.TestClock;

/** A resident, as perception would see them: 3-D landmarks posed by whatever they were last asked to do. */
final class SimulatedResident implements FrameAnalyser {

    /** The resident's own face embedding, and {@link #embedding} for anyone else. */
    static final float[] RESIDENT = embedding(0);

    enum Behaviour {
        GENUINE,
        /** A photo: never moves. */
        STILL,
        /** Turns the other way, or turns when asked for something else. */
        CONTRARY,
        /** Starts 20 ms after the prompt: a replay timed to the prompt, not a person reacting to it. */
        EARLY
    }

    final TestClock clock;
    Behaviour behaviour = Behaviour.GENUINE;
    double passive = 0.62;
    double moire = 0.70;
    int secondFaceFromFrame = Integer.MAX_VALUE;
    int newTrackAtFrame = Integer.MAX_VALUE;
    int throwAtFrame = Integer.MAX_VALUE;
    private ChallengePrompt prompt;
    private long actStart = Long.MAX_VALUE;
    private int frames;

    SimulatedResident(TestClock clock) {
        this.clock = clock;
    }

    void respond(ChallengePrompt p) {
        if (behaviour == Behaviour.STILL) {
            return;
        }
        prompt = p;
        actStart = clock.millis() + (behaviour == Behaviour.EARLY ? 20 : 450);
    }

    @Override
    public FrameObservation analyse(Frame frame) {
        int n = ++frames;
        if (n == throwAtFrame) {
            throw new IllegalStateException("simulated model failure");
        }
        long t = frame.hostArrival().toEpochMilli();
        if (n >= secondFaceFromFrame) {
            return FrameObservation.builder(FaceGate.Status.MULTIPLE_FACES, 1).build();
        }
        long track = n >= newTrackAtFrame ? 2 : 1;
        double[] pose = pose(t - actStart);
        FaceLandmarks lm = render(pose[0], pose[1], pose[2]);
        boolean quality = Math.abs(pose[0]) < 12;
        FrameObservation.Builder b = FrameObservation.builder(FaceGate.Status.ONE_FACE, track)
                .trackReset(n == newTrackAtFrame)
                .quality(quality, quality ? Guidance.NONE : Guidance.FACE_THE_CAMERA)
                .landmarks(lm);
        if (quality) {
            b.passive(passive).moire(CueResult.vote("moire", moire, 0)).embedding(RESIDENT);
        }
        return b.build();
    }

    /** {yaw degrees, eye opening (1 open, 0 closed), smile (0-1)} at {@code dt} ms after the action began. */
    private double[] pose(long dt) {
        if (prompt == null || dt < 0) {
            return new double[] {0, 1, 0};
        }
        String id = prompt.messageId();
        if (id.endsWith(".turn")) {
            double target = Integer.parseInt(prompt.params().get("angleDeg")) + 8;
            double sign = TurnDirection.valueOf(prompt.params().get("direction")).yawSign();
            if (behaviour == Behaviour.CONTRARY) {
                sign = -sign;
            }
            double yaw = dt < 300 ? target * dt / 300.0 : dt < 1100 ? target : dt < 1400 ? target * (1400 - dt) / 300.0 : 0;
            return new double[] {sign * yaw, 1, 0};
        }
        if (behaviour == Behaviour.CONTRARY) {
            return new double[] {dt < 1000 ? 30 : 0, 1, 0};
        }
        if (id.endsWith(".blink")) {
            int count = Integer.parseInt(prompt.params().get("count"));
            long cycle = dt / 500;
            boolean closed = cycle < count && dt % 500 < 200;
            return new double[] {0, closed ? 0 : 1, 0};
        }
        if (id.endsWith(".eyes-closed")) {
            int hold = Integer.parseInt(prompt.params().get("holdMs"));
            return new double[] {0, dt < hold + 700 ? 0 : 1, 0};
        }
        if (id.endsWith(".smile")) {
            return new double[] {0, 1, dt < 1200 ? 1 : 0};
        }
        return new double[] {0, 1, 0};
    }

    static float[] embedding(int at) {
        float[] v = new float[128];
        java.util.Arrays.fill(v, 0.01f);
        v[at] = 1;
        return Embeddings.normalize(v);
    }

    // ------------------------------------------------------------------------------------------ a synthetic face

    /**
     * A 478-point face in 3-D (pixel units), rendered like MediaPipe: a curved grid for the mesh, with the eyes,
     * irises and mouth corners placed on the same surface so that yaw, eye aspect ratio and smile all move
     * consistently. Eye opening scales the lid gap (EAR 0.30 open, 0.05 closed); a smile widens and lifts
     * the mouth corners.
     */
    static FaceLandmarks render(double yawDeg, double eyeOpen, double smile) {
        double[][] model = new double[FaceLandmarks.COUNT][];
        for (int i = 0; i < FaceLandmarks.COUNT; i++) {
            double u = (i % 22) / 21.0 * 2 - 1;
            double v = (i / 22) / 21.0 * 2 - 1;
            model[i] = new double[] {70 * u, 90 * v, surface(u, v)};
        }
        double h = 0.75 + 3.75 * eyeOpen;
        eye(model, FaceLandmarks.RIGHT_EYE_EAR, -30, h, false);
        eye(model, FaceLandmarks.LEFT_EYE_EAR, 30, h, true);
        place(model, FaceLandmarks.RIGHT_IRIS, -30, -25);
        place(model, FaceLandmarks.LEFT_IRIS, 30, -25);
        place(model, FaceLandmarks.MOUTH_RIGHT, -24 - 4 * smile, 45 - 3 * smile);
        place(model, FaceLandmarks.MOUTH_LEFT, 24 + 4 * smile, 45 - 3 * smile);

        double c = Math.cos(Math.toRadians(-yawDeg));
        double s = Math.sin(Math.toRadians(-yawDeg));
        double cp = Math.cos(Math.toRadians(5));
        double sp = Math.sin(Math.toRadians(5));
        float[] xyz = new float[FaceLandmarks.COUNT * 3];
        for (int i = 0; i < FaceLandmarks.COUNT; i++) {
            double x = model[i][0];
            double y = model[i][1];
            double z = model[i][2];
            double x1 = c * x + s * z;
            double z1 = -s * x + c * z;
            double y2 = cp * y - sp * z1;
            double z2 = sp * y + cp * z1 + 600;
            xyz[3 * i] = (float) (640 * x1 / z2 + 320);
            xyz[3 * i + 1] = (float) (640 * y2 / z2 + 240);
            xyz[3 * i + 2] = (float) (640 * (z2 - 600) / 600);
        }
        return new FaceLandmarks(xyz, 1);
    }

    private static double surface(double u, double v) {
        return -40 * Math.exp(-(u * u + v * v) * 4) + 25 * (u * u + v * v);
    }

    private static void place(double[][] model, int i, double x, double y) {
        model[i] = new double[] {x, y, surface(x / 70, y / 90)};
    }

    /** EAR points in order p1 (corner), p2, p3 (upper lid), p4 (corner), p5, p6 (lower lid); 30 px wide. */
    private static void eye(double[][] model, int[] p, double cx, double h, boolean mirrored) {
        double d = mirrored ? -1 : 1;
        double cy = -25;
        place(model, p[0], cx - 15 * d, cy);
        place(model, p[1], cx - 5 * d, cy - h);
        place(model, p[2], cx + 5 * d, cy - h);
        place(model, p[3], cx + 15 * d, cy);
        place(model, p[4], cx + 5 * d, cy + h);
        place(model, p[5], cx - 5 * d, cy + h);
    }
}
