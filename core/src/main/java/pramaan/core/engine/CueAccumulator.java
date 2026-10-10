/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pramaan.core.pad.CueResult;
import pramaan.core.passive.Logits;

/**
 * Per-frame cue scores to one vote per cue (Bible Part 7, temporal aggregation): the trimmed mean of logits,
 * dropping the top and bottom 10%, so one over-confident or blurred frame cannot carry the decision. Fusion
 * refuses two votes from the same cue, so this is the only way frame scores reach it.
 */
final class CueAccumulator {

    static final double TRIM = 0.1;

    private final Map<String, List<Double>> logits = new LinkedHashMap<>();
    private final Map<String, CueResult.AbstainReason> abstentions = new LinkedHashMap<>();

    void add(CueResult r) {
        if (r.abstained()) {
            abstentions.put(r.cue(), r.abstainReason());
        } else {
            addProbability(r.cue(), r.pLive());
        }
    }

    /** A raw model probability, which may be exactly 0 or 1; {@link Logits#logit} clamps it. */
    void addProbability(String cue, double pLive) {
        logits.computeIfAbsent(cue, k -> new ArrayList<>()).add(Logits.logit(pLive));
    }

    int votes(String cue) {
        List<Double> l = logits.get(cue);
        return l == null ? 0 : l.size();
    }

    /** One result per cue seen: a vote if any frame voted, otherwise the last reason it abstained. */
    List<CueResult> results() {
        List<CueResult> out = new ArrayList<>();
        logits.forEach((cue, l) -> {
            double mean = Logits.trimmedMean(l.stream().mapToDouble(Double::doubleValue).toArray(), TRIM);
            out.add(CueResult.vote(cue, Logits.sigmoid(mean), mean));
        });
        abstentions.forEach((cue, reason) -> {
            if (!logits.containsKey(cue)) {
                out.add(CueResult.abstain(cue, reason));
            }
        });
        return out;
    }

    void clear() {
        logits.clear();
        abstentions.clear();
    }
}
