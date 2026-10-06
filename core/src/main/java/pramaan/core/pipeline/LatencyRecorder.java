/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-stage latency samples with p50/p95 (Build Map #28): budgets in the Bible become measurements. */
public final class LatencyRecorder {

    private final Map<String, List<Long>> samples = new LinkedHashMap<>();

    public synchronized void record(String stage, long nanos) {
        samples.computeIfAbsent(stage, k -> new ArrayList<>()).add(nanos);
    }

    /** Percentile in milliseconds, or NaN if the stage never ran. */
    public synchronized double percentileMillis(String stage, double pct) {
        List<Long> s = samples.get(stage);
        if (s == null || s.isEmpty()) {
            return Double.NaN;
        }
        List<Long> sorted = new ArrayList<>(s);
        Collections.sort(sorted);
        int idx = (int) Math.min(sorted.size() - 1, Math.max(0, Math.ceil(pct / 100.0 * sorted.size()) - 1));
        return sorted.get(idx) / 1e6;
    }

    public synchronized int count(String stage) {
        List<Long> s = samples.get(stage);
        return s == null ? 0 : s.size();
    }

    public synchronized List<String> stages() {
        return new ArrayList<>(samples.keySet());
    }
}
