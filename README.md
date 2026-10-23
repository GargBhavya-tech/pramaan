# PRAMAAN (प्रमाण)

**Face liveness and presentation-attack detection for the MOSIP Registration Client that produces signed,
re-checkable evidence instead of a one-off pass/fail.**

Built for MOSIP Decode 2026, problem statement 04. *Pramaan* is Sanskrit for "proof".

[![build](https://github.com/GargBhavya-tech/pramaan/actions/workflows/build.yml/badge.svg)](https://github.com/GargBhavya-tech/pramaan/actions/workflows/build.yml)
![Java](https://img.shields.io/badge/Java-24-blue) ![licence](https://img.shields.io/badge/licence-MPL--2.0-green)

## The problem

National ID enrolment (MOSIP) captures a resident's face through a certified biometric device (SBI). Liveness checks
are expected to stop prints, screens and replays held up to the lens. Three gaps make that weaker than it looks:

1. **The preview is unsigned and the capture is signed, and nothing links them.** An attacker who controls the
   preview stream can pass liveness on one face and submit another. On Android the client never even sees the
   preview frames.
2. **Injected video and virtual cameras** (OBS, deepfake pipelines) are outside ISO/IEC 30107, which covers
   presentation attacks only.
3. **A verdict cannot be audited.** A pass/fail made on the client today cannot be re-checked by the server
   tomorrow, or re-scored when better models exist.

## What PRAMAAN does

- **Passive checks first.** Unpredictable challenges are added only when needed, and physics checks that ask
  nothing of the resident (flash reflection, stereo depth, scene consistency, pupil reflex) are preferred over
  asking people to perform.
- **Injection detection.** Virtual cameras are vetoed using the device's reported identity and the OS camera
  registrations. On Windows that includes DirectShow, so software cameras like OBS are seen.
- **Capture binding.** A session nonce, a frame hash chain, identity continuity and the SBI signature chain are
  all checked before an `ACCEPT`, so a capture swap is caught.
- **Signed evidence package.** The liveness evidence travels as a JWS in the face BIR. The server can re-verify
  it later with one command, or re-score the stored encrypted crops with a stronger model.
- **Inclusion by design.** Physiological cues (blink, pulse, pupil reflex) never count *against* a resident,
  because elderly, disabled and darker-skinned residents fail them for innocent reasons. There are Hindi, icon and
  text-to-speech modes, and an infant mode.
- **Insider-fraud analytics.** Per-operator z-scores are computed against the centre's own baseline. A resident
  capture that matches the logged-in operator's face is hard-blocked.
- **Signed policy and model bundles with anti-rollback**, so an operator cannot edit a threshold or replay an
  expired bundle offline. The engine fails closed.
- **An evaluation harness** that reports APCER and BPCER per attack type, with confidence intervals and DET plots.
  ACER is deliberately not reported, because ISO/IEC 30107-3 deprecated it.
- **An SBI Liveness Profile proposal**: an extension to the device standard that closes the preview/capture gap
  at the source, with a client and two reference devices that implement it.

## Architecture

```
            ┌──────────── desktop (JavaFX scaffold, ADR 0003) ────────────┐
 camera ──► │ SBI client (sbi) ──► LivenessSession (core) ──► evidence    │ ──► signed face BIR
 (SBI/MDS)  │   M-JPEG preview      cues → fusion → decision    JWS + log │      (server re-checks)
            └──────────────────────────────────────────────────────────────┘
                                         ▲
 core     headless engine: face/landmarks (ONNX), passive PAD, challenges, flash/stereo/scene/pupil cues,
          risk fusion with per-cue abstention, capture binding, policy bundles, transparency log, fraud analytics
 sbi      MOSIP SBI 1.0 client + Liveness Profile (key agreement, frame tags, illuminate, captureBurst)
 harness  corpus runner, APCER/BPCER with intervals, DET plots, synthetic attack factory, red-team fuzzers,
          conformal calibrator, evidence verifier / re-scorer
 desktop  registration scaffold: capture screen, operator login, supervisor approval, diagnostics
 android  the same engine as an AAR for deployment Mode D (inside the SBI app)
```

Every fusion number lives in `config/operating-points.json`, bound to the SHA-256 of each loaded model. If you
swap a model, the engine refuses to start until the table is regenerated.

## Results (what is measured, and what is not)

**There is no real attack corpus yet, so there are no measured APCER or BPCER figures.** Every threshold is
provisional. `docs/claim-sheet.md` lists every claim with the command that reproduces it, and the limits come
first.

What *is* measured and reproducible:

| | Number | Reproduce |
|---|---|---|
| Test suite | **665 JVM tests, 0 failures.** 2 skipped on the author's machine; **20 skipped on a fresh clone**, because the face models and the MockMDS fixtures are not in the repository and the tests that need them skip themselves. Full uncached build from a fresh clone: 8 min 36 s | `./gradlew build` |
| Flash-challenge unpredictability | 6 colours × 8 steps = 1,679,616 sequences = **20.7 bits** | `./gradlew :core:test --tests '*ChallengeGeneratorTest'` |
| Challenge uniformity | χ² over 100,000 draws, every parameter, p = 0.001 critical value | same |
| Photosensitivity | ≤ 3 colour changes/s, steps ≥ 333 ms, checked on 900 sequences | `./gradlew :core:test --tests '*FlashScheduleTest'` |
| Insider-fraud signal | 6 PAD failures in 40 sessions at a 2 % baseline gives **z ≈ 5.87** (flagged) | `./gradlew :core:test --tests '*FraudAnalyticsTest'` |
| Transparency log | Merkle heads and proofs match the Certificate Transparency test vectors | `./gradlew :core:test --tests '*MerkleLogTest'` |
| Stereo cue (synthetic) | real-face relief ≈ 25 mm vs flat print ≈ 0 mm (±4 mm) | `./gradlew :core:test --tests '*StereoDepthCueTest'` |

The repo also records its own red-team findings honestly. For example, R-03 in `docs/red-team-log.md` shows that
without a flash cue, attacker-controlled cues can sum to an accept on a synthetic screen replay.

## Status

Phases 0–11 are built: the liveness engine (`core`), the SBI client (`sbi`), the evaluation toolkit (`harness`)
and the registration scaffold (`desktop`). The Android AAR (Phase 12) builds but has **not yet run on a device**.
The Phase 13 differentiators (flash reflection, stereo depth, scene and illumination consistency, pupil light reflex,
the SBI Liveness Profile) are wired into the engine. Each one switches on only when the station has the hardware it
needs. They are tested with fakes and synthetic data, because **no station with the extra hardware, and no device
that speaks the profile, exists yet**. The CI workflow has not yet run on GitHub. The desktop screens have a designed
theme, a step rail, Hindi, larger-text and high-contrast modes; they are checked by offscreen renders and tests, and **no
operator or resident has used them yet**.

## Setup and run

Requirements: JDK 24 (CI uses Temurin 24). Gradle comes via the wrapper. `ffmpeg` is needed for some harness tests.

```bash
./gradlew build                      # everything, with tests
./gradlew :desktop:runScaffold       # the capture screen against MockMDS on 127.0.0.1:4501
```

`runScaffold` needs the face models in `models/` and a running MockMDS. Neither is in the repository: models are
listed with their licences in `docs/licences.md`, and MockMDS is fetched into `third_party/`. Tests that need them
skip themselves.

The Android engine (Mode D) is a separate build that needs the Android SDK (`android/local.properties` with
`sdk.dir`, or `ANDROID_HOME`):

```bash
./gradlew -p android :pramaan-android:check               # unit tests, lint, core's Android API check
./gradlew :core:publish && ./gradlew -p android :pramaan-android:publish   # AAR + core to build/repo
./gradlew -p android :pramaan-android:connectedDebugAndroidTest            # on a phone or emulator
```

No API keys or environment secrets are needed. Station options are command-line flags (`--flash-screen N`,
`--backdrop file`) and the signed policy bundle.

## Tech stack

Java 24 · Gradle (Kotlin DSL) · ONNX Runtime 1.20 (pinned, see `tools/versions.md`) · JavaFX 11 (matching the MOSIP
Registration Client) · Jackson · JUnit 5 · Android AAR · Python tooling for model export and fixtures · MOSIP SBI 1.0

## Screenshots / demo

These screens are **rendered offscreen from the real JavaFX code** (`StationViewRenderTest`), so they show the layout,
words and states exactly, but **no live camera or device** is behind them: the camera area shows its placeholder.

| | |
|---|---|
| ![Resident capture, a head-turn prompt](docs/media/capture-screen.png) | ![The same screen in Hindi](docs/media/hindi-accessibility.png) |
| Resident capture: step rail, one instruction, the scaffold labelled as a scaffold | Hindi (machine-drafted, not yet reviewed) |
| ![Operator sign-in](docs/media/operator-sign-in.png) | ![Fails closed](docs/media/fails-closed.png) |
| Operator sign-in | Fails closed: nothing can be registered, and it says what to check |
| ![Exception flow](docs/media/exception-flow.png) | ![Audited dialog](docs/media/audited-dialog.png) |
| Exception flow (supervisor approval, dual control) | An audited action asks for administrator and reason in one form |

More: `docs/media/hindi-large-text.png`, `docs/media/hindi-high-contrast.png`, `docs/media/supervisor-approval.png`,
`docs/media/resident-done.png`.

Still to capture (each needs hardware or a real run, so they are not faked):

- [ ] `docs/media/flash-challenge.gif`: the flash colour challenge playing on the resident's screen (`--flash-screen primary`)
- [ ] `docs/media/injection-veto.png`: OBS Virtual Camera selected and vetoed
- [ ] `docs/media/evaluation-report.png`: the harness HTML report (needs a corpus; synthetic rows would be labelled)
- [ ] A demo video: `docs/submission/07-demo-video-script.md` (3 min) or the live script `docs/demo-script.md`

## Submission (MOSIP Decode 2026, problem statement 04)

Everything for the submission is in [`docs/submission/`](docs/submission/00-START-HERE.md): the 1-2 page
[solution document](docs/submission/PRAMAAN-solution-2page.pdf), the 7-slide
[pitch](docs/submission/PRAMAAN-pitch-7slides.pdf), a judging map, 25 prepared questions, the checklist and a video
script. Start with `docs/submission/00-START-HERE.md`: it says what is confirmed, what to verify, and what only the
team can do.

## Documents

- `PRAMAAN_Final_Bible.md`: the design, with the reasoning, the math, the threat model and the research log (Parts 0–48).
- `PRAMAAN_Build_Map.md`: the build, as 100 tickets in strict order, each with Build / Watch out / Test / Done when.
- `docs/claim-sheet.md`: every claim, with its number, reproduction command and matching limitation.
- `docs/red-team-log.md`, `docs/sbi-liveness-profile.md`, `docs/adr/`: findings, the standard proposal, and decisions.

## Licence

MPL-2.0, matching MOSIP's own repositories.
