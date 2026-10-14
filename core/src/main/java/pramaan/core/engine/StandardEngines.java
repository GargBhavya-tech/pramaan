/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import pramaan.core.binding.SFaceEmbedder;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.image.ImageDecoder;
import pramaan.core.inference.OnnxModel;
import pramaan.core.landmarks.OnnxFaceMeshLandmarker;
import pramaan.core.passive.PassiveScorer;
import pramaan.core.pipeline.FramePipeline;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.EngineUnavailableException;
import pramaan.core.session.LivenessSession;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.FaceFrameSource;
import pramaan.core.source.FrameDecoder;
import pramaan.core.source.SessionCapabilities;

/**
 * The one way an engine is assembled, on desktop and on Android alike (Build Map #79, #82): the same model set,
 * loaded only as the operating-point table names it, the same detector settings, the same analysers. A platform
 * brings only what differs — how it decodes images and reads a monotonic clock. Two platforms that assemble the
 * engine differently would make "same decisions on both" a coincidence rather than a property.
 */
public final class StandardEngines {

    public static final String YUNET = "face_detection_yunet_2023mar.onnx";
    public static final String LANDMARKS = "mediapipe_face_landmarks_detector.onnx";
    public static final String MINIFAS_V2 = "minifasnet_v2_2.7_80x80.onnx";
    public static final String MINIFAS_V1SE = "minifasnet_v1se_4.0_80x80.onnx";
    public static final String SFACE = "face_recognition_sface_2021dec.onnx";
    /** Optional: when the table binds it, landmarks carry MediaPipe's 52 blendshape scores (ADR 0002). */
    public static final String BLENDSHAPES = "mediapipe_face_blendshapes.onnx";
    /** YuNet's detection and NMS thresholds, as the harness and the pipeline benchmark use them. */
    public static final float DETECT_SCORE = 0.8f;
    public static final float DETECT_NMS = 0.3f;

    /** Where model bytes come from: a directory on desktop, app assets on Android. */
    public interface ModelSource {
        byte[] read(String name) throws IOException;
    }

    private final EngineBundle bundle;
    private final JwsTrust deviceTrust;
    private final SecureRandom random;
    private final FrameDecoder frames;
    private final ImageDecoder images;
    private final LongSupplier monotonicNanos;

    /**
     * @param monotonicNanos the platform's monotonic clock, for stage latencies only (core reads no clock)
     */
    public StandardEngines(EngineBundle bundle, JwsTrust deviceTrust, SecureRandom random, FrameDecoder frames,
            ImageDecoder images, LongSupplier monotonicNanos) {
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        this.deviceTrust = Objects.requireNonNull(deviceTrust, "deviceTrust");
        this.random = Objects.requireNonNull(random, "random");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.images = Objects.requireNonNull(images, "images");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    /** Policy and table, and exactly the models the table names: an extra, missing or altered model stops here. */
    public static EngineBundle loadBundle(byte[] policyYaml, byte[] operatingPointsJson, ModelSource models)
            throws IOException, EngineUnavailableException {
        Map<String, byte[]> bytes = new TreeMap<>();
        for (String name : OperatingPointTable.modelNames(operatingPointsJson, "operating-points.json")) {
            bytes.put(name, models.read(name));
        }
        return EngineBundle.load(policyYaml, operatingPointsJson, bytes);
    }

    /**
     * As {@link #loadBundle}, but only if a signed manifest names exactly these bytes and is not older than the
     * last bundle run here (Bible Part 17.2).
     */
    public static EngineBundle loadSignedBundle(String manifestJws, pramaan.core.crypto.JwsTrust bundleTrust,
            byte[] policyYaml, byte[] operatingPointsJson, ModelSource models, pramaan.core.policy.RollbackGuard rollback)
            throws IOException, EngineUnavailableException {
        Map<String, byte[]> bytes = new TreeMap<>();
        for (String name : OperatingPointTable.modelNames(operatingPointsJson, "operating-points.json")) {
            bytes.put(name, models.read(name));
        }
        return EngineBundle.loadSigned(manifestJws, bundleTrust, policyYaml, operatingPointsJson, bytes, rollback);
    }

    public EngineBundle bundle() {
        return bundle;
    }

    public JwsTrust deviceTrust() {
        return deviceTrust;
    }

    /**
     * The engine for one session, built once the frame rate has been measured.
     *
     * @param diagnostics null unless an audited {@link DiagnosticGrant} is given too
     */
    public LivenessEngine create(LivenessSession session, FaceFrameSource source, EngineListener listener,
            int requestedScore, SessionCapabilities caps, DeviceCapabilities device, DiagnosticSink diagnostics,
            DiagnosticGrant grant) {
        return create(session, source, listener, requestedScore, caps, device, diagnostics, grant, Station.NONE);
    }

    /**
     * As above, for a station with extras around the SBI device (Bible Part 31.2): a resident-facing light, a second
     * camera, an iris camera and a stored backdrop. Each turns on the cues that need it; none changes how a station
     * without it runs.
     */
    public LivenessEngine create(LivenessSession session, FaceFrameSource source, EngineListener listener,
            int requestedScore, SessionCapabilities caps, DeviceCapabilities device, DiagnosticSink diagnostics,
            DiagnosticGrant grant, Station station) {
        YuNetDetector detector = new YuNetDetector(model(YUNET), DETECT_SCORE, DETECT_NMS);
        FramePipeline pipeline = new FramePipeline(frames, detector,
                new PassiveScorer(model(MINIFAS_V2), model(MINIFAS_V1SE)), caps.fps(), monotonicNanos);
        SFaceEmbedder sface = new SFaceEmbedder(model(SFACE));
        LivenessEngine.Builder b = LivenessEngine.builder(bundle, session)
                .device(source, deviceTrust, device.device().level())
                .analysers(new PipelineFrameAnalyser(pipeline, landmarker(), sface,
                        new pramaan.core.pad.ColorTextureCue(null, 0), station.scene()),
                        new SFaceCaptureAnalyser(deviceTrust, detector, sface, images))
                .flashLight(station.flashLight())
                .stereo(station.stereo())
                .pupil(station.pupil())
                .knownBackdrop(station.scene() != null)
                .hostCameras(station.hostCameras())
                .capabilities(caps)
                .random(random)
                .listener(listener)
                .requestedScore(requestedScore);
        if (diagnostics != null) {
            b.diagnostics(diagnostics, grant);
        }
        return b.build();
    }

    /** Embeds the face in a signed capture with the bundle SFace model (matchers, onboarding). */
    public CaptureAnalyser captureAnalyser() {
        return new SFaceCaptureAnalyser(deviceTrust, new YuNetDetector(model(YUNET), DETECT_SCORE, DETECT_NMS),
                new SFaceEmbedder(model(SFACE)), images);
    }

    /** FaceMesh, with blendshapes on top when the verified bundle includes that model. */
    private pramaan.core.landmarks.LandmarkProvider landmarker() {
        OnnxFaceMeshLandmarker mesh = new OnnxFaceMeshLandmarker(model(LANDMARKS));
        return bundle.models().containsKey(BLENDSHAPES)
                ? new pramaan.core.landmarks.OnnxBlendshapeLandmarker(mesh, model(BLENDSHAPES)) : mesh;
    }

    private OnnxModel model(String name) {
        OnnxModel m = bundle.models().get(name);
        if (m == null) {
            throw new IllegalStateException("the verified bundle has no model " + name);
        }
        return m;
    }
}
