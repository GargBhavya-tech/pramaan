/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.verify;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Re-runs the server re-check over stored evidence with a newer model (Bible Part 14; Build Map #63):
 * attacks the models of 2026 missed can be found by the models of 2028, because the evidence — signed, with
 * its encrypted crops — was kept. Each packet is a pair of files: {@code <id>.evidence.jws} and
 * {@code <id>.capture.jws}. The output lists every enrolment whose route is no longer CONFIRMED.
 */
public final class RetroactiveRescan {

    /** One enrolment's result. */
    public static final class Result {
        private final String id;
        private final RecheckReport report;

        Result(String id, RecheckReport report) {
            this.id = id;
            this.report = report;
        }

        public String id() {
            return id;
        }

        public RecheckReport report() {
            return report;
        }
    }

    private RetroactiveRescan() {
    }

    public static List<Result> scan(Path dir, RecheckVerifier verifier) throws IOException {
        List<Path> evidence;
        try (Stream<Path> files = Files.list(dir)) {
            evidence = files.filter(p -> p.getFileName().toString().endsWith(".evidence.jws")).sorted()
                    .collect(Collectors.toList());
        }
        List<Result> out = new ArrayList<>();
        for (Path e : evidence) {
            String name = e.getFileName().toString();
            String id = name.substring(0, name.length() - ".evidence.jws".length());
            Path capture = dir.resolve(id + ".capture.jws");
            RecheckReport r;
            if (Files.exists(capture)) {
                r = verifier.verify(read(e), read(capture));
            } else {
                r = new RecheckReport();
                r.review("CAPTURE_MISSING", "no " + capture.getFileName() + " beside the evidence");
            }
            out.add(new Result(id, r));
        }
        return out;
    }

    /** The review list: every enrolment not CONFIRMED, with its findings. */
    public static ObjectNode toReviewList(List<Result> results) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("scanned", results.size());
        ArrayNode review = o.putArray("toReview");
        for (Result r : results) {
            if (r.report.route() != RecheckReport.Route.CONFIRMED) {
                ObjectNode x = r.report.toJson();
                x.put("id", r.id);
                review.add(x);
            }
        }
        return o;
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.US_ASCII).trim();
    }
}
