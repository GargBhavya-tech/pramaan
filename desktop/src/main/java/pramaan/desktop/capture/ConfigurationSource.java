/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.io.IOException;

/**
 * Where the policy and the operating-point table come from at run time (Build Map #78). The scaffold reads a
 * directory ({@link DirectoryConfiguration}); in the Registration Client the same two values come from its
 * synced global parameters (ADR 0003, patch P7), so a centre's policy changes with a configuration push, never
 * with a rebuild. Either way the bytes are verified by {@link pramaan.core.session.EngineBundle#load}.
 */
public interface ConfigurationSource {

    byte[] policyYaml() throws IOException;

    byte[] operatingPointsJson() throws IOException;

    /** Where the configuration came from, for the audit log and error messages. */
    String describe();
}
