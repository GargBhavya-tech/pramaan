/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import pramaan.core.auth.OperatorAuthentication;
import pramaan.core.auth.PacketApproval;
import pramaan.core.policy.AccessibilityProfile;
import pramaan.core.session.ExceptionOutcome;
import pramaan.core.session.ExceptionReason;
import pramaan.desktop.capture.Messages;
import pramaan.desktop.capture.Text;

/**
 * The operator's task panel for each step. A panel is built once and re-worded in place ({@link Panel#retext}), so
 * switching language mid-step keeps what the operator typed, ticked and was told. Panels hold controls and words only;
 * what a button does is wired by whoever shows the panel.
 *
 * <p>Every panel states what to do, asks for one thing, and answers with a banner that says what happened and what to do
 * next. None of them shows a score or a cause of rejection.
 */
public final class Panels {

    private Panels() {
    }

    /** What a step reports back: how serious it is and the words for it. */
    public static final class Notice {
        private final Banner.Kind kind;
        private final Text text;

        public Notice(Banner.Kind kind, Text text) {
            this.kind = kind;
            this.text = text;
        }

        public Banner.Kind kind() {
            return kind;
        }

        public Text text() {
            return text;
        }
    }

    // ------------------------------------------------------------------------------------------ outcomes to words

    public static Notice loginResult(OperatorAuthentication.Outcome o) {
        switch (o) {
            case FACE_NOT_MATCHED:
                return new Notice(Banner.Kind.WARNING, Text.of("pramaan.ui.login.not-recognised"));
            case MATCH_UNAVAILABLE:
                return new Notice(Banner.Kind.ERROR, Text.of("pramaan.ui.login.match-unavailable"));
            default:
                return new Notice(Banner.Kind.WARNING, Text.of("pramaan.ui.login.incomplete"));
        }
    }

    public static Notice exceptionResult(ExceptionOutcome o) {
        switch (o) {
            case APPROVED:
                return new Notice(Banner.Kind.SUCCESS, Text.of("pramaan.ui.exception.result.APPROVED"));
            case REJECTED_SAME_PERSON:
                return new Notice(Banner.Kind.WARNING, Text.of("pramaan.ui.exception.result.REJECTED_SAME_PERSON"));
            case REJECTED_NO_SUPERVISOR:
                return new Notice(Banner.Kind.WARNING, Text.of("pramaan.ui.exception.result.REJECTED_NO_SUPERVISOR"));
            case BLOCKED_CAPTURE_ATTACK:
            case BLOCKED_DAILY_LIMIT:
                return new Notice(Banner.Kind.ERROR, Text.of("pramaan.ui.exception.result.BLOCKED"));
            default:
                return new Notice(Banner.Kind.WARNING, Text.of("pramaan.ui.exception.result.OTHER"));
        }
    }

    public static Notice approvalResult(PacketApproval.Outcome o) {
        switch (o) {
            case APPROVED:
                return new Notice(Banner.Kind.SUCCESS, Text.of("pramaan.ui.approval.approved"));
            case SAME_PERSON:
                return new Notice(Banner.Kind.WARNING, Text.of("pramaan.ui.approval.same-person"));
            case NO_VERDICT:
                return new Notice(Banner.Kind.WARNING, Text.of("pramaan.ui.approval.no-verdict"));
            default:
                return new Notice(Banner.Kind.WARNING, Text.of("pramaan.ui.approval.invalid"));
        }
    }

    // ------------------------------------------------------------------------------------------ the base panel

    /** A title, one line of instruction, the step's controls, and a banner for the answer. */
    public abstract static class Panel extends VBox {
        private final Label title = new Label();
        private final Label hint = new Label();
        private final Banner status = new Banner();
        private Notice notice;
        Messages m;

        Panel() {
            getStyleClass().add("task-panel");
            title.getStyleClass().add("panel-title");
            title.setWrapText(true);
            hint.getStyleClass().add("panel-hint");
            hint.setWrapText(true);
            hint.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            setFillWidth(true);
        }

        abstract String titleId();

        /** The id of the one-line instruction, or null if the panel needs none. */
        abstract String hintId();

        /** Re-words this panel's own controls in {@link #m}. */
        abstract void relabel();

        abstract Node initialFocus();

        final void build(Node... body) {
            List<Node> kids = new ArrayList<>();
            kids.add(title);
            if (hintId() != null) {
                kids.add(hint);
            }
            kids.addAll(List.of(body));
            if (!kids.contains(status)) {
                kids.add(status);
            }
            getChildren().setAll(kids);
        }

        /** The answer banner, for a panel that wants it somewhere other than last. */
        final Banner banner() {
            return status;
        }

        /** Re-words the whole panel; the typed values and the last answer stay. */
        public final void retext(Messages messages) {
            this.m = messages;
            title.setText(t(titleId()));
            if (hintId() != null) {
                hint.setText(t(hintId()));
            }
            relabel();
            renderNotice();
        }

        public final void say(Notice n) {
            this.notice = n;
            renderNotice();
        }

        public final void say(Banner.Kind kind, Text text) {
            say(new Notice(kind, text));
        }

        public final void clearNotice() {
            this.notice = null;
            renderNotice();
        }

        /** The answer on show, or an empty string. */
        public final String noticeText() {
            return status.text();
        }

        public final Banner.Kind noticeKind() {
            return status.kind();
        }

        public final String titleText() {
            return title.getText();
        }

        private void renderNotice() {
            if (notice == null || m == null) {
                status.hide();
                return;
            }
            status.show(notice.kind(), m.resolve(notice.text()),
                    m.resolve(Text.of("pramaan.ui.kind." + notice.kind().name().toLowerCase(java.util.Locale.ROOT))));
        }

        final void focusFirst() {
            Platform.runLater(() -> {
                Node n = initialFocus();
                if (n != null) {
                    n.requestFocus();
                }
            });
        }

        final String t(String id) {
            return m.resolve(Text.of(id));
        }

        final String t(String id, Map<String, String> params) {
            return m.resolve(Text.of(id, params));
        }
    }

    private static Label fieldLabel() {
        Label l = new Label();
        l.getStyleClass().add("field-label");
        return l;
    }

    private static Button primary() {
        Button b = new Button();
        b.getStyleClass().add("primary");
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    private static Button secondary() {
        Button b = new Button();
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    // ------------------------------------------------------------------------------------------ start-up

    /** Shown while the policy, the table and the models are being verified: it says what it is waiting for. */
    public static final class Loading extends Panel {
        public Loading() {
            ProgressIndicator spinner = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
            spinner.setMaxSize(40, 40);
            spinner.setFocusTraversable(false);
            build(spinner);
        }

        @Override
        String titleId() {
            return "pramaan.ui.loading.title";
        }

        @Override
        String hintId() {
            return "pramaan.ui.loading.hint";
        }

        @Override
        void relabel() {
        }

        @Override
        Node initialFocus() {
            return null;
        }
    }

    /** Liveness could not start. Nothing can be registered, so this says why in one place and what to check. */
    public static final class Unavailable extends Panel {
        public Unavailable() {
            build();
            say(Banner.Kind.ERROR, Text.of("pramaan.ui.unavailable.hint"));
        }

        @Override
        String titleId() {
            return "pramaan.ui.unavailable.title";
        }

        @Override
        String hintId() {
            return null;
        }

        @Override
        void relabel() {
        }

        @Override
        Node initialFocus() {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------ 1  operator sign-in

    public static final class Login extends Panel {
        public final TextField id;
        public final Button submit = primary();
        public final Button onboard = secondary();
        private final Label idLabel = fieldLabel();

        public Login(String operatorId) {
            id = new TextField(operatorId);
            idLabel.setLabelFor(id);
            submit.setDefaultButton(true);
            build(idLabel, id, submit, onboard);
        }

        @Override
        String titleId() {
            return "pramaan.ui.login.title";
        }

        @Override
        String hintId() {
            return "pramaan.ui.login.hint";
        }

        @Override
        void relabel() {
            idLabel.setText(t("pramaan.ui.login.id"));
            id.setAccessibleText(t("pramaan.ui.login.id"));
            submit.setText(t("pramaan.ui.login.submit"));
            onboard.setText(t("pramaan.ui.login.onboard"));
        }

        @Override
        Node initialFocus() {
            return id;
        }
    }

    // ------------------------------------------------------------------------------------------ 2  resident photo

    public static final class Resident extends Panel {
        public final Button scan = primary();
        public final Button next = secondary();
        private final Label registration = new Label();
        private final Label accessibility = new Label();
        private final Label accessibilityHint = new Label();
        private final List<CheckBox> boxes = new ArrayList<>();
        /** The accessibility options, folded away until needed so the buttons stay in view; the header counts the ticks. */
        private final javafx.scene.control.TitledPane accessibilityPane = new javafx.scene.control.TitledPane();
        private final Label photoTitle = new Label();
        private final Label photoEmpty = new Label();
        private final ImageView photo = new ImageView();
        private final StackPane frame = new StackPane();
        private final String registrationId;

        /** @param profiles the accessibility profiles this centre's policy offers; none means no section */
        public Resident(String registrationId, List<AccessibilityProfile> profiles) {
            this.registrationId = registrationId;
            registration.getStyleClass().add("field-label");
            accessibility.getStyleClass().add("section-label");
            accessibilityHint.getStyleClass().add("panel-hint");
            accessibilityHint.setWrapText(true);
            accessibilityHint.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            photoTitle.getStyleClass().add("section-label");
            photoEmpty.getStyleClass().add("photo-empty");
            photoEmpty.setWrapText(true);
            photo.setFitWidth(200);
            photo.setFitHeight(250);
            photo.setPreserveRatio(true);
            photo.setVisible(false);
            photo.setManaged(false);
            frame.getStyleClass().add("photo-frame");
            frame.getChildren().setAll(photoEmpty, photo);
            next.setDisable(true);
            scan.setDefaultButton(true);
            List<Node> body = new ArrayList<>();
            body.add(registration);
            if (!profiles.isEmpty()) {
                VBox options = new VBox(6);
                options.getChildren().add(accessibilityHint);
                for (AccessibilityProfile p : profiles) {
                    CheckBox b = new CheckBox();
                    b.setUserData(p);
                    b.setWrapText(true);
                    b.setMaxWidth(Double.MAX_VALUE);
                    b.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
                    b.selectedProperty().addListener((o, was, is) -> retitleAccessibility());
                    boxes.add(b);
                    options.getChildren().add(b);
                }
                accessibilityPane.setContent(options);
                accessibilityPane.setExpanded(false);
                accessibilityPane.setAnimated(false);
                accessibilityPane.getStyleClass().add("fold");
                body.add(accessibilityPane);
            }
            body.add(scan);
            body.add(next);
            body.add(banner()); // right under the buttons: the operator must not have to scroll to learn whether the scan worked
            body.add(photoTitle);
            body.add(frame);
            build(body.toArray(new Node[0]));
        }

        @Override
        String titleId() {
            return "pramaan.ui.resident.title";
        }

        @Override
        String hintId() {
            return "pramaan.ui.resident.hint";
        }

        @Override
        void relabel() {
            registration.setText(t("pramaan.ui.resident.registration", Map.of("id", registrationId)));
            accessibility.setText(t("pramaan.ui.resident.accessibility"));
            retitleAccessibility();
            accessibilityHint.setText(t("pramaan.ui.resident.accessibility.hint"));
            for (CheckBox b : boxes) {
                b.setText(t("pramaan.ui.profile." + ((AccessibilityProfile) b.getUserData()).name()));
            }
            scan.setText(t("pramaan.ui.resident.scan"));
            next.setText(t("pramaan.ui.resident.next"));
            photoTitle.setText(t("pramaan.ui.resident.photo"));
            photoEmpty.setText(t("pramaan.ui.resident.photo-empty"));
            photo.setAccessibleText(t("pramaan.ui.resident.photo"));
        }

        @Override
        Node initialFocus() {
            return scan;
        }

        private void retitleAccessibility() {
            long ticked = boxes.stream().filter(CheckBox::isSelected).count();
            accessibilityPane.setText(accessibility.getText() + (ticked > 0 ? "  (" + ticked + ")" : ""));
        }

        /** The profiles the operator has ticked. */
        public java.util.Set<AccessibilityProfile> chosenProfiles() {
            java.util.Set<AccessibilityProfile> out = java.util.EnumSet.noneOf(AccessibilityProfile.class);
            for (CheckBox b : boxes) {
                if (b.isSelected()) {
                    out.add((AccessibilityProfile) b.getUserData());
                }
            }
            return out;
        }

        public void setPhoto(Image image) {
            photo.setImage(image);
            photo.setVisible(image != null);
            photo.setManaged(image != null);
            photoEmpty.setVisible(image == null);
            photoEmpty.setManaged(image == null);
        }

        public List<CheckBox> boxes() {
            return boxes;
        }

        /** Opens the folded accessibility options (the showcase and tests tick boxes that would otherwise be hidden). */
        public void expandAccessibility() {
            accessibilityPane.setExpanded(true);
        }
    }

    // ------------------------------------------------------------------------------------------ 2b  exception

    public static final class ExceptionApproval extends Panel {
        public final ComboBox<ExceptionReason> reason = new ComboBox<>();
        public final TextField supervisor;
        public final Button approve = primary();
        public final Button next = secondary();
        private final Label reasonLabel = fieldLabel();
        private final Label supervisorLabel = fieldLabel();

        public ExceptionApproval(String supervisorId) {
            supervisor = new TextField(supervisorId);
            reason.getItems().setAll(ExceptionReason.values());
            reason.getSelectionModel().selectFirst();
            reason.setMaxWidth(Double.MAX_VALUE);
            reason.setConverter(new StringConverter<>() {
                @Override
                public String toString(ExceptionReason r) {
                    return r == null || m == null ? "" : t("pramaan.ui.exception." + r.name());
                }

                @Override
                public ExceptionReason fromString(String s) {
                    return null;
                }
            });
            reasonLabel.setLabelFor(reason);
            supervisorLabel.setLabelFor(supervisor);
            next.setDisable(true);
            approve.setDefaultButton(true);
            build(reasonLabel, reason, supervisorLabel, supervisor, approve, next);
        }

        @Override
        String titleId() {
            return "pramaan.ui.exception.title";
        }

        @Override
        String hintId() {
            return "pramaan.ui.exception.hint";
        }

        @Override
        void relabel() {
            reasonLabel.setText(t("pramaan.ui.exception.reason"));
            reason.setAccessibleText(t("pramaan.ui.exception.reason"));
            supervisorLabel.setText(t("pramaan.ui.exception.supervisor"));
            supervisor.setAccessibleText(t("pramaan.ui.exception.supervisor"));
            approve.setText(t("pramaan.ui.exception.approve"));
            next.setText(t("pramaan.ui.resident.next"));
            ExceptionReason chosen = reason.getValue();
            reason.setValue(null);
            reason.setValue(chosen);
        }

        @Override
        Node initialFocus() {
            return reason;
        }
    }

    // ------------------------------------------------------------------------------------------ 3  approval

    public static final class Approval extends Panel {
        public final TextField supervisor;
        public final Button approve = primary();
        public final Button another = secondary();
        private final Label packet = new Label();
        private final Label supervisorLabel = fieldLabel();
        private final String packetId;

        public Approval(String packetId, String supervisorId) {
            this.packetId = packetId;
            supervisor = new TextField(supervisorId);
            packet.getStyleClass().add("field-label");
            supervisorLabel.setLabelFor(supervisor);
            another.setDisable(true);
            approve.setDefaultButton(true);
            build(packet, supervisorLabel, supervisor, approve, another);
        }

        @Override
        String titleId() {
            return "pramaan.ui.approval.title";
        }

        @Override
        String hintId() {
            return "pramaan.ui.approval.hint";
        }

        @Override
        void relabel() {
            packet.setText(t("pramaan.ui.approval.packet", Map.of("id", packetId)));
            supervisorLabel.setText(t("pramaan.ui.approval.supervisor"));
            supervisor.setAccessibleText(t("pramaan.ui.approval.supervisor"));
            approve.setText(t("pramaan.ui.approval.approve"));
            another.setText(t("pramaan.ui.approval.another"));
        }

        @Override
        Node initialFocus() {
            return supervisor;
        }
    }
}
