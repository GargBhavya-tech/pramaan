/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core;

/**
 * Identity of the PRAMAAN engine. The name, version and evidence schema are recorded in every
 * Liveness Evidence Package (Bible Part 14) so the server can tell which engine produced it.
 */
public final class EngineInfo {

    public static final String NAME = "pramaan";

    /** Schema identifier of the signed evidence record (Bible Part 14). */
    public static final String EVIDENCE_SCHEMA = "pramaan.evidence/1";

    private EngineInfo() {
    }

    /** The engine version from the jar manifest, or {@code "dev"} when running from classes. */
    public static String version() {
        Package pkg = EngineInfo.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
