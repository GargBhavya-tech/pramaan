/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The corpus-day calibrator (Build Map #65, #70), on a SYNTHETIC corpus: persons with several sessions each. */
class CalibrateFromRowsTest {

    private static final Path TABLE = Path.of("..", "config", "operating-points.json");
    private static final int SESSIONS_PER_PERSON = 4;

    private static double p(double logit) {
        return 1 / (1 + Math.exp(-logit));
    }

    /** @param persons each has 2 bona fide and 2 attack sessions (screen replay, print) */
    private static List<SessionRow> corpus(int persons, long seed, int l0Persons) {
        Random r = new Random(seed);
        List<SessionRow> rows = new ArrayList<>();
        for (int i = 0; i < persons; i++) {
            String person = "P" + i;
            String level = i < l0Persons ? "L0" : "L1";
            for (int s = 0; s < SESSIONS_PER_PERSON; s++) {
                boolean genuine = s < 2;
                String pais = genuine ? null : (s == 2 ? "screen-replay" : "print");
                Map<String, Double> cues = new LinkedHashMap<>();
                cues.put("passive", p(genuine ? 1.5 + r.nextGaussian() : -0.3 + 1.2 * r.nextGaussian()));
                cues.put("moire", p(1.5 + r.nextGaussian())); // the attacks fool this cue
                cues.put("flash", r.nextDouble() < 0.5 ? p(genuine ? 2.0 + r.nextGaussian() : -2.5 + r.nextGaussian()) : null);
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("person", person);
                attributes.put("skin", i % 3 == 0 ? "I-II" : i % 3 == 1 ? "III-IV" : "V-VI");
                rows.add(new SessionRow(person + "-" + s, genuine ? SessionRow.Label.BONA_FIDE : SessionRow.Label.ATTACK, pais,
                        genuine ? 0 : 2, attributes, "RESIDENT", level, cues, null, SessionRow.Outcome.ACCEPTED));
            }
        }
        return rows;
    }

    private static CalibrateFromRows.Result run(List<SessionRow> rows, String groupBy, long seed) throws Exception {
        return CalibrateFromRows.calibrate(rows, TABLE, 0.05, groupBy, seed, "synthetic");
    }

    @Test
    void thePersonSplitNeverPutsOnePersonOnTwoSidesAndKeepsEverySession() throws Exception {
        List<SessionRow> rows = corpus(800, 1, 0);
        CalibrateFromRows.Result r = run(rows, "deviceLevel", 1);
        assertEquals(rows.size(), r.train + r.calibration + r.test);
        assertEquals(0, r.train % SESSIONS_PER_PERSON, "only whole persons in the training split");
        assertEquals(0, r.calibration % SESSIONS_PER_PERSON);
        assertEquals(0, r.test % SESSIONS_PER_PERSON);
        assertTrue(r.train > 1200 && r.train < 2000, "about half: " + r.train);
    }

    @Test
    void sessionsWithNoDecisionAreLeftOut() throws Exception {
        List<SessionRow> rows = corpus(100, 2, 0);
        rows.add(new SessionRow("timeout", SessionRow.Label.BONA_FIDE, null, 0, Map.of("person", "Z"), "RESIDENT", "L1", Map.of(),
                null, SessionRow.Outcome.NO_RESPONSE));
        CalibrateFromRows.Result r = run(rows, "deviceLevel", 1);
        assertEquals(rows.size() - 1, r.train + r.calibration + r.test);
    }

    @Test
    void theSameInputsAndSeedGiveTheSameProposalAndANewSeedGivesAnotherSplit() throws Exception {
        List<SessionRow> rows = corpus(300, 3, 0);
        assertEquals(run(rows, "deviceLevel", 5).json, run(rows, "deviceLevel", 5).json);
        assertFalse(run(rows, "deviceLevel", 5).json.equals(run(rows, "deviceLevel", 6).json));
    }

    @Test
    void theProposalCarriesItsProvenanceAndSaysNothingWasApplied() throws Exception {
        CalibrateFromRows.Result r = run(corpus(300, 4, 0), "deviceLevel", 1);
        JsonNode j = new ObjectMapper().readTree(r.json);
        assertTrue(j.path("status").asText().contains("PROPOSAL"));
        assertEquals("synthetic", j.path("provenance").path("rowsSha256").asText());
        assertEquals(64, j.path("provenance").path("tableSha256").asText().length());
        assertEquals(0.05, j.path("provenance").path("alpha").asDouble(), 1e-12);
        assertTrue(j.path("fusion").path("cues").has("flash"));
        assertTrue(r.report.contains("not applied"));
    }

    @Test
    void theShippedTableIsNeverEdited() throws Exception {
        byte[] before = Files.readAllBytes(TABLE);
        run(corpus(300, 5, 0), "deviceLevel", 1);
        assertArrayEquals(before, Files.readAllBytes(TABLE));
    }

    @Test
    void aGroupWithTooFewGenuineSessionsIsRefusedAGuaranteeNotGivenAMadeUpOne() throws Exception {
        CalibrateFromRows.Result r = run(corpus(800, 6, 6), "deviceLevel", 1); // six L0 persons only
        assertTrue(r.rejectCutOffs.get("L1").canReject());
        if (r.rejectCutOffs.containsKey("L0")) {
            assertFalse(r.rejectCutOffs.get("L0").canReject(), "a handful of genuine sessions cannot support a 5% guarantee");
            assertTrue(r.report.contains("cannot be guaranteed"), r.report);
        }
    }

    @Test
    void theFalseRejectionRateOnHeldOutPeopleStaysNearAlphaAndIsReportedWithAnInterval() throws Exception {
        CalibrateFromRows.Result r = run(corpus(2000, 7, 0), "deviceLevel", 1);
        String line = r.report.lines().filter(l -> l.contains("BPCER L1")).findFirst().orElseThrow();
        // "BPCER L1 rejected k/n = 0.0xx [lo, hi] ..."
        double rate = Double.parseDouble(line.split("=")[1].trim().split(" ")[0]);
        assertTrue(rate <= 0.05 + 0.03, line);
        assertTrue(line.contains("["), line);
    }

    @Test
    void theReportGivesApcerPerAttackSpeciesOnHeldOutPeopleOnly() throws Exception {
        CalibrateFromRows.Result r = run(corpus(1000, 8, 0), "deviceLevel", 1);
        assertTrue(r.report.contains("APCER screen-replay"), r.report);
        assertTrue(r.report.contains("APCER print"), r.report);
        assertTrue(r.report.contains("On the held-out split only"), r.report);
        assertTrue(r.report.contains("What this is not: a certification"), r.report);
    }

    @Test
    void groupingByAnAttributeGivesOneCutOffPerGroup() throws Exception {
        CalibrateFromRows.Result r = run(corpus(1500, 9, 0), "skin", 1);
        assertEquals(3, r.rejectCutOffs.size());
        assertTrue(r.rejectCutOffs.containsKey("V-VI"));
    }

    @Test
    void aCorpusWithNoAttacksSaysItCannotReportInsteadOfInventingNumbers() throws Exception {
        List<SessionRow> genuineOnly = new ArrayList<>();
        for (SessionRow row : corpus(200, 10, 0)) {
            if (row.label() == SessionRow.Label.BONA_FIDE) {
                genuineOnly.add(row);
            }
        }
        CalibrateFromRows.Result r = run(genuineOnly, "deviceLevel", 1);
        assertTrue(r.report.contains("nothing can be reported") || r.report.contains("weights not refitted"), r.report);
    }
}
