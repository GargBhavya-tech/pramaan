/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.Animation;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.PixelFormat;

/**
 * The face capture screen (Build Map #75; Bible Part 19): live preview in a face oval, one headline, one line of
 * guidance, the prompt's animation, the attempt counter. It renders {@link CaptureScreenModel.Screen}s and
 * nothing else, so it cannot show what the engine never sends.
 *
 * <p>The preview is mirrored, as residents expect; "turn to your left" therefore points to the screen's left.
 * The oval's colour follows the stage — never a score.
 */
public final class FaceCaptureView extends VBox {

    public static final double PREVIEW_WIDTH = 480;
    public static final double PREVIEW_HEIGHT = 360;

    private final Messages messages;
    private final ImageView preview = new ImageView();
    private final Ellipse oval = new Ellipse(PREVIEW_WIDTH / 2, PREVIEW_HEIGHT / 2, 105, 140);
    private final Polygon arrow = new Polygon(0, 0, 36, -26, 36, -10, 80, -10, 80, 10, 36, 10, 36, 26);
    private final TranslateTransition nudge = new TranslateTransition(Duration.millis(600), arrow);
    private final javafx.scene.text.Text headline = text(24, FontWeight.BOLD);
    private final javafx.scene.text.Text guidance = text(16, FontWeight.NORMAL);
    private final javafx.scene.text.Text notice = text(14, FontWeight.NORMAL);
    private final javafx.scene.text.Text attempt = text(13, FontWeight.NORMAL);
    private final AtomicReference<Image> pendingFrame = new AtomicReference<>();
    private CaptureScreenModel.Screen shown;
    private final Speech speech;
    private final StackPane icon = new StackPane();
    private String lastSpoken;

    public FaceCaptureView(Messages messages) {
        this(messages, Speech.NONE);
    }

    /** @param speech reads each new headline aloud (Build Map #91): words the screen shows, nothing else */
    public FaceCaptureView(Messages messages, Speech speech) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.speech = Objects.requireNonNull(speech, "speech");
        preview.setFitWidth(PREVIEW_WIDTH);
        preview.setFitHeight(PREVIEW_HEIGHT);
        preview.setPreserveRatio(false);
        preview.setScaleX(-1);
        Rectangle backdrop = new Rectangle(PREVIEW_WIDTH, PREVIEW_HEIGHT, Color.web("#1f2933"));
        oval.setFill(Color.TRANSPARENT);
        oval.setStrokeWidth(4);
        oval.setStroke(Color.WHITE);
        arrow.setFill(Color.web("#f5a623"));
        arrow.setVisible(false);
        nudge.setAutoReverse(true);
        nudge.setCycleCount(Animation.INDEFINITE);
        icon.setMaxSize(72, 72);
        icon.setStyle("-fx-background-color: rgba(255,255,255,0.85); -fx-background-radius: 36;");
        icon.setVisible(false);
        StackPane.setAlignment(icon, Pos.TOP_RIGHT);
        StackPane.setMargin(icon, new Insets(10));
        StackPane viewport = new StackPane(backdrop, preview, new Group(oval), arrow, icon);
        viewport.setMaxSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        notice.setFill(Color.web("#b3261e"));
        attempt.setFill(Color.web("#52606d"));
        setSpacing(10);
        setPadding(new Insets(16));
        setAlignment(Pos.TOP_CENTER);
        getChildren().addAll(viewport, headline, guidance, notice, attempt);
        for (javafx.scene.text.Text t : new javafx.scene.text.Text[] {headline, guidance, notice, attempt}) {
            t.setWrappingWidth(PREVIEW_WIDTH);
        }
    }

    /** Where the capture session sends frames: decoded here, painted on the FX thread, the latest one wins. */
    public FrameSink previewSink() {
        return new FrameSink() {
            @Override
            public void onFrame(Frame frame) {
                if (frame.format() != PixelFormat.JPEG) {
                    return;
                }
                Image img = new Image(new ByteArrayInputStream(frame.copyBytes()));
                if (pendingFrame.getAndSet(img) == null) {
                    Platform.runLater(() -> preview.setImage(pendingFrame.getAndSet(null)));
                }
            }

            @Override
            public void onDeviceEvent(DeviceEvent event) {
                // The screen learns about device trouble through the engine's generic messages.
            }
        };
    }

    /** Shows a screen; safe from any thread. */
    public void show(CaptureScreenModel.Screen screen) {
        if (Platform.isFxApplicationThread()) {
            apply(screen);
        } else {
            Platform.runLater(() -> apply(screen));
        }
    }

    /** The last screen applied. */
    public CaptureScreenModel.Screen shown() {
        return shown;
    }

    /** The strings on screen now. */
    public List<String> visibleText() {
        List<String> out = new ArrayList<>();
        for (javafx.scene.text.Text t : new javafx.scene.text.Text[] {headline, guidance, notice, attempt}) {
            if (t.isVisible() && !t.getText().isEmpty()) {
                out.add(t.getText());
            }
        }
        return out;
    }

    public void setPreviewImage(Image image) {
        preview.setImage(image);
    }

    private void apply(CaptureScreenModel.Screen s) {
        shown = s;
        set(headline, s.headline());
        set(guidance, s.guidance());
        set(notice, s.notice());
        set(attempt, s.attempt());
        oval.setStroke(colour(s.stage()));
        String anim = s.animation();
        boolean turn = anim != null && anim.startsWith("anim:head-turn");
        arrow.setVisible(turn);
        nudge.stop();
        if (turn) {
            boolean left = anim.endsWith("left");
            arrow.setRotate(left ? 0 : 180);
            double x = left ? -PREVIEW_WIDTH / 2 + 70 : PREVIEW_WIDTH / 2 - 70;
            arrow.setTranslateX(x);
            nudge.setFromX(x);
            nudge.setToX(x + (left ? -18 : 18));
            nudge.playFromStart();
        }
        java.util.Optional<PromptIcon> i = anim == null ? java.util.Optional.empty() : PromptIcon.of(anim);
        icon.getChildren().setAll(i.map(FaceCaptureView::draw).orElse(new Group()));
        icon.setVisible(i.isPresent());
        if (s.tone()) {
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
        String said = headline.getText();
        if (s.stage() != CaptureScreenModel.Stage.CONNECTING && !said.isEmpty() && !said.equals(lastSpoken)) {
            lastSpoken = said;
            speech.say(said);
        }
    }

    /** A plain vector icon per prompt: an eye, a closed eye, a smile, a dot, a screen, or a head with an arrow. */
    static Group draw(PromptIcon kind) {
        Color ink = Color.web("#1f2933");
        Group g = new Group();
        switch (kind) {
            case BLINK:
            case EYES_CLOSED: {
                Ellipse eye = new Ellipse(0, 0, 24, kind == PromptIcon.BLINK ? 12 : 2);
                eye.setFill(Color.TRANSPARENT);
                eye.setStroke(ink);
                eye.setStrokeWidth(3);
                g.getChildren().add(eye);
                if (kind == PromptIcon.BLINK) {
                    g.getChildren().add(new Circle(0, 0, 6, ink));
                } else {
                    for (int x = -16; x <= 16; x += 8) {
                        Line lash = new Line(x, 2, x * 1.2, 10);
                        lash.setStroke(ink);
                        lash.setStrokeWidth(2);
                        g.getChildren().add(lash);
                    }
                }
                break;
            }
            case SMILE: {
                Circle face = new Circle(0, 0, 24, Color.TRANSPARENT);
                face.setStroke(ink);
                face.setStrokeWidth(3);
                Arc mouth = new Arc(0, 2, 14, 12, 200, 140);
                mouth.setType(ArcType.OPEN);
                mouth.setFill(Color.TRANSPARENT);
                mouth.setStroke(ink);
                mouth.setStrokeWidth(3);
                g.getChildren().addAll(face, mouth, new Circle(-8, -7, 3, ink), new Circle(8, -7, 3, ink));
                break;
            }
            case GAZE:
                g.getChildren().addAll(new Circle(0, 0, 20, Color.TRANSPARENT), new Circle(0, 0, 7, Color.web("#f5a623")));
                break;
            case LOOK_AT_SCREEN: {
                Rectangle screen = new Rectangle(-24, -16, 48, 32);
                screen.setFill(Color.TRANSPARENT);
                screen.setStroke(ink);
                screen.setStrokeWidth(3);
                g.getChildren().add(screen);
                break;
            }
            case TURN_LEFT:
            case TURN_RIGHT:
            default: {
                Circle head = new Circle(0, 0, 18, Color.TRANSPARENT);
                head.setStroke(ink);
                head.setStrokeWidth(3);
                double d = kind == PromptIcon.TURN_LEFT ? -1 : 1;
                Polygon nose = new Polygon(d * 18, -4, d * 28, 0, d * 18, 4);
                nose.setFill(ink);
                g.getChildren().addAll(head, nose);
                break;
            }
        }
        return g;
    }

    private void set(javafx.scene.text.Text node, Text text) {
        node.setText(text == null ? "" : messages.resolve(text));
        node.setVisible(text != null);
        node.setManaged(text != null);
    }

    static Color colour(CaptureScreenModel.Stage stage) {
        switch (stage) {
            case SUCCESS:
                return Color.web("#2e7d32");
            case RETRY:
            case STOPPED:
                return Color.web("#b3261e");
            case CHALLENGE:
                return Color.web("#f5a623");
            case CHECKING:
            case CAPTURING:
                return Color.web("#1e88e5");
            default:
                return Color.WHITE;
        }
    }

    private static javafx.scene.text.Text text(double size, FontWeight weight) {
        javafx.scene.text.Text t = new javafx.scene.text.Text();
        t.setFont(Font.font("System", weight, size));
        t.setTextAlignment(TextAlignment.CENTER);
        return t;
    }
}
