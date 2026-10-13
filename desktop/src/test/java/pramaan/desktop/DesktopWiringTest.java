/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pramaan.core.EngineInfo;
import pramaan.sbi.SbiConstants;

/** Build Map #1 "done when": core and sbi are importable from the desktop module. */
class DesktopWiringTest {

    @Test
    void desktopSeesCoreAndSbi() {
        assertEquals("pramaan.evidence/1", EngineInfo.EVIDENCE_SCHEMA);
        assertEquals(4501, SbiConstants.FIRST_PORT);
    }
}
