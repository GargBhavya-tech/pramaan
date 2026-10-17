/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.audit.AuditLog;
import pramaan.core.auth.OperatorAuthentication;
import pramaan.core.auth.PacketApproval;
import pramaan.core.binding.VerifiedCapture;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.engine.DiagnosticGrant;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.engine.SceneContext;
import pramaan.core.engine.Station;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.SceneFingerprint;
import pramaan.core.policy.RollbackGuard;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.ExceptionOutcome;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.session.SupervisorVerdict;
import pramaan.core.source.SignedCapture;
import pramaan.desktop.IsoFaceDecoder;
import pramaan.desktop.capture.CaptureScreenModel;
import pramaan.desktop.capture.CaptureSession;
import pramaan.desktop.capture.DesktopEngines;
import pramaan.desktop.capture.DiagnosticsWindow;
import pramaan.desktop.capture.DirectoryConfiguration;
import pramaan.desktop.capture.FaceCaptureView;
import pramaan.desktop.capture.FlashWindow;
import pramaan.desktop.capture.Messages;
import pramaan.desktop.capture.Speech;
import pramaan.desktop.capture.Text;
import pramaan.desktop.capture.WindowsCameraInventory;
import pramaan.desktop.ui.Banner;
import pramaan.desktop.ui.Dialogs;
import pramaan.desktop.ui.Panels;
import pramaan.desktop.ui.StationView;
import pramaan.desktop.ui.Theme;
import pramaan.sbi.SbiConfig;
import pramaan.sbi.SbiFrameSource;

/**
 * A faithful scaffold of the Registration Client flows PRAMAAN protects (ADR 0003), all on one face capture
 * screen: operator login — strict liveness, then the match (#76); the resident face capture (#75); supervisor
 * approval by a fresh liveness session bound to this packet (#77); and an audited, policy-gated diagnostic
 * window (#78). It is labelled as a scaffold on screen; it is not the MOSIP client.
 *
 * <p>This class is the wiring: what each button does. How the window looks is {@link StationView} and
 * {@link Panels}, so the screens can be rendered and tested without an engine.
 *
 * <p>Fails closed: if the policy, the operating-point table or a model does not verify, nothing can start and
 * the screen says face verification is unavailable.
 */
public final class RegistrationScaffold extends Application {

    private Messages messages;
    private volatile EvidenceStore evidence;
    /** The logged-in operator's face: a resident capture matching it is refused (Bible Part 15.3; Build Map #88). */
    private float[] operatorFace;
    private final SessionRegistry registry = new SessionRegistry();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();
    private ScaffoldConfig config;
    private volatile DesktopEngines engines;
    private volatile SceneContext scene;
    private final AtomicBoolean cameraMoveAudited = new AtomicBoolean();
    private OperatorGallery gallery;
    private AuditLog audit;
    private FaceCaptureView view;
    private StationView station;
    private Stage stage;
    private volatile CaptureSession capture;
    private DiagnosticsWindow diagnostics;
    private DiagnosticGrant grant;
    private String operator;

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        config = ScaffoldConfig.parse(getParameters().getRaw());
        gallery = new OperatorGallery(config.galleryDir);
        messages = Messages.forLocale(config.language.isEmpty() ? Locale.getDefault() : Locale.forLanguageTag(config.language));
        view = new FaceCaptureView(messages, config.speech ? Speech.forPlatform() : Speech.NONE);
        station = new StationView(messages, view, new StationView.Actions() {
            @Override
            public void toggleLanguage() {
                messages = Messages.forLocale(messages.isHindi() ? Locale.ROOT : Locale.forLanguageTag("hi"));
                station.retext(messages);
            }

            @Override
            public void retakeBackdrop() {
                RegistrationScaffold.this.retakeBackdrop();
            }

            @Override
            public void diagnostics() {
                activateDiagnostics();
            }
        });

        Scene sc = new Scene(station, 1280, 800);
        Theme.apply(sc);
        stage.setTitle("PRAMAAN — Registration Client scaffold");
        stage.setScene(sc);
        stage.setMinWidth(1180);
        stage.setMinHeight(720);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        station.setPanel(new Panels.Loading());
        station.setToolsEnabled(false);
        view.show(new CaptureScreenModel(s -> { }).current());
        Thread loader = new Thread(this::loadEngine, "pramaan-bundle-loader");
        loader.setDaemon(true);
        loader.start();
    }

    // ------------------------------------------------------------------------------------------ start-up

    /**
     * What this workstation has around the SBI device (Bible Part 31.2): the monitor facing the resident, if one is
     * configured, and the stored fingerprint of the empty backdrop, if one exists. A configured part that cannot be
     * had — no such monitor, an unreadable fingerprint — stops the start-up: a station that was set up for a flash
     * or a backdrop check must not quietly run without it.
     */
    private Station station() throws IOException {
        WindowsCameraInventory cameras = new WindowsCameraInventory();
        cameras.warmUp(); // the slow Plug-and-Play read happens now, in the background, not at the first scan
        Station.Builder b = Station.builder().hostCameras(cameras);
        if (config.flashScreen >= 0) {
            b.flashLight(new FlashWindow(config.flashScreen));
        }
        if (config.streamSignature != null) {
            b.streamSignature(pramaan.core.source.StreamSignatureProfile.fromJson(
                    Files.readString(config.streamSignature, java.nio.charset.StandardCharsets.UTF_8)));
        }
        if (config.backdrop != null) {
            scene = new SceneContext(SceneFingerprint.fromBytes(Files.readAllBytes(config.backdrop)),
                    config.segmenter.equals("backdrop") ? new pramaan.core.pad.BackdropSubtractionSegmenter() : null);
            b.scene(scene);
        }
        return b.build();
    }

    /** A camera that has been moved since the backdrop was fingerprinted needs an audited re-fingerprint (Part 43). */
    private void noteCameraMoved() {
        SceneContext s = scene;
        if (s != null && s.cameraMoved() && cameraMoveAudited.compareAndSet(false, true)) {
            audit(AuditEvent.of("BACKDROP_CAMERA_MOVED").put("backdrop", config.backdrop.toString()).build());
        }
    }

    /** Configuration (#78: read at run time, never built in), table and models are verified once, off the UI thread. */
    private void loadEngine() {
        try {
            Files.createDirectories(config.auditLog.toAbsolutePath().getParent());
            audit = AuditLog.open(config.auditLog, clock);
            DirectoryConfiguration source = new DirectoryConfiguration(config.configDir);
            EngineBundle bundle;
            if (config.bundleKey != null) {
                // Signed bundle (Bible Part 17.2): nothing loads unless the manifest's signature and every hash hold.
                JwsTrust bundleTrust = JwsTrust.builder(clock).key("bundle", java.security.KeyFactory.getInstance("RSA")
                        .generatePublic(new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(config.bundleKey)))).build();
                String manifest = Files.readString(config.configDir.resolve("bundle.jws"));
                bundle = DesktopEngines.loadSignedBundle(source, config.modelsDir, manifest, bundleTrust,
                        RollbackGuard.file(config.rollbackFile));
            } else {
                bundle = DesktopEngines.loadBundle(source, config.modelsDir);
            }
            JwsTrust trust = deviceTrust();
            engines = new DesktopEngines(bundle, trust, random, station());
            evidence = new EvidenceStore(config.evidenceDir, bundle, trust, random, clock);
            audit(AuditEvent.of("CONFIGURATION_LOADED").put("source", source.describe())
                    .put("policySha256", bundle.policySha256()).put("operatingPointsSha256", bundle.operatingPointsSha256())
                    .put("bundleSigned", bundle.signed()).put("bundleVersion", bundle.bundleVersion())
                    .put("diagnosticMode", bundle.policy().diagnosticMode()).build());
            boolean stale = bundle.stale(clock.instant());
            Platform.runLater(() -> {
                // The scaffold never syncs: it is always offline, and says so (Build Map #94).
                station.setOffline(true, stale);
                station.setToolsEnabled(true);
                showLogin();
            });
        } catch (Exception e) {
            System.err.println("PRAMAAN: liveness unavailable: " + e);
            CaptureScreenModel m = new CaptureScreenModel(view::show);
            m.engineUnavailable();
            Platform.runLater(() -> station.setPanel(new Panels.Unavailable()));
        }
    }

    private JwsTrust deviceTrust() throws IOException, GeneralSecurityException {
        X509Certificate anchor = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(config.deviceCa)));
        return JwsTrust.builder(clock).anchor(anchor).build();
    }

    // ------------------------------------------------------------------------------------------ #76 operator login

    private void showLogin() {
        station.setStep(StationView.Step.LOGIN);
        station.setOperator(null);
        Panels.Login p = new Panels.Login(config.operatorId);
        p.submit.setOnAction(e -> faceCheck(Workflow.OPERATOR, p.id.getText().trim(), null, engine -> {
            if (engine == null) {
                return;
            }
            OperatorAuthentication.Outcome o = OperatorAuthentication.authenticate(p.id.getText().trim(), engine,
                    new EnrolledFaceMatcher(gallery, engines.captureAnalyser(), engines.bundle().table().thetaSame()),
                    this::audit);
            if (o == OperatorAuthentication.Outcome.AUTHENTICATED) {
                operator = p.id.getText().trim();
                try {
                    // The face that just logged in is the one no resident capture may be (Bible Part 15.3).
                    operatorFace = engines.captureAnalyser().embed(engine.acceptedCapture().orElseThrow());
                } catch (Exception ex) {
                    operatorFace = null;
                    System.err.println("PRAMAAN: operator face unavailable for the cross-check: " + ex.getMessage());
                }
                showRegistration();
            } else {
                p.say(Panels.loginResult(o));
            }
        }));
        p.onboard.setOnAction(e -> faceCheck(Workflow.OPERATOR, p.id.getText().trim(), null,
                engine -> p.say(onboard(p.id.getText().trim(), engine))));
        station.setPanel(p);
    }

    /**
     * Scaffold onboarding: the enrolled face is taken from a capture that passed strict liveness and binding, so
     * a photo cannot be enrolled either. (The real client onboards through its own supervised flow.)
     */
    private Panels.Notice onboard(String operatorId, LivenessEngine engine) {
        Optional<SignedCapture> c = engine == null ? Optional.empty() : engine.acceptedCapture();
        if (c.isEmpty()) {
            return new Panels.Notice(Banner.Kind.WARNING, Text.of("pramaan.ui.login.incomplete"));
        }
        try {
            float[] e = engines.captureAnalyser().embed(c.get());
            if (e == null) {
                return new Panels.Notice(Banner.Kind.WARNING, Text.of("pramaan.ui.onboard.one-face"));
            }
            gallery.enrol(operatorId, e);
            audit(AuditEvent.of("OPERATOR_ONBOARDED").put("operatorId", operatorId)
                    .put("sessionId", engine.session().sessionId()).build());
            return new Panels.Notice(Banner.Kind.SUCCESS, Text.of("pramaan.ui.onboard.done"));
        } catch (Exception ex) {
            System.err.println("PRAMAAN: onboarding failed: " + ex);
            return new Panels.Notice(Banner.Kind.ERROR, Text.of("pramaan.ui.onboard.failed"));
        }
    }

    // ------------------------------------------------------------------------------------------ #75 resident capture

    private void showRegistration() {
        station.setStep(StationView.Step.RESIDENT);
        station.setOperator(operator);
        Panels.Resident p = new Panels.Resident(config.subjectId, engines.bundle().policy().accessibilityProfiles());
        // Build Map #92: the operator selects accessibility profiles before the scan; they shape the challenges and
        // are recorded in the evidence and the audit log.
        p.scan.setOnAction(e -> {
            var chosen = p.chosenProfiles();
            faceCheck(Workflow.RESIDENT, config.subjectId, null, session -> {
                session.useAccessibilityProfiles(chosen);
                if (operatorFace != null) {
                    session.guardAgainstOperatorFace(operatorFace);
                }
            }, engine -> {
                if (engine != null && engine.session().state() == SessionState.ACCEPTED) {
                    p.setPhoto(capturedPhoto(engine.acceptedCapture()));
                    p.say(storeEvidence(engine));
                    p.next.setDisable(false);
                } else if (engine != null && engine.session().state() == SessionState.EXCEPTION_FLOW) {
                    showException(engine);
                }
            });
        });
        p.next.setOnAction(e -> showApproval());
        station.setPanel(p);
    }

    /** ADR 0003 patch P4: signed evidence into the face BIR's Others, and the attempt into the transparency log. */
    private Panels.Notice storeEvidence(LivenessEngine engine) {
        try {
            Path packet = evidence.storeAccepted(engine, operator, config.centreId);
            return new Panels.Notice(Banner.Kind.SUCCESS,
                    Text.of("pramaan.ui.resident.stored", Map.of("file", packet.getFileName().toString())));
        } catch (Exception ex) {
            // The capture passed, but without its evidence the packet is not complete: say so, never pretend.
            System.err.println("PRAMAAN: evidence could not be stored: " + ex);
            return new Panels.Notice(Banner.Kind.ERROR, Text.of("pramaan.ui.resident.store-failed"));
        }
    }

    // ------------------------------------------------------------------------------------------ #93 exception flow

    /**
     * The resident cannot pass (locked out, or no action they can do): a supervisor, not the operator, approves an
     * exception with a fixed reason, by passing their own fresh strict liveness session bound to this resident's
     * session (Bible Part 12; Build Map #50, #77). The last capture's own decision is the exception photograph's
     * check: a photo of a photo blocks the path for good.
     */
    private void showException(LivenessEngine resident) {
        station.setStep(StationView.Step.EXCEPTION);
        Panels.ExceptionApproval p = new Panels.ExceptionApproval("supervisor-1");
        LivenessSession session = resident.session();
        p.approve.setOnAction(e -> faceCheck(Workflow.SUPERVISOR, p.supervisor.getText().trim(), session.sessionId(), s -> { },
                sup -> {
                    SupervisorVerdict verdict = sup == null ? null : sup.session().supervisorVerdict().orElse(null);
                    Optional<pramaan.core.fusion.Decision> photo = resident.decision();
                    if (photo.isEmpty()) {
                        p.say(Banner.Kind.WARNING, Text.of("pramaan.ui.exception.no-photo"));
                        return;
                    }
                    ExceptionOutcome o = session.requestException(p.reason.getValue(), verdict, photo.get());
                    p.say(Panels.exceptionResult(o));
                    p.next.setDisable(o != ExceptionOutcome.APPROVED);
                    p.approve.setDisable(session.state() != SessionState.EXCEPTION_FLOW);
                }));
        p.next.setOnAction(e -> showApproval());
        station.setPanel(p);
    }

    // ------------------------------------------------------------------------------------------ #77 supervisor approval

    private void showApproval() {
        byte[] b = new byte[4];
        random.nextBytes(b);
        String packetId = String.format("P-%s-%02x%02x%02x%02x", config.subjectId, b[0], b[1], b[2], b[3]);
        station.setStep(StationView.Step.APPROVAL);
        Panels.Approval p = new Panels.Approval(packetId, "supervisor-1");
        p.approve.setOnAction(e -> faceCheck(Workflow.SUPERVISOR, p.supervisor.getText().trim(), packetId, engine -> {
            SupervisorVerdict verdict = engine == null ? null : engine.session().supervisorVerdict().orElse(null);
            PacketApproval.Outcome o = PacketApproval.approve(packetId, operator, verdict, engines.bundle().policy(),
                    registry, clock.instant(), this::audit);
            p.say(Panels.approvalResult(o));
            p.another.setDisable(o != PacketApproval.Outcome.APPROVED);
        }));
        p.another.setOnAction(e -> showRegistration());
        station.setPanel(p);
    }

    // ------------------------------------------------------------------------------------------ #78 diagnostics

    /** Policy-gated, and audited whether it is granted or refused (Bible Part 19.3). */
    private void activateDiagnostics() {
        if (engines == null) {
            return;
        }
        Optional<Dialogs.Entry> entry = Dialogs.askAudited(stage, stage.getScene(), messages, "pramaan.ui.diag.title",
                "pramaan.ui.diag.body", "pramaan.ui.diag.reason-hint", null);
        if (entry.isEmpty()) {
            return;
        }
        try {
            grant = DiagnosticGrant.activate(engines.bundle(), entry.get().adminId(), entry.get().reason(), clock.instant(),
                    this::audit);
            diagnostics = new DiagnosticsWindow(grant.adminId(), messages, stage.getScene());
            diagnostics.show();
            station.setDiagnostic(true);
        } catch (IllegalStateException refused) {
            Dialogs.notify(stage, stage.getScene(), messages, "pramaan.ui.diag.title", Banner.Kind.WARNING,
                    Text.of("pramaan.ui.diag.disabled"));
        }
    }

    // ------------------------------------------------------------------------------------------ #87 backdrop

    /**
     * Replaces the stored fingerprint of the empty backdrop (Bible Part 43): needs an administrator id, a reason, the
     * operator's word that the room is empty, and a photograph of it. Audited whether it succeeds or not. Takes effect
     * for the next session; one already running keeps the backdrop it started with.
     */
    private void retakeBackdrop() {
        DesktopEngines current = engines;
        if (current == null || station.panel() == null || station.panel().isDisabled()) {
            return;
        }
        if (config.backdrop == null) {
            Dialogs.notify(stage, stage.getScene(), messages, "pramaan.ui.backdrop.title", Banner.Kind.INFO,
                    Text.of("pramaan.ui.backdrop.no-file"));
            return;
        }
        Optional<Dialogs.Entry> entry = Dialogs.askAudited(stage, stage.getScene(), messages, "pramaan.ui.backdrop.title",
                "pramaan.ui.backdrop.body", "pramaan.ui.backdrop.reason-hint", "pramaan.ui.backdrop.empty-room");
        if (entry.isEmpty()) {
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(messages.resolve(Text.of("pramaan.ui.backdrop.choose")));
        java.io.File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        try {
            BackdropRetake.Result r = BackdropRetake.retake(Files.readAllBytes(file.toPath()), config.backdrop,
                    entry.get().adminId(), entry.get().reason(), true, clock, this::audit);
            Map<String, String> corners = Map.of("corners", Integer.toString(r.corners()));
            switch (r.outcome()) {
                case SAVED:
                    engines = new DesktopEngines(current.bundle(), current.deviceTrust(), random, station());
                    cameraMoveAudited.set(false);
                    Dialogs.notify(stage, stage.getScene(), messages, "pramaan.ui.backdrop.title", Banner.Kind.SUCCESS,
                            Text.of("pramaan.ui.backdrop.saved", corners));
                    break;
                case TOO_PLAIN:
                    Dialogs.notify(stage, stage.getScene(), messages, "pramaan.ui.backdrop.title", Banner.Kind.WARNING,
                            Text.of("pramaan.ui.backdrop.too-plain", corners));
                    break;
                default:
                    Dialogs.notify(stage, stage.getScene(), messages, "pramaan.ui.backdrop.title", Banner.Kind.WARNING,
                            Text.of("pramaan.ui.backdrop.not-image"));
            }
        } catch (IOException e) {
            System.err.println("PRAMAAN: the backdrop could not be saved: " + e);
            Dialogs.notify(stage, stage.getScene(), messages, "pramaan.ui.backdrop.title", Banner.Kind.ERROR,
                    Text.of("pramaan.ui.backdrop.failed"));
        }
    }

    // ------------------------------------------------------------------------------------------ one face check

    /**
     * One liveness session on the capture screen, for any workflow; {@code done} runs once on the FX thread when
     * it has ended — accepted, or stopped — with the engine (null if none was ever built).
     */
    private void faceCheck(Workflow workflow, String subjectId, String approves, Consumer<LivenessEngine> done) {
        faceCheck(workflow, subjectId, approves, s -> { }, done);
    }

    /** @param prepare runs on the new session before it starts: accessibility profiles, the operator-face guard */
    private void faceCheck(Workflow workflow, String subjectId, String approves, Consumer<LivenessSession> prepare,
            Consumer<LivenessEngine> done) {
        if (subjectId.isEmpty()) {
            return;
        }
        busy(true);
        closeCapture();
        view.clearPreview();
        DesktopEngines e = engines;
        AtomicBoolean finished = new AtomicBoolean();
        CaptureSession[] cs = new CaptureSession[1];
        CaptureScreenModel model = new CaptureScreenModel(s -> {
            view.show(s);
            if ((s.stage() == CaptureScreenModel.Stage.SUCCESS || s.stage() == CaptureScreenModel.Stage.STOPPED)
                    && finished.compareAndSet(false, true)) {
                Platform.runLater(() -> {
                    LivenessEngine engine = cs[0] == null ? null : cs[0].engine().orElse(null);
                    closeCapture();
                    noteCameraMoved();
                    busy(false);
                    done.accept(engine);
                });
            }
        });
        view.show(model.current());
        LivenessSession session = workflow == Workflow.SUPERVISOR
                ? e.bundle().newSupervisorSession(subjectId, approves, "sbi-face", config.centreId, registry, this::audit,
                        clock, random)
                : e.bundle().newSession(workflow, subjectId, workflow == Workflow.RESIDENT ? operator : subjectId,
                        "sbi-face", config.centreId, registry, this::audit, clock, random);
        EvidenceStore store = evidence;
        session.onAttemptEnded(store::attemptEnded); // every attempt, pass or fail, into the transparency log (#64)
        prepare.accept(session);
        SbiFrameSource source = new SbiFrameSource(SbiConfig.builder(e.deviceTrust(), clock)
                .host(config.host).ports(config.firstPort, config.lastPort).build());
        cs[0] = new CaptureSession(source,
                e.factory(session, source, model, config.requestedScore, grant == null ? null : diagnostics, grant),
                view.previewSink(), new CaptureSession.Problems() {
                    @Override
                    public void deviceUnavailable(Exception cause) {
                        System.err.println("PRAMAAN: face device unavailable: " + cause.getMessage());
                        model.deviceUnavailable();
                    }

                    @Override
                    public void engineUnavailable(Exception cause) {
                        System.err.println("PRAMAAN: liveness unavailable: " + cause);
                        model.engineUnavailable();
                    }
                });
        if (diagnostics != null) {
            cs[0].setEngineTimer(diagnostics::engineTime);
        }
        capture = cs[0];
        Thread starter = new Thread(() -> {
            if (cs[0].start()) {
                cs[0].startTicker(Duration.ofMillis(250));
            }
        }, "pramaan-device-search");
        starter.setDaemon(true);
        starter.start();
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** While a scan runs the panel and the station tools are off, so nothing can be changed under it. */
    private void busy(boolean on) {
        if (station.panel() != null) {
            station.panel().setDisable(on);
        }
        station.setToolsEnabled(!on);
    }

    /** The accepted capture, decoded from the signed ISO record the binder verified: what would be stored. */
    private javafx.scene.image.Image capturedPhoto(Optional<SignedCapture> c) {
        if (c.isEmpty()) {
            return null;
        }
        try {
            return toFx(IsoFaceDecoder.decode(VerifiedCapture.verify(c.get(), engines.deviceTrust()).isoRecord()));
        } catch (Exception ex) {
            System.err.println("PRAMAAN: could not show the capture: " + ex.getMessage());
            return null;
        }
    }

    private static WritableImage toFx(RgbImage img) {
        WritableImage out = new WritableImage(img.width(), img.height());
        PixelWriter w = out.getPixelWriter();
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                w.setArgb(x, y, 0xFF000000 | img.get(x, y, 0) << 16 | img.get(x, y, 1) << 8 | img.get(x, y, 2));
            }
        }
        return out;
    }

    private void audit(AuditEvent e) {
        try {
            audit.append(e);
        } catch (IOException ex) {
            System.err.println("PRAMAAN: audit write failed: " + ex.getMessage());
        }
    }

    private void closeCapture() {
        CaptureSession cs = capture;
        capture = null;
        if (cs != null) {
            Thread t = new Thread(cs::close, "pramaan-stream-stop");
            t.setDaemon(true);
            t.start();
        }
    }

    private void shutdown() {
        closeCapture();
        DesktopEngines e = engines;
        if (e != null) {
            e.bundle().close();
        }
    }
}
