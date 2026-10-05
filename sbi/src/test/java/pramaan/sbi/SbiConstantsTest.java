/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pramaan.core.EngineInfo;

class SbiConstantsTest {

    @Test
    void discoverySweepsTheWholeSpecifiedPortRange() {
        assertEquals(100, SbiConstants.portCount());
    }

    @Test
    void sbiModuleCanSeeCore() {
        assertEquals("pramaan", EngineInfo.NAME);
    }
}
