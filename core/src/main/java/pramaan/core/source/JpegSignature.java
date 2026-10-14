/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The encoder's signature in a JPEG's headers: which quantisation tables, Huffman tables, sampling factors and markers
 * a frame carries, and in what order. A real camera's firmware writes the same headers on every frame; a virtual camera
 * that re-encodes the picture (OBS, a replayed file, a deepfake pipeline) writes its own encoder's, and the two almost
 * never agree. Read without decoding a single pixel, so it costs microseconds per frame.
 *
 * <p>The hash covers the marker order, every DQT and DHT table, the frame type, the component sampling and table
 * assignments, the restart interval, and the <em>names</em> of application and comment segments ({@code JFIF},
 * {@code Exif}); it leaves out the image size and the content of those segments, which legitimately change.
 *
 * <p>Honest limit: this is a fingerprint of the encoder, not of the camera. An attacker who learns the enrolled
 * signature and encodes with the same tables produces the same one. It catches the common case, a pipeline that
 * re-encodes with its own defaults, and it says nothing about a pipeline that does not re-encode.
 */
public final class JpegSignature {

    private final byte[] sha256;
    private final String summary;

    private JpegSignature(byte[] sha256, String summary) {
        this.sha256 = sha256;
        this.summary = summary;
    }

    /** SHA-256 of the canonical header description (32 bytes). */
    public byte[] sha256() {
        return sha256.clone();
    }

    public String hex() {
        StringBuilder sb = new StringBuilder();
        for (byte b : sha256) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Something a person can read: "baseline 4:2:0 q~75 4 DHT JFIF". */
    public String summary() {
        return summary;
    }

    /** @return the signature, or null if these bytes are not a JPEG this parser can read the headers of */
    public static JpegSignature of(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return null;
        }
        ByteArrayOutputStream canon = new ByteArrayOutputStream();
        List<String> apps = new ArrayList<>();
        String type = "?";
        String sampling = "?";
        int dht = 0;
        int quality = -1;
        int i = 2;
        boolean sawFrame = false;
        while (i + 3 < jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            if (marker == 0xD9) {
                break;
            }
            int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (len < 2 || i + 2 + len > jpeg.length) {
                return null;
            }
            int p = i + 4;
            int end = i + 2 + len;
            canon.write(marker);
            if (marker == 0xDB) { // quantisation tables
                while (p < end) {
                    int pq = (jpeg[p] & 0xFF) >> 4;
                    int tq = jpeg[p] & 0x0F;
                    int size = pq == 0 ? 64 : 128;
                    if (p + 1 + size > end) {
                        return null;
                    }
                    canon.write(0xDB);
                    canon.write(tq);
                    canon.write(pq);
                    canon.write(jpeg, p + 1, size);
                    if (tq == 0 && quality < 0 && pq == 0) {
                        quality = estimateQuality(jpeg, p + 1);
                    }
                    p += 1 + size;
                }
            } else if (marker == 0xC4) { // Huffman tables
                while (p + 17 <= end) {
                    int tcth = jpeg[p] & 0xFF;
                    int symbols = 0;
                    for (int k = 1; k <= 16; k++) {
                        symbols += jpeg[p + k] & 0xFF;
                    }
                    if (p + 17 + symbols > end) {
                        return null;
                    }
                    canon.write(0xC4);
                    canon.write(tcth);
                    canon.write(jpeg, p + 1, 16 + symbols);
                    dht++;
                    p += 17 + symbols;
                }
            } else if ((marker >= 0xC0 && marker <= 0xCF) && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (p + 6 > end) {
                    return null;
                }
                int comps = jpeg[p + 5] & 0xFF;
                if (p + 6 + 3 * comps > end) {
                    return null;
                }
                canon.write(jpeg[p] & 0xFF); // precision; size left out on purpose
                canon.write(comps);
                StringBuilder hv = new StringBuilder();
                for (int c = 0; c < comps; c++) {
                    int base = p + 6 + 3 * c;
                    canon.write(jpeg, base, 3); // id, sampling, table
                    hv.append(String.format("%x", (jpeg[base + 1] & 0xFF)));
                    hv.append(c + 1 < comps ? "/" : "");
                }
                type = marker == 0xC0 ? "baseline" : marker == 0xC1 ? "extended" : marker == 0xC2 ? "progressive" : "sof" + marker;
                sampling = samplingName(hv.toString());
                sawFrame = true;
            } else if (marker == 0xDD) { // restart interval
                canon.write(jpeg, p, Math.min(2, end - p));
            } else if (marker >= 0xE0 && marker <= 0xEF) { // APPn: the segment's name, not its content
                int q = p;
                StringBuilder name = new StringBuilder();
                while (q < end && jpeg[q] != 0 && name.length() < 16) {
                    name.append((char) (jpeg[q] & 0xFF));
                    q++;
                }
                byte[] nameBytes = name.toString().getBytes(StandardCharsets.ISO_8859_1);
                canon.write(nameBytes, 0, nameBytes.length);
                apps.add(name.toString().isEmpty() ? "APP" + (marker - 0xE0) : name.toString());
            } else if (marker == 0xDA) { // start of scan: everything we need has been seen
                break;
            }
            i += 2 + len;
        }
        if (!sawFrame) {
            return null;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canon.toByteArray());
            return new JpegSignature(hash, type + " " + sampling + (quality >= 0 ? " q~" + quality : "") + " " + dht + " DHT"
                    + (apps.isEmpty() ? "" : " " + String.join(",", apps)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String samplingName(String hv) {
        switch (hv) {
            case "22/11/11":
                return "4:2:0";
            case "21/11/11":
                return "4:2:2";
            case "11/11/11":
                return "4:4:4";
            case "11":
                return "gray";
            default:
                return hv;
        }
    }

    private static final int[] STANDARD_LUMA = {16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16,
        24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113,
        92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};

    /** The IJG quality (1-100) whose luminance table is nearest this one. Only for the human-readable summary. */
    private static int estimateQuality(byte[] jpeg, int tableStart) {
        double ratio = 0;
        for (int k = 0; k < 64; k++) {
            ratio += (jpeg[tableStart + k] & 0xFF) * 100.0 / STANDARD_LUMA[k];
        }
        ratio /= 64; // the scale factor, in percent, of the standard table
        double q = ratio <= 100 ? (200 - ratio) / 2 : 5000 / ratio;
        return (int) Math.max(1, Math.min(100, Math.round(q)));
    }
}
