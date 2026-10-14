/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sim-to-real gap report (Bible Part 45, "Validating realism"; produced per release): for each cue, a two-sample KS
 * comparison of its statistic on synthetic attacks against the same statistic on real ones. Synthetic results are
 * always labelled as such; ISO-style headline numbers use real presentations only.
 */
public final class SimToRealReport {

    /** Significance below which the simulator is said not to match the cue. */
    public static final double ALPHA = 0.05;

    /** One cue's comparison. */
    public static final class Row {
        public final String cue;
        public final int synthetic;
        public final int real;
        public final double d;
        public final double p;

        Row(String cue, int synthetic, int real, double d, double p) {
            this.cue = cue;
            this.synthetic = synthetic;
            this.real = real;
            this.d = d;
            this.p = p;
        }

        /** True when the two samples cannot be told apart at {@link #ALPHA}. */
        public boolean matched() {
            return p >= ALPHA;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    public SimToRealReport add(String cue, double[] synthetic, double[] real) {
        double d = TwoSampleKs.statistic(synthetic, real);
        rows.add(new Row(cue, synthetic.length, real.length, d, TwoSampleKs.pValue(d, synthetic.length, real.length)));
        return this;
    }

    public List<Row> rows() {
        return rows;
    }

    /** The fraction of cues the simulator matched. */
    public double matchedFraction() {
        if (rows.isEmpty()) {
            return Double.NaN;
        }
        int ok = 0;
        for (Row r : rows) {
            ok += r.matched() ? 1 : 0;
        }
        return (double) ok / rows.size();
    }

    public String markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Sim-to-real gap (SYNTHETIC attacks vs real attacks)\n\n");
        sb.append("| Cue | n synthetic | n real | KS D | p | Verdict |\n|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %.3f | %.3f | %s |%n", r.cue, r.synthetic, r.real,
                    r.d, r.p, r.matched() ? "matched" : "DIFFERS - tune the simulator"));
        }
        sb.append("\nSynthetic results stretch training and stress tests; headline APCER uses real presentations only.\n");
        return sb.toString();
    }
}
