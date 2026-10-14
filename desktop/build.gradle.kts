/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Registration Client integration, deployment Mode C (Build Map Phase 11).
dependencies {
    implementation(project(":core"))
    implementation(project(":sbi"))
    // JPEG 2000 reader for the ISO face record in signed captures (Build Map #56).
    implementation(libs.jai.imageio.jpeg2000)
    // The scaffold's evidence store writes allow.json for the server re-check (ADR 0003 patch P4).
    implementation(libs.jackson.databind)
}

// JavaFX for the capture screen (Build Map #75; ADR 0003), at the version the Registration Client pins.
// OpenJFX publishes one jar per OS as a classifier and names it in its POMs through a Maven profile property
// that Gradle does not evaluate, so each module is declared here with the classifier and taken non-transitively.
val javafxPlatform: String = System.getProperty("os.name").lowercase().let { os ->
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> "mac"
        else -> "linux"
    }
}
dependencies {
    for (fx in listOf(libs.javafx.base, libs.javafx.graphics, libs.javafx.controls)) {
        val m = fx.get()
        implementation("${m.module.group}:${m.module.name}:${m.versionConstraint.requiredVersion}:$javafxPlatform") {
            isTransitive = false
        }
    }
}

// Shared test helpers such as TestClock (Build Map #8).
dependencies {
    testImplementation(testFixtures(project(":core")))
}

// ADR 0003: the registration scaffold against a local SBI device service (MockMDS on 4501 by default).
// ./gradlew :desktop:runScaffold   or   ./gradlew :desktop:runScaffold --args="--ports 4501-4510"
tasks.register<JavaExec>("runScaffold") {
    group = "application"
    description = "Runs the Registration Client scaffold with PRAMAAN face liveness (Build Map #75)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.desktop.scaffold.ScaffoldLauncher")
    workingDir = rootProject.projectDir
}

// Bible Part 43, Build Map #87: fingerprint this station's empty backdrop from one photograph of the empty room.
// ./gradlew :desktop:captureBackdrop --args="--image empty-room.jpg --out build/scaffold/backdrop.bin"
tasks.register<JavaExec>("captureBackdrop") {
    group = "application"
    description = "Fingerprints the empty backdrop of this station for the scene and illumination cues (Build Map #87)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.desktop.scaffold.BackdropTool")
    workingDir = rootProject.projectDir
}
