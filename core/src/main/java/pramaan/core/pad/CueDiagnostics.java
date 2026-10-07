/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import pramaan.core.audit.AuditEvent;

/**
 * Records every cue outcome in a session — raw value, probability or abstain reason — so any decision can
 * be explained afterwards without re-running it (Build Map #34). Goes to the audit log and the harness;
 * never to the resident-facing UI (Bible Part 19). Holds numbers only, never pixels.
 */
public final class CueDiagnostics {

    /** Timing unknown (the cue abstained before running, or was not timed). */
    public static final long UNTIMED = -1;

    private final String sessionId;
    private final List<CueResult> results = new ArrayList<>();
    private final List<Long> micros = new ArrayList<>();

    public CueDiagnostics(String sessionId) {
        this.sessionId = sessionId;
    }

    public synchronized void record(CueResult result) {
        record(result, UNTIMED);
    }

    /** @param elapsedMicros how long the cue took, measured by the caller's injected clock, or {@link #UNTIMED} */
    public synchronized void record(CueResult result, long elapsedMicros) {
        results.add(result);
        micros.add(elapsedMicros);
    }

    public synchronized List<CueResult> results() {
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    /** One audit event per recorded outcome. */
    public synchronized List<AuditEvent> toAuditEvents() {
        List<AuditEvent> out = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            CueResult r = results.get(i);
            AuditEvent.Builder b = AuditEvent.of("CUE_RESULT").put("sessionId", sessionId).put("cue", r.cue());
            if (micros.get(i) != UNTIMED) {
                b.put("micros", micros.get(i));
            }
            if (r.abstained()) {
                b.put("abstained", true).put("reason", r.abstainReason().name());
            } else {
                b.put("abstained", false).put("pLive", r.pLive()).put("raw", r.rawValue());
            }
            out.add(b.build());
        }
        return out;
    }
}
