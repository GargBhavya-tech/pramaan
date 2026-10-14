/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import pramaan.core.binding.EmissionLog;
import pramaan.core.challenge.FlashSequence;

/**
 * An illuminator the SBI device itself drives (SBI Liveness Profile, addition 5; Bible Part 41): the client sends a
 * random colour schedule and gets back a device-signed log of what the light actually showed and when. Unlike a
 * {@link FlashLight}, the client does not have to trust its own display for the timing.
 */
public interface DeviceIlluminator {

    /**
     * Sends {@code sequence} to the device and returns at once; the future completes, after the schedule has finished
     * (a few seconds), with the verified emission log, or fails with a {@link DeviceException} if the device refuses
     * the schedule, fails, or signs a log that does not verify. The request is started before this returns, so a caller
     * that notes the time on either side of the call knows when the schedule began to within the request's latency.
     */
    java.util.concurrent.CompletableFuture<EmissionLog> illuminate(FlashSequence sequence);
}
