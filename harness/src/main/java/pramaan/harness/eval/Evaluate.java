/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import pramaan.core.policy.LivenessPolicy;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.policy.YamlPolicySource;

/**
 * {@code evaluate}: the report from a corpus run (Build Map #68-#71).
 *
 * <pre>
 * ./gradlew :harness:evaluate --args="--rows results.jsonl --table config/operating-points.json
 *     --policy config/pramaan-policy.yaml --out report.md [--html report.html]
 *     [--export operating-points.generated.json]"
 * </pre>
 * The export binds to the models listed in the given table. Exit code 0 on success, 3 on error.
 */
public final class Evaluate {

    private Evaluate() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i], args[i + 1]);
        }
        for (String r : new String[] {"--rows", "--table", "--policy", "--out"}) {
            if (!a.containsKey(r)) {
                System.err.println("missing " + r);
                return 3;
            }
        }
        try {
            List<SessionRow> rows = SessionRow.readJsonl(Path.of(a.get("--rows")));
            byte[] tableBytes = Files.readAllBytes(Path.of(a.get("--table")));
            Map<String, String> models = new TreeMap<>();
            new ObjectMapper().readTree(tableBytes).path("models").fields()
                    .forEachRemaining(e -> models.put(e.getKey(), e.getValue().asText()));
            OperatingPointTable table = OperatingPointTable.load(tableBytes, a.get("--table"), models);
            LivenessPolicy policy = YamlPolicySource.of(Path.of(a.get("--policy"))).load();
            String md = report(rows, table, policy);
            Files.write(Path.of(a.get("--out")), md.getBytes(StandardCharsets.UTF_8));
            if (a.containsKey("--html")) {
                Files.write(Path.of(a.get("--html")), HtmlReport.render(md, rows).getBytes(StandardCharsets.UTF_8));
                System.out.println("wrote " + a.get("--html"));
            }
            if (a.containsKey("--export")) {
                OperatingPointExporter.Export e = OperatingPointExporter.export(new String(tableBytes, StandardCharsets.UTF_8),
                        new DetCurve(rows), targets(), models, rows.size() + " sessions from " + a.get("--rows"));
                Files.write(Path.of(a.get("--export")), e.json.getBytes(StandardCharsets.UTF_8));
                e.notes.forEach(n -> System.out.println("note: " + n));
            }
            System.out.println("wrote " + a.get("--out"));
            return 0;
        } catch (Exception e) {
            System.err.println("evaluation could not run: " + e);
            return 3;
        }
    }

    /** The profile targets from Bible Part 9. */
    public static List<OperatingPointExporter.Target> targets() {
        return List.of(
                new OperatingPointExporter.Target("resident-default", 0.05, 0.01, "BPCER <= 3% at APCER <= 5%"),
                new OperatingPointExporter.Target("operator-strict", 0.01, 0.01, "APCER <= 1% on the worst PAIS"),
                new OperatingPointExporter.Target("supervisor-strictest", 0.005, 0.01, "APCER <= 0.5% on the worst PAIS"));
    }

    public static String report(List<SessionRow> rows, OperatingPointTable table, LivenessPolicy policy) {
        DetCurve det = new DetCurve(rows);
        StringBuilder s = new StringBuilder("# PRAMAAN evaluation report\n\n");
        s.append(String.format("%d sessions. Thresholds %s.%n%n", rows.size(),
                table.provisional() ? "are PROVISIONAL (not yet measured)" : "come from a measured operating-point table"));
        s.append(PadReport.compute(rows).toMarkdown()).append('\n');
        s.append(String.format("**BPCER20** (BPCER at measured worst-PAIS APCER <= 5%%): %.2f%%   **BPCER100** (<= 1%%): %.2f%%%n%n",
                100 * det.bpcerAt(0.05), 100 * det.bpcerAt(0.01)));
        s.append(FairnessReport.compute(rows).toMarkdown());
        s.append(CueAblation.run(rows, table, policy).toMarkdown());
        return s.toString();
    }
}
