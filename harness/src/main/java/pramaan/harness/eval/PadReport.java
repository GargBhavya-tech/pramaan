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

/**
 * ISO/IEC 30107-3 numbers (Bible Part 21.3; Build Map #68) at the decisions the engine actually made.
 *
 * <ul>
 *   <li>APCER per PAIS = attacks accepted / attacks that got a decision.</li>
 *   <li>BPCER = bona fide rejected / bona fide that got a decision.</li>
 *   <li>APNRR / BPNRR = no decision / all presentations — reported, never hidden.</li>
 *   <li>Headline APCER = the worst PAIS.</li>
 *   <li>ACER only as a labelled secondary figure: ISO/IEC 30107-3 deprecates averaging.</li>
 * </ul>
 * Every rate carries a two-sided 95% Clopper–Pearson interval; groups under 30 are flagged small-N.
 */
public final class PadReport {

    public static final double CONFIDENCE = 0.95;
    public static final int SMALL_N = 30;

    /** One PAIS's line. */
    public static final class PaisLine {
        public final String pais;
        public final int attackPotential;
        public final Binomial.Rate apcer;
        public final Binomial.Rate apnrr;
        public final double upperOneSided;

        PaisLine(String pais, int attackPotential, Binomial.Rate apcer, Binomial.Rate apnrr, double upperOneSided) {
            this.pais = pais;
            this.attackPotential = attackPotential;
            this.apcer = apcer;
            this.apnrr = apnrr;
            this.upperOneSided = upperOneSided;
        }
    }

    private final List<PaisLine> pais = new ArrayList<>();
    private final Binomial.Rate bpcer;
    private final Binomial.Rate bpnrr;

    private PadReport(List<PaisLine> lines, Binomial.Rate bpcer, Binomial.Rate bpnrr) {
        this.pais.addAll(lines);
        this.bpcer = bpcer;
        this.bpnrr = bpnrr;
    }

    public static PadReport compute(List<SessionRow> rows) {
        Map<String, long[]> byPais = new TreeMap<>();
        Map<String, Integer> level = new TreeMap<>();
        long bfTotal = 0;
        long bfResponded = 0;
        long bfRejected = 0;
        for (SessionRow r : rows) {
            if (r.label() == SessionRow.Label.ATTACK) {
                long[] c = byPais.computeIfAbsent(r.pais(), x -> new long[3]);
                c[0]++;
                if (r.responded()) {
                    c[1]++;
                    if (r.outcome() == SessionRow.Outcome.ACCEPTED) {
                        c[2]++;
                    }
                }
                level.merge(r.pais(), r.attackPotential(), Math::max);
            } else {
                bfTotal++;
                if (r.responded()) {
                    bfResponded++;
                    if (r.outcome() == SessionRow.Outcome.REJECTED) {
                        bfRejected++;
                    }
                }
            }
        }
        List<PaisLine> lines = new ArrayList<>();
        byPais.forEach((p, c) -> lines.add(new PaisLine(p, level.get(p), Binomial.clopperPearson(c[2], c[1], CONFIDENCE),
                Binomial.clopperPearson(c[0] - c[1], c[0], CONFIDENCE), Binomial.upperOneSided(c[2], c[1], CONFIDENCE))));
        return new PadReport(lines, Binomial.clopperPearson(bfRejected, bfResponded, CONFIDENCE),
                Binomial.clopperPearson(bfTotal - bfResponded, bfTotal, CONFIDENCE));
    }

    public List<PaisLine> pais() {
        return pais;
    }

    public Binomial.Rate bpcer() {
        return bpcer;
    }

    public Binomial.Rate bpnrr() {
        return bpnrr;
    }

    /** The worst PAIS: the headline APCER. */
    public PaisLine headline() {
        PaisLine worst = null;
        for (PaisLine l : pais) {
            if (l.apcer.n() > 0 && (worst == null || l.apcer.value() > worst.apcer.value()
                    || (l.apcer.value() == worst.apcer.value() && l.apcer.upper() > worst.apcer.upper()))) {
                worst = l;
            }
        }
        return worst;
    }

    /** (worst APCER + BPCER) / 2 — for comparison with literature only. */
    public double acerDeprecated() {
        PaisLine h = headline();
        return h == null ? Double.NaN : (h.apcer.value() + bpcer.value()) / 2;
    }

    public String toMarkdown() {
        StringBuilder s = new StringBuilder();
        s.append("## Presentation attack detection (ISO/IEC 30107-3)\n\n");
        s.append("| PAIS | Level | N decided | APCER [95% CI] | 95% one-sided upper | APNRR [95% CI] | Note |\n");
        s.append("|---|---|---|---|---|---|---|\n");
        for (PaisLine l : pais) {
            s.append(String.format("| %s | %s | %d | %s | %.2f%% | %s | %s |%n", l.pais, l.attackPotential == 0 ? "-" : "L" + l.attackPotential,
                    l.apcer.n(), l.apcer, 100 * l.upperOneSided, l.apnrr, l.apcer.n() < SMALL_N ? "small N" : ""));
        }
        PaisLine h = headline();
        s.append(String.format("%n**Headline APCER (worst PAIS%s):** %s%n", h == null ? "" : ": " + h.pais, h == null ? "n/a" : h.apcer));
        s.append(String.format("%n**BPCER:** %s   **BPNRR:** %s%s%n", bpcer, bpnrr, bpcer.n() < SMALL_N ? "  *(small N)*" : ""));
        s.append(String.format("%n*ACER (deprecated by ISO/IEC 30107-3; literature comparison only): %.2f%%*%n", 100 * acerDeprecated()));
        return s.toString();
    }
}
