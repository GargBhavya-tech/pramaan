/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.util.List;
import pramaan.core.fusion.Decision;
import pramaan.core.pad.CueResult;
import pramaan.core.source.SessionCapabilities;

/**
 * What diagnostic mode shows an administrator troubleshooting a centre (Build Map #78; Bible Part 19.3): per-cue
 * scores, decisions, capabilities. It is a separate channel from {@link EngineListener} on purpose — the
 * resident's screen never receives it — and an engine accepts one only with a {@link DiagnosticGrant}, which
 * exists only when the policy allows diagnostics and whose activation is audited.
 */
public interface DiagnosticSink {

    /** The session's measured capabilities, once, when the engine is built. */
    default void capabilities(SessionCapabilities caps) {
    }

    /** One analysed frame, as perception reported it. */
    default void frame(long arrivalMillis, FrameObservation observation) {
    }

    /**
     * A fusion decision and the cue results it was made from.
     *
     * @param phase {@code "passive"} or {@code "after-challenge"}
     */
    default void decision(String phase, List<CueResult> cues, Decision decision) {
    }
}
