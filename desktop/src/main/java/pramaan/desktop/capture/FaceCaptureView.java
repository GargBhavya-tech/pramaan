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
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
import javafx.scene.shape.Shape;
import javafx.util.Duration;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.PixelFormat;
import pramaan.desktop.ui.Icons;

/**
 * The face capture screen (Build Map #75; Bible Part 19): live preview in a face oval with the room dimmed around it,
 * one headline with an icon, one line of guidance, the prompt's animation, the attempt counter. It renders
 * {@link CaptureScreenModel.Screen}s and nothing else, so it cannot show what the engine never sends.
 *
 * <p>It deliberately shows no progress bar and no count of challenge steps: how many steps a resident is asked for must
 * not tell them how the system sees them (Part 19.1, principle 2).
 *
 * <p>The preview is mirrored, as residents expect; "turn to your left" therefore points to the screen's left.
 * The oval's colour follows the stage — never a score.
 */
public final class FaceCaptureView extends VBox {

    public static final double PREVIEW_WIDTH = 560;
    public static final double PREVIEW_HEIGHT = 420;
    private static final double OVAL_RX = 122;
    private static final double OVAL_RY = 158;

    private volatile Messages messages;
    private final ImageView preview = new ImageView();
    private final Ellipse oval = new Ellipse(PREVIEW_WIDTH / 2, PREVIEW_HEIGHT / 2, OVAL_RX, OVAL_RY);
    private final Polygon arrow = new Polygon(0, 0, 36, -26, 36, -10, 80, -10, 80, 10, 36, 10, 36, 26);
    private final TranslateTransition nudge = new TranslateTransition(Duration.millis(600), arrow);
    private final Label headline = label("stage-headline");
    private final Label guidance = label("stage-guidance");
    private final Label notice = label("stage-notice");
    private final Label attempt = label("stage-attempt");
    private final HBox strip = new HBox();
    private final StackPane stageIcon = new StackPane();
    private final VBox words = new VBox(4, headline, guidance, notice, attempt);
    private final StackPane waiting = new StackPane();
    /** Position, verify, capture: where this session is, as words and a tick or number, never colour alone. */
    private final HBox progress = new HBox(8);
    private final Label[] steps = {label("progress-step"), label("progress-step"), label("progress-step")};
    private static final String[] STEP_IDS = {"pramaan.step.position", "pramaan.step.verify", "pramaan.step.capture"};
    /** Over the preview while liveness is being checked: PS04 asks for a clear sign that it is. */
    private final Label livenessChip = label("liveness-chip");
    private final AtomicReference<Image> pendingFrame = new AtomicReference<>();
    private CaptureScreenModel.Screen shown;
    private final Speech speech;
    private final StackPane icon = new StackPane();
    private String lastSpoken;
    private String stageClass;

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
        preview.setVisible(false);
        Rectangle backdrop = new Rectangle(PREVIEW_WIDTH, PREVIEW_HEIGHT, Color.web("#1f2933"));

        StackPane cameraIcon = Icons.CAMERA.node(64);
        cameraIcon.getStyleClass().add("icon-on-dark");
        waiting.getChildren().setAll(cameraIcon);
        waiting.setMaxSize(80, 80);

        Shape vignette = Shape.subtract(new Rectangle(PREVIEW_WIDTH, PREVIEW_HEIGHT),
                new Ellipse(PREVIEW_WIDTH / 2, PREVIEW_HEIGHT / 2, OVAL_RX, OVAL_RY));
        vignette.setFill(Color.web("#14202e", 0.62));
        vignette.setStroke(null);
        vignette.setMouseTransparent(true);

        oval.setFill(Color.TRANSPARENT);
        oval.setStrokeWidth(5);
        oval.setStroke(Color.WHITE);
        arrow.setFill(Color.web("#f5a623"));
        arrow.setStroke(Color.web("#14202e"));
        arrow.setStrokeWidth(2);
        arrow.setVisible(false);
        nudge.setAutoReverse(true);
        nudge.setCycleCount(Animation.INDEFINITE);
        icon.setMaxSize(84, 84);
        icon.setStyle("-fx-background-color: white; -fx-background-radius: 42; -fx-border-color: #f5a623;"
                + " -fx-border-width: 3; -fx-border-radius: 42;");
        icon.setVisible(false);
        StackPane.setAlignment(icon, Pos.TOP_RIGHT);
        StackPane.setMargin(icon, new Insets(14));

        StackPane clockIcon = Icons.CLOCK.node(18);
        clockIcon.getStyleClass().add("icon-on-dark");
        livenessChip.setGraphic(clockIcon);
        livenessChip.setGraphicTextGap(8);
        livenessChip.setVisible(false);
        livenessChip.setManaged(false);
        livenessChip.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        StackPane.setAlignment(livenessChip, Pos.TOP_LEFT);
        StackPane.setMargin(livenessChip, new Insets(14));

        progress.setAlignment(Pos.CENTER);
        progress.setMaxWidth(PREVIEW_WIDTH);
        progress.setMinWidth(PREVIEW_WIDTH);
        progress.getStyleClass().add("progress-row");
        for (Label step : steps) {
            step.setMaxWidth(Double.MAX_VALUE);
            step.setAlignment(Pos.CENTER);
            HBox.setHgrow(step, Priority.ALWAYS);
        }
        progress.getChildren().setAll(steps);

        StackPane viewport = new StackPane(backdrop, waiting, preview, vignette, new Group(oval), arrow, icon, livenessChip);
        viewport.setMinSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        viewport.setPrefSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        viewport.setMaxSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        Rectangle clip = new Rectangle(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        viewport.setClip(clip);

        strip.getStyleClass().add("stage-strip");
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getChildren().setAll(stageIcon, words);
        strip.setMaxWidth(PREVIEW_WIDTH);
        strip.setMinWidth(PREVIEW_WIDTH);
        words.setMaxWidth(PREVIEW_WIDTH - 84);
        for (Label l : new Label[] {headline, guidance, notice, attempt}) {
            l.setMaxWidth(PREVIEW_WIDTH - 84);
        }
        stageIcon.setMinSize(34, 34);
        stageIcon.setAlignment(Pos.CENTER);
        getStyleClass().add("capture-area");
        setSpacing(14);
        setPadding(new Insets(20));
        setAlignment(Pos.TOP_CENTER);
        getChildren().addAll(viewport, progress, strip);
        showProgress(CaptureScreenModel.Stage.CONNECTING);
    }

    private static Label label(String styleClass) {
        Label l = new Label();
        l.getStyleClass().add(styleClass);
        l.setWrapText(true);
        l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return l;
    }

    /** Switches the language of what is on screen, now. */
    public void setMessages(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
        CaptureScreenModel.Screen s = shown;
        if (s != null) {
            lastSpoken = null;
            apply(s, false);
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
                    Platform.runLater(() -> setPreviewImage(pendingFrame.getAndSet(null)));
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
            apply(screen, true);
        } else {
            Platform.runLater(() -> apply(screen, true));
        }
    }

    /** The last screen applied. */
    public CaptureScreenModel.Screen shown() {
        return shown;
    }

    /** The strings on screen now. */
    public List<String> visibleText() {
        List<String> out = new ArrayList<>();
        for (Label t : new Label[] {headline, guidance, notice, attempt}) {
            if (t.isVisible() && !t.getText().isEmpty()) {
                out.add(t.getText());
            }
        }
        return out;
    }

    public void setPreviewImage(Image image) {
        preview.setImage(image);
        preview.setVisible(image != null);
        waiting.setVisible(image == null);
    }

    /** Back to the camera placeholder: the last resident's face must not stay on screen into the next session. */
    public void clearPreview() {
        if (Platform.isFxApplicationThread()) {
            setPreviewImage(null);
        } else {
            Platform.runLater(() -> setPreviewImage(null));
        }
    }

    private void apply(CaptureScreenModel.Screen s, boolean fresh) {
        shown = s;
        set(headline, s.headline());
        set(guidance, s.guidance());
        set(notice, s.notice());
        set(attempt, s.attempt());
        showProgress(s.stage());
        oval.setStroke(colour(s.stage()));
        oval.setStrokeWidth(s.stage() == CaptureScreenModel.Stage.CHALLENGE ? 7 : 5);
        if (stageClass != null) {
            strip.getStyleClass().remove(stageClass);
        }
        stageClass = "stage-" + s.stage().name().toLowerCase(java.util.Locale.ROOT);
        strip.getStyleClass().add(stageClass);
        stageIcon.getChildren().setAll(iconOf(s.stage()).node(34));
        strip.setAccessibleText(String.join(". ", visibleText()));
        String anim = s.animation();
        boolean turn = anim != null && anim.startsWith("anim:head-turn");
        arrow.setVisible(turn);
        nudge.stop();
        if (turn) {
            boolean left = anim.endsWith("left");
            arrow.setRotate(left ? 0 : 180);
            double x = left ? -PREVIEW_WIDTH / 2 + 56 : PREVIEW_WIDTH / 2 - 56;
            arrow.setTranslateX(x);
            nudge.setFromX(x);
            nudge.setToX(x + (left ? -18 : 18));
            nudge.playFromStart();
        }
        java.util.Optional<PromptIcon> i = anim == null ? java.util.Optional.empty() : PromptIcon.of(anim);
        icon.getChildren().setAll(i.map(FaceCaptureView::draw).orElse(new Group()));
        icon.setVisible(i.isPresent());
        if (fresh && s.tone()) {
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
        String said = headline.getText();
        if (s.stage() != CaptureScreenModel.Stage.CONNECTING && !said.isEmpty() && !said.equals(lastSpoken)) {
            lastSpoken = said;
            speech.say(said);
        }
    }

    /** The icon beside the headline: a picture of the stage, so it is not told by colour alone. */
    static Icons iconOf(CaptureScreenModel.Stage stage) {
        switch (stage) {
            case CONNECTING:
            case CAPTURING:
                return Icons.CAMERA;
            case POSITIONING:
                return Icons.USER;
            case CHECKING:
                return Icons.CLOCK;
            case CHALLENGE:
                return Icons.EYE;
            case SUCCESS:
                return Icons.CHECK_CIRCLE;
            case RETRY:
                return Icons.REFRESH;
            case STOPPED:
            default:
                return Icons.WARNING;
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

    /** Which of the three steps a stage belongs to: 0 position, 1 verify (checking and the challenge), 2 capture, 3 all done. */
    static int stepOf(CaptureScreenModel.Stage stage) {
        switch (stage) {
            case CONNECTING:
            case POSITIONING:
                return 0;
            case CHECKING:
            case CHALLENGE:
            case RETRY:
                return 1;
            case CAPTURING:
                return 2;
            case SUCCESS:
                return 3;
            case STOPPED:
            default:
                return -1;
        }
    }

    private void showProgress(CaptureScreenModel.Stage stage) {
        int now = stepOf(stage);
        boolean failed = stage == CaptureScreenModel.Stage.RETRY || stage == CaptureScreenModel.Stage.STOPPED;
        for (int i = 0; i < steps.length; i++) {
            Label l = steps[i];
            String name = messages.resolve(Text.of(STEP_IDS[i]));
            boolean done = now > i;
            boolean current = now == i;
            l.setText((done ? "✓  " : failed && current ? "!  " : (i + 1) + "  ") + name);
            l.getStyleClass().removeAll("progress-done", "progress-now", "progress-failed", "progress-todo");
            l.getStyleClass().add(done ? "progress-done" : current ? (failed ? "progress-failed" : "progress-now") : "progress-todo");
            l.setVisible(true);
            l.setManaged(true);
        }
        boolean checking = stage == CaptureScreenModel.Stage.CHECKING || stage == CaptureScreenModel.Stage.CHALLENGE;
        livenessChip.setText(messages.resolve(Text.of("pramaan.status.checking")));
        livenessChip.setVisible(checking);
        livenessChip.setManaged(checking);
        progress.setAccessibleText(String.join(", ", java.util.Arrays.stream(steps).map(Label::getText)
                .collect(java.util.stream.Collectors.toList())));
    }

    private void set(Label node, Text text) {
        node.setText(text == null ? "" : messages.resolve(text));
        node.setVisible(text != null);
        node.setManaged(text != null);
    }

    static Color colour(CaptureScreenModel.Stage stage) {
        switch (stage) {
            case SUCCESS:
                return Color.web("#4cc27a");
            case RETRY:
            case STOPPED:
                return Color.web("#ff6b6b");
            case CHALLENGE:
                return Color.web("#f5a623");
            case CHECKING:
            case CAPTURING:
                return Color.web("#6cb0ff");
            default:
                return Color.WHITE;
        }
    }
}
