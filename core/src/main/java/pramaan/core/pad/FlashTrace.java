/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import pramaan.core.challenge.FlashColour;
import pramaan.core.challenge.FlashSchedule;

/**
 * What the camera saw while the flash played: one sample per analysed frame, in order. Each sample holds the colour
 * the light was showing at that frame's capture time (or none) and the mean RGB (0-255) of three face regions —
 * the whole skin area, the nose bridge and the cheeks. Callers fill it from {@link FlashSchedule#colourAt}.
 */
public final class FlashTrace {

    private final List<double[]> emitted = new ArrayList<>();
    private final List<double[]> face = new ArrayList<>();
    private final List<double[]> nose = new ArrayList<>();
    private final List<double[]> cheek = new ArrayList<>();

    /**
     * @param emittedColour what the light showed at this frame; empty while it was off
     * @param faceRgb mean RGB of the face skin region
     * @param noseRgb mean RGB of the nose bridge
     * @param cheekRgb mean RGB of the cheeks
     */
    public FlashTrace add(Optional<FlashColour> emittedColour, double[] faceRgb, double[] noseRgb, double[] cheekRgb) {
        emitted.add(emittedColour.map(c -> new double[] {c.r(), c.g(), c.b()}).orElse(new double[] {0, 0, 0}));
        face.add(check(faceRgb));
        nose.add(check(noseRgb));
        cheek.add(check(cheekRgb));
        return this;
    }

    /** Convenience: look the colour up in {@code schedule} at {@code frameTime}. */
    public FlashTrace add(FlashSchedule schedule, Instant frameTime, double[] faceRgb, double[] noseRgb,
            double[] cheekRgb) {
        return add(schedule.colourAt(frameTime), faceRgb, noseRgb, cheekRgb);
    }

    private static double[] check(double[] rgb) {
        if (rgb == null || rgb.length != 3) {
            throw new IllegalArgumentException("a region sample is three channel means");
        }
        return rgb.clone();
    }

    public int frames() {
        return emitted.size();
    }

    double[][] emitted() {
        return emitted.toArray(new double[0][]);
    }

    double[][] face() {
        return face.toArray(new double[0][]);
    }

    double[][] nose() {
        return nose.toArray(new double[0][]);
    }

    double[][] cheek() {
        return cheek.toArray(new double[0][]);
    }
}
