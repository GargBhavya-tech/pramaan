/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import pramaan.core.challenge.FlashColour;
import pramaan.core.challenge.FlashSequence;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;

/**
 * What the device's illuminator actually showed and when, signed by the device (SBI Liveness Profile, addition 5;
 * Bible Part 41). Times are milliseconds from the first emission. A log that does not verify, belongs to another
 * transaction, is malformed, or differs from the schedule the client asked for is refused here, so everything that
 * holds an {@code EmissionLog} may believe it.
 *
 * <p>The client does not learn the absolute time of the first emission from the log. It anchors the log to the host
 * clock at the moment it sent the request, and the flash cue's lag search (0 to 2 frames) absorbs the device's start-up
 * delay. That is an estimate; with a device that also gives sensor timestamps the anchor can be tightened.
 */
public final class EmissionLog {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_EMISSIONS = 16;

    /** One step as the device performed it. */
    public static final class Emission {
        private final FlashColour colour;
        private final long atMs;
        private final long durationMs;

        Emission(FlashColour colour, long atMs, long durationMs) {
            this.colour = colour;
            this.atMs = atMs;
            this.durationMs = durationMs;
        }

        public FlashColour colour() {
            return colour;
        }

        public long atMs() {
            return atMs;
        }

        public long durationMs() {
            return durationMs;
        }
    }

    private final String transactionId;
    private final List<Emission> emissions;

    private EmissionLog(String transactionId, List<Emission> emissions) {
        this.transactionId = transactionId;
        this.emissions = Collections.unmodifiableList(emissions);
    }

    /**
     * Verifies the device's signature and the log's own consistency.
     *
     * @param compact the compact JWS returned by {@code illuminate}
     * @param expectedTransactionId the session nonce the request carried
     * @throws JwsVerificationException if the signature, the transaction, or the log's shape is wrong
     */
    public static EmissionLog verify(String compact, JwsTrust trust, String expectedTransactionId)
            throws JwsVerificationException {
        Objects.requireNonNull(expectedTransactionId, "expectedTransactionId");
        JsonNode root;
        try {
            root = JSON.readTree(Jws.verify(compact, trust).payloadText());
        } catch (IOException e) {
            throw new JwsVerificationException("the emission log is not JSON", e);
        }
        if (!expectedTransactionId.equals(root.path("transactionId").asText(null))) {
            throw new JwsVerificationException("the emission log belongs to another transaction");
        }
        JsonNode list = root.path("emissions");
        if (!list.isArray() || list.size() == 0 || list.size() > MAX_EMISSIONS) {
            throw new JwsVerificationException("the emission log holds no usable emissions");
        }
        List<Emission> out = new ArrayList<>();
        long previousEnd = 0;
        for (JsonNode e : list) {
            FlashColour colour;
            long at;
            long duration;
            try {
                colour = FlashColour.valueOf(e.path("colour").asText(""));
                at = Long.parseLong(e.path("atMs").asText(""));
                duration = Long.parseLong(e.path("durationMs").asText(""));
            } catch (IllegalArgumentException ex) {
                throw new JwsVerificationException("an emission has a colour or time that cannot be read", ex);
            }
            if (at < previousEnd || duration <= 0 || (out.isEmpty() && at != 0)) {
                throw new JwsVerificationException("emissions overlap, run backwards, or do not start at zero");
            }
            out.add(new Emission(colour, at, duration));
            previousEnd = at + duration;
        }
        return new EmissionLog(expectedTransactionId, out);
    }

    public String transactionId() {
        return transactionId;
    }

    public List<Emission> emissions() {
        return emissions;
    }

    /** Milliseconds from the first emission to the end of the last. */
    public long totalMs() {
        Emission last = emissions.get(emissions.size() - 1);
        return last.atMs + last.durationMs;
    }

    /** The colour showing at {@code t}, if the first emission began at {@code firstEmission}; empty while dark. */
    public Optional<FlashColour> colourAt(Instant firstEmission, Instant t) {
        long ms = Duration.between(firstEmission, t).toMillis();
        for (Emission e : emissions) {
            if (ms >= e.atMs && ms < e.atMs + e.durationMs) {
                return Optional.of(e.colour);
            }
        }
        return Optional.empty();
    }

    /**
     * Why this log is not what was asked for, or null if it is: the same colours in the same order, each step as long
     * as requested and each following the last, all within {@code toleranceMs}. A device that played something else
     * has told the client nothing it can correlate against (threat T9).
     */
    public String differenceFrom(FlashSequence requested, long toleranceMs) {
        List<FlashColour> colours = requested.colours();
        int[] durations = requested.durationsMs();
        if (colours.size() != emissions.size()) {
            return "the device played " + emissions.size() + " steps, not the " + colours.size() + " asked for";
        }
        long previousEnd = 0;
        for (int i = 0; i < emissions.size(); i++) {
            Emission e = emissions.get(i);
            if (e.colour != colours.get(i)) {
                return "step " + i + " was " + e.colour + ", not " + colours.get(i);
            }
            if (Math.abs(e.durationMs - durations[i]) > toleranceMs) {
                return "step " + i + " lasted " + e.durationMs + " ms, not " + durations[i] + " ms";
            }
            if (i > 0 && Math.abs(e.atMs - previousEnd) > toleranceMs) {
                return "step " + i + " began " + (e.atMs - previousEnd) + " ms away from where the last one ended";
            }
            previousEnd = e.atMs + e.durationMs;
        }
        return null;
    }
}
