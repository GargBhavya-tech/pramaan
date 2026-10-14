/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import pramaan.core.challenge.FlashColour;
import pramaan.core.source.FlashLight;

/**
 * The resident-facing light of a desktop station (Bible Part 8.6; Build Map #85): a borderless window that fills the
 * monitor facing the resident and shows the flash colours the engine asks for. Having one is what turns the flash
 * challenge on at all; a station without a second monitor simply does not draw it.
 *
 * <p>Between colours, and after the last, the window is black. A black screen facing a resident is odd, so it hides
 * itself 1.5 seconds after the last request to show a colour. Methods may be called from any thread.
 *
 * <p>Photosensitivity is not this class's job: the engine refuses an unsafe sequence before it asks for the first
 * colour ({@code FlashSafety}). This class shows what it is told.
 */
public final class FlashWindow implements FlashLight {

    /** How long the dark window lingers after the last colour before it hides. */
    static final double HIDE_AFTER_MS = 1500;

    private final Stage stage;
    private final Region root = new Region();
    private final PauseTransition hide = new PauseTransition(Duration.millis(HIDE_AFTER_MS));

    /**
     * @param screenIndex which of {@link Screen#getScreens()} faces the resident
     * @throws IllegalArgumentException if there is no such monitor: a station configured with a resident light it
     *     does not have must say so when it starts, not silently run without the flash
     */
    public FlashWindow(int screenIndex) {
        Stage[] holder = new Stage[1];
        RuntimeException[] failure = new RuntimeException[1];
        CountDownLatch built = new CountDownLatch(1);
        Runnable build = () -> {
            try {
                List<Rectangle2D> monitors = new ArrayList<>();
                for (Screen s : Screen.getScreens()) {
                    monitors.add(s.getBounds());
                }
                Rectangle2D bounds = boundsOf(monitors, screenIndex);
                Stage s = new Stage(StageStyle.UNDECORATED);
                s.setX(bounds.getMinX());
                s.setY(bounds.getMinY());
                s.setWidth(bounds.getWidth());
                s.setHeight(bounds.getHeight());
                s.setAlwaysOnTop(true);
                s.setTitle("PRAMAAN resident light");
                root.setBackground(fill(Color.BLACK));
                s.setScene(new Scene(root));
                hide.setOnFinished(e -> s.hide());
                holder[0] = s;
            } catch (RuntimeException e) {
                failure[0] = e;
            } finally {
                built.countDown();
            }
        };
        if (Platform.isFxApplicationThread()) {
            build.run();
        } else {
            Platform.runLater(build);
            try {
                built.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while building the resident light", e);
            }
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        this.stage = holder[0];
    }

    /** The rectangle of monitor {@code screenIndex}; refuses an index with no monitor behind it. */
    static Rectangle2D boundsOf(List<Rectangle2D> monitors, int screenIndex) {
        if (screenIndex < 0 || screenIndex >= monitors.size()) {
            throw new IllegalArgumentException("no monitor " + screenIndex + " (this machine has " + monitors.size()
                    + ") to use as the resident light");
        }
        return monitors.get(screenIndex);
    }

    /** The on-screen colour for a palette entry (nominal linear RGB in [0, 1], shown as the display's RGB). */
    static Color toColor(FlashColour c) {
        return Color.color(c.r(), c.g(), c.b());
    }

    private static Background fill(Color color) {
        return new Background(new BackgroundFill(color, null, null));
    }

    @Override
    public void show(FlashColour colour) {
        Platform.runLater(() -> {
            hide.stop();
            root.setBackground(fill(toColor(colour)));
            if (!stage.isShowing()) {
                stage.show();
            }
            hide.playFromStart(); // pushed back on every colour; it only fires once the sequence is over
        });
    }

    @Override
    public void off() {
        Platform.runLater(() -> {
            root.setBackground(fill(Color.BLACK));
            if (stage.isShowing()) {
                hide.playFromStart();
            }
        });
    }
}
