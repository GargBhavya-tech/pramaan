/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.ui;

import java.util.Objects;
import javafx.scene.Parent;
import javafx.scene.Scene;

/**
 * Loads the station stylesheet and switches its two accessibility modes. The modes are style classes on the scene's
 * root, so every colour and size in {@code pramaan.css} follows from a token and nothing is restyled in code.
 */
public final class Theme {

    public static final String LARGE_TEXT = "large-text";
    public static final String HIGH_CONTRAST = "high-contrast";
    /** Devanagari needs a font that has a bold face for it; the default Latin font does not (it draws empty boxes). */
    public static final String HINDI = "lang-hi";

    private Theme() {
    }

    public static void setHindi(Scene scene, boolean on) {
        toggle(scene.getRoot(), HINDI, on);
    }

    public static boolean isHindi(Scene scene) {
        return scene.getRoot().getStyleClass().contains(HINDI);
    }

    /** The stylesheet's URL, as JavaFX wants it. */
    public static String stylesheet() {
        return Objects.requireNonNull(Theme.class.getResource("pramaan.css"), "pramaan.css is missing").toExternalForm();
    }

    public static void apply(Scene scene) {
        String sheet = stylesheet();
        if (!scene.getStylesheets().contains(sheet)) {
            scene.getStylesheets().add(sheet);
        }
    }

    public static void setLargeText(Scene scene, boolean on) {
        toggle(scene.getRoot(), LARGE_TEXT, on);
    }

    public static void setHighContrast(Scene scene, boolean on) {
        toggle(scene.getRoot(), HIGH_CONTRAST, on);
    }

    public static boolean isLargeText(Scene scene) {
        return scene.getRoot().getStyleClass().contains(LARGE_TEXT);
    }

    public static boolean isHighContrast(Scene scene) {
        return scene.getRoot().getStyleClass().contains(HIGH_CONTRAST);
    }

    /** A new scene (a dialog) looks like {@code like}: same sheet, same modes. */
    public static void match(Scene scene, Scene like) {
        apply(scene);
        setLargeText(scene, isLargeText(like));
        setHighContrast(scene, isHighContrast(like));
        setHindi(scene, isHindi(like));
    }

    private static void toggle(Parent root, String styleClass, boolean on) {
        if (on && !root.getStyleClass().contains(styleClass)) {
            root.getStyleClass().add(styleClass);
        } else if (!on) {
            root.getStyleClass().remove(styleClass);
        }
    }
}
