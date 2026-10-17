/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * One message to the operator, with an icon and the kind in words as well as a colour (so it survives colour
 * blindness and a high-contrast display). Hidden until it has something to say, and it says what to do next, not
 * only that something failed.
 */
public final class Banner extends HBox {

    /** What sort of message this is; each has its own icon and colour. */
    public enum Kind {
        INFO(Icons.INFO, "banner-info"),
        SUCCESS(Icons.CHECK_CIRCLE, "banner-success"),
        WARNING(Icons.WARNING, "banner-warning"),
        ERROR(Icons.ERROR, "banner-error");

        final Icons icon;
        final String styleClass;

        Kind(Icons icon, String styleClass) {
            this.icon = icon;
            this.styleClass = styleClass;
        }
    }

    private final Label label = new Label();
    private Kind kind;

    public Banner() {
        getStyleClass().add("banner");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);
        setAlignment(Pos.TOP_LEFT);
        hide();
    }

    /**
     * @param kindWord the kind in the reader's language ("Error", "Warning"…), read out by a screen reader before the
     *                 message; the icon and colour alone would not be
     */
    public void show(Kind kind, String message, String kindWord) {
        if (this.kind != null) {
            getStyleClass().remove(this.kind.styleClass);
        }
        this.kind = kind;
        getStyleClass().add(kind.styleClass);
        getChildren().setAll(kind.icon.node(22), label);
        label.setText(message);
        String spoken = kindWord == null || kindWord.isEmpty() ? message : kindWord + ". " + message;
        label.setAccessibleText(spoken);
        setAccessibleText(spoken);
        setVisible(true);
        setManaged(true);
    }

    public void hide() {
        setVisible(false);
        setManaged(false);
        label.setText("");
    }

    /** The words on show now, or an empty string. */
    public String text() {
        return isVisible() ? label.getText() : "";
    }

    public Kind kind() {
        return isVisible() ? kind : null;
    }
}
