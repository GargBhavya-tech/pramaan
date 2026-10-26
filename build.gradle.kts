/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

plugins {
    base
}

allprojects {
    group = "pramaan"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

    // Compile to Java 11 bytecode: the MOSIP desktop Registration Client 1.2.x runs on Java 11,
    // and Android (Phase 12) needs conservative bytecode too. Any JDK >= 11 can build this.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(11)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<JavaExec>().configureEach {
        System.getProperties().filterKeys { it.toString().startsWith("pramaan.") }.forEach { (k, v) ->
            systemProperty(k.toString(), v)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        System.getProperties().filterKeys { it.toString().startsWith("pramaan.") }.forEach { (k, v) ->
            systemProperty(k.toString(), v)
        }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Build Map #7: every model file shipped in models/ must have a row in docs/licences.md, keyed by
// its file name. A model whose weights or training data forbid our use must never slip in unnoticed.
val checkModelLicences by tasks.registering {
    group = "verification"
    description = "Fails if a file in models/ has no row in docs/licences.md (Build Map #7)."
    val modelsDir = layout.projectDirectory.dir("models")
    val licences = layout.projectDirectory.file("docs/licences.md")
    inputs.dir(modelsDir).optional()
    inputs.file(licences)
    doLast {
        val table = licences.asFile.readText()
        val unlisted = modelsDir.asFile.listFiles().orEmpty()
            .filter { it.isFile && it.name != "README.md" && !it.name.startsWith(".") }
            .map { it.name }
            .filter { name -> !table.contains("`$name`") && !table.contains("| $name |") }
        if (unlisted.isNotEmpty()) {
            throw GradleException("models without a licence row in docs/licences.md: " + unlisted.joinToString(", "))
        }
    }
}

tasks.named("check") {
    dependsOn(checkModelLicences)
}
