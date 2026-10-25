# Running and testing PRAMAAN on real devices — a step-by-step guide

This is a practical companion to `PRAMAAN_Final_Bible.md` (design) and `PRAMAAN_Build_Map.md` (tickets), both in this folder. It answers
one question: **what do I actually plug in, run, and look at, to see this thing work?**

## 0. The honest picture first

Read this section before anything else — it will save you from expecting the wrong thing.

**There is no direct "plug in a webcam" path in this codebase today.** `core` never touches a camera. It only
receives frames through one interface, `FaceFrameSource`, which has exactly two implementations:

| Implementation | What it is | Real hardware involved? |
|---|---|---|
| `MockFrameSource` (`harness`) | Replays recorded clips in memory, for automated tests | No — this is what `./gradlew build` runs |
| `SbiFrameSource` (`sbi`) | Talks the real **MOSIP SBI protocol** (HTTP, on `127.0.0.1:4501-4600`) to whatever device service is listening | **Yes, if you point it at a real one** |

So "hardware" in this project means one of three concrete things, in order of how easy they are to get:

1. **MockMDS** — MOSIP's own reference device-service software (`third_party/mosip-mock-services/MockMDS`). It is
   not a camera; it's a small Java HTTP server that *pretends* to be an SBI-compliant capture device by replaying
   image files from disk. Running the desktop scaffold against MockMDS exercises the **entire real protocol** —
   discovery, device-signed JWS captures, the hash chain, binding — with the one exception of a genuinely live
   sensor. This is the standard way to run PRAMAAN today, and it needs no special hardware at all, just a JDK.
2. **A real MOSIP-certified SBI device** (a fingerprint/iris/face capture unit from a MOSIP device-provider
   partner, running its own device-service software) — if you own one, point the same `SbiFrameSource` config at
   its port instead of MockMDS's, and everything downstream (binding, evidence, the whole engine) runs unchanged.
   **This project has never been tested against one** — nobody on this build has had access to one. Section 3
   below tells you exactly what to change if you get one.
3. **An Android phone** — the one place actual camera hardware is exercised is the Android engine (`android/`),
   via `connectedDebugAndroidTest`. Today that test is a **smoke test**, not a full interactive liveness UI: it
   loads the verified model bundle and scores one bundled face image on the phone's own CPU. There is no on-phone
   capture screen yet (Build Map #80/#81 are not built). Section 4 covers this.

If what you actually want is "run our liveness challenges against my own face on my own laptop camera," the
closest thing that exists is **Section 5: your own recorded clips through the evaluation harness** — record short
videos of yourself with any camera (a phone works fine), and feed them through the same engine the desktop
scaffold uses, offline. It is not live/interactive, but it runs the real detectors on real footage of a real face.

---

## 1. Prerequisites (do this once)

### 1.1 JDK and Gradle

- JDK: any JDK that reports `24.0.1` or matches whatever `tools/versions.md` says was last confirmed (Gradle
  itself needs JDK 17+; the project's own bytecode target is Java 11, set in the build files — you don't need to
  install an old JDK, the modern one cross-compiles to it).
- Gradle: don't install it — the repo ships `./gradlew` (Gradle 8.14.3, pinned with a checksum). Always use that
  wrapper, not a system `gradle`.
- Check it works:

```bash
./gradlew --version
```

### 1.2 The models (`models/`)

Not committed to git (see `models/README.md`) — you have to obtain them yourself. Every file below must land in
`models/` at the repo root, named **exactly** as shown (the engine refuses to start if a name or hash doesn't
match `config/operating-points.json`):

| File | SHA-256 (must match) | How to get it |
|---|---|---|
| `face_detection_yunet_2023mar.onnx` | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` | Download directly from [OpenCV Zoo](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet) |
| `face_recognition_sface_2021dec.onnx` | `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79` | Download directly from [OpenCV Zoo](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface) |
| `mediapipe_face_landmarks_detector.onnx` | `b8b8ea16213c5f5f93517cc4d9a13f22f9859588851de64a0914e1d66f02c9f8` | **Needs conversion** — MediaPipe ships this as `.tflite` inside its `face_landmarker.task` bundle, not as `.onnx`. Extract `face_landmarks_detector.tflite` and run it through `tf2onnx` (see `docs/adr/0002-landmarks.md` and `tools/versions.md` for the exact toolchain: TensorFlow 2.21 + tf2onnx 1.17 in `tools/.venv-tf`) |
| `mediapipe_face_blendshapes.onnx` | `baafa07fdf2bc17d3ca3f1079c315b8d0da73e55863b6c0a0fce700aa595ac95` | Same bundle, `face_blendshapes.tflite`, same conversion |
| `minifasnet_v2_2.7_80x80.onnx` | `eb0feb2c6896cf52755d71b38888ea1ef56ceb266d9583ba64de30ea66f7717b` | **Needs conversion** — download `2.7_80x80_MiniFASNetV2.pth` from [Silent-Face-Anti-Spoofing](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing), export with `tools/export_minifasnet.py` |
| `minifasnet_v1se_4.0_80x80.onnx` | `17246cc267b4b3d23e49966f2062650dc4e6fe3f1a3c7ecc3ae7b0f67ad1aec4` | Same tool, `4_0_0_80x80_MiniFASNetV1SE.pth` |

Verify a file landed correctly (PowerShell):

```powershell
Get-FileHash models\face_detection_yunet_2023mar.onnx -Algorithm SHA256
```

...or (Bash):

```bash
sha256sum models/face_detection_yunet_2023mar.onnx
```

The hash must match the table above (or the row in `config/operating-points.json`) exactly, or `EngineBundle`
refuses to load — on purpose (Bible Part 22, "fail closed").

**Quick sanity check that all six are present and correctly named:**

```bash
./gradlew :harness:onnxSmoke
```

This doesn't need all six — it just proves ONNX Runtime itself loads and runs on your machine (see 1.4). To check
the full bundle loads, run the desktop test that specifically requires the real models:

```bash
./gradlew :desktop:test --tests "pramaan.desktop.capture.DesktopEnginesTest"
```

If the models aren't there, this test **skips itself** (prints "models/ not downloaded") rather than failing —
that's intentional, not a bug.

### 1.3 Build everything once

```bash
./gradlew build
```

**Expected output:** `BUILD SUCCESSFUL`, with a test summary somewhere above it. As of this guide, the full suite
is **718 tests, 0 failures**, with about 3 skipped on a machine that has the models and about 14 on a clean clone after
`python tools/setup.py` (the skips are real-corpus, MockMDS-fixture and device tests that need files you may not have: see 1.2). If you see failures, stop here and fix them before going further; nothing past this point will
work reliably on a broken build.

### 1.4 ONNX Runtime sanity check

```bash
./gradlew :harness:onnxSmoke
```

**Expected output** (numbers will differ by machine, but the shape is the same — this uses a tiny built-in smoke
model, `core/src/test/resources/models/smoke_affine.onnx`, so it needs none of §1.2's real models):

```
model          : core/src/test/resources/models/smoke_affine.onnx
ONNX Runtime   : 1.20.0
java           : 24.0.1 (Windows 11)
input shape    : [1, 4]
output shape   : [1, 4]
cold start     : 995.xx ms (load + first inference)
warm p50 / p95 : 0.2x / 1.0x ms over 200 runs
```

If instead you see `UnsatisfiedLinkError: ... onnxruntime.dll: A dynamic link library (DLL) initialization
routine failed`, your JDK's bundled `msvcp140.dll`/`vcruntime140.dll` are older than what a newer ONNX Runtime
needs. This project pins ONNX Runtime to **1.20.0** specifically to avoid this (see `tools/versions.md`) — if you
changed that version, put it back.

---

## 2. Path A — the standard local run: MockMDS + the desktop scaffold

This is the path that needs **no special hardware** and is what almost every "does it work" question should
start with.

### 2.1 Build MockMDS

MockMDS is vendored under `third_party/mosip-mock-services/MockMDS`. It's a separate Maven project (Java 21,
Maven 3.9.x — **not** built by the root Gradle build).

```bash
cd third_party/mosip-mock-services/MockMDS
mvn clean install -Dmaven.test.skip=true -Dgpg.skip=true
```

**Expected output:** `BUILD SUCCESS` near the end. This produces a runnable jar under `target/`.

### 2.2 Run MockMDS in Registration mode

```bash
cd target
java -cp "mock-mds-1.3.0-SNAPSHOT.jar;lib/*" io.mosip.mock.sbi.test.TestMockSBI \
  "mosip.mock.sbi.device.purpose=Registration"
```

(On Linux/macOS, use `:` instead of `;` in the classpath.)

**Expected output:** log lines showing an embedded HTTP server starting on a port in the range **4501-4600**
(MockMDS picks the first free port in that range; the desktop scaffold's default of `127.0.0.1:4501-4510`
assumes it lands near the bottom — if it doesn't, use `--ports` when you launch the scaffold, see 2.3). Leave
this process running in its own terminal — it's your "camera."

**Sanity-check it's alive**, in a second terminal. SBI discovery is not a plain `GET` — it's the custom HTTP verb
`MOSIPDISC` on `/device` (`docs/sbi-notes.md`), which `curl` can still send with `-X`:

```bash
curl -X MOSIPDISC http://127.0.0.1:4501/device -d "{\"type\":\"Biometric Device\"}"
```

You should get back JSON listing three devices: a finger slap device, a **face device (id 3, subId 0)**, and an
iris device — all reported as security level L0. If `curl` hangs or refuses, MockMDS isn't listening on that
port; check its console log for the actual port it bound. (A plain `curl http://127.0.0.1:4501/device` without
`-X MOSIPDISC` will not work — MockMDS doesn't answer ordinary `GET`.)

### 2.3 Run the desktop scaffold against it

In the repo root, with MockMDS still running:

```bash
./gradlew :desktop:runScaffold
```

or, if MockMDS landed on a different port:

```bash
./gradlew :desktop:runScaffold --args="--ports 4501-4510"
```

**What you should see:** a JavaFX window titled "PRAMAAN — Registration Client scaffold" opens, showing step 1,
**"Operator login"** — a live camera-style preview (actually MockMDS's replayed stream) with a face-detection
oval and guidance text. This is the real engine driving a real capture session against a real (mock) device
service.

**Important caveat, so you don't think something's broken:** the stock MockMDS face stream is a small, dimly
lit sample image (490x360, inter-ocular distance under 60 px). PRAMAAN's quality gate genuinely rejects this —
by design, a blurry/small/badly-lit face is a *quality* failure, shown as guidance ("Please move closer" /
"improve lighting"), never scored as a spoof. **Out of the box, you will mostly see guidance text, not a
full accept.** To see an actual ACCEPT, you need to replace MockMDS's source image(s) with a larger, evenly lit,
front-facing photo (MockMDS supports "switching between different user profiles" — see its own
`third_party/mosip-mock-services/MockMDS/README.md` for where the profile image files live) or use a real
device once you have one.

**The flow, screen by screen** (`RegistrationScaffold.java`; the step rail on the left of the window names each one):

1. **Operator sign-in** (button: *Sign in with face*) — a person (you, acting as the operator) completes a liveness check under the strict
   operator policy (`operator-strict` operating point). Passing logs you in as `operator-1` (configurable with
   `--operator`).
2. **Resident photo** (button: *Start face scan*) — the same, under the resident policy (more lenient thresholds, an accessibility
   picker, a challenge-plan step-up ladder if passive alone isn't conclusive). On ACCEPT, a signed evidence packet
   is written (see 2.4).
3. **Exception flow** (only if resident liveness reaches no usable action path — e.g. an infant test image) — a
   second, supervisor face-check screen, requiring a distinct supervisor login.
4. **Supervisor approval** (button: *Approve with face*) — the final screen shows the outcome.

Nothing on any of these screens ever shows a score, probability, or the word "spoof" (Bible Part 19) — that's
intentional, not a missing feature. If a session fails, you'll see one of a small number of generic messages
("Face verification could not be completed. Please try again."), identical in English and Hindi
(`--lang hi`), for every security-relevant failure — this is also intentional (Bible Part 22, "never leak which
defense caught you").

### 2.4 What gets written to disk

By default, under `build/scaffold/`:

- `audit.log` — the append-only, hash-chained audit trail of every decision (`AuditLog`). Verify its own
  integrity with:

  ```bash
  ./gradlew :harness:fraudReport --args="--audit build/scaffold/audit.log --out build/scaffold/fraud.md"
  ```

  This writes `fraud.md` (a markdown table) plus one `fraud-funnel-<signal>.svg` per signal. With only your own
  test sessions in the log there's nothing statistically interesting yet — the point here is just to confirm the
  chain reads back intact ("Audit chain intact: N entries" at the top of the report).

- `packets/<sessionId>-a<attempt>.cbeff.xml` / `.evidence.jws` / `.capture.jws` — the signed evidence for every
  **accepted** attempt.
- `attempts.log` / `latest.sth.jws` / `sth.counter` — the local transparency log (every attempt, including
  failures, as a Merkle leaf) and its latest signed tree head.
- `keys/` — development signing keys generated on first run (never real production keys).

**Re-check a signed evidence packet** (this is the "server side" check, run by hand):

```bash
./gradlew :harness:verifyEvidence --args="--evidence build/scaffold/packets/<id>-a1.evidence.jws \
  --capture build/scaffold/packets/<id>-a1.capture.jws \
  --evidence-key build/scaffold/keys/evidence.pub.der \
  --device-ca sbi/testdata/mockmds-rootca.cer \
  --allow build/scaffold/allow.json"
```

**Expected output:** a JSON report printed to stdout, ending with `"route": "CONFIRMED"` for a genuine, untampered
packet, and exit code **0**. (Exit codes: 0 = CONFIRMED, 1 = ADJUDICATE, 2 = REJECT, 3 = a usage/input error —
check the error text on stderr.)

---

## 3. Path B — a real MOSIP-certified SBI device (if you have one)

Nobody on this build has tested this path against real hardware — this section is what the architecture says
should work, unverified.

1. Install and start the device's own vendor-supplied SBI device-service software. It must expose the standard
   SBI HTTP endpoints (`MOSIPDISC`, `MOSIPDINFO`, `RCAPTURE`, `STREAM`) on `127.0.0.1`, port range 4501-4600 —
   exactly like MockMDS.
2. Point the scaffold at it the same way you would MockMDS — same `./gradlew :desktop:runScaffold`, adjusting
   `--ports`/`--host` only if the real service binds somewhere else. Nothing in `SbiFrameSource` distinguishes a
   real device from MockMDS; both speak the identical protocol.
3. **Trust anchor:** `--device-ca` must point at *that device's own* certificate authority, not MockMDS's
   `sbi/testdata/mockmds-rootca.cer`. Using the wrong CA will make every capture fail signature verification —
   this is deliberate (Bible Part 13, lock "nonce": never derive trust from what the device itself sends).
   Obtain the correct root/issuing CA certificate from the device provider; export it as a `.cer`/`.crt` file the
   same way `sbi/testdata/mockmds-rootca.cer` was produced (see `docs/sbi-notes.md`, "Round 2 observations").
4. Everything else — binding, evidence, the engine's decisions — runs unchanged; it has no idea whether the
   device behind the HTTP calls is real or mocked.
5. **Frame rate matters.** The engine measures your device's actual frame rate and gates which liveness checks
   can run (Bible: blink needs ~10 fps; a 3 fps stream falls back to "close your eyes for a second" instead).
   Whatever your real device reports, the engine adapts — but a genuinely slow device (some real SBI streams are
   as low as 3 fps) will feel noticeably more limited than MockMDS's ~21 fps stream.

---

## 4. Path C — an Android phone (real camera hardware)

### 4.1 Prerequisites

- Android SDK installed, with `compileSdk 34` and `build-tools 35.0.0` available.
- Either set `ANDROID_HOME`, or create `android/local.properties` with `sdk.dir=<path to your SDK>`.
- A physical Android phone (API 26+, i.e. Android 8.0+) connected via USB with USB debugging enabled, **or** a
  running emulator. Check it's visible:

  ```bash
  adb devices
  ```

  You should see your device listed as `device` (not `unauthorized` — if so, accept the debugging prompt on the
  phone's own screen).

### 4.2 Build and check

```bash
./gradlew -p android :pramaan-android:check
```

**Expected output:** `BUILD SUCCESSFUL` — this runs unit tests, lint, and (Build Map #79's own check) an
API-compatibility scan that fails loudly if `core` uses any Java API newer than Android's minSdk 26 supports.

### 4.3 Publish the AAR locally (needed before the on-device test can resolve `core`)

```bash
./gradlew :core:publish
./gradlew -p android :pramaan-android:publish
```

This writes `pramaan:core` and `pramaan:pramaan-android` into `build/repo` at the repo root, which the Android
build resolves as a Maven repository.

### 4.4 Run the on-device test

With the phone connected and the models present in `models/` (see 1.2) and the MockMDS stream fixture present
at `sbi/testdata/stream-sample.mjpeg` (produced once by running the fixture-capture tooling against MockMDS —
see `docs/sbi-notes.md`):

```bash
./gradlew -p android :pramaan-android:connectedDebugAndroidTest
```

**What this actually tests** — be clear-eyed about scope: it is **not** an interactive capture flow. It:

1. Copies the models and one JPEG frame (extracted from the MockMDS stream fixture) into the test APK's assets
   at build time.
2. On the phone, loads the same verified `EngineBundle` (policy + operating-point table + all six models) the
   desktop build uses.
3. Runs YuNet face detection and MiniFASNet passive scoring on that one bundled image, entirely on the phone's
   own CPU via the Android build of ONNX Runtime.
4. Asserts exactly one face was found and the passive score is a valid probability in [0, 1].

**Expected output:** a JUnit result — `1 test, 0 failures` — visible in the console and in
`android/pramaan-android/build/reports/androidTests/connected/`. If the models or the stream fixture weren't
copied in (because they weren't present in `models/`/`sbi/testdata/` at build time), the test **skips itself**
(`Assume.assumeTrue` — reported as "skipped", not "failed").

**What is genuinely not built yet** (so you don't go looking for it): there is no on-phone capture screen, no
live camera preview, and no interactive challenge UI on Android (Build Map #80 "Mock SBI Android app with the
engine inside" and #81 "Result surfacing" are both unbuilt). Testing the *interactive* challenge/liveness
experience on real hardware today means the desktop scaffold (Path A), not the phone.

---

## 5. Path D — test with your own footage (no SBI hardware needed)

This is the most useful path if what you want is "run the real detectors against a real human face" without
setting up MockMDS or owning MOSIP-certified hardware. Any camera works — your laptop's own webcam, your phone.

### 5.1 Record a few short clips

- 10-15 seconds looking at the camera, neutral expression (a "bona fide" passive-window clip).
- Optionally: a clip of you turning your head left, one turning right, one blinking, one with eyes closed for
  ≥ 2.5 s, one smiling — these let the harness answer challenge prompts automatically instead of timing out.
- Save each clip as either a directory of numbered JPEG frames (`000.jpg`, `001.jpg`, ...) or an M-JPEG file, per
  `docs/corpus-plan.md`. A simple way to get numbered JPEGs from an ordinary video file, if you have `ffmpeg`:

  ```bash
  ffmpeg -i my-clip.mp4 -qscale:v 2 clips/me-neutral/%03d.jpg
  ```

- Put these **outside the repository** (or in the gitignored `corpus/` directory) — never commit real footage of
  a real face.

### 5.2 Write a manifest

Create `corpus/manifest.csv` with this exact header (from `CorpusManifest.HEADER`):

```
clip,label,pais,person,lighting,eyewear,device,fps
corpus/clips/me-neutral,bona_fide,,P01,good,none,my-webcam,30
```

- `label` is `bona_fide` or `attack`.
- `pais` is left empty for `bona_fide` rows; for an attack clip (e.g. holding up a printed photo of yourself),
  give it a PAI-species id like `print-matte-a4` (see `docs/corpus-plan.md` for the full naming convention).
- `person` is a pseudonym (e.g. `P01`) — never a real name, per the corpus plan's privacy rules.

Optionally, `corpus/actions.csv` (see 5.1's second bullet) so the harness can answer challenge prompts instead of
every session timing out on the action step:

```
person,action,clip
P01,TURN_LEFT,corpus/clips/me-turn-left
P01,TURN_RIGHT,corpus/clips/me-turn-right
P01,BLINK,corpus/clips/me-blink
P01,EYES_CLOSED,corpus/clips/me-eyes-closed
P01,SMILE,corpus/clips/me-smile
```

### 5.3 Run the corpus through the real engine

```bash
./gradlew :harness:runCorpus --args="--manifest corpus/manifest.csv --out build/results/rows.jsonl \
  --actions corpus/actions.csv"
```

**Expected output:** one line of progress per clip (`[1/1] BONA_FIDE clips/me-neutral -> ACCEPTED` or similar),
and `build/results/rows.jsonl` containing one JSON object per session — its outcome, cue scores, and P(live).

### 5.4 Turn that into a report

```bash
./gradlew :harness:evaluate --args="--rows build/results/rows.jsonl --table config/operating-points.json \
  --policy config/pramaan-policy.yaml --out build/results/report.md --html build/results/report.html"
```

**Expected output:** `build/results/report.md` (and the `.html` version — open it in a browser) containing:

- A headline APCER/BPCER table with confidence intervals (with just your own clips, the sample size is far too
  small to mean anything statistically — this is about confirming the pipeline runs end to end, not producing a
  real number).
- A DET curve.
- A fairness table and an ablation table.
- A corpus summary (counts by class, lighting, device).

With a handful of your own clips and no real attack presentations, expect the report to say things like "no
threshold demonstrates worst-PAIS APCER ≤ X% with this many attacks" — that's the harness being honest about
small-sample statistics, not a bug.

### 5.5 Individual per-frame timing (no manifest needed)

If you just want to see the per-stage latency (decode / detect / quality / passive) on one clip, replayed at a
given frame rate — this one **does** need the real models from §1.2 (it runs YuNet and MiniFASNet for real):

```bash
./gradlew :harness:pipelineBench --args="corpus/clips/me-neutral 10"
```

The second number (`10` here) is the frame rate to replay the clip at, in fps — not an iteration count. Omit both
arguments to bench the bundled MockMDS stream fixture at its own measured ~21 fps.

**Expected output** (numbers vary by machine — see `tools/versions.md` for a reference run on this project's own
test hardware):

```
clip           : corpus/clips/me-neutral (N frames at 10.0 fps)
java / os      : 24.0.1 / Windows 11
one face in    : N of N frames
quality passed : N of N single-face frames
  SIZE                mean score 0.9x, below the limit in 0 frame(s)
  POSE                mean score 0.9x, below the limit in 0 frame(s)
  SHARPNESS           mean score 0.8x, below the limit in 0 frame(s)
  BRIGHTNESS          mean score 0.8x, below the limit in 0 frame(s)
  UNIFORMITY          mean score 0.8x, below the limit in 0 frame(s)
  BACKLIGHT           mean score 0.9x, below the limit in 0 frame(s)
passive every  : 1 frame(s); mean raw p(live) = 0.9x
decode         : p50   23.xx ms   p95   60.xx ms   (n=N)
detect         : p50   46.xx ms   p95   75.xx ms   (n=N)
quality        : p50    2.xx ms   p95   12.xx ms   (n=N)
passive        : p50   22.xx ms   p95   67.xx ms   (n=N)
```

---

## 6. Checklist — what "it's working" looks like at each stage

Run these in order; each one should pass before you move to the next.

| # | Command | Pass looks like |
|---|---|---|
| 1 | `./gradlew build` | `BUILD SUCCESSFUL`, 0 failures |
| 2 | `./gradlew :harness:onnxSmoke` | Cold start under ~2 s, warm p50 under a few ms |
| 3 | `sha256sum models/*.onnx` (or `Get-FileHash`) | Every hash matches §1.2's table |
| 4 | `./gradlew :desktop:test --tests "*.DesktopEnginesTest"` | Passes (not "skipped") once models are in place |
| 5 | MockMDS: `curl -X MOSIPDISC http://127.0.0.1:4501/device -d "{\"type\":\"Biometric Device\"}"` | JSON listing 3 devices, one of type face |
| 6 | `./gradlew :desktop:runScaffold` | A JavaFX window opens on "Operator login" with a live (mock) preview |
| 7 | A full session (operator login → resident capture) | Either an ACCEPT screen, or actionable guidance text — never a raw error or a frozen window |
| 8 | `./gradlew :harness:verifyEvidence --args="..."` on a written packet | `"route": "CONFIRMED"`, exit code 0 |
| 9 | (Android) `./gradlew -p android :pramaan-android:connectedDebugAndroidTest` | `1 test, 0 failures` (or a clean "skipped" if assets weren't present) |
| 10 | `./gradlew :harness:runCorpus` + `:harness:evaluate` on your own clips | A report is written; it may say confidence intervals are wide — that's expected with few clips |

---

## 7. Common problems

| Symptom | Cause | Fix |
|---|---|---|
| `UnsatisfiedLinkError: ... onnxruntime.dll ... DLL initialization routine failed` | ONNX Runtime ≥ 1.21 needs a newer MSVC runtime than your JDK bundles | Confirm `gradle/libs.versions.toml` still pins ONNX Runtime to **1.20.0**; don't bump it without re-testing on the target JDK (`tools/versions.md`) |
| Desktop tests report "models/ not downloaded (gitignored)" and skip | `models/` is empty or a file is missing/renamed | Re-check §1.2's table; file names must match exactly |
| `EngineBundle` throws about a model/policy hash mismatch | A model file's bytes don't match `config/operating-points.json`'s recorded SHA-256 | Re-download/re-convert that model; verify its hash before running anything |
| Scaffold shows only guidance text, never ACCEPT, against stock MockMDS | The stock MockMDS face image is too small/dim to pass the quality gate (§2.3) | Expected — replace MockMDS's source image with a larger, evenly lit one, or use a real device |
| `curl -X MOSIPDISC ...` fails to connect | MockMDS didn't bind to 4501 | Check MockMDS's own console output for the port it actually chose (it scans 4501-4600) and pass `--ports` to the scaffold accordingly |
| `--device-ca` verification fails against a real device | Using MockMDS's root CA (`sbi/testdata/mockmds-rootca.cer`) against a different device | Export and use *that device's* own CA (§3) |
| Android test reports "skipped" | Models or the MJPEG stream fixture weren't present when the test APK was built | Ensure `models/*.onnx` and `sbi/testdata/stream-sample.mjpeg` exist, then rebuild (`preBuild` copies them in) |
| `adb devices` shows nothing, or "unauthorized" | USB debugging not enabled, or the on-phone debugging prompt wasn't accepted | Enable Developer Options -> USB debugging; accept the RSA fingerprint prompt on the phone |

---

## 8. If something here is wrong or out of date

This file was written by reading the actual source (`ScaffoldConfig.java`, `CorpusManifest.java`,
`android/pramaan-android/build.gradle.kts`, `docs/sbi-notes.md`, `tools/versions.md`) and running the commands it
describes, on 2026-09-26, on Windows with JDK 24.0.1. If a command's flags or output shape has since changed,
trust the source files named above over this document, and update this file in the same change.
