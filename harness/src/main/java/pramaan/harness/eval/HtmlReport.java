/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The evaluation report as one self-contained HTML page (Build Map #96): a corpus summary, a DET plot with one curve
 * per PAIS on normal-deviate axes (ISO/IEC 30107-3 plots APCER against BPCER), and the markdown report's tables. No
 * scripts, no external files: the page can be attached to a submission as it is.
 */
public final class HtmlReport {

    /** DET axes run from 0.1% to 50%, the range a corpus of this size can say anything about. */
    static final double AXIS_MIN = 0.001;
    static final double AXIS_MAX = 0.5;
    private static final String[] COLOURS = {"#1b6ac9", "#c9361b", "#2e9a3c", "#9a2e8f", "#c98a1b", "#1b9ac9", "#555555"};

    private HtmlReport() {
    }

    public static String render(String markdown, List<SessionRow> rows) {
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>PRAMAAN evaluation</title><style>")
                .append(":root{--bg:#fff;--fg:#1a1a1a;--muted:#666;--rule:#ddd;--head:#f3f3f3}")
                .append("@media (prefers-color-scheme: dark){:root{--bg:#161616;--fg:#e8e8e8;--muted:#999;--rule:#333;--head:#222}}")
                .append("body{background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif;max-width:1000px;")
                .append("margin:0 auto;padding:24px 16px}table{border-collapse:collapse;margin:12px 0;display:block;overflow-x:auto}")
                .append("th,td{border:1px solid var(--rule);padding:4px 8px;text-align:left}th{background:var(--head)}")
                .append(".num{text-align:right;font-variant-numeric:tabular-nums}.muted{color:var(--muted)}")
                .append("svg{max-width:100%;height:auto;background:#fff;border:1px solid var(--rule)}")
                .append("</style></head><body>\n");
        h.append("<h1>PRAMAAN evaluation report</h1>\n");
        h.append(corpusSummary(rows));
        h.append("<h2>DET: APCER per PAIS against BPCER</h2>\n");
        h.append("<p class=\"muted\">Normal-deviate axes. Each curve is one PAIS swept over the fused P(live) threshold; "
                + "points at 0% or 100% are drawn at the axis edge. Few attacks per PAIS make a curve a staircase: read "
                + "the confidence intervals in the table below, not the line.</p>\n");
        h.append(detSvg(new DetCurve(rows)));
        // The markdown's own "# title" line is the page's h1 already.
        h.append(markdownToHtml(markdown.startsWith("# ") ? markdown.substring(Math.max(0, markdown.indexOf('\n'))) : markdown));
        return h.append("</body></html>\n").toString();
    }

    /** Sessions by label and PAIS, then by every attribute the manifest records (lighting, eyewear, device, ...). */
    static String corpusSummary(List<SessionRow> rows) {
        Map<String, int[]> byPais = new TreeMap<>();
        Map<String, Map<String, int[]>> byAttr = new TreeMap<>();
        for (SessionRow r : rows) {
            String key = r.label() == SessionRow.Label.ATTACK ? "attack: " + r.pais() : "bona fide";
            count(byPais, key, r);
            count(byAttr.computeIfAbsent("deviceLevel", k -> new TreeMap<>()), r.deviceLevel(), r);
            count(byAttr.computeIfAbsent("workflow", k -> new TreeMap<>()), r.workflow(), r);
            r.attributes().forEach((k, v) -> count(byAttr.computeIfAbsent(k, x -> new TreeMap<>()), v, r));
        }
        StringBuilder s = new StringBuilder("<h2>Corpus</h2>\n<p>").append(rows.size()).append(" sessions.</p>\n");
        s.append(table("class", byPais));
        byAttr.forEach((attr, m) -> s.append("<h3>by ").append(esc(attr)).append("</h3>\n").append(table(attr, m)));
        return s.toString();
    }

    private static void count(Map<String, int[]> m, String key, SessionRow r) {
        int[] c = m.computeIfAbsent(key == null ? "(none)" : key, k -> new int[3]);
        c[0]++;
        if (r.label() == SessionRow.Label.ATTACK) {
            c[1]++;
        } else {
            c[2]++;
        }
    }

    private static String table(String head, Map<String, int[]> m) {
        StringBuilder s = new StringBuilder("<table><tr><th>").append(esc(head))
                .append("</th><th>sessions</th><th>attacks</th><th>bona fide</th></tr>\n");
        m.forEach((k, c) -> s.append("<tr><td>").append(esc(k)).append("</td><td class=\"num\">").append(c[0])
                .append("</td><td class=\"num\">").append(c[1]).append("</td><td class=\"num\">").append(c[2]).append("</td></tr>\n"));
        return s.append("</table>\n").toString();
    }

    /** One polyline per PAIS through (APCER, BPCER) at every threshold, both axes as normal deviates. */
    static String detSvg(DetCurve det) {
        int w = 560;
        int hgt = 480;
        int ml = 60;
        int mr = 150;
        int mt = 20;
        int mb = 50;
        double pw = w - ml - mr;
        double ph = hgt - mt - mb;
        double lo = probit(AXIS_MIN);
        double hi = probit(AXIS_MAX);
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.ROOT, "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" "
                + "font-family=\"sans-serif\" font-size=\"11\" role=\"img\" aria-label=\"DET curves per PAIS\">%n", w, hgt, w));
        for (double tick : new double[] {0.001, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5}) {
            double x = ml + pw * (probit(tick) - lo) / (hi - lo);
            double y = mt + ph * (1 - (probit(tick) - lo) / (hi - lo));
            String label = tick < 0.01 ? String.format(Locale.ROOT, "%.1f%%", 100 * tick) : String.format(Locale.ROOT, "%.0f%%", 100 * tick);
            s.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"#e4e4e4\"/>"
                    + "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"#333\">%s</text>%n", x, mt, x, mt + ph, x, mt + ph + 14, label));
            s.append(String.format(Locale.ROOT, "<line x1=\"%d\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"#e4e4e4\"/>"
                    + "<text x=\"%d\" y=\"%.1f\" text-anchor=\"end\" fill=\"#333\">%s</text>%n", ml, y, ml + pw, y, ml - 4, y + 4, label));
        }
        s.append(String.format(Locale.ROOT, "<rect x=\"%d\" y=\"%d\" width=\"%.1f\" height=\"%.1f\" fill=\"none\" stroke=\"#333\"/>%n",
                ml, mt, pw, ph));
        s.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%d\" text-anchor=\"middle\" fill=\"#333\">APCER</text>%n",
                ml + pw / 2, hgt - 12));
        s.append(String.format(Locale.ROOT, "<text x=\"14\" y=\"%.1f\" text-anchor=\"middle\" fill=\"#333\" "
                + "transform=\"rotate(-90 14 %.1f)\">BPCER</text>%n", mt + ph / 2, mt + ph / 2));
        List<String> pais = new ArrayList<>(det.points().isEmpty() ? List.of() : det.points().get(0).apcer.keySet());
        for (int i = 0; i < pais.size(); i++) {
            String colour = COLOURS[i % COLOURS.length];
            StringBuilder pts = new StringBuilder();
            for (DetCurve.Point p : det.points()) {
                if (Double.isNaN(p.bpcer)) {
                    continue;
                }
                double x = ml + pw * (probit(clamp(p.apcer.get(pais.get(i)))) - lo) / (hi - lo);
                double y = mt + ph * (1 - (probit(clamp(p.bpcer)) - lo) / (hi - lo));
                pts.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, y));
            }
            s.append("<polyline fill=\"none\" stroke-width=\"2\" stroke=\"").append(colour).append("\" points=\"")
                    .append(pts).append("\"/>\n");
            s.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"%s\" stroke-width=\"2\"/>"
                    + "<text x=\"%.1f\" y=\"%d\" fill=\"#333\">%s</text>%n", ml + pw + 10, mt + 10 + 16 * i, ml + pw + 28,
                    mt + 10 + 16 * i, colour, ml + pw + 32, mt + 14 + 16 * i, esc(pais.get(i))));
        }
        return s.append("</svg>\n").toString();
    }

    private static double clamp(double p) {
        return Math.max(AXIS_MIN, Math.min(AXIS_MAX, p));
    }

    /**
     * The standard normal quantile, by Acklam's rational approximation (relative error below 1.2e-9), which is far
     * finer than a plot needs.
     */
    static double probit(double p) {
        final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02,
            -3.066479806614716e+01, 2.506628277459239e+00};
        final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01,
            -1.328068155288572e+01};
        final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00,
            4.374664141464968e+00, 2.938163982698783e+00};
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double pLow = 0.02425;
        if (p < pLow) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - pLow) {
            return -probit(1 - p);
        }
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    /** Just enough markdown for the report this harness writes: headings, pipe tables, bold, paragraphs. */
    static String markdownToHtml(String md) {
        StringBuilder out = new StringBuilder();
        List<String> para = new ArrayList<>();
        boolean inTable = false;
        boolean headerRow = false;
        for (String line : md.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("|")) {
                flush(out, para);
                if (!inTable) {
                    out.append("<table>\n");
                    inTable = true;
                    headerRow = true;
                }
                if (t.matches("\\|[\\s:|-]+\\|")) {
                    continue;
                }
                String[] cells = t.substring(1, t.endsWith("|") ? t.length() - 1 : t.length()).split("\\|", -1);
                out.append("<tr>");
                for (String cell : cells) {
                    String tag = headerRow ? "th" : "td";
                    String v = cell.trim();
                    boolean num = !headerRow && v.matches("[-+]?[0-9.,%\\[\\]\\s()–-]+");
                    out.append('<').append(tag).append(num ? " class=\"num\">" : ">").append(inline(v))
                            .append("</").append(tag).append('>');
                }
                out.append("</tr>\n");
                headerRow = false;
                continue;
            }
            if (inTable) {
                out.append("</table>\n");
                inTable = false;
            }
            if (t.startsWith("#")) {
                flush(out, para);
                int level = 0;
                while (level < t.length() && t.charAt(level) == '#') {
                    level++;
                }
                int hl = Math.min(6, level + 1); // the page's own title is the only h1
                out.append("<h").append(hl).append('>').append(inline(t.substring(level).trim())).append("</h").append(hl).append(">\n");
            } else if (t.isEmpty()) {
                flush(out, para);
            } else {
                para.add(t);
            }
        }
        if (inTable) {
            out.append("</table>\n");
        }
        flush(out, para);
        return out.toString();
    }

    private static void flush(StringBuilder out, List<String> para) {
        if (!para.isEmpty()) {
            out.append("<p>").append(inline(String.join(" ", para))).append("</p>\n");
            para.clear();
        }
    }

    private static String inline(String s) {
        return esc(s).replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>").replaceAll("`(.+?)`", "<code>$1</code>");
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
