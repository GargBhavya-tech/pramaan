/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.harness.corpus.ClipLoader;

/**
 * Per-person recordings of each action (Bible Part 21.1: "pre-recorded clip libraries of each action let the
 * harness answer challenges automatically"). {@code actions.csv}, next to the manifest:
 *
 * <pre>person,action,clip
 * P01,TURN_LEFT,clips/P01/turn-left
 * P01,EYES_CLOSED,clips/P01/eyes-closed.mjpeg</pre>
 *
 * Actions: TURN_LEFT, TURN_RIGHT (the resident's own side), BLINK (one blink; played as many times as asked),
 * EYES_CLOSED (must hold at least 2.5 s: the longest hold asked is 2 s), SMILE. Clips are JPEG directories or
 * M-JPEG files, loaded once.
 */
public final class ActionLibrary {

    public static final String HEADER = "person,action,clip";
    static final Set<String> ACTIONS = Set.of("TURN_LEFT", "TURN_RIGHT", "BLINK", "EYES_CLOSED", "SMILE");

    private final Map<String, List<byte[]>> clips;

    private ActionLibrary(Map<String, List<byte[]>> clips) {
        this.clips = clips;
    }

    public static ActionLibrary empty() {
        return new ActionLibrary(Collections.emptyMap());
    }

    public static ActionLibrary load(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).trim().equals(HEADER)) {
            throw new IOException(csv + ": first line must be exactly '" + HEADER + "'");
        }
        Path base = csv.toAbsolutePath().getParent();
        Map<String, List<byte[]>> clips = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length != 3) {
                throw new IOException(csv + " line " + (i + 1) + ": expected 3 columns");
            }
            String person = f[0].trim();
            String action = f[1].trim().toUpperCase(Locale.ROOT);
            if (!person.matches("P[0-9]{2,4}")) {
                throw new IOException(csv + " line " + (i + 1) + ": person must be a pseudonymous id like P07");
            }
            if (!ACTIONS.contains(action)) {
                throw new IOException(csv + " line " + (i + 1) + ": action must be one of " + ACTIONS);
            }
            clips.put(person + "/" + action, ClipLoader.load(base.resolve(f[2].trim()).normalize()));
        }
        return new ActionLibrary(clips);
    }

    /**
     * The frames that answer this prompt for this person, or empty when the library has no such recording —
     * the harness then cannot answer, and says so, rather than failing a real person on its own behalf.
     */
    public Optional<List<byte[]>> answer(String person, ChallengePrompt prompt) {
        String key;
        int repeat = 1;
        switch (prompt.kind()) {
            case TURN:
                key = "TURN_" + prompt.params().get("direction");
                break;
            case BLINK:
                key = "BLINK";
                repeat = Integer.parseInt(prompt.params().getOrDefault("count", "1"));
                break;
            case EYES_CLOSED:
                key = "EYES_CLOSED";
                break;
            case SMILE:
                key = "SMILE";
                break;
            default:
                return Optional.empty();
        }
        List<byte[]> clip = clips.get(person + "/" + key);
        if (clip == null) {
            return Optional.empty();
        }
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < repeat; i++) {
            out.addAll(clip);
        }
        return Optional.of(out);
    }

    public boolean has(String person) {
        String prefix = person + "/";
        return clips.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }
}
