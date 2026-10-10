/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import pramaan.core.source.Frame;

/**
 * Perception for one frame: decode, detect, gate, track, quality, passive model, moiré, landmarks, SFace.
 * Stateful (the gate and tracker remember earlier frames), so one analyser serves one session.
 *
 * <p>An undecodable frame is reported as {@link FrameObservation#undecodable()}, not thrown. Anything thrown
 * is an engine error, and the engine fails the attempt closed.
 */
public interface FrameAnalyser {

    FrameObservation analyse(Frame frame) throws Exception;
}
