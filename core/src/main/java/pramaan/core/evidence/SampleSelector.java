/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import pramaan.core.detect.FaceBox;
import pramaan.core.image.RgbImage;
import pramaan.core.passive.PassiveScorer;

/**
 * Picks the few face crops that travel with the evidence (Bible Part 14; Build Map #61): the best passive
 * frame, the best frame of each challenge step, topped up by quality to at least three, at most five. Never
 * the stream. Each crop is the passive model's own context (2.7x the face box, square) at 112x112, so the
 * server can re-score exactly what the client's model looked at.
 */
public final class SampleSelector {

    public static final int SIZE = 112;
    public static final int MIN = 3;
    public static final int MAX = 5;
    public static final double CONTEXT_SCALE = 2.7;

    /** A candidate frame. {@code step} is the challenge step, or -1 for the passive window. */
    public static final class Candidate {
        final int step;
        final double quality;
        final RgbImage crop;

        public Candidate(int step, double quality, RgbImage crop) {
            this.step = step;
            this.quality = quality;
            this.crop = Objects.requireNonNull(crop, "crop");
        }

        public int step() {
            return step;
        }

        public double quality() {
            return quality;
        }

        public RgbImage crop() {
            return crop;
        }
    }

    private SampleSelector() {
    }

    public static RgbImage contextCrop(RgbImage frame, FaceBox face) {
        return PassiveScorer.contextCrop(frame, face, CONTEXT_SCALE, SIZE);
    }

    public static List<RgbImage> select(List<Candidate> candidates) {
        Map<Integer, Candidate> bestPerStep = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            Candidate prev = bestPerStep.get(c.step);
            if (prev == null || c.quality > prev.quality) {
                bestPerStep.put(c.step, c);
            }
        }
        List<Candidate> chosen = new ArrayList<>();
        bestPerStep.keySet().stream().sorted().forEach(step -> chosen.add(bestPerStep.get(step)));
        if (chosen.size() > MAX) {
            chosen.subList(MAX, chosen.size()).clear();
        }
        List<Candidate> rest = new ArrayList<>(candidates);
        rest.removeAll(chosen);
        rest.sort(Comparator.comparingDouble((Candidate c) -> c.quality).reversed());
        for (Candidate c : rest) {
            if (chosen.size() >= MIN) {
                break;
            }
            chosen.add(c);
        }
        List<RgbImage> out = new ArrayList<>();
        for (Candidate c : chosen) {
            out.add(c.crop);
        }
        return out;
    }
}
