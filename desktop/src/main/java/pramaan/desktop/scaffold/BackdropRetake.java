/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;
import pramaan.core.audit.AuditEvent;
import pramaan.core.pad.SceneConsistencyCue;
import pramaan.core.pad.SceneFingerprint;

/**
 * The audited re-fingerprint of this station's empty backdrop (Bible Part 43; Build Map #87). The backdrop is what the
 * scene cues compare every resident against, so replacing it is replacing the reference an attacker would most like to
 * control: it needs a named administrator and a reason, and leaves an audit event whether it succeeds or not, with the
 * hash of the fingerprint it replaced and of the one it wrote. The old fingerprint is kept beside the new one so a
 * mistaken re-take can be undone by hand.
 *
 * <p>Honest limit: nothing here checks that the photograph really is an empty room. A person standing in it becomes
 * part of the backdrop. The operator confirms that on screen, and the confirmation is part of what is audited.
 */
public final class BackdropRetake {

    /** How a re-take ended. */
    public enum Outcome {
        /** The new fingerprint is in place. */
        SAVED,
        /** Too few corners to tell this room from another: nothing was changed. */
        TOO_PLAIN,
        /** The bytes were not an image: nothing was changed. */
        UNREADABLE
    }

    /** What happened, and how many corners the photograph gave. */
    public static final class Result {
        private final Outcome outcome;
        private final int corners;

        Result(Outcome outcome, int corners) {
            this.outcome = outcome;
            this.corners = corners;
        }

        public Outcome outcome() {
            return outcome;
        }

        public int corners() {
            return corners;
        }
    }

    private static final int MAX_REASON = 200;

    private BackdropRetake() {
    }

    /**
     * @param photo a photograph of the empty room from the registration camera, in its normal lighting
     * @param target where the fingerprint is kept (the scaffold's {@code --backdrop} file)
     * @param roomConfirmedEmpty that the operator said the room is empty; recorded, not checked
     * @param audit receives {@code BACKDROP_RETAKEN} or {@code BACKDROP_RETAKE_REFUSED}
     */
    public static Result retake(byte[] photo, Path target, String adminId, String reason, boolean roomConfirmedEmpty,
            Clock clock, Consumer<AuditEvent> audit) throws IOException {
        Objects.requireNonNull(photo, "photo");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(audit, "audit");
        if (adminId == null || adminId.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a re-take needs an administrator id and a reason");
        }
        if (!roomConfirmedEmpty) {
            throw new IllegalArgumentException("the operator must confirm the room is empty");
        }
        String why = reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
        SceneFingerprint fp;
        try {
            fp = BackdropTool.fingerprint(photo);
        } catch (IOException e) {
            audit.accept(refused(adminId, why, "unreadable", 0, clock));
            return new Result(Outcome.UNREADABLE, 0);
        }
        if (fp.keypointCount() < SceneConsistencyCue.MIN_FINGERPRINT_KEYPOINTS) {
            audit.accept(refused(adminId, why, "too-plain", fp.keypointCount(), clock));
            return new Result(Outcome.TOO_PLAIN, fp.keypointCount());
        }
        byte[] bytes = fp.toBytes();
        String oldHash = "none";
        Path abs = target.toAbsolutePath();
        if (abs.getParent() != null) {
            Files.createDirectories(abs.getParent());
        }
        if (Files.exists(abs)) {
            oldHash = sha256(Files.readAllBytes(abs));
            Files.copy(abs, abs.resolveSibling(abs.getFileName() + ".previous"), StandardCopyOption.REPLACE_EXISTING);
        }
        Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
        audit.accept(AuditEvent.of("BACKDROP_RETAKEN").put("adminId", adminId).put("reason", why)
                .put("corners", fp.keypointCount()).put("oldSha256", oldHash).put("newSha256", sha256(bytes))
                .put("roomConfirmedEmpty", true).put("at", clock.instant().toString()).build());
        return new Result(Outcome.SAVED, fp.keypointCount());
    }

    private static AuditEvent refused(String adminId, String reason, String why, int corners, Clock clock) {
        return AuditEvent.of("BACKDROP_RETAKE_REFUSED").put("adminId", adminId).put("reason", reason).put("why", why)
                .put("corners", corners).put("at", clock.instant().toString()).build();
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
