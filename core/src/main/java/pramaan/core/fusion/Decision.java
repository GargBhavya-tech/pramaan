/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.fusion;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import pramaan.core.audit.AuditEvent;

/**
 * A fusion decision. Only {@link #zone()} and {@link #displayDelayMillis()} may reach the resident-facing
 * UI; tier, reasons and scores go to the audit log (Bible Part 9, anti-oracle: no score-dependent UI).
 */
public final class Decision {

    /** Why the zone is what it is. */
    public enum Reason {
        /** The score decided. */
        SCORE,
        /** A hard veto rejected without fusion. */
        VETO,
        /** Fewer than minCues contributed, so a would-be accept or reject became a challenge. */
        TOO_FEW_CUES,
        /** Contributing cues disagreed by more than the table's gap, so an accept became a challenge. */
        DISAGREEMENT,
        /** A random audit challenge in WHITE. */
        AUDIT
    }

    private final Zone zone;
    private final Tier tier;
    private final Set<Reason> reasons;
    private final Set<Veto> vetoes;
    private final FusionResult fusion;
    private final String operatingPoint;
    private final long displayDelayMillis;

    Decision(Zone zone, Tier tier, Set<Reason> reasons, Set<Veto> vetoes, FusionResult fusion, String operatingPoint,
            long displayDelayMillis) {
        this.zone = zone;
        this.tier = tier;
        this.reasons = Collections.unmodifiableSet(EnumSet.copyOf(reasons));
        this.vetoes = vetoes.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(vetoes));
        this.fusion = fusion;
        this.operatingPoint = operatingPoint;
        this.displayDelayMillis = displayDelayMillis;
    }

    public Zone zone() {
        return zone;
    }

    public Tier tier() {
        return tier;
    }

    public Set<Reason> reasons() {
        return reasons;
    }

    public Set<Veto> vetoes() {
        return vetoes;
    }

    /** Null when a veto decided without fusion. */
    public FusionResult fusion() {
        return fusion;
    }

    public String operatingPoint() {
        return operatingPoint;
    }

    /** How long the caller must wait before showing this verdict (timing jitter). */
    public long displayDelayMillis() {
        return displayDelayMillis;
    }

    public AuditEvent toAuditEvent(String sessionId) {
        AuditEvent.Builder b = AuditEvent.of("FUSION_DECISION").put("sessionId", sessionId)
                .put("zone", zone.name()).put("tier", tier.name()).put("operatingPoint", operatingPoint)
                .put("reasons", reasons.stream().map(Enum::name).collect(Collectors.joining(",")))
                .put("delayMs", displayDelayMillis);
        if (!vetoes.isEmpty()) {
            b.put("vetoes", vetoes.stream().map(Enum::name).collect(Collectors.joining(",")));
        }
        if (fusion != null) {
            b.put("logOdds", fusion.logOdds()).put("pLive", fusion.pLive()).put("contributing", fusion.contributing())
                    .put("logitGap", fusion.logitGap());
        }
        return b.build();
    }
}
