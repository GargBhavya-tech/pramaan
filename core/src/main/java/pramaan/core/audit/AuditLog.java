/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Append-only JSON Lines audit log with a hash chain (Build Map #10).
 *
 * <p>Each line is {@code {"fields":…,"hash":…,"prev":…,"seq":…,"ts":…,"type":…}} with keys in sorted order,
 * where {@code hash = SHA256(prev || body)}, {@code body} is the line without {@code hash}, and the first
 * {@code prev} is SHA-256 of the empty string. Altering, inserting or reordering any line breaks
 * {@link #verify}.
 *
 * <p>What the chain cannot show: lines deleted from the <em>end</em>. Anchoring the head elsewhere is the
 * job of the transparency log (Bible Part 42; Build Map #64).
 */
public final class AuditLog {

    /** SHA-256 of the empty string: the chain's starting point. */
    public static final String GENESIS = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Path file;
    private final Clock clock;
    private long nextSeq;
    private String head;

    private AuditLog(Path file, Clock clock, long nextSeq, String head) {
        this.file = file;
        this.clock = clock;
        this.nextSeq = nextSeq;
        this.head = head;
    }

    /**
     * Opens (or creates) a log. An existing log is verified first; a broken one is refused, because
     * appending to it would make the break look like part of history.
     */
    public static AuditLog open(Path file, Clock clock) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(clock, "clock");
        if (!Files.exists(file)) {
            return new AuditLog(file, clock, 1, GENESIS);
        }
        Verification v = verify(file);
        if (!v.intact()) {
            throw new IOException("refusing to append to a broken audit log: " + v.problem());
        }
        return new AuditLog(file, clock, v.entries() + 1, v.head());
    }

    /** Appends one event durably and returns its chain hash. */
    public synchronized String append(AuditEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        ObjectNode body = JSON.createObjectNode();
        body.set("fields", JSON.valueToTree(event.fields()));
        body.put("prev", head);
        body.put("seq", nextSeq);
        body.put("ts", clock.instant().toString());
        body.put("type", event.type());

        String hash = chain(head, canonical(body));
        body.put("hash", hash);
        byte[] line = (canonical(body) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.DSYNC);

        head = hash;
        nextSeq++;
        return hash;
    }

    /** The hash of the most recent entry, or {@link #GENESIS} for an empty log. */
    public synchronized String head() {
        return head;
    }

    /** Recomputes the whole chain. */
    public static Verification verify(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String prev = GENESIS;
        long expectedSeq = 1;
        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String line = lines.get(i);
            if (line.isEmpty()) {
                return Verification.broken(lineNo, "empty line");
            }
            ObjectNode entry;
            try {
                JsonNode parsed = JSON.readTree(line);
                if (!(parsed instanceof ObjectNode)) {
                    return Verification.broken(lineNo, "not a JSON object");
                }
                entry = (ObjectNode) parsed;
            } catch (IOException e) {
                return Verification.broken(lineNo, "not valid JSON");
            }
            String recorded = entry.path("hash").asText("");
            if (!prev.equals(entry.path("prev").asText(""))) {
                return Verification.broken(lineNo, "prev does not match the previous entry's hash");
            }
            if (entry.path("seq").asLong(-1) != expectedSeq) {
                return Verification.broken(lineNo, "sequence number " + entry.path("seq") + ", expected " + expectedSeq);
            }
            entry.remove("hash");
            String recomputed = chain(prev, canonical(entry));
            if (!recomputed.equals(recorded)) {
                return Verification.broken(lineNo, "hash mismatch: the entry was altered");
            }
            prev = recorded;
            expectedSeq++;
        }
        return Verification.intact(lines.size(), prev);
    }

    private static String canonical(JsonNode node) throws IOException {
        // Round-trip through a sorted map so key order never depends on insertion order.
        Map<?, ?> sorted = JSON.treeToValue(node, java.util.TreeMap.class);
        return JSON.writeValueAsString(sorted);
    }

    private static String chain(String prevHex, String body) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(prevHex.getBytes(StandardCharsets.US_ASCII));
            sha.update(body.getBytes(StandardCharsets.UTF_8));
            byte[] digest = sha.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /** Result of {@link #verify}: intact with an entry count and head, or broken at a line with a reason. */
    public static final class Verification {

        private final boolean intact;
        private final long entries;
        private final String head;
        private final int brokenLine;
        private final String problem;

        private Verification(boolean intact, long entries, String head, int brokenLine, String problem) {
            this.intact = intact;
            this.entries = entries;
            this.head = head;
            this.brokenLine = brokenLine;
            this.problem = problem;
        }

        static Verification intact(long entries, String head) {
            return new Verification(true, entries, head, 0, null);
        }

        static Verification broken(int line, String reason) {
            return new Verification(false, 0, null, line, "line " + line + ": " + reason);
        }

        public boolean intact() {
            return intact;
        }

        public long entries() {
            return entries;
        }

        public String head() {
            return head;
        }

        /** 1-based line of the first problem, or 0 when intact. */
        public int brokenLine() {
            return brokenLine;
        }

        public String problem() {
            return problem;
        }
    }
}
