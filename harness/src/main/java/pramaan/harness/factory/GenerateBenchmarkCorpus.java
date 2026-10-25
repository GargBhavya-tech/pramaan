/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import pramaan.harness.eval.SessionRow;

/**
 * Generates a <b>synthetic</b> corpus of session rows in the Corpus Plan's shape (Build Map #6, #65-#71): persons are labels, not
 * people, and every score is drawn from a hand-chosen distribution with a fixed seed. It exercises the calibration and ISO/IEC
 * 30107-3 reporting code end to end; the error rates it produces are properties of these constants, not measurements of PRAMAAN
 * (see docs/evidence-report.md, section 3.4). Covers MST skin-tone bands, eyewear, lighting and print, screen and paper-mask attacks.
 */
public final class GenerateBenchmarkCorpus {

    private static double sigmoid(double logit) {
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    private static double clamp(double v) {
        return Math.max(0.001, Math.min(0.999, v));
    }

    public static void main(String[] args) throws IOException {
        Path out = args.length > 0 ? Path.of(args[0]) : Path.of("build/results/rows.jsonl");
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }

        Random rnd = new Random(42);
        int numPersons = 400; // 400 persons x 4 sessions = 1,600 sessions
        List<SessionRow> rows = new ArrayList<>();

        String[] skinTones = {"MST1-2", "MST3-4", "MST5-6", "MST7-8", "MST9-10"};
        String[] lightings = {"good", "dim", "backlit", "fluorescent"};

        for (int i = 0; i < numPersons; i++) {
            String personId = String.format("P%03d", i);
            String skin = skinTones[i % skinTones.length];
            String lighting = lightings[rnd.nextInt(lightings.length)];
            String eyewear = (i % 3 == 0) ? "glasses" : "none";
            String deviceLevel = (i % 4 == 0) ? "L0" : "L1";

            // 2 Genuine sessions per person
            for (int s = 0; s < 2; s++) {
                String sessionId = personId + "-bf-" + s;
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("person", personId);
                attrs.put("skinTone", skin);
                attrs.put("lighting", lighting);
                attrs.put("eyewear", eyewear);

                Map<String, Double> cues = new LinkedHashMap<>();
                // Genuine distributions
                double passiveLogit = 2.4 + 0.8 * rnd.nextGaussian();
                double moireLogit = 2.0 + 0.7 * rnd.nextGaussian();
                double flatnessLogit = 2.2 + 0.6 * rnd.nextGaussian();
                cues.put("passive", clamp(sigmoid(passiveLogit)));
                cues.put("moire", clamp(sigmoid(moireLogit)));
                cues.put("flatness", clamp(sigmoid(flatnessLogit)));
                if ("L1".equals(deviceLevel)) {
                    double flashLogit = 2.5 + 0.7 * rnd.nextGaussian();
                    cues.put("flash", clamp(sigmoid(flashLogit)));
                }

                double pLive = clamp(sigmoid(2.5 + 0.6 * rnd.nextGaussian()));
                SessionRow.Outcome outcome = (pLive >= 0.85) ? SessionRow.Outcome.ACCEPTED
                        : (pLive <= 0.20 ? SessionRow.Outcome.REJECTED : SessionRow.Outcome.ACCEPTED);

                rows.add(new SessionRow(sessionId, SessionRow.Label.BONA_FIDE, null, 0, attrs,
                        "RESIDENT", deviceLevel, cues, pLive, outcome));
            }

            // 2 Attack sessions per person (different species)
            String[] attacks = {"print-matte-a4", "screen-phone-video", "mask-paper", "screen-phone-photo"};
            for (int a = 0; a < 2; a++) {
                String pais = attacks[(i * 2 + a) % attacks.length];
                int level = pais.contains("video") || pais.contains("mask") ? 2 : 1;
                String sessionId = personId + "-atk-" + pais + "-" + a;

                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("person", personId);
                attrs.put("skinTone", skin);
                attrs.put("lighting", lighting);
                attrs.put("eyewear", eyewear);

                Map<String, Double> cues = new LinkedHashMap<>();
                if (pais.equals("print-matte-a4")) {
                    cues.put("passive", clamp(sigmoid(-1.5 + 0.9 * rnd.nextGaussian())));
                    cues.put("flatness", clamp(sigmoid(-2.0 + 0.7 * rnd.nextGaussian())));
                    cues.put("moire", clamp(sigmoid(1.8 + 0.8 * rnd.nextGaussian()))); // print may not have screen moire
                } else if (pais.contains("screen")) {
                    cues.put("passive", clamp(sigmoid(-0.8 + 1.1 * rnd.nextGaussian())));
                    cues.put("moire", clamp(sigmoid(-2.2 + 0.8 * rnd.nextGaussian()))); // strong moire detection
                    cues.put("flatness", clamp(sigmoid(-1.6 + 0.9 * rnd.nextGaussian())));
                } else { // mask-paper
                    cues.put("passive", clamp(sigmoid(-1.2 + 1.0 * rnd.nextGaussian())));
                    cues.put("flatness", clamp(sigmoid(0.2 + 1.1 * rnd.nextGaussian())));
                    cues.put("moire", clamp(sigmoid(1.5 + 0.9 * rnd.nextGaussian())));
                }

                if ("L1".equals(deviceLevel)) {
                    // attacks fail the active flash challenge
                    cues.put("flash", clamp(sigmoid(-2.8 + 0.7 * rnd.nextGaussian())));
                }

                double pLive = clamp(sigmoid(-2.0 + 0.8 * rnd.nextGaussian()));
                SessionRow.Outcome outcome = (pLive >= 0.85) ? SessionRow.Outcome.ACCEPTED : SessionRow.Outcome.REJECTED;

                rows.add(new SessionRow(sessionId, SessionRow.Label.ATTACK, pais, level, attrs,
                        "RESIDENT", deviceLevel, cues, pLive, outcome));
            }
        }

        SessionRow.writeJsonl(rows, out);
        System.out.printf("Successfully generated %d sessions for %d individuals into %s%n",
                rows.size(), numPersons, out);
    }
}
