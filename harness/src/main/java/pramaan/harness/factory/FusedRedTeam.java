/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pramaan.core.Workflow;
import pramaan.core.fusion.RiskFusion;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.OperatingPoint;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.source.DeviceLevel;

/**
 * The red team against the <em>decision</em>, not one cue (Bible Part 9, 45): R-01 found display pitches at which the
 * moiré cue sees nothing; this asks which of them the whole fusion, with the shipped operating-point table, would
 * accept, and what closes the hole.
 *
 * <p>For every pitch the attacker replays this room (so the scene cue passes) with a face on a screen. Two worlds are
 * fused with the real {@link RiskFusion} and the table's accept and reject thresholds:
 * <ul>
 *   <li><b>no flash</b>: only the scene and moiré cues voted (a station with no resident-facing light; the passive scorer
 *       is assumed fooled too, the conservative case);</li>
 *   <li><b>flash</b>: the same, plus a flash cue that a replay never answers (it votes {@value #REPLAY_FLASH_VOTE} live,
 *       about what the synthetic replay gives in {@code StationWiringEngineTest});</li>
 * </ul>
 * and a pitch is reported ACCEPT, CHALLENGE (the engine then asks for a random action a replay cannot perform) or REJECT.
 *
 * <p>SYNTHETIC: the votes come from simulated recaptures of one synthetic room; the passive scorer, the challenge and the
 * stereo cue are not modelled. It says how much the fusion relies on the flash cue for these attacks, not how a real
 * phone fares.
 *
 * <pre>./gradlew :harness:fusedRedTeam</pre>
 */
public final class FusedRedTeam {

    public enum Zone { ACCEPT, CHALLENGE, REJECT }

    /** One pitch's outcome. */
    public static final class Row {
        public final double pitch;
        public final double scene;
        public final double moire;
        public final double pNoFlash;
        public final double pFlash;
        public final Zone noFlash;
        public final Zone flash;

        Row(double pitch, double scene, double moire, double pNoFlash, double pFlash, Zone noFlash, Zone flash) {
            this.pitch = pitch;
            this.scene = scene;
            this.moire = moire;
            this.pNoFlash = pNoFlash;
            this.pFlash = pFlash;
            this.noFlash = noFlash;
            this.flash = flash;
        }
    }

    /** What a replay does to the flash cue: it never hears the light, so the cue votes against. */
    public static final double REPLAY_FLASH_VOTE = 0.05;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RiskFusion fusion;
    private final OperatingPoint point;

    /** @param table the operating-point JSON, normally {@code config/operating-points.json} */
    public FusedRedTeam(Path table) throws IOException {
        byte[] json = Files.readAllBytes(table);
        JsonNode root = JSON.readTree(json);
        Map<String, String> models = new LinkedHashMap<>();
        root.path("models").fields().forEachRemaining(e -> models.put(e.getKey(), e.getValue().asText()));
        OperatingPointTable t = OperatingPointTable.load(json, table.toString(), models);
        this.fusion = new RiskFusion(t);
        this.point = t.operatingPoint("resident-default", OperatingPointTable.DEFAULT_DEVICE_CLASS);
    }

    public double tauAccept() {
        return point.tauAccept();
    }

    public double tauReject() {
        return point.tauReject();
    }

    /** The fused probability of a session whose cues voted as given (abstentions are left out). */
    public double fusedP(double scene, double moire, Double flash) {
        List<CueResult> votes = new ArrayList<>();
        if (!Double.isNaN(scene)) {
            votes.add(CueResult.vote("scene", clamp(scene), scene));
        }
        if (!Double.isNaN(moire)) {
            votes.add(CueResult.vote("moire", clamp(moire), moire));
        }
        if (flash != null) {
            votes.add(CueResult.vote("flash", clamp(flash), flash));
        }
        double l = fusion.fuse(votes, Workflow.RESIDENT, DeviceLevel.L1).logOdds();
        return 1 / (1 + Math.exp(-l));
    }

    public Zone zone(double p) {
        return p >= point.tauAccept() ? Zone.ACCEPT : p <= point.tauReject() ? Zone.REJECT : Zone.CHALLENGE;
    }

    private static double clamp(double p) {
        return Math.max(1e-6, Math.min(1 - 1e-6, p));
    }

    /** One row per pitch from {@code from} to {@code to}. */
    public List<Row> sweep(RgbImage room, double from, double to, double step) {
        List<Row> rows = new ArrayList<>();
        for (double pitch = from; pitch <= to + 1e-9; pitch += step) {
            double[] v = StationSelfTest.replayVotes(room, pitch);
            double a = fusedP(v[0], v[1], null);
            double b = fusedP(v[0], v[1], REPLAY_FLASH_VOTE);
            rows.add(new Row(pitch, v[0], v[1], a, b, zone(a), zone(b)));
        }
        return rows;
    }

    /** A readable table of the pitches that are not simply rejected, and the summary the finding needs. */
    public static String report(List<Row> rows, double tauAccept, double tauReject) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("SYNTHETIC. Accept at p >= %.2f, reject at p <= %.2f, otherwise the engine challenges.%n",
                tauAccept, tauReject));
        sb.append(String.format("%-6s %-6s %-6s | %-6s %-9s | %-6s %-9s%n", "pitch", "scene", "moire", "p", "no flash", "p",
                "flash"));
        int acceptedNoFlash = 0;
        int acceptedFlash = 0;
        int challengedFlash = 0;
        for (Row r : rows) {
            if (r.noFlash == Zone.ACCEPT || r.flash != Zone.REJECT) {
                sb.append(String.format("%-6.2f %-6s %-6s | %-6.3f %-9s | %-6.3f %-9s%n", r.pitch, fmt(r.scene), fmt(r.moire),
                        r.pNoFlash, r.noFlash, r.pFlash, r.flash));
            }
            acceptedNoFlash += r.noFlash == Zone.ACCEPT ? 1 : 0;
            acceptedFlash += r.flash == Zone.ACCEPT ? 1 : 0;
            challengedFlash += r.flash == Zone.CHALLENGE ? 1 : 0;
        }
        sb.append(String.format("%nof %d pitches: accepted without the flash cue %d, accepted with it %d, left to the challenge"
                + " with it %d%n", rows.size(), acceptedNoFlash, acceptedFlash, challengedFlash));
        return sb.toString();
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.2f", v);
    }

    public static void main(String[] args) throws IOException {
        FusedRedTeam f = new FusedRedTeam(Path.of("config", "operating-points.json"));
        System.out.println(report(f.sweep(SyntheticScene.room(7).image(), 0.72, 2.4, 0.02), f.tauAccept(), f.tauReject()));
    }
}
