/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * BPCER and BPNRR per demographic group (Bible Part 16.2; Build Map #71), with intervals, a max/min
 * differential against the 1.5 target, and small-N warnings. The attributes are corpus annotations (a
 * documented skin-tone scale, age band, eyewear, lighting) — they are never inferred at runtime.
 */
public final class FairnessReport {

    public static final double DIFFERENTIAL_TARGET = 1.5;

    /** One group's numbers. */
    public static final class Group {
        public final String attribute;
        public final String value;
        public final Binomial.Rate bpcer;
        public final Binomial.Rate bpnrr;

        Group(String attribute, String value, Binomial.Rate bpcer, Binomial.Rate bpnrr) {
            this.attribute = attribute;
            this.value = value;
            this.bpcer = bpcer;
            this.bpnrr = bpnrr;
        }

        public boolean smallN() {
            return bpcer.n() < PadReport.SMALL_N;
        }
    }

    private final Map<String, Map<String, Group>> groups = new TreeMap<>();

    private FairnessReport() {
    }

    public static FairnessReport compute(List<SessionRow> rows) {
        TreeSet<String> attributes = new TreeSet<>();
        for (SessionRow r : rows) {
            if (r.label() == SessionRow.Label.BONA_FIDE) {
                attributes.addAll(r.attributes().keySet());
            }
        }
        FairnessReport f = new FairnessReport();
        for (String a : attributes) {
            Map<String, long[]> c = new TreeMap<>();
            for (SessionRow r : rows) {
                String v = r.attributes().get(a);
                if (r.label() != SessionRow.Label.BONA_FIDE || v == null) {
                    continue;
                }
                long[] x = c.computeIfAbsent(v, k -> new long[3]);
                x[0]++;
                if (r.responded()) {
                    x[1]++;
                    if (r.outcome() == SessionRow.Outcome.REJECTED) {
                        x[2]++;
                    }
                }
            }
            Map<String, Group> m = new TreeMap<>();
            c.forEach((v, x) -> m.put(v, new Group(a, v, Binomial.clopperPearson(x[2], x[1], PadReport.CONFIDENCE),
                    Binomial.clopperPearson(x[0] - x[1], x[0], PadReport.CONFIDENCE))));
            f.groups.put(a, m);
        }
        return f;
    }

    public Map<String, Map<String, Group>> groups() {
        return groups;
    }

    /** max/min BPCER across the attribute's groups; NaN when the best group has no errors (undefined, not "perfect"). */
    public double differential(String attribute) {
        double max = 0;
        double min = Double.POSITIVE_INFINITY;
        for (Group g : groups.get(attribute).values()) {
            if (g.bpcer.n() == 0) {
                continue;
            }
            max = Math.max(max, g.bpcer.value());
            min = Math.min(min, g.bpcer.value());
        }
        return min == 0 || Double.isInfinite(min) ? Double.NaN : max / min;
    }

    public String toMarkdown() {
        StringBuilder s = new StringBuilder("## Fairness (bona fide presentations)\n\n");
        groups.forEach((a, m) -> {
            s.append("### ").append(a).append("\n\n| Group | BPCER [95% CI] | BPNRR [95% CI] | Note |\n|---|---|---|---|\n");
            m.values().forEach(g -> s.append(String.format("| %s | %s | %s | %s |%n", g.value, g.bpcer, g.bpnrr,
                    g.smallN() ? "small N: interval too wide to rank" : "")));
            double d = differential(a);
            s.append(Double.isNaN(d)
                    ? "\nDifferential: undefined (a group has no errors or no data); compare the intervals instead.\n\n"
                    : String.format("%nDifferential max/min BPCER: **%.2f** (target <= %.1f)%s%n%n", d, DIFFERENTIAL_TARGET,
                            d > DIFFERENTIAL_TARGET ? " - **over target**" : ""));
        });
        return s.toString();
    }
}
