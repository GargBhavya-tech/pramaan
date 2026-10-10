/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import pramaan.core.source.SignedCapture;

/**
 * The SFace embedding of the face in a signed capture, for the identity lock (Bible Part 13, lock 3).
 * Implementations verify the capture's signature before they decode it: pixels are read only from what the
 * device signed. The binder verifies again on its own; this is not where trust is decided.
 */
public interface CaptureAnalyser {

    /** @return the embedding, or null when the capture does not hold exactly one face */
    float[] embed(SignedCapture capture) throws Exception;
}
