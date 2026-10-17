/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.ui;

import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * The station's own line icons, drawn as vector paths on a 24-unit grid so they scale with the text size and need no
 * font or image file. They are coloured by the {@code .icon} rule in {@code pramaan.css}, so an icon inherits the
 * colour of the banner, chip or strip it sits in. An icon never stands alone: a word always goes with it.
 */
public enum Icons {
    CHECK("M5 12.5l4.5 4.5L19 7"),
    CHECK_CIRCLE("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0 -18z M7.8 12.4l3 3l5.6 -6"),
    INFO("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0 -18z M12 11v5.5 M12 7.6v0.6"),
    WARNING("M12 3.5l9.5 16.5h-19z M12 10v4.6 M12 17.2v0.6"),
    ERROR("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0 -18z M9 9l6 6 M15 9l-6 6"),
    USER("M12 4a4 4 0 1 1 0 8a4 4 0 1 1 0 -8z M4.5 20.5c0 -4 3.5 -6.5 7.5 -6.5s7.5 2.5 7.5 6.5"),
    CAMERA("M3.5 8h4l1.5 -2.5h6l1.5 2.5h4v11h-17z M12 10.5a3.2 3.2 0 1 1 0 6.4a3.2 3.2 0 1 1 0 -6.4z"),
    EYE("M2 12s4 -7 10 -7s10 7 10 7s-4 7 -10 7s-10 -7 -10 -7z M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0 -6z"),
    CLOCK("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0 -18z M12 7v5l3 2"),
    REFRESH("M20 12a8 8 0 1 1 -2.4 -5.7 M20 4v5h-5"),
    OFFLINE("M2 9a15 15 0 0 1 20 0 M5 13a10 10 0 0 1 14 0 M9 17a4 4 0 0 1 6 0 M3 3l18 18"),
    GLOBE("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0 -18z M3 12h18 M12 3c3.2 3 3.2 15 0 18 M12 3c-3.2 3 -3.2 15 0 18"),
    SHIELD("M12 3l8 3v6c0 5 -3.5 8 -8 9.5c-4.5 -1.5 -8 -4.5 -8 -9.5v-6z"),
    /** A circle shaded on its left half. */
    CONTRAST("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0 -18z M12 3v18 M8 6.2h4 M5.4 9.8h6.6 M4.4 13.4h7.6 M5.4 17h6.6"),
    LOCK("M6 11h12v9h-12z M8.5 11v-3a3.5 3.5 0 0 1 7 0v3");

    private final String path;

    Icons(String path) {
        this.path = path;
    }

    /** A square node of {@code size} pixels holding this icon; add style classes to recolour it. */
    public StackPane node(double size) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add("icon");
        double scale = size / 24.0;
        p.setScaleX(scale);
        p.setScaleY(scale);
        StackPane box = new StackPane(new Group(p));
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setFocusTraversable(false);
        box.setMouseTransparent(true);
        return box;
    }
}
