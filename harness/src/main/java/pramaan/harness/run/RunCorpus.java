/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.audit.AuditLog;
import pramaan.core.engine.DiagnosticGrant;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.desktop.capture.ConfigurationSource;
import pramaan.desktop.capture.DesktopEngines;
import pramaan.harness.corpus.CorpusManifest;
import pramaan.harness.eval.SessionRow;

/**
 * Build Map #67: the whole corpus through the production engine, one JSON row per clip.
 *
 * <pre>./gradlew :harness:runCorpus --args="--manifest corpus/manifest.csv --out build/results/rows.jsonl
 *     [--actions corpus/actions.csv] [--config config] [--models models] [--workflow RESIDENT]
 *     [--fps 3] [--reaction-ms 600] [--audit build/results/audit.log]"</pre>
 *
 * Then {@code ./gradlew :harness:evaluate --args="--rows build/results/rows.jsonl ..."} writes the report.
 *
 * <p>The scores in each row are read through diagnostic mode, the one audited channel that carries them. If
 * the policy under test ships with {@code diagnosticMode: false}, that flag alone is switched on in memory for
 * the run; it gates only the channel, never a decision. {@code rows.jsonl.run.json} records the hash of the
 * policy as shipped and as run, the table, the models and the options, so a result can be tied to what made it.
 */
public final class RunCorpus {

    private RunCorpus() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i], args[i + 1]);
        }
        if (!a.containsKey("--manifest") || !a.containsKey("--out")) {
            System.err.println("usage: --manifest manifest.csv --out rows.jsonl [--actions actions.csv] [--config dir] "
                    + "[--models dir] [--workflow RESIDENT|OPERATOR|SUPERVISOR] [--fps n] [--reaction-ms n] [--audit file]");
            return 2;
        }
        try {
            Path out = Path.of(a.get("--out"));
            Summary s = runAll(Path.of(a.get("--manifest")), out,
                    a.containsKey("--actions") ? ActionLibrary.load(Path.of(a.get("--actions"))) : ActionLibrary.empty(),
                    Path.of(a.getOrDefault("--config", "config")), Path.of(a.getOrDefault("--models", "models")),
                    Path.of(a.getOrDefault("--audit", out + ".audit.log")), options(a));
            System.out.println("wrote " + s.rows + " rows to " + out + " " + s.outcomes);
            return 0;
        } catch (Exception e) {
            System.err.println("corpus run failed: " + e.getMessage());
            return 1;
        }
    }

    private static CorpusRunner.Options options(Map<String, String> a) {
        CorpusRunner.Options o = new CorpusRunner.Options();
        if (a.containsKey("--workflow")) {
            o.workflow(Workflow.valueOf(a.get("--workflow")));
        }
        if (a.containsKey("--fps")) {
            o.fps(Double.parseDouble(a.get("--fps")));
        }
        if (a.containsKey("--reaction-ms")) {
            o.reaction(Duration.ofMillis(Long.parseLong(a.get("--reaction-ms"))));
        }
        return o;
    }

    /** What a run produced. */
    static final class Summary {
        final int rows;
        final Map<String, Integer> outcomes;

        Summary(int rows, Map<String, Integer> outcomes) {
            this.rows = rows;
            this.outcomes = outcomes;
        }
    }

    static Summary runAll(Path manifestFile, Path out, ActionLibrary actions, Path configDir, Path modelsDir, Path auditFile,
            CorpusRunner.Options options) throws Exception {
        CorpusManifest manifest = CorpusManifest.load(manifestFile);
        byte[] shipped = Files.readAllBytes(configDir.resolve("pramaan-policy.yaml"));
        String text = new String(shipped, StandardCharsets.UTF_8);
        byte[] runPolicy = text.replace("diagnosticMode: false", "diagnosticMode: true").getBytes(StandardCharsets.UTF_8);
        byte[] table = Files.readAllBytes(configDir.resolve("operating-points.json"));
        ConfigurationSource config = new ConfigurationSource() {
            @Override
            public byte[] policyYaml() {
                return runPolicy;
            }

            @Override
            public byte[] operatingPointsJson() {
                return table;
            }

            @Override
            public String describe() {
                return configDir.toAbsolutePath().normalize() + " (diagnostic channel on for scoring)";
            }
        };
        if (out.toAbsolutePath().getParent() != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
        }
        if (auditFile.toAbsolutePath().getParent() != null) {
            Files.createDirectories(auditFile.toAbsolutePath().getParent());
        }
        AuditLog log = AuditLog.open(auditFile, Clock.systemUTC());
        java.util.function.Consumer<AuditEvent> audit = e -> {
            try {
                log.append(e);
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        };
        List<SessionRow> rows = new ArrayList<>();
        Map<String, Integer> outcomes = new TreeMap<>();
        try (EngineBundle bundle = DesktopEngines.loadBundle(config, modelsDir)) {
            DiagnosticGrant grant = DiagnosticGrant.activate(bundle, "harness", "corpus evaluation run",
                    Clock.systemUTC().instant(), audit);
            CorpusRunner runner = new CorpusRunner(bundle, grant, actions, options, audit);
            Path base = manifestFile.toAbsolutePath().getParent();
            int n = 0;
            for (CorpusManifest.Entry e : manifest.entries()) {
                String id = base.relativize(e.clip().toAbsolutePath()).toString().replace('\\', '/');
                SessionRow row = runner.run(e, id);
                rows.add(row);
                outcomes.merge(row.outcome().name(), 1, Integer::sum);
                System.out.printf("[%d/%d] %s %s -> %s%n", ++n, manifest.entries().size(), row.label(), id, row.outcome());
            }
            SessionRow.writeJsonl(rows, out);
            writeRunRecord(out, manifestFile, shipped, bundle, options, outcomes, rows.size());
        }
        return new Summary(rows.size(), outcomes);
    }

    private static void writeRunRecord(Path out, Path manifest, byte[] shippedPolicy, EngineBundle bundle,
            CorpusRunner.Options options, Map<String, Integer> outcomes, int rows) throws IOException {
        ObjectMapper json = new ObjectMapper();
        ObjectNode r = json.createObjectNode();
        r.put("manifest", manifest.toAbsolutePath().normalize().toString());
        r.put("rows", rows);
        r.put("policySha256Shipped", OperatingPointTable.sha256Hex(shippedPolicy));
        r.put("policySha256Run", bundle.policySha256());
        r.put("operatingPointsSha256", bundle.operatingPointsSha256());
        r.put("provisionalThresholds", bundle.table().provisional());
        ObjectNode models = r.putObject("models");
        bundle.table().models().forEach(models::put);
        r.put("workflow", options.workflow.name());
        if (!Double.isNaN(options.fpsOverride)) {
            r.put("fpsOverride", options.fpsOverride);
        }
        r.put("reactionMs", options.reaction.toMillis());
        ObjectNode o = r.putObject("outcomes");
        outcomes.forEach(o::put);
        Files.write(Path.of(out + ".run.json"), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(r));
    }
}
