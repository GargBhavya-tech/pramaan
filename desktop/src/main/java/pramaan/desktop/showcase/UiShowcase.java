/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.showcase;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import pramaan.core.auth.OperatorAuthentication;
import pramaan.core.auth.PacketApproval;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.Challenge;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.challenge.TurnDirection;
import pramaan.core.policy.AccessibilityProfile;
import pramaan.core.quality.Guidance;
import pramaan.core.session.ExceptionOutcome;
import pramaan.core.session.SessionState;
import pramaan.desktop.capture.CaptureScreenModel;
import pramaan.desktop.capture.FaceCaptureView;
import pramaan.desktop.capture.Messages;
import pramaan.desktop.capture.Text;
import pramaan.desktop.ui.Banner;
import pramaan.desktop.ui.Panels;
import pramaan.desktop.ui.StationView;
import pramaan.desktop.ui.Theme;

/**
 * A runnable walk through the station screens that needs no camera, no device, no models and no policy: the REAL
 * {@link StationView}, {@link Panels} and {@link CaptureScreenModel}, driven by SIMULATED engine events. It exists so a
 * reviewer can see and click through the screens on any machine, and so the demo video is made from the real code.
 *
 * <p>It is not a liveness run: nothing here decides anything, and the screen says so. Every beat carries a caption (what
 * the screen is showing) and a narration line (what a voice-over says), kept in one place so the two cannot drift.
 *
 * <pre>
 *   ./gradlew :desktop:showcase                              interactive; SPACE pauses, arrows step
 *   ./gradlew :desktop:showcaseRender --args="build/showcase" offscreen PNG per beat plus beats.json
 * </pre>
 */
public final class UiShowcase extends Application {

    static final double WIDTH = 1280;
    static final double HEIGHT = 856;

    /** One held screen: what it looks like, how long it is held, what is said over it. */
    static final class Beat {
        final String id;
        final double seconds;
        final String caption;
        final String narration;
        final Consumer<Show> apply;

        Beat(String id, double seconds, String caption, String narration, Consumer<Show> apply) {
            this.id = id;
            this.seconds = seconds;
            this.caption = caption;
            this.narration = narration;
            this.apply = apply;
        }
    }

    /** The window, and the simulated engine that drives it. Must be used on the FX thread. */
    static final class Show {
        final Messages en = Messages.forLocale(Locale.ROOT);
        final Messages hi = Messages.forLocale(Locale.forLanguageTag("hi"));
        final FaceCaptureView capture = new FaceCaptureView(en);
        final StationView station;
        final Label caption = new Label();
        final BorderPane root = new BorderPane();
        Scene scene;

        Show() {
            station = new StationView(en, capture, new StationView.Actions() {
                @Override
                public void toggleLanguage() {
                }

                @Override
                public void retakeBackdrop() {
                }

                @Override
                public void diagnostics() {
                }
            });
            station.setOffline(true, false);
            Label tag = new Label("SIMULATED SESSION");
            tag.setStyle("-fx-background-color: #f5a623; -fx-text-fill: #14202e; -fx-font-weight: bold;"
                    + " -fx-padding: 4 10 4 10; -fx-background-radius: 4;");
            caption.setWrapText(true);
            caption.setStyle("-fx-text-fill: white; -fx-font-size: 18px;");
            HBox bar = new HBox(14, tag, caption);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setPadding(new Insets(12, 20, 12, 20));
            bar.setMinHeight(56);
            bar.setStyle("-fx-background-color: #14202e;");
            HBox.setHgrow(caption, javafx.scene.layout.Priority.ALWAYS);
            root.setCenter(station);
            root.setBottom(bar);
        }

        Scene scene() {
            if (scene == null) {
                scene = new Scene(root, WIDTH, HEIGHT);
                Theme.apply(scene);
            }
            return scene;
        }

        /** Shows the capture screen as the real model would, for one situation. */
        void screen(SessionState state, Guidance guidance, ChallengePrompt prompt, boolean stepDone, String message,
                Long cooldownMillis) {
            AtomicReference<CaptureScreenModel.Screen> last = new AtomicReference<>();
            CaptureScreenModel model = new CaptureScreenModel(last::set);
            model.attempt(1, 3);
            model.stateChanged(state);
            model.guidance(guidance);
            if (prompt != null) {
                model.prompt(prompt, 0, 2);
                if (stepDone) {
                    model.stepCompleted(0, 2);
                }
            }
            if (message != null) {
                model.message(message);
            }
            if (cooldownMillis != null) {
                model.cooldown(cooldownMillis);
            }
            capture.show(last.get());
        }

        /** A plain silhouette: the showcase never shows a face, only that a photo would appear. */
        Image silhouette() {
            Canvas c = new Canvas(200, 250);
            GraphicsContext g = c.getGraphicsContext2D();
            g.setFill(Color.web("#d5dbe1"));
            g.fillRect(0, 0, 200, 250);
            g.setFill(Color.web("#8795a3"));
            g.fillOval(65, 45, 70, 82);
            g.fillOval(25, 140, 150, 160);
            WritableImage out = new WritableImage(200, 250);
            c.snapshot(null, out);
            return out;
        }
    }

    // ------------------------------------------------------------------------------------------ the script

    static List<Beat> script() {
        List<Beat> b = new ArrayList<>();
        final ChallengePrompt turn = ChallengePrompt.of(Challenge.turn(TurnDirection.LEFT, 20), AccessibilityAdjustments.NONE);
        final Panels.Login[] login = new Panels.Login[1];
        final Panels.Resident[] resident = new Panels.Resident[1];

        b.add(new Beat("loading", 4,
                "Start-up: the policy, the operating-point table, the models and the device certificate are verified.",
                "At start-up the engine verifies its policy, its operating-point table, every model and the device certificate. "
                        + "Nothing can be registered until that passes.",
                s -> {
                    s.station.setStep(StationView.Step.LOGIN);
                    s.station.setPanel(new Panels.Loading());
                    s.screen(SessionState.IDLE, Guidance.NONE, null, false, null, null);
                }));

        b.add(new Beat("sign-in", 6,
                "Step 1, operator sign-in. A quality problem gets guidance, not a rejection.",
                "The operator signs in with their face. If the framing is poor, the screen gives guidance, such as please move "
                        + "closer. It is never reported as an attack.",
                s -> {
                    login[0] = new Panels.Login("operator-1");
                    s.station.setPanel(login[0]);
                    s.screen(SessionState.ACQUIRING, Guidance.MOVE_CLOSER, null, false, null, null);
                }));

        b.add(new Beat("sign-in-failed", 6,
                "A failed sign-in says what to do next. It never says why the check failed.",
                "If the face is not recognised, the operator is told to check the I D and try again. The screen never reveals "
                        + "which check failed, because that would tell an attacker what to change.",
                s -> {
                    login[0].say(Panels.loginResult(OperatorAuthentication.Outcome.FACE_NOT_MATCHED));
                    s.screen(SessionState.FAILED, Guidance.NONE, null, false, "pramaan.error.not-completed", 2000L);
                }));

        b.add(new Beat("resident", 6,
                "Step 2, resident photo. The operator ticks accessibility needs; the system chooses the checks.",
                "For the resident, the operator can tick accessibility needs. The system chooses the checks. "
                        + "The resident never picks a challenge.",
                s -> {
                    s.station.setOperator("operator-1");
                    s.station.setStep(StationView.Step.RESIDENT);
                    resident[0] = new Panels.Resident("REG-2041", java.util.Arrays.asList(AccessibilityProfile.values()));
                    s.station.setPanel(resident[0]);
                    s.screen(SessionState.PASSIVE, Guidance.NONE, null, false, null, null);
                }));

        b.add(new Beat("challenge", 7,
                "An unpredictable challenge, with an icon and a spoken prompt. The arrow points to the screen's left.",
                "If the passive checks are not enough, the engine asks for something a recording cannot contain, chosen at random, "
                        + "with an icon and a spoken prompt.",
                s -> {
                    resident[0].expandAccessibility();
                    resident[0].boxes().get(1).setSelected(true);
                    s.screen(SessionState.CHALLENGING, Guidance.NONE, turn, false, null, null);
                }));

        b.add(new Beat("step-done", 4,
                "Action detected. The screen shows no score and no progress bar.",
                "When the action is detected, the screen says so. There is no score and no progress bar, because the number of "
                        + "steps must not tell the resident how the system sees them.",
                s -> s.screen(SessionState.CHALLENGING, Guidance.NONE, turn, true, null, null)));

        b.add(new Beat("capturing", 4,
                "Hold still: the signed capture is requested and bound to this session.",
                "Then the signed capture is requested, and four locks decide whether it is the same face that was watched.",
                s -> s.screen(SessionState.CAPTURING, Guidance.NONE, null, false, null, null)));

        b.add(new Beat("accepted", 7,
                "Accepted: the packet is written with signed liveness evidence. (A stand-in silhouette, not a face.)",
                "On success, the packet is written with signed liveness evidence, so a server can re-check the decision later.",
                s -> {
                    resident[0].setPhoto(s.silhouette());
                    resident[0].say(Banner.Kind.SUCCESS,
                            Text.of("pramaan.ui.resident.stored", java.util.Map.of("file", "P-REG-2041.zip")));
                    resident[0].next.setDisable(false);
                    s.screen(SessionState.ACCEPTED, Guidance.NONE, null, false, null, null);
                }));

        b.add(new Beat("retry", 6,
                "A failed attempt shows one generic sentence, the attempt count and a wait that depends only on the count.",
                "When an attempt fails, the resident sees one generic sentence, the attempt number, and a wait that depends only "
                        + "on how many attempts were used. Never on a score.",
                s -> {
                    // a new scan for the next resident: the previous resident's success banner must not carry over
                    resident[0] = new Panels.Resident("REG-2042", java.util.Arrays.asList(AccessibilityProfile.values()));
                    s.station.setPanel(resident[0]);
                    s.screen(SessionState.FAILED, Guidance.NONE, null, false, "pramaan.error.not-completed", 4000L);
                }));

        b.add(new Beat("exception", 7,
                "Locked out: a supervisor, not the operator, approves an exception with a reason.",
                "If a resident cannot pass, a supervisor, not the operator, approves an exception with a reason, "
                        + "by passing their own fresh liveness check. That is dual control.",
                s -> {
                    s.station.setStep(StationView.Step.EXCEPTION);
                    Panels.ExceptionApproval p = new Panels.ExceptionApproval("supervisor-1");
                    s.station.setPanel(p);
                    p.say(Panels.exceptionResult(ExceptionOutcome.REJECTED_SAME_PERSON));
                    s.screen(SessionState.LOCKED_OUT, Guidance.NONE, null, false, null, null);
                }));

        b.add(new Beat("approval", 6,
                "Step 3, supervisor approval of the packet. The operator cannot approve their own.",
                "Finally, a supervisor approves the packet with their own face. The operator cannot approve their own packet.",
                s -> {
                    s.station.setStep(StationView.Step.APPROVAL);
                    Panels.Approval p = new Panels.Approval("P-REG-2041-9f3a01c2", "supervisor-1");
                    s.station.setPanel(p);
                    p.say(Panels.approvalResult(PacketApproval.Outcome.APPROVED));
                    p.another.setDisable(false);
                    s.screen(SessionState.ACCEPTED, Guidance.NONE, null, false, null, null);
                }));

        b.add(new Beat("hindi", 7,
                "Hindi on every screen, switched in one click. What was typed and answered stays. (Machine-drafted, unreviewed.)",
                "Every screen is also in Hindi, switched with one click. The Hindi is machine drafted and has not been reviewed by a "
                        + "native speaker yet.",
                s -> {
                    s.station.setStep(StationView.Step.RESIDENT);
                    resident[0].next.setDisable(false);
                    s.station.setPanel(resident[0]);
                    s.station.retext(s.hi);
                    s.screen(SessionState.ACQUIRING, Guidance.MOVE_CLOSER, null, false, null, null);
                }));

        b.add(new Beat("large-text", 5,
                "Larger text and high contrast are one click each.",
                "Larger text and high contrast are one click each, for residents and operators who need them.",
                s -> {
                    Theme.setLargeText(s.scene(), true);
                    Theme.setHighContrast(s.scene(), true);
                }));

        b.add(new Beat("fails-closed", 7,
                "Fails closed: if anything does not verify, nothing can be registered and the screen says what to check.",
                "And it fails closed. If the policy, a model or the certificate does not verify, nothing can be registered, "
                        + "and the screen says what to check.",
                s -> {
                    Theme.setLargeText(s.scene(), false);
                    Theme.setHighContrast(s.scene(), false);
                    s.station.retext(s.en);
                    s.station.setStep(StationView.Step.LOGIN);
                    s.station.setOffline(true, true);
                    s.station.setPanel(new Panels.Unavailable());
                    CaptureScreenModel m = new CaptureScreenModel(s.capture::show);
                    m.engineUnavailable();
                }));
        return b;
    }

    // ------------------------------------------------------------------------------------------ interactive

    private int index = -1;
    private PauseTransition timer;
    private boolean paused;
    private Show show;
    private List<Beat> beats;

    @Override
    public void start(Stage stage) {
        show = new Show();
        beats = script();
        Scene sc = show.scene();
        sc.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE) {
                paused = !paused;
                if (!paused) {
                    advance();
                } else if (timer != null) {
                    timer.stop();
                }
            } else if (e.getCode() == KeyCode.RIGHT) {
                advance();
            } else if (e.getCode() == KeyCode.LEFT) {
                index = Math.max(-1, index - 2);
                advance();
            }
        });
        stage.setTitle("PRAMAAN: station screens (simulated session, no camera)");
        stage.setScene(sc);
        stage.show();
        advance();
    }

    private void advance() {
        if (timer != null) {
            timer.stop();
        }
        index = (index + 1) % beats.size();
        if (index == 0) {
            show = resetShow();
        }
        Beat beat = beats.get(index);
        beat.apply.accept(show);
        show.caption.setText(beat.caption);
        if (!paused) {
            timer = new PauseTransition(Duration.seconds(beat.seconds));
            timer.setOnFinished(e -> advance());
            timer.play();
        }
    }

    /** Looping back to the start needs the first screen's state, so the window is rebuilt on the same stage. */
    private Show resetShow() {
        Show fresh = new Show();
        Scene old = show.scene();
        Scene sc = fresh.scene();
        sc.setOnKeyPressed(old.getOnKeyPressed());
        ((Stage) old.getWindow()).setScene(sc);
        return fresh;
    }

    // ------------------------------------------------------------------------------------------ offscreen render

    /** Writes {@code beat-NN-id.png} for every beat and a {@code beats.json} naming each with its time and words. */
    public static List<String> render(Path dir) throws Exception {
        Files.createDirectories(dir);
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
            if (!up.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the JavaFX toolkit did not start");
            }
        } catch (IllegalStateException alreadyRunning) {
            // the toolkit is already up (a test started it)
        }
        Show s = onFx(Show::new);
        onFx(() -> s.scene());
        List<Beat> beats = script();
        List<String> files = new ArrayList<>();
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < beats.size(); i++) {
            Beat beat = beats.get(i);
            String name = String.format(Locale.ROOT, "beat-%02d-%s.png", i + 1, beat.id);
            Path file = dir.resolve(name);
            onFx(() -> {
                beat.apply.accept(s);
                s.caption.setText(beat.caption);
                s.root.applyCss();
                s.root.layout();
                WritableImage img = s.root.snapshot(null, null);
                ImageIO.write(toAwt(img), "png", file.toFile());
                return null;
            });
            files.add(name);
            json.append(String.format(Locale.ROOT, "  {\"index\": %d, \"id\": \"%s\", \"file\": \"%s\", \"seconds\": %.1f,"
                    + " \"caption\": \"%s\", \"narration\": \"%s\"}%s%n", i + 1, beat.id, name, beat.seconds,
                    esc(beat.caption), esc(beat.narration), i + 1 < beats.size() ? "," : ""));
        }
        json.append("]\n");
        Files.write(dir.resolve("beats.json"), json.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static BufferedImage toAwt(WritableImage img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader r = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, r.getArgb(x, y));
            }
        }
        return out;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(work.call());
            } catch (Exception | Error e) {
                f.completeExceptionally(e);
            }
        });
        return f.get(60, TimeUnit.SECONDS);
    }

    /** {@code --render <dir>} writes the screens as PNGs and exits; no argument opens the interactive window. */
    public static void main(String[] args) throws IOException {
        if (args.length >= 2 && args[0].equals("--render")) {
            try {
                List<String> files = render(Path.of(args[1]));
                System.out.println("rendered " + files.size() + " screens to " + args[1]);
            } catch (Exception e) {
                throw new IOException("render failed: " + e, e);
            } finally {
                Platform.exit();
            }
            return;
        }
        launch(args);
    }
}
