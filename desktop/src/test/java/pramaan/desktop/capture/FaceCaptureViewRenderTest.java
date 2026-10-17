/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.Challenge;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.challenge.TurnDirection;
import pramaan.core.quality.Guidance;
import pramaan.core.session.SessionState;

/**
 * The real JavaFX screen, rendered offscreen: it shows what the model says and nothing more, and the snapshots in
 * {@code desktop/build/reports/scaffold/} are there to be looked at. Skipped where no graphics toolkit can start.
 */
class FaceCaptureViewRenderTest {

    private static final Path OUT = Path.of("build", "reports", "scaffold");
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

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(work.call());
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f.get(20, TimeUnit.SECONDS);
    }

    /** The first JPEG in the MockMDS stream sample: a real preview frame. */
    private static byte[] firstFrame() throws Exception {
        byte[] m = Files.readAllBytes(Path.of("..", "sbi", "testdata", "stream-sample.mjpeg"));
        int start = -1;
        for (int i = 0; i + 1 < m.length; i++) {
            if (start < 0 && (m[i] & 0xFF) == 0xFF && (m[i + 1] & 0xFF) == 0xD8) {
                start = i;
            } else if (start >= 0 && (m[i] & 0xFF) == 0xFF && (m[i + 1] & 0xFF) == 0xD9) {
                return java.util.Arrays.copyOfRange(m, start, i + 2);
            }
        }
        throw new IllegalStateException("no JPEG in the stream sample");
    }

    @Test
    void theScreenShowsTheModelsWordsAndNothingElse() throws Exception {
        assumeTrue(toolkit, "no JavaFX toolkit on this machine");
        assumeTrue(Files.exists(Path.of("..", "sbi", "testdata", "stream-sample.mjpeg")),
                "MockMDS stream fixture absent (gitignored: it shows a real person)");
        Messages en = Messages.forLocale(Locale.ROOT);
        byte[] jpeg = firstFrame();
        List<CaptureScreenModel.Screen> screens = new ArrayList<>();
        CaptureScreenModel model = new CaptureScreenModel(screens::add);
        FaceCaptureView view = onFx(() -> {
            FaceCaptureView v = new FaceCaptureView(en);
            pramaan.desktop.ui.Theme.apply(new Scene(v, 640, 700));
            v.setPreviewImage(new Image(new ByteArrayInputStream(jpeg)));
            return v;
        });
        Files.createDirectories(OUT);

        model.stateChanged(SessionState.ACQUIRING);
        model.guidance(Guidance.MOVE_CLOSER);
        assertEquals(List.of("Centre your face in the oval", "Please move closer"), render(view, screens, "1-positioning"));

        model.guidance(Guidance.NONE);
        model.stateChanged(SessionState.PASSIVE);
        model.stateChanged(SessionState.CHALLENGING);
        model.prompt(ChallengePrompt.of(Challenge.turn(TurnDirection.LEFT, 20), AccessibilityAdjustments.NONE), 0, 2);
        assertEquals(List.of("Please turn your head to your left"), render(view, screens, "2-challenge-turn-left"));

        model.stateChanged(SessionState.ACCEPTED);
        assertEquals(List.of("Good, face captured successfully"), render(view, screens, "3-success"));

        model.stateChanged(SessionState.FAILED);
        model.message("pramaan.error.not-completed");
        List<String> failed = render(view, screens, "4-not-completed");
        assertEquals(List.of("Face verification could not be completed. Please try again."), failed);
        for (String s : failed) {
            assertFalse(CaptureScreenModelTest.FORBIDDEN.matcher(s).find(), s);
        }
    }

    private static List<String> render(FaceCaptureView view, List<CaptureScreenModel.Screen> screens, String name)
            throws Exception {
        CaptureScreenModel.Screen s = screens.get(screens.size() - 1);
        return onFx(() -> {
            view.show(s);
            view.applyCss();
            view.layout();
            WritableImage shot = view.snapshot(null, null);
            ImageIO.write(toAwt(shot), "png", OUT.resolve(name + ".png").toFile());
            assertTrue(shot.getWidth() > 400, "rendered " + shot.getWidth() + " px wide");
            return view.visibleText();
        });
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
}
