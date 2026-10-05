/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import java.io.IOException;

/**
 * Where a policy comes from. Today: a YAML file ({@link YamlPolicySource}). Later: a server-signed,
 * anti-rollback-protected bundle (Bible Part 17.2) — a new implementation of this seam, with no change
 * for callers.
 */
public interface PolicySource {

    /**
     * @throws IOException            if the policy cannot be read
     * @throws InvalidPolicyException if it can be read but must not be trusted
     */
    LivenessPolicy load() throws IOException;
}
