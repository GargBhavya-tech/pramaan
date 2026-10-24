/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.showcase;

import java.io.IOException;

/**
 * The entry point for {@link UiShowcase}. A main class that itself extends {@code javafx.application.Application} makes the
 * JVM demand JavaFX on the module path and fail with "JavaFX runtime components are missing"; a plain class that calls
 * it does not (the same reason the scaffold has its own launcher).
 */
public final class ShowcaseLauncher {

    private ShowcaseLauncher() {
    }

    public static void main(String[] args) throws IOException {
        UiShowcase.main(args);
    }
}
