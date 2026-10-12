/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.policy.LivenessPolicy;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.policy.YamlPolicySource;

/** Build Map #68-#71 on hand-computed and synthetic data with known answers. */
class EvaluationTest {

    static byte[] tableBytes;
    static final Map<String, String> MODELS = new TreeMap<>();
    static OperatingPointTable table;
    static LivenessPolicy policy;

    @BeforeAll
    static void load() throws Exception {
        tableBytes = Files.readAllBytes(Path.of("..", "config", "operating-points.json"));
        Iterator<Map.Entry<String, JsonNode>> it = new ObjectMapper().readTree(tableBytes).path("models").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            MODELS.put(e.getKey(), e.getValue().asText());
        }
        table = OperatingPointTable.load(tableBytes, "operating-points.json", MODELS);
        policy = YamlPolicySource.of(Path.of("..", "config", "pramaan-policy.yaml")).load();
    }

    static SessionRow bf(String id, SessionRow.Outcome o, Double p, Map<String, String> attrs) {
        return new SessionRow(id, SessionRow.Label.BONA_FIDE, null, 0, attrs, "RESIDENT", "L1", Map.of(), p, o);
    }

    static SessionRow attack(String id, String pais, int level, SessionRow.Outcome o, Double p) {
        return new SessionRow(id, SessionRow.Label.ATTACK, pais, level, Map.of(), "RESIDENT", "L1", Map.of(), p, o);
    }

    // ---------------------------------------------------------------- #68
    @Test
    void clopperPearsonMatchesIndependentExactValues() {
        // Computed with exact rational binomial tails in Python (Bible 47.13).
        double[][] ref = {
            {0, 60, 0.0, 0.0596294923}, {2, 150, 0.0016188270, 0.0473330195}, {3, 50, 0.0125485878, 0.1654819466},
            {1, 10, 0.0025285785, 0.4450161170}, {10, 10, 0.6915028922, 1.0}, {7, 300, 0.0094315021, 0.0474825650},
            {0, 300, 0.0, 0.0122209747},
        };
        for (double[] r : ref) {
            Binomial.Rate rate = Binomial.clopperPearson((long) r[0], (long) r[1], 0.95);
            assertEquals(r[2], rate.lower(), 1e-8, (long) r[0] + "/" + (long) r[1] + " lower");
            assertEquals(r[3], rate.upper(), 1e-8, (long) r[0] + "/" + (long) r[1] + " upper");
        }
        assertEquals(1 - Math.pow(0.05, 1.0 / 60), Binomial.upperOneSided(0, 60, 0.95), 1e-9, "0/60: the rule-of-three ~5%");
    }

    @Test
    void theHandComputedToyCorpusReproducesExactly() {
        List<SessionRow> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(attack("p" + i, "print", 1, SessionRow.Outcome.REJECTED, 0.1));
        }
        for (int i = 0; i < 150; i++) {
            rows.add(attack("s" + i, "screen", 1, i < 2 ? SessionRow.Outcome.ACCEPTED : SessionRow.Outcome.REJECTED, 0.2));
        }
        rows.add(attack("s-nr", "screen", 1, SessionRow.Outcome.NO_RESPONSE, null));
        for (int i = 0; i < 50; i++) {
            rows.add(bf("b" + i, i < 3 ? SessionRow.Outcome.REJECTED : SessionRow.Outcome.ACCEPTED, 0.9, Map.of()));
        }
        rows.add(bf("b-nr1", SessionRow.Outcome.NO_RESPONSE, null, Map.of()));
        rows.add(bf("b-nr2", SessionRow.Outcome.NO_RESPONSE, null, Map.of()));
        PadReport r = PadReport.compute(rows);
        PadReport.PaisLine print = r.pais().get(0);
        PadReport.PaisLine screen = r.pais().get(1);
        assertEquals("print", print.pais);
        assertEquals(0.0, print.apcer.value(), 0);
        assertEquals(0.0596294923, print.apcer.upper(), 1e-8, "0/60 is not '0%': the upper bound is ~6%");
        assertEquals(2.0 / 150, screen.apcer.value(), 1e-12, "non-responses are not in APCER's denominator");
        assertEquals(1.0 / 151, screen.apnrr.value(), 1e-12);
        assertEquals("screen", r.headline().pais);
        assertEquals(3.0 / 50, r.bpcer().value(), 1e-12);
        assertEquals(0.1654819466, r.bpcer().upper(), 1e-8);
        assertEquals(2.0 / 52, r.bpnrr().value(), 1e-12);
        String md = r.toMarkdown();
        assertTrue(md.contains("Headline APCER (worst PAIS: screen)") && md.contains("deprecated"), md);
    }

    // ---------------------------------------------------------------- #69
    /** Synthetic corpus: bona fide scores high, prints low, screens medium; seeded, so the numbers are fixed. */
    static List<SessionRow> synthetic(long seed, int n) {
        Random rnd = new Random(seed);
        List<SessionRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(bf("b" + i, SessionRow.Outcome.ACCEPTED, clamp(0.85 + 0.1 * rnd.nextGaussian()),
                    Map.of("skinTone", i % 4 == 0 ? "MST8-10" : "MST1-3")));
            rows.add(attack("p" + i, "print", 1, SessionRow.Outcome.REJECTED, clamp(0.15 + 0.1 * rnd.nextGaussian())));
            rows.add(attack("s" + i, "screen", 1, SessionRow.Outcome.REJECTED, clamp(0.35 + 0.12 * rnd.nextGaussian())));
        }
        return rows;
    }

    static double clamp(double p) {
        return Math.max(0.001, Math.min(0.999, p));
    }

    @Test
    void theDetCurveMovesTheRightWayAndBpcer20IsStated() {
        DetCurve det = new DetCurve(synthetic(1, 400));
        DetCurve.Point low = det.at(0.2);
        DetCurve.Point high = det.at(0.8);
        assertTrue(low.apcer.get("screen") > high.apcer.get("screen"));
        assertTrue(low.bpcer < high.bpcer);
        assertTrue(det.bpcerAt(0.05) <= det.bpcerAt(0.01), "a stricter APCER costs more friction");
        assertTrue(det.toCsv().startsWith("threshold,bpcer,apcer_print,apcer_screen"));
    }

    @Test
    void anExportedOperatingPointIsDemonstratedAndReproducesOnFreshData() throws Exception {
        List<SessionRow> tuning = synthetic(1, 400);
        DetCurve det = new DetCurve(tuning);
        OperatingPointExporter.Export e = OperatingPointExporter.export(new String(tableBytes, StandardCharsets.UTF_8), det,
                Evaluate.targets(), MODELS, "synthetic tuning half");
        OperatingPointTable exported = OperatingPointTable.load(e.json.getBytes(StandardCharsets.UTF_8), "exported", MODELS);
        double tau = exported.operatingPoint("resident-default", "default").tauAccept();
        DetCurve.Point atTau = det.at(tau);
        for (Map.Entry<String, Double> a : atTau.apcer.entrySet()) {
            long k = Math.round(a.getValue() * 400);
            assertTrue(Binomial.upperOneSided(k, 400, 0.95) <= 0.05, a.getKey() + ": 5% must be demonstrated, not just measured");
        }
        long nBf = 400;
        Binomial.Rate promise = Binomial.clopperPearson(Math.round(atTau.bpcer * nBf), nBf, 0.95);
        DetCurve heldOut = new DetCurve(synthetic(2, 400));
        double measured = heldOut.at(tau).bpcer;
        // The promise is an upper-bounded claim: fresh data must not exceed it, and the two samples must agree
        // (overlapping intervals). A point-in-interval test would wrongly fail 0/400 against a promise of 1/400.
        Binomial.Rate fresh = Binomial.clopperPearson(Math.round(measured * nBf), nBf, 0.95);
        assertTrue(measured <= promise.upper(),
                String.format("held-out BPCER %.4f above the promised upper bound %.4f", measured, promise.upper()));
        assertTrue(fresh.lower() <= promise.upper() && promise.lower() <= fresh.upper(),
                "tuning " + promise + " and held-out " + fresh + " disagree");
        assertTrue(heldOut.at(tau).worstApcer() <= 0.05, "held-out worst APCER within the 5% target");
        // 400 attacks per PAIS can demonstrate 5% and 1% (0 errors: 0.75%) but not 0.5%: the export says exactly that.
        assertTrue(e.provisional);
        assertTrue(e.notes.stream().anyMatch(n -> n.startsWith("supervisor-strictest")), e.notes.toString());
        assertFalse(e.notes.stream().anyMatch(n -> n.startsWith("resident-default") || n.startsWith("operator-strict")), e.notes.toString());
    }

    // ---------------------------------------------------------------- #70
    static SessionRow cued(String id, SessionRow.Label label, String pais, double passive, double color, double moire) {
        Map<String, Double> cues = new LinkedHashMap<>();
        cues.put("passive", passive);
        cues.put("colorTexture", color);
        cues.put("moire", moire);
        return new SessionRow(id, label, pais, 1, Map.of(), "RESIDENT", "L1", cues, 0.5,
                label == SessionRow.Label.ATTACK ? SessionRow.Outcome.REJECTED : SessionRow.Outcome.ACCEPTED);
    }

    @Test
    void removingMoireOpensScreenReplaysButNotPrints() {
        List<SessionRow> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(cued("s" + i, SessionRow.Label.ATTACK, "screen", 0.90, 0.85, 0.05));
            rows.add(cued("p" + i, SessionRow.Label.ATTACK, "print", 0.10, 0.30, 0.60));
            rows.add(cued("b" + i, SessionRow.Label.BONA_FIDE, null, 0.95, 0.90, 0.80));
        }
        CueAblation.Result r = CueAblation.run(rows, table, policy);
        assertEquals(0.0, r.apcerByRemovedCue.get("(none)").get("screen").value(), 0);
        assertEquals(1.0, r.apcerByRemovedCue.get("moire").get("screen").value(), 0, "moire alone carries screen replays");
        assertEquals(0.0, r.apcerByRemovedCue.get("moire").get("print").value(), 0, "prints do not depend on moire");
        assertTrue(r.toMarkdown().contains("| moire |"));
    }

    // ---------------------------------------------------------------- #71
    @Test
    void fairnessReportsIntervalsDifferentialsAndSmallNWarnings() {
        List<SessionRow> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(bf("l" + i, i < 2 ? SessionRow.Outcome.REJECTED : SessionRow.Outcome.ACCEPTED, 0.9, Map.of("skinTone", "MST1-3")));
        }
        for (int i = 0; i < 20; i++) {
            rows.add(bf("d" + i, i < 3 ? SessionRow.Outcome.REJECTED : SessionRow.Outcome.ACCEPTED, 0.9, Map.of("skinTone", "MST8-10")));
        }
        FairnessReport f = FairnessReport.compute(rows);
        assertEquals(0.15 / 0.02, f.differential("skinTone"), 1e-9);
        assertTrue(f.groups().get("skinTone").get("MST8-10").smallN());
        String md = f.toMarkdown();
        assertTrue(md.contains("over target") && md.contains("small N"), md);

        List<SessionRow> perfect = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            perfect.add(bf("x" + i, SessionRow.Outcome.ACCEPTED, 0.9, Map.of("eyewear", i % 2 == 0 ? "none" : "glasses")));
        }
        assertTrue(Double.isNaN(FairnessReport.compute(perfect).differential("eyewear")), "0 vs 0 errors is undefined, not 'fair'");
    }

    // ---------------------------------------------------------------- the one command
    @Test
    void rowsRoundTripAndTheCliWritesTheReport(@TempDir Path dir) throws Exception {
        List<SessionRow> rows = synthetic(3, 60);
        rows.add(cued("c1", SessionRow.Label.ATTACK, "screen", 0.9, 0.85, 0.05));
        Path file = dir.resolve("rows.jsonl");
        SessionRow.writeJsonl(rows, file);
        List<SessionRow> back = SessionRow.readJsonl(file);
        assertEquals(rows.size(), back.size());
        assertEquals(rows.get(0).toJson(), back.get(0).toJson());
        Path out = dir.resolve("report.md");
        Path export = dir.resolve("op.json");
        int code = Evaluate.run(new String[] {"--rows", file.toString(), "--table", "../config/operating-points.json", "--policy",
            "../config/pramaan-policy.yaml", "--out", out.toString(), "--export", export.toString(),
            "--html", dir.resolve("report.html").toString()});
        assertEquals(0, code);
        String report = Files.readString(out);
        assertTrue(report.contains("Headline APCER") && report.contains("BPCER20") && report.contains("Fairness")
                && report.contains("ablation"), report);
        OperatingPointTable.load(Files.readAllBytes(export), "op.json", MODELS);
        String html = Files.readString(dir.resolve("report.html"));
        assertTrue(html.startsWith("<!DOCTYPE html>") && html.contains("<svg") && html.contains("<table>")
                && html.contains("Headline APCER") && html.contains("attack: screen"), html);
        assertEquals(1, html.split("<h1>", -1).length - 1, "one title");
        assertTrue(!html.contains("<script"), "self-contained, no scripts");
    }

    @Test
    void theDetAxesAreNormalDeviates() {
        assertEquals(0.0, HtmlReport.probit(0.5), 1e-9);
        assertEquals(-1.6449, HtmlReport.probit(0.05), 1e-4);
        assertEquals(-3.0902, HtmlReport.probit(0.001), 1e-4);
        assertEquals(2.3263, HtmlReport.probit(0.99), 1e-4);
    }

    @Test
    void markdownTablesBecomeHtmlTablesAndTextIsEscaped() {
        String h = HtmlReport.markdownToHtml("## A <b>\n\n| x | y |\n|---|---:|\n| **k** | 1.5% |\n\ntext");
        assertTrue(h.contains("<h3>A &lt;b&gt;</h3>"), h);
        assertTrue(h.contains("<tr><th>x</th><th>y</th></tr>"), h);
        assertTrue(h.contains("<td><strong>k</strong></td><td class=\"num\">1.5%</td>"), h);
        assertTrue(h.contains("<p>text</p>"), h);
    }
}
