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
    System.getProperties().filterKeys { it.toString().startsWith("pramaan.") }.forEach { (k, v) ->
        systemProperty(k.toString(), v)
    }
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

// Bible Part 5: record the JPEG header signature of this station's real camera, for the stream fingerprint.
// ./gradlew :desktop:streamSignature --args="--from-device --device-ca ca.cer --out build/scaffold/stream-signature.json"
tasks.register<JavaExec>("streamSignature") {
    group = "application"
    description = "Enrols the JPEG encoder signature of this station's camera (Bible Part 5)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.desktop.scaffold.StreamSignatureTool")
    workingDir = rootProject.projectDir
}

// A walk through the station screens that needs no camera, device or models: the real UI driven by simulated events.
// ./gradlew :desktop:showcase                                interactive window (SPACE pauses, arrows step)
// ./gradlew :desktop:showcaseRender --args="build/showcase"  one PNG per screen plus beats.json, for the demo video
tasks.register<JavaExec>("showcase") {
    group = "application"
    description = "Opens the station screens with a simulated session (no camera, device or models needed)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.desktop.showcase.ShowcaseLauncher")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("showcaseRender") {
    group = "verification"
    description = "Renders the simulated station walk-through offscreen to PNG files and a beats.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.desktop.showcase.ShowcaseLauncher")
    args("--render", "build/showcase")
    workingDir = rootProject.projectDir
}

// Docs: docs/research-plan-real-data.md (E1). Plays the engine's flash sequence on a screen while a camera records, and
// writes frames, a frame index and a run log for the analyser. Records only with --consent-given.
// ./gradlew :desktop:flashExperiment --args="--list-cameras"
tasks.register<JavaExec>("flashExperiment") {
    group = "verification"
    description = "Records the first real-data flash experiment: screen flash plus camera (docs/research-plan-real-data.md)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.desktop.experiment.FlashExperiment")
    workingDir = rootProject.projectDir
}
