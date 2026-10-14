/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.util.List;
import pramaan.core.pad.CueResult;
import pramaan.core.source.Frame;

/**
 * A stretch of an attempt that runs after the resident's action steps and before the post-challenge decision, fed by
 * the same frames: the flash sequence (Bible Part 8.6) and the pupil reflex (Part 32). Each asks nothing of the
 * resident, produces cues, and must leave nothing running when it ends or is aborted.
 */
interface AttemptPhase {

    /**
     * Gives the phase one frame.
     *
     * @return true while the phase still needs more frames; false once it has finished and its results are ready
     */
    boolean advance(Frame frame, FrameObservation observation);

    /** The cues this phase produced (votes or abstentions); meaningful once {@link #advance} has returned false. */
    List<CueResult> results();

    /** Stops whatever the phase started (turns the light off). Safe to call at any time, more than once. */
    void abort();
}
