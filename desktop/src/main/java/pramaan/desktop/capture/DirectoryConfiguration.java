/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Policy and table read from a directory at run time: edit a file, restart, and the behaviour follows (#78). */
public final class DirectoryConfiguration implements ConfigurationSource {

    public static final String POLICY = "pramaan-policy.yaml";
    public static final String OPERATING_POINTS = "operating-points.json";

    private final Path dir;

    public DirectoryConfiguration(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir");
    }

    @Override
    public byte[] policyYaml() throws IOException {
        return Files.readAllBytes(dir.resolve(POLICY));
    }

    @Override
    public byte[] operatingPointsJson() throws IOException {
        return Files.readAllBytes(dir.resolve(OPERATING_POINTS));
    }

    @Override
    public String describe() {
        return dir.toAbsolutePath().normalize().toString();
    }
}
