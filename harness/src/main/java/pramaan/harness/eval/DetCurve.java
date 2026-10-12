/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * APCER per PAIS against BPCER as the accept threshold on fused P(live) sweeps (Bible Part 21.5; Build Map
 * #69). At threshold t an attack is accepted if P(live) >= t and a bona fide presentation rejected if
 * P(live) < t. Only sessions that reached a decision carry a score.
 */
public final class DetCurve {

    /** One threshold's error rates. */
    public static final class Point {
        public final double threshold;
        public final Map<String, Double> apcer;
        public final double bpcer;

        Point(double threshold, Map<String, Double> apcer, double bpcer) {
            this.threshold = threshold;
            this.apcer = apcer;
            this.bpcer = bpcer;
        }

        public double worstApcer() {
            return apcer.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        }
    }

    private final Map<String, List<Double>> attacks = new TreeMap<>();
    private final List<Double> bonaFide = new ArrayList<>();
    private final List<Point> points = new ArrayList<>();

    public DetCurve(List<SessionRow> rows) {
        TreeSet<Double> thresholds = new TreeSet<>();
        thresholds.add(0.0);
        thresholds.add(1.0 + 1e-9);
        for (SessionRow r : rows) {
            if (r.pLive() == null) {
                continue;
            }
            thresholds.add(r.pLive());
            if (r.label() == SessionRow.Label.ATTACK) {
                attacks.computeIfAbsent(r.pais(), x -> new ArrayList<>()).add(r.pLive());
            } else {
                bonaFide.add(r.pLive());
            }
        }
        for (double t : thresholds) {
            points.add(at(t));
        }
    }

    public Point at(double t) {
        Map<String, Double> ap = new TreeMap<>();
        attacks.forEach((p, s) -> ap.put(p, s.stream().filter(v -> v >= t).count() / (double) s.size()));
        double bp = bonaFide.isEmpty() ? Double.NaN : bonaFide.stream().filter(v -> v < t).count() / (double) bonaFide.size();
        return new Point(t, ap, bp);
    }

    public List<Point> points() {
        return points;
    }

    /** The lowest threshold whose measured worst-PAIS APCER is within {@code maxApcer}. */
    public Point lowestThresholdWithin(double maxApcer) {
        for (Point p : points) {
            if (p.worstApcer() <= maxApcer) {
                return p;
            }
        }
        return points.get(points.size() - 1);
    }

    /**
     * The lowest threshold at which, for every PAIS, the one-sided 95% upper bound on APCER is within
     * {@code maxApcer} — a threshold whose APCER is <em>demonstrated</em>, not merely measured. Null if the data
     * cannot demonstrate it at any threshold (too few attacks: 0 errors in n still leaves 1 - 0.05^(1/n)).
     */
    public Point lowestThresholdProven(double maxApcer) {
        for (Point p : points) {
            boolean ok = true;
            for (Map.Entry<String, List<Double>> e : attacks.entrySet()) {
                long k = e.getValue().stream().filter(v -> v >= p.threshold).count();
                if (Binomial.upperOneSided(k, e.getValue().size(), 0.95) > maxApcer) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return p;
            }
        }
        return null;
    }

    /** BPCER at the stated worst-PAIS APCER: BPCER20 = bpcerAt(0.05), BPCER100 = bpcerAt(0.01). */
    public double bpcerAt(double maxApcer) {
        return lowestThresholdWithin(maxApcer).bpcer;
    }

    public String toCsv() {
        StringBuilder s = new StringBuilder("threshold,bpcer");
        for (String p : attacks.keySet()) {
            s.append(",apcer_").append(p);
        }
        s.append('\n');
        for (Point p : points) {
            s.append(String.format("%.6f,%.6f", p.threshold, p.bpcer));
            for (String k : attacks.keySet()) {
                s.append(String.format(",%.6f", p.apcer.get(k)));
            }
            s.append('\n');
        }
        return s.toString();
    }
}
