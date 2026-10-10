/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.evidence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.TreeSet;

/**
 * One byte-exact serialisation of an evidence record (Build Map #59), so a signature survives a round trip:
 * object keys in ascending order, no whitespace, integers as integers, fractions rounded half-even to six
 * decimals with trailing zeros stripped, NaN and infinities refused, strings UTF-8 with only the escapes
 * JSON requires. A payload is accepted only if re-serialising it gives the same bytes.
 */
public final class CanonicalJson {

    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private CanonicalJson() {
    }

    public static ObjectMapper mapper() {
        return JSON;
    }

    public static byte[] write(JsonNode node) {
        StringBuilder s = new StringBuilder();
        write(node, s);
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static JsonNode parse(byte[] json) throws IOException {
        return JSON.readTree(json);
    }

    /** True if these bytes are exactly the canonical form of what they encode. */
    public static boolean isCanonical(byte[] json) {
        try {
            return Arrays.equals(write(parse(json)), json);
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private static void write(JsonNode n, StringBuilder s) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            s.append("null");
        } else if (n.isObject()) {
            s.append('{');
            TreeSet<String> keys = new TreeSet<>();
            Iterator<String> it = n.fieldNames();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            boolean first = true;
            for (String k : keys) {
                if (!first) {
                    s.append(',');
                }
                first = false;
                quote(k, s);
                s.append(':');
                write(n.get(k), s);
            }
            s.append('}');
        } else if (n.isArray()) {
            s.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) {
                    s.append(',');
                }
                write(n.get(i), s);
            }
            s.append(']');
        } else if (n.isTextual()) {
            quote(n.textValue(), s);
        } else if (n.isBoolean()) {
            s.append(n.booleanValue());
        } else if (n.isIntegralNumber()) {
            s.append(n.bigIntegerValue());
        } else if (n.isNumber()) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("evidence numbers must be finite, got " + d);
            }
            BigDecimal b = new BigDecimal(d).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros();
            s.append(b.signum() == 0 ? "0" : b.toPlainString());
        } else {
            throw new IllegalArgumentException("unsupported JSON node " + n.getNodeType());
        }
    }

    private static void quote(String v, StringBuilder s) {
        s.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') {
                s.append('\\').append(c);
            } else if (c < 0x20) {
                s.append(String.format("\\u%04x", (int) c));
            } else {
                s.append(c);
            }
        }
        s.append('"');
    }
}
