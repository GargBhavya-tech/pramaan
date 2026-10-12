/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import pramaan.core.audit.AuditLog;
import pramaan.core.audit.FraudAnalytics;
import pramaan.core.audit.FraudAnalytics.Signal;

/**
 * {@code fraudReport}: per-operator signals from the audit log (Bible Part 18.2; Build Map #88).
 *
 * <pre>
 * ./gradlew :harness:fraudReport --args="--audit build/scaffold/audit.log --out fraud.md"
 * </pre>
 * Every {@code ATTEMPT_ENDED} is one attempt; an approved {@code EXCEPTION_REQUEST} marks the last attempt of its
 * session. Writes a markdown table and, next to it, one funnel-plot SVG per signal. A broken audit chain is reported
 * at the top, never silently analysed. Exit code 0 on success, 3 on error.
 */
public final class FraudReport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FraudReport() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i], args[i + 1]);
        }
        if (!a.containsKey("--audit") || !a.containsKey("--out")) {
            System.err.println("usage: --audit audit.log --out fraud.md");
            return 3;
        }
        try {
            Path audit = Path.of(a.get("--audit"));
            Path out = Path.of(a.get("--out")).toAbsolutePath();
            AuditLog.Verification v = AuditLog.verify(audit);
            List<FraudAnalytics.Line> lines = FraudAnalytics.analyse(read(audit));
            String base = out.getFileName().toString().replaceFirst("\\.md$", "");
            Map<Signal, String> svgs = new LinkedHashMap<>();
            for (Signal s : Signal.values()) {
                String name = base + "-funnel-" + s.name().toLowerCase(Locale.ROOT) + ".svg";
                Files.write(out.resolveSibling(name), funnelSvg(lines, s).getBytes(StandardCharsets.UTF_8));
                svgs.put(s, name);
            }
            Files.write(out, markdown(lines, v, svgs).getBytes(StandardCharsets.UTF_8));
            System.out.println("wrote " + out);
            return 0;
        } catch (Exception e) {
            System.err.println("fraud report could not run: " + e);
            return 3;
        }
    }

    /** The attempts in an audit log, each with the signals it raised. */
    static List<FraudAnalytics.Attempt> read(Path audit) throws IOException {
        List<String> ops = new ArrayList<>();
        List<String> centres = new ArrayList<>();
        List<Set<Signal>> signals = new ArrayList<>();
        Map<String, Integer> lastOfSession = new HashMap<>();
        for (String line : Files.readAllLines(audit, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            JsonNode e = JSON.readTree(line);
            JsonNode f = e.path("fields");
            String type = e.path("type").asText();
            if ("ATTEMPT_ENDED".equals(type)) {
                Set<Signal> s = EnumSet.noneOf(Signal.class);
                String outcome = f.path("outcome").asText();
                if ("REJECT_PAD".equals(outcome) || "REJECT_VETO".equals(outcome)) {
                    s.add(Signal.PAD_FAIL);
                } else if ("REJECT_BINDING".equals(outcome)) {
                    s.add(Signal.BINDING_FAIL);
                }
                if (!f.path("accessibility").asText("").isEmpty()) {
                    s.add(Signal.ACCESSIBILITY);
                }
                lastOfSession.put(f.path("sessionId").asText(), signals.size());
                ops.add(f.path("operatorId").asText("?"));
                centres.add(f.path("centreId").asText("?"));
                signals.add(s);
            } else if ("EXCEPTION_REQUEST".equals(type) && "APPROVED".equals(f.path("outcome").asText())) {
                Integer i = lastOfSession.get(f.path("sessionId").asText());
                if (i != null) {
                    signals.get(i).add(Signal.EXCEPTION);
                }
            }
        }
        List<FraudAnalytics.Attempt> out = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            out.add(new FraudAnalytics.Attempt(ops.get(i), centres.get(i), signals.get(i)));
        }
        return out;
    }

    static String markdown(List<FraudAnalytics.Line> lines, AuditLog.Verification v, Map<Signal, String> svgs) {
        StringBuilder md = new StringBuilder("# Operator fraud signals\n\n");
        md.append(v.intact() ? "Audit chain intact: " + v.entries() + " entries.\n\n"
                : "**Audit chain BROKEN (" + v.problem() + "). The figures below cannot be trusted.**\n\n");
        md.append("Each operator is compared with their own centre's rate, z = (x - n p0) / sqrt(n p0 (1 - p0)). ")
                .append("Flagged: z >= ").append(FraudAnalytics.FLAG_Z)
                .append(" and above the funnel's upper limit. A flag starts a review; it blocks nobody (Bible Part 18.3).\n\n");
        md.append("| operator | centre | signal | x | n | rate | centre rate | upper limit | z | flag |\n");
        md.append("|---|---|---|---:|---:|---:|---:|---:|---:|---|\n");
        for (FraudAnalytics.Line l : lines) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %d | %.3f | %.3f | %.3f | %.2f | %s |%n",
                    l.operatorId, l.centreId, l.signal, l.count, l.attempts, l.rate(), l.centreRate, l.upperLimit, l.z,
                    l.flagged() ? "**REVIEW**" : ""));
        }
        md.append("\n");
        svgs.forEach((s, name) -> md.append("![").append(s).append(" funnel](").append(name).append(")\n\n"));
        return md.toString();
    }

    /**
     * A funnel plot for one signal: each operator a dot at (attempts, rate), the pooled rate as a line, and the ±3 SD
     * limits drawn from the pooled rate across all centres. Flagged operators are red and labelled.
     */
    static String funnelSvg(List<FraudAnalytics.Line> lines, Signal signal) {
        List<FraudAnalytics.Line> ls = new ArrayList<>();
        long x = 0;
        long n = 0;
        for (FraudAnalytics.Line l : lines) {
            if (l.signal == signal) {
                ls.add(l);
                x += l.count;
                n += l.attempts;
            }
        }
        double p = n == 0 ? 0 : (double) x / n;
        long maxN = 10;
        double maxRate = Math.max(0.05, p * 2);
        for (FraudAnalytics.Line l : ls) {
            maxN = Math.max(maxN, l.attempts);
            maxRate = Math.max(maxRate, l.rate());
        }
        maxRate = Math.min(1, maxRate * 1.1);
        int w = 640;
        int h = 400;
        int ml = 56;
        int mb = 44;
        int mt = 30;
        int mr = 20;
        double pw = w - ml - mr;
        double ph = h - mt - mb;
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.ROOT, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" "
                + "font-family=\"sans-serif\" font-size=\"11\">%n", w, h));
        s.append(String.format(Locale.ROOT, "<rect width=\"%d\" height=\"%d\" fill=\"white\"/>%n", w, h));
        s.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"18\" font-size=\"13\">%s: rate by attempts "
                + "(pooled %.3f, ±%.0f SD)</text>%n", ml, signal, p, FraudAnalytics.FLAG_Z));
        s.append(String.format(Locale.ROOT, "<line x1=\"%d\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"black\"/>%n",
                ml, mt + ph, ml + pw, mt + ph));
        s.append(String.format(Locale.ROOT, "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%.1f\" stroke=\"black\"/>%n",
                ml, mt, ml, mt + ph));
        s.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">attempts (n), max %d</text>%n",
                ml + pw / 2, h - 8, maxN));
        s.append(String.format(Locale.ROOT, "<text x=\"12\" y=\"%.1f\" transform=\"rotate(-90 12 %.1f)\" "
                + "text-anchor=\"middle\">rate, max %.2f</text>%n", mt + ph / 2, mt + ph / 2, maxRate));
        StringBuilder upper = new StringBuilder();
        StringBuilder lower = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            long ni = Math.max(1, Math.round(maxN * i / 100.0));
            double[] lim = FraudAnalytics.funnelLimits(ni, p, FraudAnalytics.FLAG_Z);
            double px = ml + pw * ni / maxN;
            upper.append(String.format(Locale.ROOT, "%.1f,%.1f ", px, mt + ph * (1 - Math.min(lim[1], maxRate) / maxRate)));
            lower.append(String.format(Locale.ROOT, "%.1f,%.1f ", px, mt + ph * (1 - lim[0] / maxRate)));
        }
        s.append("<polyline fill=\"none\" stroke=\"#c33\" stroke-dasharray=\"4 3\" points=\"").append(upper).append("\"/>\n");
        s.append("<polyline fill=\"none\" stroke=\"#c33\" stroke-dasharray=\"4 3\" points=\"").append(lower).append("\"/>\n");
        double py = mt + ph * (1 - p / maxRate);
        s.append(String.format(Locale.ROOT, "<line x1=\"%d\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"#555\"/>%n",
                ml, py, ml + pw, py));
        for (FraudAnalytics.Line l : ls) {
            double cx = ml + pw * l.attempts / maxN;
            double cy = mt + ph * (1 - l.rate() / maxRate);
            s.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"4\" fill=\"%s\"/>%n", cx, cy,
                    l.flagged() ? "#c33" : "#36c"));
            if (l.flagged()) {
                s.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%.1f\">%s</text>%n", cx + 6, cy - 4, xml(l.operatorId)));
            }
        }
        return s.append("</svg>\n").toString();
    }

    private static String xml(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
