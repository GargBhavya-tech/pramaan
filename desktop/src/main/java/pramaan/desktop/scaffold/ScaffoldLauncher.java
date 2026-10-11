/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import javafx.application.Application;

/**
 * Starts the registration scaffold (ADR 0003). A separate main class, because a JavaFX {@code Application}
 * launched directly from the class path fails with "JavaFX runtime components are missing" on Java 11+.
 *
 * <pre>./gradlew :desktop:runScaffold
 * ./gradlew :desktop:runScaffold --args="--ports 4501-4510 --subject 10001100010000120260923"</pre>
 */
public final class ScaffoldLauncher {

    private ScaffoldLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(RegistrationScaffold.class, args);
    }
}
