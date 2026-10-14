/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The JPEG header signatures this station's camera was seen to produce when it was enrolled (Bible Part 5): an
 * installation step like the backdrop, repeated after a firmware update or a camera swap. A live frame whose
 * {@link JpegSignature} is not in the set was not written by the encoder that was enrolled.
 *
 * <p>File format (JSON, written by {@code StreamSignatureTool}):
 * <pre>{"version":1,"device":"Acme Cam1","enrolledAt":"2026-10-14T10:00:00Z",
 *  "signatures":[{"sha256":"ab12...","summary":"baseline 4:2:0 q~75 4 DHT JFIF"}]}</pre>
 */
public final class StreamSignatureProfile {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SIGNATURES = 8;

    private final String device;
    private final Instant enrolledAt;
    private final Map<String, String> signatures; // sha256 hex -> summary

    private StreamSignatureProfile(String device, Instant enrolledAt, Map<String, String> signatures) {
        this.device = device;
        this.enrolledAt = enrolledAt;
        this.signatures = Collections.unmodifiableMap(signatures);
    }

    /**
     * Enrols the signatures of the given frames. More than {@value #MAX_SIGNATURES} distinct ones means the camera does
     * not write a stable header, and a fingerprint would be meaningless.
     *
     * @throws IllegalArgumentException if no frame is a readable JPEG, or the encoder is not stable
     */
    public static StreamSignatureProfile enrol(String device, Instant at, Iterable<byte[]> frames) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (byte[] f : frames) {
            JpegSignature s = JpegSignature.of(f);
            if (s != null) {
                seen.putIfAbsent(s.hex(), s.summary());
            }
        }
        if (seen.isEmpty()) {
            throw new IllegalArgumentException("none of the frames is a JPEG whose headers can be read");
        }
        if (seen.size() > MAX_SIGNATURES) {
            throw new IllegalArgumentException(seen.size() + " different encoder signatures in one enrolment: this camera"
                    + " does not write stable headers, so a signature cannot tell it from anything else");
        }
        return new StreamSignatureProfile(Objects.requireNonNull(device, "device"), Objects.requireNonNull(at, "at"), seen);
    }

    public String device() {
        return device;
    }

    public Instant enrolledAt() {
        return enrolledAt;
    }

    /** Enrolled signatures, hex to readable summary. */
    public Map<String, String> signatures() {
        return signatures;
    }

    public boolean contains(JpegSignature s) {
        return signatures.containsKey(s.hex());
    }

    public String toJson() {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        root.put("device", device);
        root.put("enrolledAt", enrolledAt.toString());
        ArrayNode list = root.putArray("signatures");
        signatures.forEach((hex, summary) -> {
            ObjectNode n = list.addObject();
            n.put("sha256", hex);
            n.put("summary", summary);
        });
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws IllegalArgumentException for anything that is not a version-1 profile */
    public static StreamSignatureProfile fromJson(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (root.path("version").asInt(0) != 1) {
                throw new IllegalArgumentException("not a version 1 stream signature profile");
            }
            Map<String, String> sigs = new LinkedHashMap<>();
            for (JsonNode n : root.path("signatures")) {
                String hex = n.path("sha256").asText("");
                if (!hex.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("a signature is not 64 hex characters");
                }
                sigs.put(hex, n.path("summary").asText(""));
            }
            if (sigs.isEmpty() || sigs.size() > MAX_SIGNATURES) {
                throw new IllegalArgumentException("a profile holds 1 to " + MAX_SIGNATURES + " signatures");
            }
            return new StreamSignatureProfile(root.path("device").asText(""),
                    Instant.parse(root.path("enrolledAt").asText()), sigs);
        } catch (IOException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("not a stream signature profile: " + e.getMessage(), e);
        }
    }
}
