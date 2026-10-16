/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Evaluation harness: corpus replay through the production engine, ISO/IEC 30107-3 metrics with
// confidence intervals, and report generation (Build Map Phase 10).
dependencies {
    implementation(project(":core"))
    implementation(project(":sbi"))
}

// Build Map #2: measure ONNX Runtime cold-start and warm latency on this machine.
// ./gradlew :harness:onnxSmoke   or   ./gradlew :harness:onnxSmoke --args="path/to/model.onnx"
tasks.register<JavaExec>("onnxSmoke") {
    group = "verification"
    description = "Runs one ONNX model and prints cold-start and warm latency (Build Map #2)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.OnnxSmokeCheck")
    workingDir = rootProject.projectDir
}

// Shared test helpers such as TestClock (Build Map #8).
dependencies {
    testImplementation(testFixtures(project(":core")))
}

// The replay mock builds signed capture payloads (Build Map #15).
dependencies {
    implementation(libs.jackson.databind)
}

// The pipeline benchmark and integration tests decode real JPEG frames on the desktop JVM.
dependencies {
    implementation(project(":desktop"))
}

// Build Map #28: per-stage latency on this machine. ./gradlew :harness:pipelineBench [--args="clip.mjpeg fps"]
tasks.register<JavaExec>("pipelineBench") {
    group = "verification"
    description = "Runs the frame pipeline over a clip and prints per-stage p50/p95 latency (Build Map #28)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.PipelineBench")
    workingDir = rootProject.projectDir
}

// Build Map #62: the server re-check in one command. ./gradlew :harness:verifyEvidence --args="--evidence e.jws ..."
tasks.register<JavaExec>("verifyEvidence") {
    group = "verification"
    description = "Re-checks a signed evidence record against its capture, allow-list and sample crops (Build Map #62)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.verify.VerifyEvidence")
    workingDir = rootProject.projectDir
}

// Build Map #63: re-run the server re-check with a newer model over stored evidence.
tasks.register<JavaExec>("rescanEvidence") {
    group = "verification"
    description = "Rescans a directory of stored evidence with a newer model and lists enrolments to review (Build Map #63)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.verify.RescanEvidence")
    workingDir = rootProject.projectDir
}

// Bible Part 17.2: sign a policy/table/model bundle. ./gradlew :harness:signBundle --args="--config config --models models --key k.p8.der --version 1"
tasks.register<JavaExec>("signBundle") {
    group = "verification"
    description = "Signs the liveness bundle (policy, operating-point table, models) with a server key (Bible Part 17.2)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.SignBundle")
    workingDir = rootProject.projectDir
}

// Build Map #88: per-operator fraud signals from an audit log. ./gradlew :harness:fraudReport --args="--audit build/scaffold/audit.log --out fraud.md"
tasks.register<JavaExec>("fraudReport") {
    group = "verification"
    description = "Per-operator z-scores against the centre baseline, with funnel-plot limits, from the audit log (Build Map #88)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.eval.FraudReport")
    workingDir = rootProject.projectDir
}

// Build Map #67: the corpus through the production engine, one JSON row per clip.
// ./gradlew :harness:runCorpus --args="--manifest corpus/manifest.csv --out build/results/rows.jsonl [--actions corpus/actions.csv]"
tasks.register<JavaExec>("runCorpus") {
    group = "verification"
    description = "Runs every corpus clip through the production engine and writes SessionRow JSON Lines (Build Map #67)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.run.RunCorpus")
    workingDir = rootProject.projectDir
}

// Build Map #68-#71: the evaluation report (and optionally the operating-point table) from a corpus run.
tasks.register<JavaExec>("evaluate") {
    group = "verification"
    description = "Writes the ISO/IEC 30107-3 report, fairness and ablation tables from harness result rows (Build Map #68-#71)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.eval.Evaluate")
    workingDir = rootProject.projectDir
}

tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
}

// A runnable device that speaks the whole SBI Liveness Profile over loopback HTTP, signed through a throwaway CA.
// ./gradlew :harness:runProfileDevice --args="--port 4501 --frame some-face.jpg"
tasks.register<JavaExec>("runProfileDevice") {
    group = "application"
    description = "Runs a development SBI device that implements the Liveness Profile (Build Map #100)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.mock.ProfileStubDevice")
    workingDir = rootProject.projectDir
}

// Scores person segmenters (the old rectangle, the no-model subtraction, optionally a model) on synthetic scenes with
// known truth. ./gradlew :harness:segmentationBench --args="[--model models/x.onnx --spec u2net320] [--overlay dir]"
tasks.register<JavaExec>("segmentationBench") {
    group = "verification"
    description = "Compares person-segmentation routes on synthetic scenes (Bible Part 43)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.factory.SegmentationBench")
    workingDir = rootProject.projectDir
}

// An automated red team: searches the attack simulator's parameters for what the moire cue likes best (SYNTHETIC).
// ./gradlew :harness:redTeam --args="--seed 7 --generations 40"
tasks.register<JavaExec>("redTeam") {
    group = "verification"
    description = "Evolution-strategy red team against the moire cue on synthetic recaptures (Bible Part 45)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.factory.RedTeamFuzz")
    workingDir = rootProject.projectDir
}

// An install-time self-test: controls and attacks made from this station's own empty-room photo, through the real cues.
// ./gradlew :harness:stationSelfTest --args="--photo empty-room.jpg"
tasks.register<JavaExec>("stationSelfTest") {
    group = "verification"
    description = "Runs attacks made from this station's backdrop through the scene and moire cues (Bible Part 43, 45)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.factory.StationSelfTest")
    workingDir = rootProject.projectDir
}

// Docs: docs/research-plan-real-data.md (E1). Asks the real flash cue whether faces in recorded runs answered the light.
// ./gradlew :harness:analyseFlash --args="--dir build/e1"
tasks.register<JavaExec>("analyseFlash") {
    group = "verification"
    description = "Analyses recordings from :desktop:flashExperiment with the real flash cue (docs/research-plan-real-data.md)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.experiment.FlashExperimentAnalysis")
    workingDir = rootProject.projectDir
}

// The red team against the fused decision, with the shipped operating-point table (SYNTHETIC).
// ./gradlew :harness:fusedRedTeam
tasks.register<JavaExec>("fusedRedTeam") {
    group = "verification"
    description = "Which display pitches the whole fusion accepts, with and without the flash cue (Bible Part 9, 45)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("pramaan.harness.factory.FusedRedTeam")
    workingDir = rootProject.projectDir
}
