/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import pramaan.core.fusion.ConformalThreshold;

/**
 * What to run on corpus day (Build Map #65, #70; Bible Part 9, 24): from the recorded session rows, a <em>proposal</em> for
 * the fusion weights, the cut-offs and their measured error rates, written with enough provenance that a reviewer can
 * reproduce it and a deployment can refuse it. It never edits {@code operating-points.json}; a person reads the report
 * and decides.
 *
 * <p>The procedure, in order:
 * <ol>
 *   <li><b>split by person</b>, not by session, so the same face never appears on both sides: half to fit the weights,
 *       a quarter to calibrate the cut-offs, a quarter held out to report on (stable in the seed and the person id);</li>
 *   <li><b>fit weights</b> ({@link FusionLearner}) from the hand-set table, shrunk toward it;</li>
 *   <li><b>cut-offs</b> per group: the reject cut-off by {@link ConformalThreshold} on that group's genuine calibration
 *       sessions (a guaranteed BPCER, or an honest refusal when the group is too small), and a proposed accept cut-off at a
 *       high quantile of the calibration attacks (no guarantee: checked on the held-out split);</li>
 *   <li><b>report on the held-out split only</b>: BPCER per group and APCER per PAIS, each with a 95% Clopper-Pearson
 *       interval, because anything measured on the data it was fitted to is optimistic.</li>
 * </ol>
 *
 * <p>Row format: {@link SessionRow}. Cues that abstained are {@code null} in the row and are left out of the fusion.
 *
 * <pre>./gradlew :harness:calibrate --args="--rows build/results/rows.jsonl --table config/operating-points.json
 *     --alpha 0.05 --group-by deviceLevel --out build/calibration"</pre>
 */
public final class CalibrateFromRows {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The share of calibration attacks the proposed accept cut-off sits above. */
    private static final double ACCEPT_QUANTILE = 0.99;

    /** What the calibration produced. */
    public static final class Result {
        public final String json;
        public final String report;
        public final int train;
        public final int calibration;
        public final int test;
        public final Map<String, ConformalThreshold> rejectCutOffs;

        Result(String json, String report, int train, int calibration, int test, Map<String, ConformalThreshold> cuts) {
            this.json = json;
            this.report = report;
            this.train = train;
            this.calibration = calibration;
            this.test = test;
            this.rejectCutOffs = cuts;
        }
    }

    private CalibrateFromRows() {
    }

    /**
     * @param groupBy {@code deviceLevel}, {@code workflow}, or the name of a key in the rows' attributes
     * @param inputSha256 provenance: the hash of the rows file this was computed from
     */
    public static Result calibrate(List<SessionRow> all, Path table, double alpha, String groupBy, long seed,
            String inputSha256) throws IOException {
        JsonNode root = JSON.readTree(Files.readAllBytes(table));
        FusionLearner.Weights hand = prior(root);
        Set<String> physiological = physiological(root);
        List<SessionRow> usable = all.stream().filter(SessionRow::responded).collect(Collectors.toList());

        List<SessionRow> train = new ArrayList<>();
        List<SessionRow> calibration = new ArrayList<>();
        List<SessionRow> test = new ArrayList<>();
        for (SessionRow r : usable) {
            int bucket = Math.floorMod((personOf(r) + "|" + seed).hashCode(), 100);
            (bucket < 50 ? train : bucket < 75 ? calibration : test).add(r);
        }
        FusionLearner.Weights learned = hand;
        String fitNote = "weights not refitted (the training split lacks genuine or attack sessions)";
        if (hasBoth(train)) {
            learned = FusionLearner.learn(samples(train), hand, physiological, 0.05, 400, 0.5);
            fitNote = "weights refitted on " + train.size() + " training sessions, shrunk toward the table";
        }

        Map<String, List<Double>> genuineByGroup = new TreeMap<>();
        List<Double> calibrationAttacks = new ArrayList<>();
        for (SessionRow r : calibration) {
            double l = FusionLearner.score(learned, sample(r), physiological);
            if (r.label() == SessionRow.Label.BONA_FIDE) {
                genuineByGroup.computeIfAbsent(group(r, groupBy), g -> new ArrayList<>()).add(l);
            } else {
                calibrationAttacks.add(l);
            }
        }
        Map<String, ConformalThreshold> cuts = new LinkedHashMap<>();
        genuineByGroup.forEach((g, scores) -> cuts.put(g, ConformalThreshold.calibrate(
                scores.stream().mapToDouble(Double::doubleValue).toArray(), alpha)));
        double acceptCut = quantile(calibrationAttacks, ACCEPT_QUANTILE);

        StringBuilder report = new StringBuilder();
        report.append(String.format("CALIBRATION PROPOSAL (not applied). rows %d usable of %d; split by person: train %d, calibration"
                + " %d, held-out %d; alpha %.3f; groups by %s; seed %d%n", usable.size(), all.size(), train.size(),
                calibration.size(), test.size(), alpha, groupBy, seed));
        report.append(fitNote).append("\n\n");
        report.append("Cut-offs (fused log-odds; reject at or below, accept above the accept cut-off):\n");
        for (Map.Entry<String, ConformalThreshold> e : cuts.entrySet()) {
            report.append(String.format("  %-14s %s%n", e.getKey(), e.getValue().statement()));
        }
        if (cuts.isEmpty()) {
            report.append("  no genuine sessions in the calibration split: nothing can be guaranteed.\n");
        }
        report.append(String.format("  proposed accept cut-off %.2f: the %.0f%% quantile of %d calibration attacks (no guarantee;"
                + " see APCER below)%n%n", acceptCut, 100 * ACCEPT_QUANTILE, calibrationAttacks.size()));

        report.append("On the held-out split only:\n");
        Map<String, long[]> bpcer = new TreeMap<>(); // {genuine, rejected, notAccepted}
        Map<String, long[]> apcer = new TreeMap<>(); // {attacks, accepted, notRejected}
        for (SessionRow r : test) {
            double l = FusionLearner.score(learned, sample(r), physiological);
            String g = group(r, groupBy);
            ConformalThreshold c = cuts.get(g);
            boolean rejected = c != null && !c.accepts(l);
            boolean accepted = !Double.isNaN(acceptCut) && l > acceptCut;
            if (r.label() == SessionRow.Label.BONA_FIDE) {
                long[] b = bpcer.computeIfAbsent(g, k -> new long[3]);
                b[0]++;
                b[1] += rejected ? 1 : 0;
                b[2] += accepted ? 0 : 1;
            } else {
                long[] a = apcer.computeIfAbsent(r.pais(), k -> new long[3]);
                a[0]++;
                a[1] += accepted ? 1 : 0;
                a[2] += rejected ? 0 : 1;
            }
        }
        bpcer.forEach((g, b) -> report.append(String.format(
                "  BPCER %-12s rejected %s   not accepted first time (challenged or rejected) %s%n", g, rate(b[1], b[0]),
                rate(b[2], b[0]))));
        apcer.forEach((p, a) -> report.append(String.format(
                "  APCER %-12s accepted %s   not rejected (accepted or challenged) %s%n", p, rate(a[1], a[0]),
                rate(a[2], a[0]))));
        if (bpcer.isEmpty() || apcer.isEmpty()) {
            report.append("  the held-out split lacks genuine or attack sessions: nothing can be reported.\n");
        }
        report.append("\nWhat this is not: a certification, an ACER, or a claim about any group with fewer sessions than the interval"
                + " allows. Intervals are 95% Clopper-Pearson. Nothing here has been applied to operating-points.json.\n");

        ObjectNode out = JSON.createObjectNode();
        out.put("version", 1);
        out.put("status", "PROPOSAL: a person must review it; nothing was applied");
        ObjectNode prov = out.putObject("provenance");
        prov.put("rowsSha256", inputSha256);
        prov.put("rows", all.size());
        prov.put("usable", usable.size());
        prov.put("train", train.size());
        prov.put("calibration", calibration.size());
        prov.put("heldOut", test.size());
        prov.put("alpha", alpha);
        prov.put("groupBy", groupBy);
        prov.put("seed", seed);
        prov.put("tableSha256", sha256(Files.readAllBytes(table)));
        out.set("fusion", JSON.readTree(learned.toJson(physiological)));
        ObjectNode groups = out.putObject("rejectCutOffs");
        cuts.forEach((g, c) -> {
            ObjectNode n = groups.putObject(g);
            n.put("canReject", c.canReject());
            if (c.canReject()) {
                n.put("logOddsAtOrBelow", c.threshold());
            }
            n.put("guaranteedBpcer", c.guaranteedBpcer());
            n.put("calibrationGenuine", c.samples());
            n.put("minimumGenuineForAlpha", ConformalThreshold.minimumSamples(alpha));
        });
        if (!Double.isNaN(acceptCut)) {
            out.put("proposedAcceptLogOdds", acceptCut);
        }
        return new Result(out.toPrettyString(), report.toString(), train.size(), calibration.size(), test.size(), cuts);
    }

    private static String rate(long k, long n) {
        Binomial.Rate r = Binomial.clopperPearson(k, n, 0.95);
        return String.format("%d/%d = %.3f [%.3f, %.3f]", k, n, r.value(), r.lower(), r.upper());
    }

    private static boolean hasBoth(List<SessionRow> rows) {
        return rows.stream().anyMatch(r -> r.label() == SessionRow.Label.BONA_FIDE)
                && rows.stream().anyMatch(r -> r.label() == SessionRow.Label.ATTACK);
    }

    private static String personOf(SessionRow r) {
        return r.attributes().getOrDefault("person", r.id());
    }

    private static String group(SessionRow r, String groupBy) {
        switch (groupBy) {
            case "deviceLevel":
                return String.valueOf(r.deviceLevel());
            case "workflow":
                return String.valueOf(r.workflow());
            default:
                return r.attributes().getOrDefault(groupBy, "unknown");
        }
    }

    private static List<FusionLearner.Sample> samples(List<SessionRow> rows) {
        return rows.stream().map(CalibrateFromRows::sample).collect(Collectors.toList());
    }

    private static FusionLearner.Sample sample(SessionRow r) {
        Map<String, Double> votes = new LinkedHashMap<>();
        r.cues().forEach((k, v) -> {
            if (v != null && v > 0 && v < 1) {
                votes.put(k, v);
            }
        });
        return new FusionLearner.Sample(r.label() == SessionRow.Label.BONA_FIDE, votes);
    }

    private static double quantile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double[] s = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return s[Math.min(s.length - 1, (int) Math.floor(q * s.length))];
    }

    private static FusionLearner.Weights prior(JsonNode root) {
        Map<String, double[]> w = new LinkedHashMap<>();
        root.path("fusion").path("cues").fields().forEachRemaining(
                e -> w.put(e.getKey(), new double[] {e.getValue().path("wPos").asDouble(), e.getValue().path("wNeg").asDouble()}));
        return new FusionLearner.Weights(root.path("fusion").path("bias").path("L1").asDouble(0), w);
    }

    private static Set<String> physiological(JsonNode root) {
        Set<String> out = new java.util.HashSet<>();
        root.path("fusion").path("cues").fields().forEachRemaining(e -> {
            if (e.getValue().path("physiological").asBoolean(false)) {
                out.add(e.getKey());
            }
        });
        return out;
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------ the command

    public static void main(String[] args) throws Exception {
        Path rows = null;
        Path table = Path.of("config", "operating-points.json");
        Path out = Path.of("build", "calibration");
        double alpha = 0.05;
        String groupBy = "deviceLevel";
        long seed = 1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--rows":
                    rows = Path.of(args[++i]);
                    break;
                case "--table":
                    table = Path.of(args[++i]);
                    break;
                case "--out":
                    out = Path.of(args[++i]);
                    break;
                case "--alpha":
                    alpha = Double.parseDouble(args[++i]);
                    break;
                case "--group-by":
                    groupBy = args[++i];
                    break;
                case "--seed":
                    seed = Long.parseLong(args[++i]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (rows == null) {
            System.err.println("usage: --rows <rows.jsonl> [--table ..] [--alpha 0.05] [--group-by deviceLevel|workflow|<attribute>]"
                    + " [--seed 1] [--out build/calibration]");
            System.exit(2);
        }
        Result r = calibrate(SessionRow.readJsonl(rows), table, alpha, groupBy, seed, sha256(Files.readAllBytes(rows)));
        Files.createDirectories(out);
        Files.write(out.resolve("calibration.json"), r.json.getBytes(StandardCharsets.UTF_8));
        Files.write(out.resolve("calibration-report.txt"), r.report.getBytes(StandardCharsets.UTF_8));
        System.out.print(r.report);
        System.out.println("wrote " + out.resolve("calibration.json") + " and calibration-report.txt");
    }
}
