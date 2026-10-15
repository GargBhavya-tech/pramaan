/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a recorded flash experiment actually showed and when (Bible Part 8.6; docs/research-plan-real-data.md): the host's
 * own record of each colour step, in epoch milliseconds from the same clock that stamps the camera frames. The recorder
 * writes it and the analyser reads it, so the correlation is computed against what the screen was told to show, not
 * against what was planned.
 *
 * <p>Honest limit: the times are when the host <em>asked</em> the screen to change colour. A real display adds its own
 * latency (tens of milliseconds, plus a frame or two of compositor delay), and nothing attests it, unlike the device's
 * emission log. The cue's lag search of up to two frames absorbs it at 10 frames per second; a slower screen shows up as a
 * larger fitted lag, which the analyser reports.
 *
 * <pre>{"version":1,"label":"genuine","camera":"Integrated Camera","startedAtEpochMs":1760000000000,
 *  "steps":[{"colour":"GREEN","shownAtEpochMs":1760000000700,"durationMs":400}],
 *  "offAtEpochMs":1760000001100,"windowShown":true}</pre>
 */
public final class FlashRunLog {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One colour step as shown. */
    public static final class Step {
        private final FlashColour colour;
        private final long shownAtEpochMs;
        private final int durationMs;

        public Step(FlashColour colour, long shownAtEpochMs, int durationMs) {
            this.colour = Objects.requireNonNull(colour, "colour");
            this.shownAtEpochMs = shownAtEpochMs;
            this.durationMs = durationMs;
        }

        public FlashColour colour() {
            return colour;
        }

        public long shownAtEpochMs() {
            return shownAtEpochMs;
        }

        public int durationMs() {
            return durationMs;
        }
    }

    private final String label;
    private final String camera;
    private final long startedAtEpochMs;
    private final List<Step> steps;
    private final long offAtEpochMs;
    private final boolean windowShown;

    public FlashRunLog(String label, String camera, long startedAtEpochMs, List<Step> steps, long offAtEpochMs,
            boolean windowShown) {
        this.label = Objects.requireNonNull(label, "label");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.startedAtEpochMs = startedAtEpochMs;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.offAtEpochMs = offAtEpochMs;
        this.windowShown = windowShown;
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i).shownAtEpochMs < steps.get(i - 1).shownAtEpochMs) {
                throw new IllegalArgumentException("steps are out of order");
            }
        }
    }

    /** "genuine" or "replay" (or any tag the experimenter chose): what the camera was pointed at. */
    public String label() {
        return label;
    }

    public String camera() {
        return camera;
    }

    public long startedAtEpochMs() {
        return startedAtEpochMs;
    }

    public List<Step> steps() {
        return steps;
    }

    public long offAtEpochMs() {
        return offAtEpochMs;
    }

    /** False for a dry run: the colours were logged but no window was shown, so no light reached anyone. */
    public boolean windowShown() {
        return windowShown;
    }

    /**
     * The colour on screen at this host time, or empty while dark. A step ends when the next begins, or after its own
     * duration, whichever is first; the last also ends at the logged off time.
     */
    public Optional<FlashColour> colourAt(long epochMs) {
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            long end = s.shownAtEpochMs + s.durationMs;
            if (i + 1 < steps.size()) {
                end = Math.min(end, steps.get(i + 1).shownAtEpochMs);
            } else {
                end = Math.min(end, offAtEpochMs);
            }
            if (epochMs >= s.shownAtEpochMs && epochMs < end) {
                return Optional.of(s.colour);
            }
        }
        return Optional.empty();
    }

    /** Host time the first colour was shown, or the start time for a log with no steps. */
    public long firstColourAtEpochMs() {
        return steps.isEmpty() ? startedAtEpochMs : steps.get(0).shownAtEpochMs;
    }

    public String toJson() {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        root.put("label", label);
        root.put("camera", camera);
        root.put("startedAtEpochMs", startedAtEpochMs);
        ArrayNode list = root.putArray("steps");
        for (Step s : steps) {
            ObjectNode n = list.addObject();
            n.put("colour", s.colour.name());
            n.put("shownAtEpochMs", s.shownAtEpochMs);
            n.put("durationMs", s.durationMs);
        }
        root.put("offAtEpochMs", offAtEpochMs);
        root.put("windowShown", windowShown);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws IllegalArgumentException for anything that is not a version-1 run log */
    public static FlashRunLog fromJson(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (root.path("version").asInt(0) != 1) {
                throw new IllegalArgumentException("not a version 1 flash run log");
            }
            List<Step> steps = new ArrayList<>();
            for (JsonNode n : root.path("steps")) {
                steps.add(new Step(FlashColour.valueOf(n.path("colour").asText()), n.path("shownAtEpochMs").asLong(),
                        n.path("durationMs").asInt()));
            }
            return new FlashRunLog(root.path("label").asText("unlabelled"), root.path("camera").asText(""),
                    root.path("startedAtEpochMs").asLong(), steps, root.path("offAtEpochMs").asLong(),
                    root.path("windowShown").asBoolean(true));
        } catch (IOException e) {
            throw new IllegalArgumentException("not a flash run log: " + e.getMessage(), e);
        }
    }
}
