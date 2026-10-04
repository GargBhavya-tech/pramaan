/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// The liveness engine. Build Map #1: it must be testable headlessly, so its only permitted
// runtime dependencies are ONNX Runtime, OpenCV and the Jackson JSON/YAML library. `check` enforces this.

plugins {
    // TestClock and other shared test helpers, reusable by every module's tests (Build Map #8).
    `java-test-fixtures`
    `maven-publish`
}

// Build Map #79: the Android AAR depends on this jar, so both are published side by side to a local repository
// (build/repo) that a device vendor's app can point at. ./gradlew :core:publish
publishing {
    publications {
        create<MavenPublication>("core") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "local"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "pramaan-core", "Implementation-Version" to project.version)
    }
}

dependencies {
    // ONNX Runtime types (OrtException) appear in core's public API, so it is an `api` dependency.
    api(libs.onnxruntime)
    // Policy files (Build Map #9) and the audit log (#10).
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
}

val allowedCoreGroups = setOf(
    "com.microsoft.onnxruntime",       // inference
    "org.openpnp",                     // OpenCV Java bindings
    "com.fasterxml.jackson.core",      // JSON
    "com.fasterxml.jackson.dataformat",// Jackson's YAML format, for policy files (Bible Part 17.1)
    "org.yaml",                        // SnakeYAML, pulled in by Jackson YAML; pure Java, headless
)

val checkCoreDependencies by tasks.registering {
    group = "verification"
    description = "Fails if core's runtime classpath has anything outside the allowed groups (Build Map #1)."
    val runtime = configurations.named("runtimeClasspath")
    doLast {
        // Check the jars that actually ship, not every node in the graph: platform BOMs such as
        // jackson-bom pin versions but contain no code, so they are not a dependency in the sense that matters.
        val offenders = runtime.get().incoming.artifacts.artifacts
            .map { it.id.componentIdentifier }
            .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
            .filter { it.group !in allowedCoreGroups }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "core may depend only on ONNX Runtime, OpenCV and Jackson. Not allowed: " +
                    offenders.joinToString(", ")
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkCoreDependencies)
}
