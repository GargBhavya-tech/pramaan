/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One corpus session's result (Build Map #67): the row the harness runner writes and every report reads.
 * Stored as JSON Lines. Demographic attributes exist here, in the evaluation corpus, and nowhere at runtime
 * (Bible Part 16).
 */
public final class SessionRow {

    public enum Label {
        BONA_FIDE,
        ATTACK
    }

    /** How the session ended. NO_RESPONSE: no decision (timeout, no usable face, device error). */
    public enum Outcome {
        ACCEPTED,
        REJECTED,
        NO_RESPONSE
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String id;
    private final Label label;
    private final String pais;
    private final int attackPotential;
    private final Map<String, String> attributes;
    private final String workflow;
    private final String deviceLevel;
    private final Map<String, Double> cues;
    private final Double pLive;
    private final Outcome outcome;

    /**
     * @param pais attack species id, null for bona fide
     * @param attackPotential 1, 2 or 3 (iBeta-style level), 0 if not recorded
     * @param cues per-cue P(live); a null value means the cue abstained
     * @param pLive the fused P(live) of the first decision, null for a non-response
     */
    public SessionRow(String id, Label label, String pais, int attackPotential, Map<String, String> attributes, String workflow,
            String deviceLevel, Map<String, Double> cues, Double pLive, Outcome outcome) {
        this.id = Objects.requireNonNull(id, "id");
        this.label = Objects.requireNonNull(label, "label");
        if ((label == Label.ATTACK) != (pais != null)) {
            throw new IllegalArgumentException(id + ": an attack needs a PAIS, and only an attack has one");
        }
        this.pais = pais;
        this.attackPotential = attackPotential;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        this.workflow = workflow;
        this.deviceLevel = deviceLevel;
        this.cues = Collections.unmodifiableMap(new LinkedHashMap<>(cues));
        this.pLive = pLive;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public String id() {
        return id;
    }

    public Label label() {
        return label;
    }

    public String pais() {
        return pais;
    }

    public int attackPotential() {
        return attackPotential;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public String workflow() {
        return workflow;
    }

    public String deviceLevel() {
        return deviceLevel;
    }

    public Map<String, Double> cues() {
        return cues;
    }

    public Double pLive() {
        return pLive;
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean responded() {
        return outcome != Outcome.NO_RESPONSE;
    }

    public ObjectNode toJson() {
        ObjectNode o = JSON.createObjectNode();
        o.put("id", id);
        o.put("label", label.name());
        if (pais != null) {
            o.put("pais", pais);
        }
        o.put("attackPotential", attackPotential);
        ObjectNode a = o.putObject("attributes");
        attributes.forEach(a::put);
        o.put("workflow", workflow);
        o.put("deviceLevel", deviceLevel);
        ObjectNode c = o.putObject("cues");
        cues.forEach((k, v) -> {
            if (v == null) {
                c.putNull(k);
            } else {
                c.put(k, v);
            }
        });
        if (pLive != null) {
            o.put("pLive", pLive);
        }
        o.put("outcome", outcome.name());
        return o;
    }

    public static SessionRow fromJson(JsonNode n) {
        Map<String, String> attrs = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = n.path("attributes").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            attrs.put(e.getKey(), e.getValue().asText());
        }
        Map<String, Double> cues = new LinkedHashMap<>();
        it = n.path("cues").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            cues.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asDouble());
        }
        return new SessionRow(n.path("id").asText(), Label.valueOf(n.path("label").asText()),
                n.hasNonNull("pais") ? n.get("pais").asText() : null, n.path("attackPotential").asInt(0), attrs,
                n.path("workflow").asText("RESIDENT"), n.path("deviceLevel").asText("L1"), cues,
                n.hasNonNull("pLive") ? n.get("pLive").asDouble() : null, Outcome.valueOf(n.path("outcome").asText()));
    }

    public static List<SessionRow> readJsonl(Path file) throws IOException {
        List<SessionRow> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(fromJson(JSON.readTree(line)));
            }
        }
        return out;
    }

    public static void writeJsonl(List<SessionRow> rows, Path file) throws IOException {
        StringBuilder s = new StringBuilder();
        for (SessionRow r : rows) {
            s.append(JSON.writeValueAsString(r.toJson())).append('\n');
        }
        Files.write(file, s.toString().getBytes(StandardCharsets.UTF_8));
    }
}
