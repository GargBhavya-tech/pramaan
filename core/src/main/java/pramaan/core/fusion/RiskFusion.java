/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.fusion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import pramaan.core.Workflow;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.CueWeight;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.source.DeviceLevel;

/**
 * Log-odds fusion with asymmetric weights (Bible Part 9; Build Map #35, #40):
 * {@code L = b(level) + sum over voting cues of w * logit(p)}, where w is {@code wPos} for evidence of
 * life and {@code wNeg} for evidence of attack.
 *
 * <p>Two rules are enforced here, not left to configuration:
 * <ul>
 *   <li>An abstaining cue contributes nothing — it is not in the sum at all.</li>
 *   <li>For residents, a physiological cue's negative weight is zero. A missing blink, pulse or reflex
 *       can only fail to add evidence; it can never count against the person, and it cannot trigger the
 *       disagreement rule either.</li>
 * </ul>
 * A voting cue with no weight in the table is a configuration error, and fails closed.
 */
public final class RiskFusion {

    private final OperatingPointTable table;

    public RiskFusion(OperatingPointTable table) {
        this.table = Objects.requireNonNull(table, "table");
    }

    public FusionResult fuse(List<CueResult> results, Workflow workflow, DeviceLevel level) {
        double logOdds = table.bias(level);
        Map<String, Double> contributions = new LinkedHashMap<>();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CueResult r : results) {
            if (r.abstained()) {
                continue;
            }
            if (!seen.add(r.cue())) {
                throw new IllegalStateException("cue '" + r.cue() + "' voted twice in one decision");
            }
            CueWeight w = table.weight(r.cue()).orElseThrow(() -> new IllegalStateException(
                    "cue '" + r.cue() + "' voted but the operating-point table has no weight for it"));
            double logit = Math.log(r.pLive() / (1 - r.pLive()));
            double weight = logit >= 0 ? w.wPos() : effectiveNegativeWeight(w, workflow);
            if (weight == 0) {
                continue;
            }
            double term = weight * logit;
            logOdds += term;
            contributions.put(r.cue(), term);
            min = Math.min(min, logit);
            max = Math.max(max, logit);
        }
        // Disagreement means conflict: one cue says live, another says attack. Two cues that both say live
        // with different confidence are not in conflict, so a weak positive reflex cannot widen the gap.
        double gap = max > 0 && min < 0 ? max - min : 0;
        return new FusionResult(logOdds, contributions.size(), gap, contributions);
    }

    static double effectiveNegativeWeight(CueWeight w, Workflow workflow) {
        return w.physiological() && workflow == Workflow.RESIDENT ? 0 : w.wNeg();
    }
}
