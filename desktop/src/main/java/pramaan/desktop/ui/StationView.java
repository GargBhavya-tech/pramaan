/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import pramaan.desktop.capture.FaceCaptureView;
import pramaan.desktop.capture.Messages;
import pramaan.desktop.capture.Text;

/**
 * The whole station window, apart from what the engine decides: a header (who, what state, language and
 * accessibility switches), the registration steps on the left, the resident-facing capture area in the middle and the
 * operator's task panel on the right. It owns no workflow logic, so it can be put on screen and photographed without
 * an engine, a camera or a device.
 */
public final class StationView extends BorderPane {

    /** Where the operator is. {@code EXCEPTION} appears in the rail only while it is the current step. */
    public enum Step { LOGIN, RESIDENT, EXCEPTION, APPROVAL }

    /** What the header's buttons do; the view itself changes nothing but the look. */
    public interface Actions {
        /** Switch between English and Hindi. */
        void toggleLanguage();

        void retakeBackdrop();

        void diagnostics();
    }

    public static final double PANEL_WIDTH = 380;

    private Messages messages;
    private final FaceCaptureView capture;
    private final StepRail rail = new StepRail();
    private final HBox header = new HBox();
    private final VBox notices = new VBox();
    private final ScrollPane panelScroll = new ScrollPane();
    private final ToggleButton larger = new ToggleButton();
    private final ToggleButton contrast = new ToggleButton();
    private final Button language = new Button();
    private final MenuButton tools = new MenuButton();
    private final MenuItem retake = new MenuItem();
    private final MenuItem diagnostics = new MenuItem();
    private final Banner tag = new Banner();
    private final Banner stale = new Banner();
    private Step step = Step.LOGIN;
    private String operator;
    private boolean showOffline;
    private boolean diagnosticOn;
    private boolean staleSettings;
    private Panels.Panel panel;

    public StationView(Messages messages, FaceCaptureView capture, Actions actions) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.capture = Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(actions, "actions");

        header.getStyleClass().add("app-header");
        header.setAlignment(Pos.CENTER_LEFT);
        tag.getStyleClass().add("scaffold-strip");
        stale.getStyleClass().add("notice-strip");

        larger.setOnAction(e -> {
            if (getScene() != null) {
                Theme.setLargeText(getScene(), larger.isSelected());
            }
        });
        contrast.setOnAction(e -> {
            if (getScene() != null) {
                Theme.setHighContrast(getScene(), contrast.isSelected());
            }
        });
        language.setOnAction(e -> actions.toggleLanguage());
        retake.setOnAction(e -> actions.retakeBackdrop());
        diagnostics.setOnAction(e -> actions.diagnostics());
        tools.getItems().setAll(retake, diagnostics);

        setTop(new VBox(header, notices));
        setLeft(rail);

        ScrollPane centre = new ScrollPane(capture);
        centre.setFitToWidth(true);
        centre.getStyleClass().add("plain-scroll");
        setCenter(centre);

        panelScroll.setFitToWidth(true);
        panelScroll.setFitToHeight(true);
        panelScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        panelScroll.setMinWidth(PANEL_WIDTH);
        panelScroll.setPrefWidth(PANEL_WIDTH);
        panelScroll.setMaxWidth(PANEL_WIDTH);
        panelScroll.getStyleClass().add("plain-scroll");
        setRight(panelScroll);
        sceneProperty().addListener((o, was, now) -> {
            if (now != null) {
                Theme.setHindi(now, this.messages.isHindi());
            }
        });
        retext(messages);
    }

    /** Re-words the header and the rail in {@code messages}' language, and the open panel with them. */
    public void retext(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
        if (getScene() != null) {
            Theme.setHindi(getScene(), messages.isHindi());
        }
        capture.setMessages(messages);
        rebuildHeader();
        rebuildNotices();
        rebuildRail();
        if (panel != null) {
            panel.retext(messages);
        }
    }

    public void setStep(Step step) {
        this.step = step;
        rebuildRail();
    }

    /** The signed-in operator, or null when nobody is. */
    public void setOperator(String operator) {
        this.operator = operator;
        rebuildHeader();
    }

    /** The scaffold never syncs, so it is always offline and says so (Build Map #94). */
    public void setOffline(boolean offline, boolean staleSettings) {
        this.showOffline = offline;
        this.staleSettings = staleSettings;
        rebuildHeader();
        rebuildNotices();
    }

    public void setDiagnostic(boolean on) {
        this.diagnosticOn = on;
        rebuildHeader();
    }

    /** The station tools stay disabled while a scan is running. */
    public void setToolsEnabled(boolean enabled) {
        tools.setDisable(!enabled);
    }

    public void setPanel(Panels.Panel next) {
        this.panel = next;
        next.retext(messages);
        panelScroll.setContent(next);
        panelScroll.setVvalue(0);
        next.focusFirst();
    }

    public Panels.Panel panel() {
        return panel;
    }

    public FaceCaptureView capture() {
        return capture;
    }

    // ------------------------------------------------------------------------------------------ header

    private String t(String id) {
        return messages.resolve(Text.of(id));
    }

    private void rebuildHeader() {
        Label name = new Label("PRAMAAN");
        name.getStyleClass().add("brand-name");
        Label dev = new Label("प्रमाण");
        dev.getStyleClass().addAll("brand-name", "devanagari");
        Label sub = new Label(t("pramaan.ui.app.subtitle"));
        sub.getStyleClass().add("brand-sub");
        VBox brand = new VBox(1, new HBox(10, name, dev), sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        List<Node> kids = new ArrayList<>();
        kids.add(brand);
        kids.add(spacer);
        if (showOffline) {
            kids.add(chip(Icons.OFFLINE, t("pramaan.status.offline"), false));
        }
        kids.add(chip(Icons.USER, operator == null ? t("pramaan.ui.header.signed-out")
                : messages.resolve(Text.of("pramaan.ui.header.operator", Map.of("operator", operator))), false));
        if (diagnosticOn) {
            kids.add(chip(Icons.SHIELD, t("pramaan.ui.header.diagnostic"), true));
        }
        larger.setText("A+");
        larger.setAccessibleText(t("pramaan.ui.header.text-size"));
        larger.setTooltip(new javafx.scene.control.Tooltip(t("pramaan.ui.header.text-size")));
        contrast.setText(null);
        contrast.setGraphic(Icons.CONTRAST.node(20));
        contrast.setAccessibleText(t("pramaan.ui.header.contrast"));
        contrast.setTooltip(new javafx.scene.control.Tooltip(t("pramaan.ui.header.contrast")));
        language.setText(messages.isHindi() ? "English" : "हिन्दी");
        if (!language.getStyleClass().contains("devanagari")) {
            language.getStyleClass().add("devanagari");
        }
        language.setAccessibleText(t("pramaan.ui.header.language"));
        language.setGraphic(Icons.GLOBE.node(18));
        tools.setText(t("pramaan.ui.menu.station"));
        retake.setText(t("pramaan.ui.menu.retake-backdrop"));
        diagnostics.setText(t("pramaan.ui.menu.diagnostics"));
        kids.add(larger);
        kids.add(contrast);
        kids.add(language);
        kids.add(tools);
        header.setSpacing(10);
        header.getChildren().setAll(kids);
    }

    private static HBox chip(Icons icon, String text, boolean warn) {
        Label l = new Label(text);
        HBox c = new HBox(6, icon.node(16), l);
        c.getStyleClass().add("chip");
        if (warn) {
            c.getStyleClass().add("chip-warn");
        }
        c.setAccessibleText(text);
        return c;
    }

    /** The permanent honest label, and the out-of-date-settings warning when it applies. */
    private void rebuildNotices() {
        tag.show(Banner.Kind.INFO, t("pramaan.ui.scaffold.tag"), null);
        List<Node> kids = new ArrayList<>();
        kids.add(tag);
        if (staleSettings) {
            stale.show(Banner.Kind.WARNING, t("pramaan.status.stale-policy"), t("pramaan.ui.kind.warning"));
            kids.add(stale);
        }
        notices.getChildren().setAll(kids);
    }

    // ------------------------------------------------------------------------------------------ step rail

    private void rebuildRail() {
        List<Step> shown = new ArrayList<>(List.of(Step.LOGIN, Step.RESIDENT));
        if (step == Step.EXCEPTION) {
            shown.add(Step.EXCEPTION);
        }
        shown.add(Step.APPROVAL);
        List<StepRail.Step> rows = new ArrayList<>();
        int n = 1;
        for (Step s : shown) {
            StepRail.State state = s == step ? StepRail.State.CURRENT : s.ordinal() < step.ordinal() ? StepRail.State.DONE
                    : StepRail.State.TODO;
            String word = t(state == StepRail.State.DONE ? "pramaan.ui.step.state.done"
                    : state == StepRail.State.CURRENT ? "pramaan.ui.step.state.current" : "pramaan.ui.step.state.todo");
            rows.add(new StepRail.Step(Integer.toString(n++), t("pramaan.ui.step." + s.name().toLowerCase(java.util.Locale.ROOT)),
                    word, state));
        }
        rail.update(t("pramaan.ui.rail.heading"), rows);
    }
}
