/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import pramaan.core.policy.AccessibilityProfile;
import pramaan.core.policy.RollbackGuard;
import pramaan.core.session.ExceptionOutcome;
import pramaan.core.session.ExceptionReason;
import pramaan.desktop.capture.Speech;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
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
import pramaan.core.image.RgbImage;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.session.SupervisorVerdict;
import pramaan.core.source.SignedCapture;
import pramaan.desktop.IsoFaceDecoder;
import pramaan.desktop.capture.CaptureScreenModel;
import pramaan.desktop.capture.CaptureSession;
import pramaan.core.engine.SceneContext;
import pramaan.core.engine.Station;
import pramaan.core.pad.SceneFingerprint;
import pramaan.desktop.capture.DesktopEngines;
import pramaan.desktop.capture.FlashWindow;
import pramaan.desktop.capture.DiagnosticsWindow;
import pramaan.desktop.capture.DirectoryConfiguration;
import pramaan.desktop.capture.FaceCaptureView;
import pramaan.desktop.capture.Messages;
import pramaan.sbi.SbiConfig;
import pramaan.sbi.SbiFrameSource;

/**
 * A faithful scaffold of the Registration Client flows PRAMAAN protects (ADR 0003), all on one face capture
 * screen: operator login — strict liveness, then the match (#76); the resident face capture (#75); supervisor
 * approval by a fresh liveness session bound to this packet (#77); and an audited, policy-gated diagnostic
 * window (#78). It is labelled as a scaffold on screen; it is not the MOSIP client.
 *
 * <p>Fails closed: if the policy, the operating-point table or a model does not verify, nothing can start and
 * the screen says face verification is unavailable.
 */
public final class RegistrationScaffold extends Application {

    private Messages messages;
    private volatile EvidenceStore evidence;
    /** The logged-in operator's face: a resident capture matching it is refused (Bible Part 15.3; Build Map #88). */
    private float[] operatorFace;
    private final Label badges = new Label();
    private final SessionRegistry registry = new SessionRegistry();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();
    private final ImageView captured = new ImageView();
    private final VBox form = new VBox(10);
    private final Label step = new Label();
    private final Button diagnosticsButton = new Button("Diagnostics…");
    private ScaffoldConfig config;
    private volatile DesktopEngines engines;
    private volatile SceneContext scene;
    private final AtomicBoolean cameraMoveAudited = new AtomicBoolean();
    private OperatorGallery gallery;
    private AuditLog audit;
    private FaceCaptureView view;
    private volatile CaptureSession capture;
    private DiagnosticsWindow diagnostics;
    private DiagnosticGrant grant;
    private String operator;

    @Override
    public void start(Stage stage) {
        config = ScaffoldConfig.parse(getParameters().getRaw());
        gallery = new OperatorGallery(config.galleryDir);
        messages = Messages.forLocale(config.language.isEmpty() ? Locale.getDefault() : Locale.forLanguageTag(config.language));
        view = new FaceCaptureView(messages, config.speech ? Speech.forPlatform() : Speech.NONE);
        captured.setFitWidth(150);
        captured.setFitHeight(190);
        captured.setPreserveRatio(true);

        Label title = new Label("Registration Client");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label tag = new Label("PRAMAAN scaffold — not the MOSIP Registration Client (ADR 0003)");
        tag.setStyle("-fx-text-fill: #52606d;");
        step.setStyle("-fx-font-size: 14px;");
        diagnosticsButton.setOnAction(e -> activateDiagnostics());
        badges.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 12px;");
        HBox topRow = new HBox(16, new VBox(2, title, tag), step, badges);
        topRow.setAlignment(Pos.CENTER_LEFT);
        topRow.setPadding(new Insets(12, 16, 4, 16));

        form.setPadding(new Insets(16));
        form.setPrefWidth(250);
        VBox result = new VBox(6, new Label("Captured photo"), captured);
        result.setPadding(new Insets(16));
        result.setAlignment(Pos.TOP_CENTER);
        HBox bottom = new HBox(diagnosticsButton);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        bottom.setPadding(new Insets(8, 16, 12, 16));

        BorderPane root = new BorderPane(view, topRow, result, bottom, form);
        stage.setTitle("PRAMAAN — Registration Client scaffold");
        stage.setScene(new Scene(root, 1040, 720));
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        form.setDisable(true);
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
        Station.Builder b = Station.builder();
        if (config.flashScreen >= 0) {
            b.flashLight(new FlashWindow(config.flashScreen));
        }
        if (config.backdrop != null) {
            scene = new SceneContext(SceneFingerprint.fromBytes(Files.readAllBytes(config.backdrop)));
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
                String text = messages.resolve(pramaan.desktop.capture.Text.of("pramaan.status.offline"));
                if (stale) {
                    text += "   " + messages.resolve(pramaan.desktop.capture.Text.of("pramaan.status.stale-policy"));
                    badges.setStyle("-fx-text-fill: #b3261e; -fx-font-size: 12px;");
                }
                badges.setText(text);
                showLogin();
            });
        } catch (Exception e) {
            System.err.println("PRAMAAN: liveness unavailable: " + e);
            CaptureScreenModel m = new CaptureScreenModel(view::show);
            m.engineUnavailable();
        }
    }

    private JwsTrust deviceTrust() throws IOException, GeneralSecurityException {
        X509Certificate anchor = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(config.deviceCa)));
        return JwsTrust.builder(clock).anchor(anchor).build();
    }

    // ------------------------------------------------------------------------------------------ #76 operator login

    private void showLogin() {
        step.setText("1  Operator login");
        TextField id = new TextField(config.operatorId);
        Label status = new Label();
        status.setWrapText(true);
        Button login = new Button("Log in with face");
        login.setDefaultButton(true);
        Button onboard = new Button("Onboard my face");
        login.setOnAction(e -> faceCheck(Workflow.OPERATOR, id.getText().trim(), null, engine -> {
            if (engine == null) {
                return;
            }
            OperatorAuthentication.Outcome o = OperatorAuthentication.authenticate(id.getText().trim(), engine,
                    new EnrolledFaceMatcher(gallery, engines.captureAnalyser(), engines.bundle().table().thetaSame()),
                    this::audit);
            if (o == OperatorAuthentication.Outcome.AUTHENTICATED) {
                operator = id.getText().trim();
                try {
                    // The face that just logged in is the one no resident capture may be (Bible Part 15.3).
                    operatorFace = engines.captureAnalyser().embed(engine.acceptedCapture().orElseThrow());
                } catch (Exception ex) {
                    operatorFace = null;
                    System.err.println("PRAMAAN: operator face unavailable for the cross-check: " + ex.getMessage());
                }
                showRegistration();
            } else {
                status.setText(loginMessage(o));
            }
        }));
        onboard.setOnAction(e -> faceCheck(Workflow.OPERATOR, id.getText().trim(), null,
                engine -> status.setText(onboard(id.getText().trim(), engine))));
        setForm(new Label("Operator id"), id, login, onboard, status);
    }

    private static String loginMessage(OperatorAuthentication.Outcome o) {
        switch (o) {
            case FACE_NOT_MATCHED:
                return "Face not recognised for this operator.";
            case MATCH_UNAVAILABLE:
                return "Face matching is unavailable. Please contact support.";
            default:
                return "Face verification could not be completed. Please try again.";
        }
    }

    /**
     * Scaffold onboarding: the enrolled face is taken from a capture that passed strict liveness and binding, so
     * a photo cannot be enrolled either. (The real client onboards through its own supervised flow.)
     */
    private String onboard(String operatorId, LivenessEngine engine) {
        Optional<SignedCapture> c = engine == null ? Optional.empty() : engine.acceptedCapture();
        if (c.isEmpty()) {
            return "Face verification could not be completed. Please try again.";
        }
        try {
            float[] e = engines.captureAnalyser().embed(c.get());
            if (e == null) {
                return "The capture did not show exactly one face. Please try again.";
            }
            gallery.enrol(operatorId, e);
            audit(AuditEvent.of("OPERATOR_ONBOARDED").put("operatorId", operatorId)
                    .put("sessionId", engine.session().sessionId()).build());
            return "Face enrolled. You can now log in with your face.";
        } catch (Exception ex) {
            return "Onboarding failed: " + ex.getMessage();
        }
    }

    // ------------------------------------------------------------------------------------------ #75 resident capture

    private void showRegistration() {
        step.setText("2  Resident biometrics — operator " + operator);
        captured.setImage(null);
        Label subject = new Label("Registration " + config.subjectId);
        Button scan = new Button("Scan face");
        scan.setDefaultButton(true);
        Button next = new Button("Next: supervisor approval");
        next.setDisable(true);
        Label status = new Label();
        status.setWrapText(true);
        // Build Map #92: the operator selects accessibility profiles before the scan; they shape the challenges and
        // are recorded in the evidence and the audit log.
        VBox profiles = new VBox(4, new Label("Accessibility (operator selects)"));
        java.util.List<CheckBox> boxes = new java.util.ArrayList<>();
        for (AccessibilityProfile p : engines.bundle().policy().accessibilityProfiles()) {
            CheckBox b = new CheckBox(p.name());
            b.setUserData(p);
            boxes.add(b);
            profiles.getChildren().add(b);
        }
        scan.setOnAction(e -> {
            Set<AccessibilityProfile> chosen = EnumSet.noneOf(AccessibilityProfile.class);
            boxes.stream().filter(CheckBox::isSelected).forEach(b -> chosen.add((AccessibilityProfile) b.getUserData()));
            faceCheck(Workflow.RESIDENT, config.subjectId, null, session -> {
                session.useAccessibilityProfiles(chosen);
                if (operatorFace != null) {
                    session.guardAgainstOperatorFace(operatorFace);
                }
            }, engine -> {
                if (engine != null && engine.session().state() == SessionState.ACCEPTED) {
                    showCapture(engine.acceptedCapture());
                    status.setText(storeEvidence(engine));
                    next.setDisable(false);
                } else if (engine != null && engine.session().state() == SessionState.EXCEPTION_FLOW) {
                    showException(engine);
                }
            });
        });
        next.setOnAction(e -> showApproval());
        setForm(new Label("Face"), subject, profiles, scan, next, status);
    }

    /** ADR 0003 patch P4: signed evidence into the face BIR's Others, and the attempt into the transparency log. */
    private String storeEvidence(LivenessEngine engine) {
        try {
            Path packet = evidence.storeAccepted(engine, operator, config.centreId);
            return "Packet written with signed liveness evidence: " + packet.getFileName();
        } catch (Exception ex) {
            // The capture passed, but without its evidence the packet is not complete: say so, never pretend.
            System.err.println("PRAMAAN: evidence could not be stored: " + ex);
            return "The liveness evidence could not be stored. Do not continue with this packet.";
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
        step.setText("2b  Exception — supervisor approval required");
        ComboBox<ExceptionReason> reason = new ComboBox<>();
        reason.getItems().setAll(ExceptionReason.values());
        reason.getSelectionModel().selectFirst();
        TextField supervisor = new TextField("supervisor-1");
        Label status = new Label("Face verification could not be completed for this resident. A supervisor must approve"
                + " an exception, with a reason.");
        status.setWrapText(true);
        Button approve = new Button("Approve exception with supervisor face");
        Button next = new Button("Next: supervisor approval");
        next.setDisable(true);
        LivenessSession session = resident.session();
        approve.setOnAction(e -> faceCheck(Workflow.SUPERVISOR, supervisor.getText().trim(), session.sessionId(), s -> { },
                sup -> {
                    SupervisorVerdict verdict = sup == null ? null : sup.session().supervisorVerdict().orElse(null);
                    Optional<pramaan.core.fusion.Decision> photo = resident.decision();
                    if (photo.isEmpty()) {
                        status.setText("No exception photograph was checked in this session. Start a new registration.");
                        return;
                    }
                    ExceptionOutcome o = session.requestException(reason.getValue(), verdict, photo.get());
                    status.setText(exceptionMessage(o));
                    next.setDisable(o != ExceptionOutcome.APPROVED);
                    approve.setDisable(session.state() != SessionState.EXCEPTION_FLOW);
                }));
        next.setOnAction(e -> showApproval());
        setForm(new Label("Exception reason"), reason, new Label("Supervisor id"), supervisor, approve, next, status);
    }

    private static String exceptionMessage(ExceptionOutcome o) {
        switch (o) {
            case APPROVED:
                return "Exception approved. The packet is flagged EXCEPTION for central review.";
            case REJECTED_SAME_PERSON:
                return "The operator cannot approve their own exception.";
            case REJECTED_NO_SUPERVISOR:
                return "Supervisor face verification did not pass. Please try again.";
            case BLOCKED_CAPTURE_ATTACK:
            case BLOCKED_DAILY_LIMIT:
                return "Exception not possible. This case is referred for central review.";
            default:
                return "This approval is not valid for this resident. Please verify again.";
        }
    }

    // ------------------------------------------------------------------------------------------ #77 supervisor approval

    private void showApproval() {
        byte[] b = new byte[4];
        random.nextBytes(b);
        String packetId = String.format("P-%s-%02x%02x%02x%02x", config.subjectId, b[0], b[1], b[2], b[3]);
        step.setText("3  Supervisor approval");
        TextField supervisor = new TextField("supervisor-1");
        Label status = new Label();
        status.setWrapText(true);
        Button approve = new Button("Approve with face");
        approve.setDefaultButton(true);
        Button another = new Button("New registration");
        another.setDisable(true);
        approve.setOnAction(e -> faceCheck(Workflow.SUPERVISOR, supervisor.getText().trim(), packetId, engine -> {
            SupervisorVerdict verdict = engine == null ? null : engine.session().supervisorVerdict().orElse(null);
            PacketApproval.Outcome o = PacketApproval.approve(packetId, operator, verdict, engines.bundle().policy(),
                    registry, clock.instant(), this::audit);
            status.setText(o == PacketApproval.Outcome.APPROVED ? "Packet approved." : approvalMessage(o));
            another.setDisable(o != PacketApproval.Outcome.APPROVED);
        }));
        another.setOnAction(e -> showRegistration());
        setForm(new Label("Packet " + packetId), new Label("Supervisor id"), supervisor, approve, another, status);
    }

    private static String approvalMessage(PacketApproval.Outcome o) {
        switch (o) {
            case SAME_PERSON:
                return "The operator cannot approve their own packet.";
            case NO_VERDICT:
                return "Face verification could not be completed. Please try again.";
            default:
                return "This approval is not valid for this packet. Please verify again.";
        }
    }

    // ------------------------------------------------------------------------------------------ #78 diagnostics

    /** Policy-gated, and audited whether it is granted or refused (Bible Part 19.3). */
    private void activateDiagnostics() {
        if (engines == null) {
            return;
        }
        Optional<String> admin = ask("Administrator id", "Diagnostic mode is audited. Administrator id:");
        Optional<String> reason = admin.isPresent() ? ask("Reason", "Why is diagnostic mode needed?") : Optional.empty();
        if (admin.isEmpty() || reason.isEmpty() || admin.get().isBlank() || reason.get().isBlank()) {
            return;
        }
        try {
            grant = DiagnosticGrant.activate(engines.bundle(), admin.get().trim(), reason.get().trim(), clock.instant(),
                    this::audit);
            diagnostics = new DiagnosticsWindow(grant.adminId());
            diagnostics.show();
        } catch (IllegalStateException refused) {
            new Alert(Alert.AlertType.INFORMATION, "Diagnostic mode is disabled by policy.").showAndWait();
        }
    }

    private static Optional<String> ask(String title, String prompt) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(prompt);
        return d.showAndWait();
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
        form.setDisable(true);
        closeCapture();
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
                    form.setDisable(false);
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

    private void setForm(Node... nodes) {
        form.getChildren().setAll(nodes);
        form.setDisable(false);
    }

    /** The accepted capture, decoded from the signed ISO record the binder verified: what would be stored. */
    private void showCapture(Optional<SignedCapture> c) {
        c.ifPresent(cap -> {
            try {
                captured.setImage(toFx(IsoFaceDecoder.decode(VerifiedCapture.verify(cap, engines.deviceTrust()).isoRecord())));
            } catch (Exception ex) {
                System.err.println("PRAMAAN: could not show the capture: " + ex.getMessage());
            }
        });
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
