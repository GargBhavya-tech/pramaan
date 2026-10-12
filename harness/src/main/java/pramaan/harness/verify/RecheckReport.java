/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.verify;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the server re-check concluded (Bible Part 14; Build Map #62). */
public final class RecheckReport {

    /** Where the packet goes next. */
    public enum Route {
        /** Everything verified and the server agrees. */
        CONFIRMED,
        /** A finding a human must look at: unapproved model, client/server disagreement, missing samples. */
        ADJUDICATE,
        /** The evidence or its binding to the capture was tampered with. */
        REJECT
    }

    /** One finding. {@code tamper} findings route to REJECT; the rest to ADJUDICATE. */
    public static final class Finding {
        private final String code;
        private final String detail;
        private final boolean tamper;

        Finding(String code, String detail, boolean tamper) {
            this.code = code;
            this.detail = detail;
            this.tamper = tamper;
        }

        public String code() {
            return code;
        }

        public String detail() {
            return detail;
        }

        public boolean tamper() {
            return tamper;
        }
    }

    private final List<Finding> findings = new ArrayList<>();
    private Double serverPLive;

    void tamper(String code, String detail) {
        findings.add(new Finding(code, detail, true));
    }

    public void review(String code, String detail) {
        findings.add(new Finding(code, detail, false));
    }

    void serverPLive(double p) {
        serverPLive = p;
    }

    public Route route() {
        if (findings.stream().anyMatch(Finding::tamper)) {
            return Route.REJECT;
        }
        return findings.isEmpty() ? Route.CONFIRMED : Route.ADJUDICATE;
    }

    public List<Finding> findings() {
        return Collections.unmodifiableList(findings);
    }

    public boolean has(String code) {
        return findings.stream().anyMatch(f -> f.code.equals(code));
    }

    public ObjectNode toJson() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("route", route().name());
        if (serverPLive != null) {
            o.put("serverPLive", serverPLive);
        }
        ArrayNode a = o.putArray("findings");
        for (Finding f : findings) {
            a.addObject().put("code", f.code).put("detail", f.detail).put("tamper", f.tamper);
        }
        return o;
    }
}
