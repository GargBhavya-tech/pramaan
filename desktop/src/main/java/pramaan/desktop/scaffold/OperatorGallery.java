/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import pramaan.core.binding.SFaceEmbedder;

/**
 * The scaffold stand-in for the operator biometrics the Registration Client keeps after onboarding: one SFace
 * embedding per operator id, in a local directory under {@code build/} (gitignored). A face template is
 * biometric data; it never leaves the machine and is never committed.
 */
final class OperatorGallery {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Path dir;

    OperatorGallery(Path dir) {
        this.dir = dir;
    }

    void enrol(String operatorId, float[] embedding) throws IOException {
        if (embedding.length != SFaceEmbedder.DIM) {
            throw new IllegalArgumentException("expected a " + SFaceEmbedder.DIM + "-dimensional embedding");
        }
        ByteBuffer b = ByteBuffer.allocate(4 * embedding.length);
        for (float f : embedding) {
            b.putFloat(f);
        }
        Files.createDirectories(dir);
        Files.write(file(operatorId), b.array());
    }

    Optional<float[]> enrolled(String operatorId) throws IOException {
        Path f = file(operatorId);
        if (!Files.exists(f)) {
            return Optional.empty();
        }
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f));
        float[] out = new float[SFaceEmbedder.DIM];
        for (int i = 0; i < out.length; i++) {
            out[i] = b.getFloat();
        }
        return Optional.of(out);
    }

    private Path file(String operatorId) {
        if (!ID.matcher(operatorId).matches()) {
            throw new IllegalArgumentException("operator ids are letters, digits, dot, dash and underscore");
        }
        return dir.resolve(operatorId + ".sface");
    }
}
