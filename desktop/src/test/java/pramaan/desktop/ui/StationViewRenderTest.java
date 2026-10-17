/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

/**
 * The operator station, put on screen offscreen and photographed: every step in English and Hindi, at large text and
 * in high contrast. The PNGs in {@code desktop/build/reports/scaffold/} are for looking at; the assertions are for the
 * things a picture cannot guarantee (words, state kept across a language switch, every outcome having a message).
 * Skipped where no graphics toolkit can start.
 */
class StationViewRenderTest {

    private static final Path OUT = Path.of("build", "reports", "scaffold");
    /** The same words the capture-screen tests forbid anywhere a resident could read them (Bible Part 19.1). */
    private static final java.util.regex.Pattern FORBIDDEN = java.util.regex.Pattern.compile(
            "(?i)score|probab|confiden|zone|cue|threshold|moir|spoof|fake|attack|replay|tau|p_?live|logit|%|\\d+\\.\\d+");
    private static final Messages EN = Messages.forLocale(Locale.ROOT);
    private static final Messages HI = Messages.forLocale(Locale.forLanguageTag("hi"));
    private static boolean toolkit;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
            toolkit = up.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            toolkit = true;
        } catch (RuntimeException | UnsatisfiedLinkError noDisplay) {
            toolkit = false;
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(work.call());
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f.get(30, TimeUnit.SECONDS);
    }

    private static final StationView.Actions NO_ACTIONS = new StationView.Actions() {
        @Override
        public void toggleLanguage() {
        }

        @Override
        public void retakeBackdrop() {
        }

        @Override
        public void diagnostics() {
        }
    };

    private static StationView station(Messages m) throws Exception {
        return onFx(() -> {
            StationView v = new StationView(m, new FaceCaptureView(m), NO_ACTIONS);
            Scene sc = new Scene(v, 1280, 800);
            Theme.apply(sc);
            v.setOffline(true, false);
            return v;
        });
    }

    private static void showScreen(StationView v, SessionState state, Guidance g, ChallengePrompt prompt, String message)
            throws Exception {
        java.util.concurrent.atomic.AtomicReference<CaptureScreenModel.Screen> last = new java.util.concurrent.atomic.AtomicReference<>();
        CaptureScreenModel model = new CaptureScreenModel(last::set);
        model.attempt(1, 3);
        model.stateChanged(state);
        model.guidance(g);
        if (prompt != null) {
            model.prompt(prompt, 0, 2);
        }
        if (message != null) {
            model.message(message);
        }
        onFx(() -> {
            v.capture().show(last.get());
            return null;
        });
    }

    private static Path shot(StationView v, String name) throws Exception {
        Files.createDirectories(OUT);
        Path file = OUT.resolve(name + ".png");
        onFx(() -> {
            v.applyCss();
            v.layout();
            WritableImage img = v.getScene().getRoot().snapshot(null, null);
            ImageIO.write(toAwt(img), "png", file.toFile());
            return null;
        });
        return file;
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

    private static WritableImage samplePhoto() {
        WritableImage img = new WritableImage(200, 250);
        PixelWriter w = img.getPixelWriter();
        for (int y = 0; y < 250; y++) {
            for (int x = 0; x < 200; x++) {
                int g = 90 + (int) (80.0 * y / 250);
                w.setArgb(x, y, 0xFF000000 | (g + 20) << 16 | g << 8 | (g - 10));
            }
        }
        return img;
    }

    // ------------------------------------------------------------------------------------------ the steps, photographed

    @Test
    void everyStepRendersAndSaysWhatToDo() throws Exception {
        assumeTrue(toolkit, "no JavaFX toolkit on this machine");
        StationView v = station(EN);

        onFx(() -> {
            v.setPanel(new Panels.Loading());
            return null;
        });
        shot(v, "ui-0-loading");
        assertEquals("Starting up", v.panel().titleText());

        Panels.Login login = new Panels.Login("operator-1");
        onFx(() -> {
            v.setStep(StationView.Step.LOGIN);
            v.setPanel(login);
            return null;
        });
        showScreen(v, SessionState.ACQUIRING, Guidance.MOVE_CLOSER, null, null);
        shot(v, "ui-1-login");
        assertEquals("Operator sign-in", login.titleText());

        onFx(() -> {
            login.say(Panels.loginResult(OperatorAuthentication.Outcome.FACE_NOT_MATCHED));
            return null;
        });
        shot(v, "ui-1b-login-not-recognised");
        assertEquals(Banner.Kind.WARNING, login.noticeKind());
        assertTrue(login.noticeText().contains("Check the ID"), "says what to do next: " + login.noticeText());

        Panels.Resident resident = new Panels.Resident("REG-2041", List.of(AccessibilityProfile.values()));
        onFx(() -> {
            v.setStep(StationView.Step.RESIDENT);
            v.setOperator("operator-1");
            v.setPanel(resident);
            return null;
        });
        showScreen(v, SessionState.CHALLENGING, Guidance.NONE,
                ChallengePrompt.of(Challenge.turn(TurnDirection.LEFT, 20), AccessibilityAdjustments.NONE), null);
        shot(v, "ui-2-resident-challenge");

        onFx(() -> {
            resident.boxes().get(1).setSelected(true);
            resident.setPhoto(samplePhoto());
            resident.say(Banner.Kind.SUCCESS, Text.of("pramaan.ui.resident.stored", java.util.Map.of("file", "P-REG-2041.zip")));
            resident.next.setDisable(false);
            return null;
        });
        showScreen(v, SessionState.ACCEPTED, Guidance.NONE, null, null);
        shot(v, "ui-2b-resident-done");
        assertEquals(java.util.Set.of(AccessibilityProfile.NO_VISION), resident.chosenProfiles());

        Panels.ExceptionApproval ex = new Panels.ExceptionApproval("supervisor-1");
        onFx(() -> {
            v.setStep(StationView.Step.EXCEPTION);
            v.setPanel(ex);
            return null;
        });
        showScreen(v, SessionState.LOCKED_OUT, Guidance.NONE, null, null);
        shot(v, "ui-3-exception");

        Panels.Approval approval = new Panels.Approval("P-REG-2041-9f3a01c2", "supervisor-1");
        onFx(() -> {
            v.setStep(StationView.Step.APPROVAL);
            v.setPanel(approval);
            approval.say(Panels.approvalResult(PacketApproval.Outcome.APPROVED));
            approval.another.setDisable(false);
            return null;
        });
        showScreen(v, SessionState.ACCEPTED, Guidance.NONE, null, null);
        shot(v, "ui-4-approval");
        assertEquals("Packet approved.", approval.noticeText());

        StationView broken = station(EN);
        onFx(() -> {
            broken.setOffline(true, true);
            broken.setPanel(new Panels.Unavailable());
            return null;
        });
        CaptureScreenModel m = new CaptureScreenModel(s -> {
            try {
                onFx(() -> {
                    broken.capture().show(s);
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        m.engineUnavailable();
        shot(broken, "ui-5-unavailable-stale");
        assertEquals(Banner.Kind.ERROR, broken.panel().noticeKind());
    }

    @Test
    void hindiAndTheAccessibilityModesRender() throws Exception {
        assumeTrue(toolkit, "no JavaFX toolkit on this machine");
        StationView v = station(HI);
        Panels.Resident resident = new Panels.Resident("REG-2041", List.of(AccessibilityProfile.values()));
        onFx(() -> {
            v.setStep(StationView.Step.RESIDENT);
            v.setOperator("operator-1");
            v.setPanel(resident);
            resident.say(Banner.Kind.SUCCESS, Text.of("pramaan.ui.resident.stored", java.util.Map.of("file", "P-REG-2041.zip")));
            return null;
        });
        showScreen(v, SessionState.ACQUIRING, Guidance.MOVE_CLOSER, null, null);
        shot(v, "ui-hi-resident");

        onFx(() -> {
            Theme.setLargeText(v.getScene(), true);
            return null;
        });
        shot(v, "ui-hi-resident-large-text");

        onFx(() -> {
            Theme.setLargeText(v.getScene(), false);
            Theme.setHighContrast(v.getScene(), true);
            return null;
        });
        shot(v, "ui-hi-resident-high-contrast");
    }

    @Test
    void switchingLanguageKeepsWhatTheOperatorTypedTickedAndWasTold() throws Exception {
        assumeTrue(toolkit, "no JavaFX toolkit on this machine");
        StationView v = station(EN);
        Panels.Login login = new Panels.Login("operator-1");
        onFx(() -> {
            v.setPanel(login);
            login.id.setText("op-77");
            login.say(Panels.loginResult(OperatorAuthentication.Outcome.FACE_NOT_MATCHED));
            return null;
        });
        String english = login.noticeText();
        String englishTitle = login.titleText();
        onFx(() -> {
            v.retext(HI);
            return null;
        });
        assertEquals("op-77", login.id.getText(), "the typed id survives the switch");
        assertEquals(Banner.Kind.WARNING, login.noticeKind(), "the answer survives the switch");
        assertNotEquals(english, login.noticeText(), "and is now in Hindi");
        assertNotEquals(englishTitle, login.titleText());
        shot(v, "ui-hi-login-after-switch");

        Panels.Resident resident = new Panels.Resident("REG-1", List.of(AccessibilityProfile.values()));
        onFx(() -> {
            v.setPanel(resident);
            resident.boxes().get(0).setSelected(true);
            v.retext(EN);
            return null;
        });
        assertTrue(resident.boxes().get(0).isSelected(), "a ticked box stays ticked");
        assertEquals("Infant or young child", resident.boxes().get(0).getText());
    }

    // ------------------------------------------------------------------------------------------ the words

    @Test
    void everyOutcomeTheStationCanReportHasAMessageInBothLanguages() {
        for (OperatorAuthentication.Outcome o : OperatorAuthentication.Outcome.values()) {
            assertResolves(Panels.loginResult(o));
        }
        for (ExceptionOutcome o : ExceptionOutcome.values()) {
            assertResolves(Panels.exceptionResult(o));
        }
        for (PacketApproval.Outcome o : PacketApproval.Outcome.values()) {
            assertResolves(Panels.approvalResult(o));
        }
    }

    private static void assertResolves(Panels.Notice n) {
        String id = n.text().id();
        assertTrue(EN.has(id), "no English for " + id);
        assertTrue(HI.has(id), "no Hindi for " + id);
        assertNotEquals(id, EN.resolve(n.text()));
    }

    @Test
    void noOperatorScreenTextCarriesAScoreOrACauseOfRejection() throws Exception {
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = Messages.class.getResourceAsStream("messages.properties");
                java.io.InputStreamReader r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(r);
        }
        int checked = 0;
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("pramaan.ui.") && !key.startsWith("pramaan.ui.diag")) {
                String v = p.getProperty(key);
                assertFalse(FORBIDDEN.matcher(v).find(), key + ": " + v);
                checked++;
            }
        }
        assertTrue(checked > 80, "checked " + checked);
    }

    // ------------------------------------------------------------------------------------------ the dialogs

    @Test
    void theAuditedFormStaysOffUntilItCanBeAcceptedAndShowsTheTick() throws Exception {
        assumeTrue(toolkit, "no JavaFX toolkit on this machine");
        Dialogs.AuditedForm form = onFx(() -> {
            Dialogs.AuditedForm f = new Dialogs.AuditedForm(EN, "pramaan.ui.backdrop.title", "pramaan.ui.backdrop.body",
                    "pramaan.ui.backdrop.reason-hint", "pramaan.ui.backdrop.empty-room");
            Scene sc = new Scene(f);
            Theme.apply(sc);
            return f;
        });
        assertTrue(form.ok.isDisabled());
        onFx(() -> {
            form.admin.setText("admin-1");
            form.reason.setText("camera moved");
            return null;
        });
        assertTrue(form.ok.isDisabled(), "the room must be confirmed empty first");
        onFx(() -> {
            form.confirm.setSelected(true);
            return null;
        });
        assertFalse(form.ok.isDisabled());
        assertEquals("admin-1", form.entry().adminId());
        shot(form, "ui-dialog-backdrop");
    }

    private static void shot(Node node, String name) throws Exception {
        Files.createDirectories(OUT);
        onFx(() -> {
            node.applyCss();
            ((javafx.scene.Parent) node).layout();
            ImageIO.write(toAwt(node.snapshot(null, null)), "png", OUT.resolve(name + ".png").toFile());
            return null;
        });
    }
}
