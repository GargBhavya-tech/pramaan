/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.quality;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-component scores in [0, 1] where 0.5 is exactly at the limit, and the overall
 * Q = min(components) — the minimum, so one terrible component is never hidden by good ones.
 */
public final class QualityReport {

    public static final double PASS = 0.5;

    private final Map<QualityComponent, Double> scores;

    QualityReport(Map<QualityComponent, Double> scores) {
        this.scores = Collections.unmodifiableMap(new EnumMap<>(scores));
    }

    public Map<QualityComponent, Double> scores() {
        return scores;
    }

    public double q() {
        double q = 1.0;
        for (double s : scores.values()) {
            q = Math.min(q, s);
        }
        return q;
    }

    public boolean passes() {
        return q() >= PASS;
    }

    /** The component that sets Q. */
    public QualityComponent worst() {
        QualityComponent worst = null;
        for (Map.Entry<QualityComponent, Double> e : scores.entrySet()) {
            if (worst == null || e.getValue() < scores.get(worst)) {
                worst = e.getKey();
            }
        }
        return worst;
    }
}
