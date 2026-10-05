/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads a policy from YAML. Duplicate keys are an error (YAML would otherwise keep the last one
 * silently), and so is any unknown field (see {@link StrictNode}).
 */
public final class YamlPolicySource implements PolicySource {

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private final byte[] yaml;
    private final String origin;

    private YamlPolicySource(byte[] yaml, String origin) {
        this.yaml = yaml;
        this.origin = origin;
    }

    public static YamlPolicySource of(Path file) throws IOException {
        return new YamlPolicySource(Files.readAllBytes(file), file.toString());
    }

    public static YamlPolicySource of(byte[] yaml, String origin) {
        return new YamlPolicySource(Objects.requireNonNull(yaml, "yaml").clone(), origin);
    }

    @Override
    public LivenessPolicy load() throws IOException {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (JsonProcessingException e) {
            throw new InvalidPolicyException(origin + ": not valid YAML: " + e.getOriginalMessage(), e);
        }
        try {
            return LivenessPolicy.fromTree(root);
        } catch (InvalidPolicyException e) {
            throw new InvalidPolicyException(origin + ": " + e.getMessage(), e);
        }
    }
}
