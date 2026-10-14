/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pramaan.core.source.CaptureRequest;
import pramaan.core.testing.TestClock;

/** Every scalar in an SBI request is a string (docs/sbi-notes.md: the reference device rejects numbers). */
class SbiRequestsTest {

    @Test
    void rcaptureSendsEveryScalarAsAStringAndTheNonceAsTransactionId() throws Exception {
        CaptureRequest request = new CaptureRequest("0123456789abcdef0123456789abcdef", "", 40, Duration.ofSeconds(10));
        byte[] body = SbiRequests.rcaptureFace(request, "3", "0", "Staging", "0.9.5", TestClock.at("2026-09-23T10:00:00.750Z"));
        JsonNode root = new ObjectMapper().readTree(body);

        assertEquals("0123456789abcdef0123456789abcdef", root.path("transactionId").asText());
        assertEquals("10000", root.path("timeout").asText());
        assertEquals("2026-09-23T10:00:00Z", root.path("captureTime").asText());
        assertEquals("1", root.path("bio").get(0).path("count").asText());
        assertEquals("40", root.path("bio").get(0).path("requestedScore").asText());

        List<String> numbers = new ArrayList<>();
        collectNumbers(root, "$", numbers);
        assertTrue(numbers.isEmpty(), "numeric JSON values found: " + numbers);
    }

    @Test
    void theProfileRequestsAlsoSendEveryScalarAsAString() throws Exception {
        JsonNode burst = new ObjectMapper().readTree(SbiRequests.captureBurst("0123456789abcdef0123456789abcdef", 3, 200));
        assertEquals("3", burst.path("count").asText());
        assertEquals("200", burst.path("intervalMs").asText());
        List<String> numbers = new ArrayList<>();
        collectNumbers(burst, "$", numbers);
        assertTrue(numbers.isEmpty(), "numeric JSON values found: " + numbers);
    }

    @Test
    void streamTimeoutIsAString() throws Exception {
        JsonNode root = new ObjectMapper().readTree(SbiRequests.stream("3", "0", 10_000));
        assertTrue(root.path("timeout").isTextual());
    }

    private static void collectNumbers(JsonNode node, String path, List<String> out) {
        if (node.isNumber()) {
            out.add(path);
        }
        if (node.isObject()) {
            for (java.util.Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                collectNumbers(e.getValue(), path + "." + e.getKey(), out);
            }
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectNumbers(node.get(i), path + "[" + i + "]", out);
            }
        }
    }
}
