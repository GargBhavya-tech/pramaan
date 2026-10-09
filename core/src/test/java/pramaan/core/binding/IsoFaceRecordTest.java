/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The mock devices' face record: what {@link IsoFaceRecord#encode} writes, {@link IsoFaceRecord#parse} reads back. */
class IsoFaceRecordTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, 5, (byte) 0xFF, (byte) 0xD9};

    @Test
    void anEncodedRecordParsesBackToTheSameImage() throws IOException {
        byte[] iso = IsoFaceRecord.encode(IsoFaceRecord.ImageFormat.JPEG, JPEG, 640, 480);
        assertEquals("FAC\u0000030\u0000", new String(iso, 0, 8, StandardCharsets.US_ASCII));
        assertEquals(iso.length, ((iso[8] & 0xFF) << 24) | ((iso[9] & 0xFF) << 16) | ((iso[10] & 0xFF) << 8) | (iso[11] & 0xFF),
                "the record length field covers the whole record");
        IsoFaceRecord r = IsoFaceRecord.parse(iso);
        assertEquals(IsoFaceRecord.ImageFormat.JPEG, r.format());
        assertArrayEquals(JPEG, r.image());
    }

    @Test
    void aBareJpegIsNotAFaceRecord() {
        assertThrows(IOException.class, () -> IsoFaceRecord.parse(JPEG));
    }
}
