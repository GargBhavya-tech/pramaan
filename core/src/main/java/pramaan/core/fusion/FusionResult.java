/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.fusion;

import java.util.Collections;
import java.util.Map;

/** The fused evidence before any zone is chosen. */
public final class FusionResult {

    private final double logOdds;
    private final int contributing;
    private final double logitGap;
    private final Map<String, Double> contributions;

    FusionResult(double logOdds, int contributing, double logitGap, Map<String, Double> contributions) {
        this.logOdds = logOdds;
        this.contributing = contributing;
        this.logitGap = logitGap;
        this.contributions = Collections.unmodifiableMap(contributions);
    }

    /** L = b + sum of weighted logits. */
    public double logOdds() {
        return logOdds;
    }

    /** P(live) = sigmoid(L). */
    public double pLive() {
        return 1 / (1 + Math.exp(-logOdds));
    }

    /** Cues whose evidence carried a non-zero weight. Abstentions and zero-weighted evidence do not count. */
    public int contributing() {
        return contributing;
    }

    /**
     * Max minus min raw logit among contributing cues when they point opposite ways (some live, some
     * attack); 0 when they agree in sign.
     */
    public double logitGap() {
        return logitGap;
    }

    /** Each contributing cue's term in L, for diagnostics. */
    public Map<String, Double> contributions() {
        return contributions;
    }
}
