/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Objects;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.engine.CaptureAnalyser;
import pramaan.core.engine.DiagnosticGrant;
import pramaan.core.engine.DiagnosticSink;
import pramaan.core.engine.EngineListener;
import pramaan.core.engine.StandardEngines;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.EngineUnavailableException;
import pramaan.core.session.LivenessSession;
import pramaan.core.source.FaceFrameSource;
import pramaan.desktop.ImageIoFrameDecoder;
import pramaan.desktop.IsoFaceDecoder;

/**
 * The desktop engine: {@link StandardEngines} — the assembly Android uses too — with ImageIO decoding (JPEG 2000
 * included) and {@code System.nanoTime}. Loading reads exactly the models the operating-point table names, so an
 * extra, missing or altered model stops the engine before any session exists (fail closed).
 */
public final class DesktopEngines {

    public static final String YUNET = StandardEngines.YUNET;
    public static final String LANDMARKS = StandardEngines.LANDMARKS;
    public static final String MINIFAS_V2 = StandardEngines.MINIFAS_V2;
    public static final String MINIFAS_V1SE = StandardEngines.MINIFAS_V1SE;
    public static final String SFACE = StandardEngines.SFACE;

    private final StandardEngines engines;

    public DesktopEngines(EngineBundle bundle, JwsTrust deviceTrust, SecureRandom random) {
        this.engines = new StandardEngines(Objects.requireNonNull(bundle, "bundle"), deviceTrust, random,
                new ImageIoFrameDecoder(), IsoFaceDecoder::decodeImage, System::nanoTime);
    }

    /** Policy and table from {@code configDir}, and exactly the table's models from {@code modelsDir}. */
    public static EngineBundle loadBundle(Path configDir, Path modelsDir) throws IOException, EngineUnavailableException {
        return loadBundle(new DirectoryConfiguration(configDir), modelsDir);
    }

    /** Policy and table from a configuration source (#78), and exactly the models the table names. */
    public static EngineBundle loadBundle(ConfigurationSource config, Path modelsDir)
            throws IOException, EngineUnavailableException {
        return StandardEngines.loadBundle(config.policyYaml(), config.operatingPointsJson(),
                name -> Files.readAllBytes(modelsDir.resolve(name)));
    }

    /** Signed: {@code bundle.jws} beside the policy must name exactly these bytes, and not be older than the last run. */
    public static EngineBundle loadSignedBundle(ConfigurationSource config, Path modelsDir, String manifestJws,
            pramaan.core.crypto.JwsTrust bundleTrust, pramaan.core.policy.RollbackGuard rollback)
            throws IOException, EngineUnavailableException {
        return StandardEngines.loadSignedBundle(manifestJws, bundleTrust, config.policyYaml(), config.operatingPointsJson(),
                name -> Files.readAllBytes(modelsDir.resolve(name)), rollback);
    }

    public EngineBundle bundle() {
        return engines.bundle();
    }

    public JwsTrust deviceTrust() {
        return engines.deviceTrust();
    }

    /** The engine for one session, once the capture session has measured the frame rate. */
    public CaptureSession.EngineFactory factory(LivenessSession session, FaceFrameSource source, EngineListener listener,
            int requestedScore) {
        return factory(session, source, listener, requestedScore, null, null);
    }

    /** As above, with diagnostic mode attached when both a sink and an audited grant are given. */
    public CaptureSession.EngineFactory factory(LivenessSession session, FaceFrameSource source, EngineListener listener,
            int requestedScore, DiagnosticSink diagnostics, DiagnosticGrant grant) {
        return (caps, device) -> engines.create(session, source, listener, requestedScore, caps, device, diagnostics, grant);
    }

    /** Embeds the face in a signed capture with the bundle SFace model, for the scaffold matcher and onboarding. */
    public CaptureAnalyser captureAnalyser() {
        return engines.captureAnalyser();
    }
}
