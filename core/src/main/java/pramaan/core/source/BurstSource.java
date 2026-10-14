/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.time.Duration;
import java.util.List;

/**
 * Several signed frames in quick succession (SBI Liveness Profile, addition 4; Bible Part 41). Each is verified like a
 * capture before it is returned, so a frame that is unsigned, for another transaction, or the wrong number never
 * reaches the caller.
 */
public interface BurstSource {

    /** @throws DeviceException if the device refuses, fails, or returns anything that does not verify */
    List<SignedCapture> captureBurst(int count, Duration interval) throws DeviceException;
}
