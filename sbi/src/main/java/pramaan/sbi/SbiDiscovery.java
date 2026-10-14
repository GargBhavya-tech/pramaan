/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import pramaan.core.source.DeviceException;
import pramaan.core.source.DeviceLevel;

/**
 * Sweeps the SBI port range with MOSIPDISC (Build Map #12). Ports are probed in parallel with a short
 * timeout, because a full sequential sweep of 100 ports would take the operator's patience with it.
 */
final class SbiDiscovery {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SbiDiscovery() {
    }

    static List<DiscoveredDevice> discover(SbiHttp http, int firstPort, int lastPort, Duration perPortTimeout)
            throws DeviceException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(16, lastPort - firstPort + 1), r -> {
            Thread t = new Thread(r, "sbi-discovery");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<List<DiscoveredDevice>>> probes = new ArrayList<>();
            for (int port = firstPort; port <= lastPort; port++) {
                final int p = port;
                probes.add(CompletableFuture.supplyAsync(() -> probe(http, p, perPortTimeout), pool));
            }
            List<DiscoveredDevice> found = new ArrayList<>();
            for (CompletableFuture<List<DiscoveredDevice>> probe : probes) {
                found.addAll(probe.join());
            }
            return found;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<DiscoveredDevice> probe(SbiHttp http, int port, Duration timeout) {
        try {
            byte[] body = http.call(port, "MOSIPDISC", "/device", SbiRequests.discovery(), timeout);
            return parse(port, body);
        } catch (DeviceException | IOException e) {
            return new ArrayList<>();
        }
    }

    static List<DiscoveredDevice> parse(int port, byte[] body) throws IOException {
        JsonNode root = JSON.readTree(body);
        List<DiscoveredDevice> out = new ArrayList<>();
        if (!root.isArray()) {
            return out;
        }
        for (JsonNode d : root) {
            JsonNode digital = digitalId(d.path("digitalId").asText(""));
            List<String> subIds = new ArrayList<>();
            for (JsonNode s : d.path("deviceSubId")) {
                subIds.add(s.asText());
            }
            List<String> specs = new ArrayList<>();
            for (JsonNode s : d.path("specVersion")) {
                specs.add(s.asText());
            }
            out.add(new DiscoveredDevice(port, d.path("deviceId").asText(""), subIds, d.path("deviceStatus").asText(""),
                    d.path("purpose").asText(""), DeviceLevel.fromSbi(d.path("certification").asText("")),
                    digital.path("type").asText(""), d.path("deviceCode").asText(""), digital.path("make").asText(""),
                    digital.path("model").asText(""), digital.path("serialNo").asText(""), specs,
                    d.path("livenessProfile")));
        }
        return out;
    }

    /**
     * Before registration a digitalId is base64 JSON; after, a signed JWT whose payload holds the same
     * JSON. Either way, read it for description only — trust comes from the rcapture signature.
     */
    private static JsonNode digitalId(String raw) {
        try {
            String json = raw.contains(".") ? raw.split("\\.")[1] : raw;
            byte[] decoded = Base64.getUrlDecoder().decode(json.replace('+', '-').replace('/', '_').replace("=", ""));
            return JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return JSON.createObjectNode();
        }
    }
}
