/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import pramaan.core.Workflow;
import pramaan.core.fusion.Decision;
import pramaan.core.fusion.FusionResult;
import pramaan.core.fusion.RiskFusion;
import pramaan.core.fusion.Veto;
import pramaan.core.fusion.Zone;
import pramaan.core.fusion.ZoneDecider;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.LivenessPolicy;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.source.DeviceLevel;

/**
 * Per-cue ablation (Bible Part 21.5; Build Map #70): re-fuse every session's recorded cue values through the
 * production fusion and zone decision with one cue removed at a time, and report APCER per PAIS. It measures
 * the first, passive decision only — a session the full system sent to a challenge counts as not accepted —
 * and it is the evidence for "the holes don't overlap": if one cue carries a PAIS alone, this table shows it.
 */
public final class CueAblation {

    /** APCER per PAIS with one cue removed ("(none)" = the full system). */
    public static final class Result {
        public final Map<String, Map<String, Binomial.Rate>> apcerByRemovedCue;
        public final Map<String, Binomial.Rate> bpcerByRemovedCue;

        Result(Map<String, Map<String, Binomial.Rate>> a, Map<String, Binomial.Rate> b) {
            this.apcerByRemovedCue = a;
            this.bpcerByRemovedCue = b;
        }

        public String toMarkdown() {
            TreeSet<String> pais = new TreeSet<>();
            apcerByRemovedCue.values().forEach(m -> pais.addAll(m.keySet()));
            StringBuilder s = new StringBuilder("## Per-cue ablation (APCER at the passive decision)\n\n| Cue removed |");
            for (String p : pais) {
                s.append(' ').append(p).append(" |");
            }
            s.append(" BPCER |\n|---|");
            for (int i = 0; i <= pais.size(); i++) {
                s.append("---|");
            }
            s.append('\n');
            apcerByRemovedCue.forEach((cue, m) -> {
                s.append("| ").append(cue).append(" |");
                for (String p : pais) {
                    Binomial.Rate r = m.get(p);
                    s.append(String.format(" %.1f%% |", r == null ? Double.NaN : 100 * r.value()));
                }
                s.append(String.format(" %.1f%% |%n", 100 * bpcerByRemovedCue.get(cue).value()));
            });
            return s.toString();
        }
    }

    private CueAblation() {
    }

    public static Result run(List<SessionRow> rows, OperatingPointTable table, LivenessPolicy policy) {
        TreeSet<String> cues = new TreeSet<>();
        for (SessionRow r : rows) {
            cues.addAll(r.cues().keySet());
        }
        List<String> variants = new ArrayList<>();
        variants.add("(none)");
        variants.addAll(cues);
        Map<String, Map<String, Binomial.Rate>> apcer = new LinkedHashMap<>();
        Map<String, Binomial.Rate> bpcer = new LinkedHashMap<>();
        RiskFusion fusion = new RiskFusion(table);
        ZoneDecider decider = new ZoneDecider(() -> 0.999, table.disagreementLogitGap());
        for (String removed : variants) {
            Map<String, long[]> counts = new TreeMap<>();
            long bfN = 0;
            long bfRejected = 0;
            for (SessionRow r : rows) {
                if (!r.responded()) {
                    continue;
                }
                List<CueResult> results = new ArrayList<>();
                r.cues().forEach((name, p) -> {
                    if (!name.equals(removed)) {
                        results.add(p == null ? CueResult.abstain(name, CueResult.AbstainReason.NOT_APPLICABLE)
                                : CueResult.vote(name, Math.max(1e-6, Math.min(1 - 1e-6, p)), Double.NaN));
                    }
                });
                Workflow w = Workflow.valueOf(r.workflow());
                FusionResult f = fusion.fuse(results, w, DeviceLevel.valueOf(r.deviceLevel()));
                Decision d = decider.decide(f, table.operatingPoint(policy.profile(w).operatingPoint(), "default"),
                        policy.profile(w), EnumSet.noneOf(Veto.class));
                if (r.label() == SessionRow.Label.ATTACK) {
                    long[] c = counts.computeIfAbsent(r.pais(), x -> new long[2]);
                    c[0]++;
                    if (d.zone() == Zone.ACCEPT_CANDIDATE) {
                        c[1]++;
                    }
                } else {
                    bfN++;
                    if (d.zone() == Zone.REJECT_PAD) {
                        bfRejected++;
                    }
                }
            }
            Map<String, Binomial.Rate> m = new TreeMap<>();
            counts.forEach((p, c) -> m.put(p, Binomial.clopperPearson(c[1], c[0], PadReport.CONFIDENCE)));
            apcer.put(removed, m);
            bpcer.put(removed, Binomial.clopperPearson(bfRejected, bfN, PadReport.CONFIDENCE));
        }
        return new Result(apcer, bpcer);
    }
}
