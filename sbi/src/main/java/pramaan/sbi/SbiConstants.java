/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

/** Fixed facts of the MOSIP SBI transport (MDS specification 1.2.0; Bible Part 1.2). */
public final class SbiConstants {

    /** Device services listen on the loopback interface only. */
    public static final String HOST = "127.0.0.1";

    /** First port a device service may listen on. Discovery sweeps the whole range. */
    public static final int FIRST_PORT = 4501;

    /** Last port (inclusive) of the device-service range. */
    public static final int LAST_PORT = 4600;

    /** Minimum stream rate the specification guarantees; the engine must work at this rate. */
    public static final int GUARANTEED_MIN_FPS = 3;

    private SbiConstants() {
    }

    /** Number of ports discovery has to probe. */
    public static int portCount() {
        return LAST_PORT - FIRST_PORT + 1;
    }
}
