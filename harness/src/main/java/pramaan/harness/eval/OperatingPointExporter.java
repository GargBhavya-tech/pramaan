/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the operating-point table from evidence (Bible Part 21.6; Build Map #69). For each profile target:
 * {@code tauAccept} is the lowest P(live) threshold at which every PAIS's APCER is <em>demonstrated</em>
 * within the target — its one-sided 95% upper bound, not just its measured value — and {@code tauReject}
 * the highest threshold below it that rejects at most the allowed share of bona fide presentations: only
 * clear attacks are rejected outright; everything between is challenged.
 *
 * <p>If no threshold can demonstrate a target (too few attacks: about 300 per PAIS to show 1%), that profile
 * gets the zero-measured-error threshold and the whole table stays {@code provisional}, with a note naming
 * the profile and PAIS. Weights and bias are carried over unchanged — fitting them is a separate step.
 */
public final class OperatingPointExporter {

    /** One profile's target. */
    public static final class Target {
        final String name;
        final double apcerMax;
        final double bpcerRejectMax;
        final String description;

        public Target(String name, double apcerMax, double bpcerRejectMax, String description) {
            this.name = name;
            this.apcerMax = apcerMax;
            this.bpcerRejectMax = bpcerRejectMax;
            this.description = description;
        }
    }

    /** What was exported, and whether the data could support it. */
    public static final class Export {
        public final String json;
        public final boolean provisional;
        public final List<String> notes;

        Export(String json, boolean provisional, List<String> notes) {
            this.json = json;
            this.provisional = provisional;
            this.notes = notes;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private OperatingPointExporter() {
    }

    /**
     * @param templateJson the current operating-point table (weights, bias, gap and binding are kept)
     * @param modelSha256 the exact models the corpus run used; the table is bound to them
     */
    public static Export export(String templateJson, DetCurve curve, List<Target> targets, Map<String, String> modelSha256,
            String runDescription) throws IOException {
        ObjectNode table = (ObjectNode) JSON.readTree(templateJson);
        ObjectNode models = table.putObject("models");
        modelSha256.forEach(models::put);
        ObjectNode points = (ObjectNode) table.path("deviceClasses").path("default");
        List<String> notes = new ArrayList<>();
        boolean provisional = false;
        for (Target t : targets) {
            DetCurve.Point accept = curve.lowestThresholdProven(t.apcerMax);
            if (accept == null) {
                provisional = true;
                accept = curve.lowestThresholdWithin(0.0);
                notes.add(String.format("%s: no threshold demonstrates worst-PAIS APCER <= %.2f%% with this many attacks "
                        + "(0 errors in n still leaves a 95%% upper bound of 1 - 0.05^(1/n)); using the zero-error threshold",
                        t.name, 100 * t.apcerMax));
            }
            double tauAccept = clamp(accept.threshold);
            double tauReject = 1e-3;
            for (DetCurve.Point p : curve.points()) {
                if (p.threshold < tauAccept && p.bpcer <= t.bpcerRejectMax) {
                    tauReject = Math.max(tauReject, clamp(p.threshold));
                }
            }
            if (tauReject >= tauAccept) {
                tauReject = tauAccept / 2;
            }
            ObjectNode o = points.putObject(t.name);
            o.put("tauAccept", tauAccept);
            o.put("tauReject", tauReject);
            o.put("target", t.description + String.format(" (measured at tauAccept: worst APCER %.2f%%, BPCER %.2f%%)",
                    100 * accept.worstApcer(), 100 * accept.bpcer));
        }
        table.put("provisional", provisional);
        table.put("generatedBy", "Harness #69: " + runDescription + (provisional ? " - PROVISIONAL, see notes" : ""));
        return new Export(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(table), provisional, notes);
    }

    private static double clamp(double t) {
        return Math.max(1e-3, Math.min(1 - 1e-3, t));
    }
}
