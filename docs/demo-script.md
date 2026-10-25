# PRAMAAN demo script (Build Map #97, Bible Part 28)

Nine scenes. Each one has the setup, the exact click path, the text that should appear on screen, what to say, and
the fallback if the hardware misbehaves. The script is written so it can be run without narrating from memory.

**Read this first.** The scaffold runs against **MockMDS**, which replays image files — it is not a live sensor
(`docs/hardware-testing-guide.md`, section 0). So each scene says which of three modes it runs in:

| Mode | What runs | Needs |
|---|---|---|
| **LIVE** | `./gradlew :desktop:runScaffold` against MockMDS (or a certified SBI device) | MockMDS up on 127.0.0.1:4501–4510, models in `models/` |
| **HARNESS** | A corpus clip through the production engine, then the report | A recorded clip (`docs/corpus-plan.md`) and the models |
| **HARDWARE** | A real camera, a second webcam, a resident-facing light, a phone | Equipment from `docs/hardware-testing-guide.md` |

A scene marked **NOT YET** has code behind it but nothing that can be shown on a real presentation until the
named prerequisite exists. Do not demo a scene that is marked NOT YET as if it worked; say what is missing.

## Before you start (once, then again 10 minutes before)

| Check | How | Pass looks like |
|---|---|---|
| Models present | `ls models/` | the six `.onnx` files named in `config/operating-points.json` |
| Build is green | `./gradlew build` | `BUILD SUCCESSFUL` |
| MockMDS running | the hardware guide, section 2.2 | an HTTP server on a port in 4501–4600 |
| Scaffold opens | `./gradlew :desktop:runScaffold` | a window titled "PRAMAAN — Registration Client scaffold", a step rail on the left, and a strip under the header reading "Scaffold, not the MOSIP Registration Client" |
| Operator onboarded | step 1 of scene 1, once, with **First time? Enrol my face** | the operator's face template is saved under `build/scaffold/operators`, and the banner says "Face enrolled. You can now sign in with your face." |
| Offline badge | header chip | "Offline — working normally" |

Keep a terminal open next to the window: scenes 3, 4, 7 and 9 end with a command.

---

## Scene 1 — Honest resident, good light  `LIVE`

**Claim shown:** an honest person is not annoyed.

1. Operator ID field: type the operator id, click **Sign in with face**. The step rail moves to "Resident photo" and the
   header shows "Operator <id>".
2. Click **Start face scan**. Look at the camera, centred in the oval.
3. Expected on-screen text, in order: `Connecting to the camera…` → `Centre your face in the oval` →
   `Checking face liveness…` → `Hold still` → `Good, face captured successfully`.
4. The "Captured photo" panel fills in and a green banner says the packet was written with signed liveness evidence.
   Open **Station tools ▸ Diagnostics…**, give an administrator id and a reason in the one form, to show the audited cue
   view (never with a resident in front of the screen).

**Say:** "Nothing was asked of the resident. Passive checks decided; the evidence is signed and stored."

**Fallback:** if the camera step stalls at `Connecting to the camera…`, the message `Camera not connected. Please
check the device.` is correct behaviour — restart MockMDS and rescan. Do not click through it.

## Scene 2 — Bad light is guidance, not rejection  `LIVE`

**Claim shown:** we do not punish poor conditions.

1. With the resident in strong backlight (a window behind them, or MockMDS fed the backlit sample), click **Start face scan**.
2. Expected: a guidance line such as `Please move so that the light is not behind you`, or `Too much light on your
   face. Please move away from the bright light`. The attempt counter does **not** advance on a quality problem.
3. Move; the scan continues to `Good, face captured successfully`.

**Say:** "Quality problems get guidance. They are never reported as a spoof."

**Fallback:** if the guidance does not appear, show the quality components in **Station tools ▸ Diagnostics…** and say which one
is below threshold.

## Scene 3 — A printed photo  `LIVE` or `HARNESS`

**Claim shown:** cues that fail differently — print is caught by colour-texture and flatness.

1. Hold a printed photo of the enrolled person to the camera (or feed the print clip from the corpus).
2. Click **Start face scan**.
3. Expected on screen: only the generic message, `We could not verify face liveness. Please try again.`
   No reason, no score.
4. Open **Station tools ▸ Diagnostics…** (operator-gated, audited; the form asks for administrator and reason): the
   cues that voted against are listed.
5. Harness version: `./gradlew :harness:runCorpus` over the print clip, then `:harness:evaluate`; show the PAIS row.

**Say:** "The resident learns nothing they could use to adapt. The operator can see which cues fired."

**Fallback:** if flatness abstains for lack of head motion, say so — abstention is correct. The colour-texture cue
**abstains `UNTRAINED`** until its weights are trained on the corpus (Bible 8.1), so without a corpus this scene is
carried by the passive model and flatness alone. Say that if it is the case.

## Scene 4 — A phone replaying a video of a teammate  `HARDWARE` or `HARNESS`

**Claim shown:** the scene that separates us from passive-only systems. Rehearse this one the most.

1. A teammate's recorded video of blinking, smiling and turning plays on a phone held to the camera.
2. Click **Start face scan**. The engine issues a parameterised challenge the video cannot contain (for example `Please
   turn your head to your left`, then `Please blink 2 times`).
3. Expected: the video does not perform the requested action in time → the generic message, `We could not verify
   face liveness. Please try again.`
4. **Station tools ▸ Diagnostics…** shows the challenge step failed on wrong action or latency, and the moiré cue's peakiness.
5. Harness version: the phone-replay clip through `:harness:runCorpus`; the PAIS row shows it rejected.

**Say:** "A recording has every action a person can do, but not the one we randomly picked, at the time we picked it."

**Fallback:** if the phone's glare defeats detection of the screen, say it, show the challenge failure, and move on.
The challenge failure is the claim; moiré is supporting.

## Scene 5 — Operator login with a supervisor's photo  `LIVE`

**Claim shown:** the strict tier protects the people who protect the system.

1. At step "Operator sign-in", hold the supervisor's photo to the camera and click **Sign in with face**.
2. Expected: the operator workflow runs at a stricter operating point than the resident's (Bible Part 9), so the
   attempt ends with `Face verification could not be completed. Please try again.` or `Your face was not recognised for
   this operator ID. Check the ID and try again.`
3. Open the audit log (`:harness:fraudReport` reads it) and show the failed attempt with the operator id.

**Say:** "Operators and supervisors get the strictest operating point, and the attempt is logged."

**Fallback / limit:** the **flash** challenge is part of the Bible's version of this scene. It is wired: start the
scaffold with `--flash-screen N` (the monitor facing the resident) and the engine draws and plays it, the cue votes,
and the Diagnostics window shows the `flash` cue. It is **tested with a fake light and synthetic faces; the real window
has not been rehearsed on a screen**, and without `--flash-screen` nothing is flashed. Say which of the two you are
showing; never claim a flash result you did not see.  **NOT YET (flash on real hardware).**

## Scene 6 — Virtual camera / injected video  `HARDWARE`

**Claim shown:** the attack the problem statement never names.

1. Install OBS Virtual Camera; select it as the capture source for MockMDS or a device service.
2. Click **Start face scan**.
3. Expected: the injection guard's hard veto — the generic message only; **Station tools ▸ Diagnostics…** and the audit
   log show `INJECTION_DETECTED`.
4. A normal webcam with an odd driver name is **not** vetoed; show one to prove it.

**Say:** "A virtual camera bound to the capture path is vetoed before fusion even runs."

**Fallback / limit:** the veto matches the *device's reported make/model/code* and, on Windows, the OS's camera
registrations (DirectShow and Plug and Play, read at start-up) against a built-in denylist, and a signed `digitalId`
that fails its certificate chain is a veto. It was tried on one real PC with OBS installed and nowhere else (claim L9):
a driver can pick any name, no registered device was tried, and there is no sensor-noise (PRNU) check. Say so.
**NOT YET (a renamed virtual camera; flash correlation ρ ≈ 0 on real hardware).**

## Scene 7 — Capture swap  `HARNESS`

**Claim shown:** the liveness result is bound to the signed capture.

1. Run `./gradlew :harness:test --tests '*BindingAttacksTest'`.
2. Show the outcome: a live preview passes, the device then returns a different signed face → `REJECT_BINDING`,
   audited, with the identity-continuity lock named.

**Say:** "Preview is unsigned; the capture is signed. Without binding, you can pass with one face and enrol another."

**Fallback:** open `harness/src/test/java/pramaan/harness/mock/BindingAttacksTest.java` and read the attack names
aloud; each is one row of the Bible's binding table.

## Scene 8 — Accessibility  `LIVE`

**Claim shown:** inclusion is executable.

1. Start the scaffold in Hindi: `./gradlew :desktop:runScaffold --args="--lang hi"` (add `--speech true` for audio), or
   click the language button in the header (it reads हिन्दी / English) at any step: what was typed and answered stays.
   Show **A+** (larger text) and the contrast button next to it.
2. On the resident screen tick **Cannot see the screen** under "Accessibility needs" before **Start face scan**.
3. Expected: prompts in Hindi with icons, **no gaze challenge**; the attempt completes for a real person.
4. **Station tools ▸ Diagnostics…** shows the accessibility profile recorded; the operator's use of profiles is counted
   as a fraud signal (Build Map #88), not hidden.

**Say:** "A resident who cannot see a dot on the screen still gets through, and over-use of the profile is itself visible."

**Fallback:** text-to-speech needs Windows `System.Speech`; without it the text prompts still appear. The Hindi
strings were drafted by machine and checked by the author — say so.

## Scene 9 — Close on the report  `HARNESS`

**Claim shown:** numbers with their uncertainty, and limits stated first.

1. `./gradlew :harness:evaluate --args="--rows build/results/rows.jsonl --table config/operating-points.json ..."`
   (full flags: `docs/hardware-testing-guide.md`, section 5), writing the HTML report.
2. Open the page and walk: APCER per PAIS **with confidence intervals**, BPCER per group, the per-cue ablation
   (holes do not overlap), the DET plot, the corpus summary.
3. End on `docs/claim-sheet.md`: every claim, its number, its interval and its limitation.

**Say:** "Zero failures in 60 attacks still means the true rate could be about 5%. We report the interval, not the zero."

**Fallback:** if there is no real corpus yet, **say so at the start of the scene** and show a synthetic-run report
labelled SYNTHETIC (`harness/factory`). Never show synthetic rows as headline numbers.

---

## Rehearsal log (Build Map #97 requires at least three full runs)

| Run | Date | Who ran it | Scenes that failed | Fixed / disclosed |
|---|---|---|---|---|
| 1 | | | | |
| 2 | | | | |
| 3 | | | | |

Scene 4 gets its own log: it is the one to rehearse most.

| Scene 4 run | Phone/video used | Challenge issued | Result | Notes |
|---|---|---|---|---|
| | | | | |
