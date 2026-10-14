/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import java.nio.file.Path;
import java.util.List;

/** Command-line settings for the scaffold; every default runs against a local MockMDS from the repository root. */
final class ScaffoldConfig {

    Path configDir = Path.of("config");
    Path modelsDir = Path.of("models");
    /** The device CA pinned as trust anchor; MockMDS's own root by default (docs/sbi-notes.md). */
    Path deviceCa = Path.of("sbi", "testdata", "mockmds-rootca.cer");
    Path auditLog = Path.of("build", "scaffold", "audit.log");
    /** Enrolled operator face templates: biometric data, local only, gitignored with build/. */
    Path galleryDir = Path.of("build", "scaffold", "operators");
    String host = "127.0.0.1";
    int firstPort = 4501;
    int lastPort = 4510;
    String subjectId = "10001100010000120260923";
    String operatorId = "operator-1";
    String centreId = "10001";
    /** The capture quality asked of the device; the Registration Client sends its configured face threshold. */
    int requestedScore = 40;
    /** Where packets, the transparency log and the development keys go (ADR 0003 patch P4). */
    Path evidenceDir = Path.of("build", "scaffold");
    /** The screen's language, e.g. "hi"; empty for the machine's default (Build Map #91). */
    String language = "";
    /** Read every headline aloud with the platform's voice (Build Map #91). */
    boolean speech;
    /**
     * Index of the monitor that faces the resident (Bible Part 8.6), or -1 for none. With one, the flash challenge may
     * be drawn and plays on it; without one it is never drawn.
     */
    int flashScreen = -1;
    /** The stored fingerprint of this station's empty backdrop (Bible Part 43), or null when none was ever captured. */
    Path backdrop;
    /** The public key (X.509 DER) the policy bundle must be signed with; null accepts an unsigned directory. */
    Path bundleKey;
    /** Highest bundle version seen, kept across runs (anti-rollback, Bible Part 17.2). */
    Path rollbackFile = Path.of("build", "scaffold", "bundle.version");

    static ScaffoldConfig parse(List<String> args) {
        ScaffoldConfig c = new ScaffoldConfig();
        for (int i = 0; i < args.size(); i++) {
            String key = args.get(i);
            if (i + 1 >= args.size()) {
                throw new IllegalArgumentException("missing value for " + key);
            }
            String v = args.get(++i);
            switch (key) {
                case "--config":
                    c.configDir = Path.of(v);
                    break;
                case "--models":
                    c.modelsDir = Path.of(v);
                    break;
                case "--device-ca":
                    c.deviceCa = Path.of(v);
                    break;
                case "--gallery":
                    c.galleryDir = Path.of(v);
                    break;
                case "--audit":
                    c.auditLog = Path.of(v);
                    break;
                case "--host":
                    c.host = v;
                    break;
                case "--ports":
                    String[] p = v.split("-");
                    c.firstPort = Integer.parseInt(p[0]);
                    c.lastPort = Integer.parseInt(p.length > 1 ? p[1] : p[0]);
                    break;
                case "--subject":
                    c.subjectId = v;
                    break;
                case "--operator":
                    c.operatorId = v;
                    break;
                case "--centre":
                    c.centreId = v;
                    break;
                case "--requested-score":
                    c.requestedScore = Integer.parseInt(v);
                    break;
                case "--evidence":
                    c.evidenceDir = Path.of(v);
                    break;
                case "--lang":
                    c.language = v;
                    break;
                case "--flash-screen":
                    c.flashScreen = Integer.parseInt(v);
                    break;
                case "--backdrop":
                    c.backdrop = Path.of(v);
                    break;
                case "--speech":
                    c.speech = Boolean.parseBoolean(v);
                    break;
                case "--bundle-key":
                    c.bundleKey = Path.of(v);
                    break;
                case "--rollback-file":
                    c.rollbackFile = Path.of(v);
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + key);
            }
        }
        return c;
    }
}
