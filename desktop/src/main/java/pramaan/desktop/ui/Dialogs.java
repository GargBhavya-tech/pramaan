/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.ui;

import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import pramaan.desktop.capture.Messages;
import pramaan.desktop.capture.Text;

/**
 * The station's own modal dialogs, in the station's look, language and text size. They replace the stock pop-ups: an
 * audited action asks for the administrator and the reason in one form, not two prompts in a row, and the OK button
 * stays off until the form can be accepted. Escape cancels; focus starts on the first field.
 */
public final class Dialogs {

    private Dialogs() {
    }

    /** What an audited form collects. */
    public static final class Entry {
        private final String adminId;
        private final String reason;

        public Entry(String adminId, String reason) {
            this.adminId = adminId;
            this.reason = reason;
        }

        public String adminId() {
            return adminId;
        }

        public String reason() {
            return reason;
        }
    }

    /** The form itself, so it can be shown in a window or photographed in a test. */
    public static final class AuditedForm extends VBox {
        public final TextField admin = new TextField();
        public final TextField reason = new TextField();
        public final CheckBox confirm;
        public final Button ok = new Button();
        public final Button cancel = new Button();

        public AuditedForm(Messages m, String titleId, String bodyId, String reasonLabelId, String confirmId) {
            getStyleClass().add("dialog-root");
            setPrefWidth(480);
            Label title = new Label(m.resolve(Text.of(titleId)));
            title.getStyleClass().add("dialog-title");
            title.setWrapText(true);
            Label body = new Label(m.resolve(Text.of(bodyId)));
            body.getStyleClass().add("panel-hint");
            body.setWrapText(true);
            body.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            Label adminLabel = new Label(m.resolve(Text.of("pramaan.ui.admin.id")));
            adminLabel.getStyleClass().add("field-label");
            adminLabel.setLabelFor(admin);
            admin.setAccessibleText(adminLabel.getText());
            Label reasonLabel = new Label(m.resolve(Text.of(reasonLabelId)));
            reasonLabel.getStyleClass().add("field-label");
            reasonLabel.setWrapText(true);
            reasonLabel.setLabelFor(reason);
            reason.setAccessibleText(reasonLabel.getText());
            confirm = confirmId == null ? null : new CheckBox(m.resolve(Text.of(confirmId)));
            if (confirm != null) {
                confirm.setWrapText(true);
            }
            cancel.setText(m.resolve(Text.of("pramaan.ui.dialog.cancel")));
            ok.setText(m.resolve(Text.of("pramaan.ui.dialog.confirm")));
            ok.getStyleClass().add("primary");
            ok.setDefaultButton(true);
            cancel.setCancelButton(true);
            HBox buttons = new HBox(10, cancel, ok);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            getChildren().addAll(title, body, adminLabel, admin, reasonLabel, reason);
            if (confirm != null) {
                getChildren().add(confirm);
            }
            getChildren().add(buttons);
            Runnable gate = () -> ok.setDisable(admin.getText().isBlank() || reason.getText().isBlank()
                    || (confirm != null && !confirm.isSelected()));
            admin.textProperty().addListener((o, a, b) -> gate.run());
            reason.textProperty().addListener((o, a, b) -> gate.run());
            if (confirm != null) {
                confirm.selectedProperty().addListener((o, a, b) -> gate.run());
            }
            gate.run();
        }

        public Entry entry() {
            return new Entry(admin.getText().trim(), reason.getText().trim());
        }
    }

    /** A message with a banner and one button. */
    public static final class NoticeForm extends VBox {
        public final Button close = new Button();
        public final Banner banner = new Banner();

        public NoticeForm(Messages m, String titleId, Banner.Kind kind, Text text) {
            getStyleClass().add("dialog-root");
            setPrefWidth(480);
            Label title = new Label(m.resolve(Text.of(titleId)));
            title.getStyleClass().add("dialog-title");
            title.setWrapText(true);
            banner.show(kind, m.resolve(text), m.resolve(Text.of("pramaan.ui.kind." + kind.name().toLowerCase(java.util.Locale.ROOT))));
            close.setText(m.resolve(Text.of("pramaan.ui.dialog.close")));
            close.getStyleClass().add("primary");
            close.setDefaultButton(true);
            close.setCancelButton(true);
            HBox buttons = new HBox(close);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            getChildren().addAll(title, banner, buttons);
        }
    }

    /** Asks for an administrator and a reason (and, if {@code confirmId} is given, a tick); empty if cancelled. */
    public static Optional<Entry> askAudited(Window owner, Scene like, Messages m, String titleId, String bodyId,
            String reasonLabelId, String confirmId) {
        AuditedForm form = new AuditedForm(m, titleId, bodyId, reasonLabelId, confirmId);
        Stage stage = stage(owner, like, m, titleId, form);
        Entry[] result = new Entry[1];
        form.ok.setOnAction(e -> {
            result[0] = form.entry();
            stage.close();
        });
        form.cancel.setOnAction(e -> stage.close());
        stage.setOnShown(e -> form.admin.requestFocus());
        stage.showAndWait();
        return Optional.ofNullable(result[0]);
    }

    public static void notify(Window owner, Scene like, Messages m, String titleId, Banner.Kind kind, Text text) {
        NoticeForm form = new NoticeForm(m, titleId, kind, text);
        Stage stage = stage(owner, like, m, titleId, form);
        form.close.setOnAction(e -> stage.close());
        stage.setOnShown(e -> form.close.requestFocus());
        stage.showAndWait();
    }

    private static Stage stage(Window owner, Scene like, Messages m, String titleId, javafx.scene.Parent content) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(m.resolve(Text.of(titleId)));
        stage.setResizable(false);
        Scene scene = new Scene(content);
        if (like != null) {
            Theme.match(scene, like);
        } else {
            Theme.apply(scene);
        }
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.setScene(scene);
        return stage;
    }
}
