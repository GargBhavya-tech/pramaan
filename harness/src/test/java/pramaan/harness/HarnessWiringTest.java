/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pramaan.core.EngineInfo;

/** Build Map #1 "done when": core is importable from the harness module. */
class HarnessWiringTest {

    @Test
    void harnessSeesCore() {
        assertEquals("pramaan", EngineInfo.NAME);
    }
}
