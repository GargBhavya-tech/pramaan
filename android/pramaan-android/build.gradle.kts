/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Build Map #79: the engine as an AAR for Android SBI apps (deployment Mode D). No UI, no MOSIP dependency.
plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "pramaan.android"
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        // The floor MOSIP's Android mock SBI sets (MockAndroidSBI/app/build.gradle, minSdkVersion 26).
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        // The same bytecode level as core (root build.gradle.kts): Java 11.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Published next to core in the main build's build/repo: an app adds pramaan:pramaan-android and receives core and
// onnxruntime-android with it. ./gradlew :core:publish  and  ./gradlew -p android :pramaan-android:publish
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "pramaan"
                artifactId = "pramaan-android"
                version = "0.1.0-SNAPSHOT"
            }
        }
        repositories {
            maven {
                name = "local"
                url = uri(rootDir.parentFile.resolve("build/repo"))
            }
        }
    }
}

dependencies {
    // The engine itself, from the main build. Its JVM ONNX Runtime is swapped for the Android build of the
    // same pinned version (tools/versions.md): same ai.onnxruntime API, Android native libraries.
    // api, not implementation: the AAR's own API returns core types (EngineBundle, StandardEngines).
    api("pramaan:core:0.1.0-SNAPSHOT") {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime")
    }
    api("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}

// The on-device test (#79) needs the real models and a face. Both are gitignored (models/, and the MockMDS
// stream, which shows a real person), so they are copied into the test APK at build time, never into git. When
// either is missing the assets are simply absent and the test skips itself.
val pramaanTestAssets = layout.buildDirectory.dir("generated/pramaanTestAssets")
val copyPramaanTestAssets by tasks.registering {
    val repo = rootDir.parentFile
    outputs.dir(pramaanTestAssets)
    doLast {
        val out = pramaanTestAssets.get().asFile.resolve("pramaan")
        out.deleteRecursively()
        out.resolve("models").mkdirs()
        repo.resolve("config/pramaan-policy.yaml").copyTo(out.resolve("pramaan-policy.yaml"))
        repo.resolve("config/operating-points.json").copyTo(out.resolve("operating-points.json"))
        repo.resolve("models").listFiles { f -> f.name.endsWith(".onnx") }?.forEach { it.copyTo(out.resolve("models/" + it.name)) }
        val stream = repo.resolve("sbi/testdata/stream-sample.mjpeg")
        if (stream.exists()) {
            val b = stream.readBytes()
            var start = -1
            for (i in 0 until b.size - 2) {
                if (start < 0 && b[i] == 0xFF.toByte() && b[i + 1] == 0xD8.toByte() && b[i + 2] == 0xFF.toByte()) {
                    start = i
                } else if (start >= 0 && b[i] == 0xFF.toByte() && b[i + 1] == 0xD9.toByte()) {
                    out.resolve("face.jpg").writeBytes(b.copyOfRange(start, i + 2))
                    break
                }
            }
        }
    }
}
android.sourceSets.getByName("androidTest").assets.srcDir(pramaanTestAssets)
tasks.named("preBuild") { dependsOn(copyPramaanTestAssets) }

// ---- Build Map #79: core must run on Android at minSdk -------------------------------------------------------
// core targets Java 11, but Android is not Java 11: a Java 9-11 API that is missing at minSdk compiles and then
// crashes at run time. D8 backports many of them (String.isBlank, List.of, ...), so the honest check is to dex
// core exactly as an app build would, then look up every java.* / javax.* reference left in the dex in the
// SDK's API database. Anything newer than minSdk, or absent from Android, fails the build with its name.
val coreForApiCheck: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
val coreDepsForApiCheck: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies {
    coreForApiCheck("pramaan:core:0.1.0-SNAPSHOT")
    coreDepsForApiCheck("pramaan:core:0.1.0-SNAPSHOT")
}

val NL = System.lineSeparator()

val checkCoreAndroidApi by tasks.registering {
    group = "verification"
    description = "Dexes core and its runtime libraries at minSdk; fails on any platform API Android lacks there (Build Map #79)."
    inputs.files(coreForApiCheck, coreDepsForApiCheck)
    val report = layout.buildDirectory.file("reports/core-android-api.txt")
    outputs.file(report)
    val sdk = android.sdkDirectory
    val minApi = android.defaultConfig.minSdk!!
    val platform = sdk.resolve("platforms/android-${android.compileSdk}")
    val buildTools = sdk.resolve("build-tools/${android.buildToolsVersion}")
    doLast {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val core = coreForApiCheck.singleFile
        // The JVM build of ONNX Runtime is replaced by onnxruntime-android in the AAR; it is classpath only here.
        val jars = listOf(core) + coreDepsForApiCheck.files.filter { it != core && !it.name.startsWith("onnxruntime") }
        fun run(c: List<String>): String {
            val p = ProcessBuilder(c).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw GradleException("failed: ${c.first()} ...$NL$text")
            return text
        }

        class ApiClass(val since: Int, val members: Map<String, Int>, val supers: List<String>)
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(platform.resolve("data/api-versions.xml"))
        val api = HashMap<String, ApiClass>()
        val nodes = xml.getElementsByTagName("class")
        for (i in 0 until nodes.length) {
            val c = nodes.item(i) as org.w3c.dom.Element
            val since = c.getAttribute("since").ifEmpty { "1" }.toInt()
            val members = HashMap<String, Int>()
            val supers = ArrayList<String>()
            val kids = c.childNodes
            for (k in 0 until kids.length) {
                val e = kids.item(k) as? org.w3c.dom.Element ?: continue
                when (e.tagName) {
                    "method", "field" -> members[e.getAttribute("name")] = e.getAttribute("since").ifEmpty { since.toString() }.toInt()
                    "extends", "implements" -> supers += e.getAttribute("name")
                }
            }
            api[c.getAttribute("name")] = ApiClass(since, members, supers)
        }
        // A member inherited from a class the database does not list cannot be checked: report it as unverifiable,
        // not as missing. The database lists StringBuilder as extending Object, although these methods are declared
        // on the hidden AbstractStringBuilder and are present on every Android release.
        val hidden = -1
        val knownHidden = setOf("java/lang/StringBuilder.indexOf(Ljava/lang/String;)I",
            "java/lang/StringBuilder.setCharAt(IC)V", "java/lang/StringBuffer.setCharAt(IC)V")
        fun since(cls: String, member: String, seen: MutableSet<String> = HashSet()): Int? {
            val c = api[cls] ?: return hidden
            if (!seen.add(cls)) return null
            c.members[member]?.let { return maxOf(it, c.since) }
            var sawHidden = false
            for (s in c.supers) {
                when (val r = since(s, member, seen)) {
                    null -> {}
                    hidden -> sawHidden = true
                    else -> return r
                }
            }
            return if (sawHidden) hidden else null
        }
        val refPattern = Regex("""L((?:java|javax)/[\w/$]+);\.([\w<>$]+):(\([^)]*\)[^\s,]+|[^\s,(]+)""")
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "dexdump.exe" else "dexdump"
        val lines = ArrayList<String>()
        val coreProblems = ArrayList<String>()
        for (jar in jars) {
            val work = File(temporaryDir, jar.nameWithoutExtension)
            work.deleteRecursively()
            work.mkdirs()
            val cmd = mutableListOf(java, "-cp", buildTools.resolve("lib/d8.jar").path, "com.android.tools.r8.D8",
                "--release", "--min-api", minApi.toString(), "--lib", platform.resolve("android.jar").path,
                "--output", work.path)
            coreDepsForApiCheck.files.filter { it != jar }.forEach { cmd += listOf("--classpath", it.path) }
            cmd += jar.path
            run(cmd)
            val problems = ArrayList<String>()
            val unverifiable = ArrayList<String>()
            var refs = 0
            work.listFiles { f -> f.name.endsWith(".dex") }!!.sorted().forEach { dex ->
                val found = refPattern.findAll(run(listOf(buildTools.resolve(exe).path, "-d", dex.path)))
                    .map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toSortedSet(
                        compareBy<Triple<String, String, String>>({ it.first }, { it.second }, { it.third }))
                refs += found.size
                for ((cls, name, desc) in found) {
                    val member = if (desc.startsWith("(")) name + desc else name
                    val level = if (api[cls] == null) null else if ("$cls.$member" in knownHidden) hidden else since(cls, member)
                    when {
                        api[cls] == null -> problems += "  $cls is not in Android"
                        level == null -> problems += "  $cls.$member is not in Android"
                        level == hidden -> unverifiable += "  $cls.$member (inherited from a hidden class)"
                        level > minApi -> problems += "  $cls.$member needs API $level"
                    }
                }
            }
            lines += "${jar.name}: $refs platform references, ${problems.distinct().size} problem(s), " +
                "${unverifiable.distinct().size} unverifiable"
            lines += problems.distinct()
            lines += unverifiable.distinct()
            if (jar == core) {
                coreProblems += problems.distinct()
            }
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText("After D8 at min-api $minApi:$NL" + lines.joinToString(NL) + NL)
        println(out.readText())
        if (coreProblems.isNotEmpty()) {
            throw GradleException("core uses APIs Android $minApi does not have:$NL" + coreProblems.joinToString(NL))
        }
    }
}
tasks.named("check") { dependsOn(checkCoreAndroidApi) }
