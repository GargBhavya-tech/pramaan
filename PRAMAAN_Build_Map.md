# PRAMAAN — Build Map (solo, AI-assisted)

**Companion to `PRAMAAN_Final_Bible.md`.** The Bible explains WHY, with the math and the evidence. This file says WHAT to build, in what order, what generated code will get wrong, and how to prove each piece works.

**Target:** MOSIP Decode 2026, problem statement 04 — face liveness and Presentation Attack Detection for the MOSIP Registration Client.

**Build model:** solo, AI-assisted. You specify and verify; a model writes most of the code. That inversion shapes every ticket — the Test line is the only thing between confidently-wrong generated code and a demo that lies.

---

## How To Read This Map

Tickets are in strict build order. `Blocked by` only ever points to a lower number, so working top to bottom never leaves you stuck.

| Tag | Meaning |
|---|---|
| 🟥 **CORE** | The spine, or a headline claim. If you build nothing else, build these. |
| 🟨 **SUPPORTING** | Real and demoed, but one panel or one number. Build after the spine works end to end. |
| 🟦 **STRETCH** | Only if you are ahead. Cutting these costs a metric, never a capability. |
| ⬜ **DETACHABLE** | Runs in parallel, blocks nothing, can fail without hurting the demo. |

Each ticket has:

- **Build** — precise enough to paste as a prompt: formulas, signatures, invariants. Vague spec → confidently vague code.
- **Watch out** — the specific way *this* piece fails, taken from the Bible's edge-case tables. Not general advice.
- **Test** — concrete assertions with expected numbers where known.
- **Done when** — the pass condition.

**Golden rule:** do not start a ticket until its blockers pass their own Test. "Mostly works" silently poisons everything downstream, and with generated code you will not notice until the demo.

---

## Five Facts That Shape Every Ticket

These come from the research in Bible Part 47. Getting any of them wrong wastes days.

| # | Fact | Consequence for the build |
|---|---|---|
| 1 | **Desktop** gets an unsigned M-JPEG stream from the device service; **Android** gets no stream at all (the SBI app runs in the foreground and returns only the signed capture) | Desktop = engine in the client (Mode C). Android = engine inside an SBI app (Mode D). Two integration tracks, one engine — Phases 11 and 12 |
| 2 | The stream guarantees only **3 fps** | Every cue is gated on measured fps (#14). Blink becomes "close your eyes for a second". Saccade/pursuit and rPPG are off by default |
| 3 | `transactionId` allows **only letters, digits and hyphens**, 4–50 chars, and is echoed inside the device-signed JWT | The session nonce is 32 hex characters and doubles as the replay lock (#18, #57) |
| 4 | SBI already chains captures with `previousHash` (first = SHA-256 of the empty string) | Binding links our frame chain to the SBI chain (#58) rather than inventing a parallel mechanism |
| 5 | Liveness evidence belongs in the face BIR **`others`** map as a stringified signed JSON | Evidence work (#63–#65) never touches `bdb`, so ABIS matchers are unaffected |

---

## Time Budget and Cut Lines

Phases 0–10 are the product. Phases 11–15 are how it gets believed.

| Block | Phases | Share of time | If you run out of time |
|---|---|---|---|
| Spine | 0–8 | ~45% | Never cut. Without binding (Phase 8) the rest is theatre |
| Evidence + harness | 9–10 | ~20% | Keep the harness; the numbers *are* the pitch. Evidence can shrink to "signed JSON, verified by a CLI" |
| One client integration | 11 **or** 12 | ~15% | Do desktop first; Android becomes a documented design + an AAR that builds |
| Differentiators | 13 | ~10% | Ship **two**: injection guard (#90) and flash (#89) are the highest payoff |
| UX, proof, demo | 14–15 | ~10% | Never cut #100–#104. A working system nobody can see scores zero |

---

## Phase 0 — Foundations & Decisions

### #1: Repo and module skeleton
Priority: 🟥 CORE · Type: Prototype · Blocked by: —

**Build:** Gradle multi-module Java project: `core/` (engine, no UI, no device code), `sbi/` (device client), `desktop/` (JavaFX integration), `android/` (later), `harness/` (evaluation), `tools/` (Python: model conversion, corpus prep, report generation). Java 11+ to match the Registration Client. MPL-2.0 headers.
**Watch out:** the engine must not depend on JavaFX, Android, or HTTP. If `core` can't be unit-tested headlessly, Phase 12 will be a rewrite.
**Test:** `./gradlew build` passes with an empty test in each module; `core` has zero non-test dependencies outside ONNX Runtime, OpenCV and a JSON library.
**Done when:** the module graph compiles and `core` is importable from both `desktop` and `harness`.

### #2: ONNX Runtime smoke test (Java)
Priority: 🟥 CORE · Type: Prototype · Blocked by: #1

**Build:** Load any small ONNX model in `core` via ONNX Runtime Java, run one inference on a synthetic tensor, print output shape and latency.
**Watch out:** native library loading differs per OS; pin the exact `onnxruntime` artifact version now and record it in `tools/versions.md`.
**Test:** inference runs on the target demo laptop and prints a latency number.
**Done when:** you know, in writing, the ORT version and the measured cold-start and warm latency.

### #3: Mock SBI running
Priority: 🟥 CORE · Type: Research · Blocked by: #1

**Build:** Clone and run MOSIP's `mosip-mock-services` MockMDS. Hit device discovery, device info, stream and rcapture by hand (curl/Postman) for a Face device.
**Watch out:** SBI listens on 127.0.0.1 in the 4501–4600 port range and discovery sweeps that range — don't hardcode one port.
**Test:** you have saved, on disk, a real device-info response and a real rcapture response from the mock.
**Done when:** `sbi/testdata/` contains those captured payloads, used as fixtures from here on.

### #4: Decode the SBI contract from the captured payloads
Priority: 🟥 CORE · Type: Research · Blocked by: #3

**Build:** Parse the saved rcapture response: split the JWS, base64url-decode the payload, and confirm by inspection that `transactionId`, `timestamp`, `digitalId`, `qualityScore` and `bioValue` are inside the signed data, and that `hash` sits beside it. Write the findings into `docs/sbi-notes.md` with quotes.
**Watch out:** the chained `hash` is *not* inside the signature — the verifier recomputes it from the signed `bioValue` and the previous hash. Do not "fix" that by inventing a signature over it.
**Test:** a unit test asserts the nonce you sent appears in the decoded payload.
**Done when:** the replay lock (#57) has a proven basis rather than an assumed one.

### #5: Deployment-mode decision record
Priority: 🟥 CORE · Type: Discuss · Blocked by: #4

**Build:** `docs/adr/0001-deployment-modes.md` — desktop Mode C, Android Mode D, one engine, what each mode sees, and the consequences for the demo.
**Watch out:** this is the decision most likely to be challenged by a judge or mentor. Write it with the spec quotes from #4 attached.
**Done when:** the ADR exists and every later ticket refers to it rather than re-arguing.

### #6: Corpus plan and consent
Priority: 🟥 CORE · Type: Discuss · Blocked by: —

**Build:** Written plan: who is recorded (team + volunteers, signed consent), attack instruments to build (matte print, glossy print, cut-out-eyes print, curved print, phone photo, phone video, tablet video, laptop video, paper mask), lighting conditions (good / dim / backlit / fluorescent) and storage rules (encrypted, never committed, deleted after the event).
**Watch out:** this is the riskiest dependency in the plan — every number in Phase 10 rests on it. A corpus recorded in one lighting condition produces a system that only works in that condition.
**Test:** the consent form is signed and the instrument shopping list has prices.
**Done when:** the plan exists and a recording date is on the calendar (before Phase 10, not during).

### #7: Licence and dependency policy
Priority: 🟥 CORE · Type: Discuss · Blocked by: #1

**Build:** `docs/licences.md` — project MPL-2.0; per-dependency licence; and a hard rule: **a model may ship only if its weights' licence and its training data's terms both allow it** (Bible Part 7: MiniFASNet Apache-2.0 is in; SAFAS, PSCF and FLIP weights are not).
**Watch out:** "the code is MIT" is not permission to ship weights trained on academic-only datasets.
**Test:** every third-party file under `models/` has a row in the table with a licence link.
**Done when:** the table exists and nothing is in `models/` that isn't in it.

### #8: Test harness skeleton and fake clock
Priority: 🟥 CORE · Type: Prototype · Blocked by: #1

**Build:** JUnit 5 in every module, plus a `TestClock implements Clock` with `advance(Duration)`. Every time-dependent API in `core` takes a timestamp or a `Clock` — no `System.currentTimeMillis()` inside logic.
**Watch out:** one hidden clock call makes the session-machine tests (#51) flaky and slow forever.
**Test:** a test advances the fake clock by 30 s in microseconds of real time.
**Done when:** a lint/grep check fails the build if `System.currentTimeMillis` or `Instant.now` appears in `core/src/main`.

### #9: Policy model and loader
Priority: 🟥 CORE · Type: Prototype · Blocked by: #1

**Build:** `LivenessPolicy` types for the YAML in Bible Part 17.1 (profiles, operating-point name, challenge pool, minSteps/maxSteps, timeouts, retries, accessibility profiles, exception limits). Loader reads YAML into immutable records. Bundle signature verification is future work; leave a `SignedBundleLoader` seam now so it can be added without touching callers.
**Watch out:** no thresholds in code. A hardcoded `0.5` anywhere in `core` is a bug — Part 9 stores *operating points*, not raw numbers.
**Test:** loading the sample YAML yields the expected resident/operator/supervisor profiles; an unknown field fails loudly rather than silently defaulting.
**Done when:** `core` reads all its knobs from a policy object.

### #10: Audit log skeleton
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #8

**Build:** Append-only JSONL audit log with a per-entry hash chain (`H_i = SHA256(H_{i-1} || entry)`), event types for every decision and error, and the rule that PAD detail goes here and never to the UI.
**Watch out:** no biometric data, no images, no names in the log — ids and scores only.
**Test:** altering one line and re-verifying the chain fails.
**Done when:** every later ticket can emit audit events without inventing a format.

---

## Phase 1 — Frame Source and Mock Device

### #11: Frame and capability types, `FaceFrameSource`
Priority: 🟥 CORE · Type: Prototype · Blocked by: #8

**Build:** `Frame` (pixels, format, width, height, sensorTimestampNs, hostArrivalNs, frameIndex, deviceMeta), `DeviceCapabilities` (level L0/L1, resolution, fps, NIR, illuminator control, focus control, second camera, resident display, signed stream, capture burst), `DeviceEvent`, and the `FaceFrameSource` interface from Bible Part 4.1 — including `capture(CaptureRequest)`.
**Watch out:** keep `capture()` on the same interface as the stream. Splitting them is exactly how the capture-swap hole (Part 13) gets built in.
**Test:** a no-op implementation compiles, and a test asserts the interface has no HTTP or UI types in its signatures.
**Done when:** both the real device client (#12) and the mock (#15) can implement it.

### #12: SBI device client — discovery, info, rcapture
Priority: 🟥 CORE · Type: Prototype · Blocked by: #11, #4

**Build:** `SbiFrameSource`: port-range discovery (4501–4600), device-info parsing (digitalId, certification level), and `rcapture` with `transactionId`, `env`, `purpose`, `specVersion`, `timeout`, `bio[]` incl. `previousHash`. Verify the response JWS signature against the device certificate.
**Watch out:** `transactionId` charset and length (4–50, letters/digits/hyphen). A base64url nonce is rejected by the device — use the 32-hex nonce from #18.
**Test:** against MockMDS: discovery finds the face device; rcapture returns a payload whose signature verifies and whose `transactionId` equals the one sent.
**Done when:** the client round-trips with the mock without hand-editing anything.

### #13: M-JPEG stream client
Priority: 🟥 CORE · Type: Prototype · Blocked by: #12

**Build:** HTTP chunked multipart reader yielding decoded `Frame`s: parse boundaries, decode JPEG, stamp `hostArrivalNs`, and drop the oldest frame when the consumer is behind (bounded queue, never unbounded buffering).
**Watch out:** generated code loves `readAllBytes()` on a stream that never ends. This is an infinite stream; it must be incremental and survive a truncated final chunk.
**Test:** run against MockMDS for 60 s: frames decode, memory is flat, and killing the device service mid-stream produces a `DISCONNECTED` event rather than a hang.
**Done when:** a 5-minute stream runs with stable memory and a clean shutdown.

### #14: Measured frame rate and capability gating
Priority: 🟥 CORE · Type: Prototype · Blocked by: #13

**Build:** Measure actual fps over the first second of a session; expose `SessionCapabilities` that gate cues exactly per Bible Part 1.2's table (blink needs ~10 fps; flash needs ~2 frames per colour step; latency checks need ~10 fps; rPPG and eye-movement cues off below 10/30 fps).
**Watch out:** the spec floor is 3 fps. Anything assuming 30 fps works on your webcam and fails on a certified device.
**Test:** feed the mock at 3, 10 and 30 fps; assert the gate table flips the expected cues off and on, and that the blink challenge is replaced by eyes-closed-hold at 3 fps.
**Done when:** running at 3 fps degrades the challenge set instead of failing people.

### #15: MockFrameSource (corpus replay)
Priority: 🟥 CORE · Type: Prototype · Blocked by: #11

**Build:** `MockFrameSource implements FaceFrameSource`, replaying a labelled clip at a configurable fps, and signing rcapture responses with a test key so binding (Phase 8) can be exercised offline.
**Watch out:** it must drive the *same* code path as the real source. A test-only shortcut here invalidates every number in Phase 10.
**Test:** the same engine session code runs unmodified against `SbiFrameSource` and `MockFrameSource`.
**Done when:** the harness can run a whole session from a file.

### #16: Attack injection in the mock
Priority: 🟥 CORE · Type: Prototype · Blocked by: #15

**Build:** Corpus manifest (`clip path`, `label`, `PAIS`, `lighting`, `device class`) and a mock mode that plays attack clips, including a "wrong answer" mode that responds to a challenge with the wrong action, or the right action at the wrong time.
**Watch out:** label the PAI *species* precisely (matte print vs glossy print vs curved print) — Part 21 reports APCER per species, and a lumped "print" label destroys that.
**Test:** the harness can request "play a glossy-print attack at 3 fps in dim light" and get exactly that.
**Done when:** the corpus manifest drives playback.

### #17: Fault injection in the mock
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #15

**Build:** Mock modes for: disconnect mid-session, corrupt JPEG, wrong pixel format, latency spike, device error response, empty stream.
**Test:** each fault produces the mapped error state from Bible Part 22 and never an unhandled exception.
**Done when:** every row of Part 22's error table has a mock trigger.

### #18: Session context and nonce
Priority: 🟥 CORE · Type: Prototype · Blocked by: #12

**Build:** `SessionContext` (sessionId, 128-bit nonce as **32 lowercase hex chars** from `SecureRandom`, workflow RESIDENT/OPERATOR/SUPERVISOR, policy, capabilities, clock). The nonce flows into every rcapture as `transactionId`.
**Watch out:** one nonce per session, never reused, never derived from the clock.
**Test:** 10,000 generated nonces are unique, match `^[0-9a-f]{32}$`, and are accepted by MockMDS.
**Done when:** the binding lock has its identifier.

---

## Phase 2 — Face Gate, Tracking and Quality

### #19: Face detection and the single-face rule
Priority: 🟥 CORE · Type: Prototype · Blocked by: #14

**Build:** YuNet (OpenCV Zoo, ONNX) detector in `core`; confidence ≥ 0.8; boxes + 5 landmarks. Emit `FACE_NOT_DETECTED` / `MULTIPLE_FACES` with a 0.5 s debounce, ignoring faces whose inter-ocular distance is < 40% of the primary face's.
**Watch out:** an operator standing behind the resident trips `MULTIPLE_FACES` forever without the size filter and the debounce.
**Test:** clip with a passer-by crossing for 200 ms → no error; standing for 800 ms → error.
**Done when:** a normal two-person registration-desk scene produces no spurious errors.

### #20: Face tracking and continuity
Priority: 🟥 CORE · Type: Prototype · Blocked by: #19

**Build:** IoU tracker assigning a stable `trackId`; expose "track lost for > 300 ms" as an event that resets session evidence.
**Watch out:** the reset must also clear the hash chain (#56) — a session that survives a face swap is the bug Phase 8 exists to prevent.
**Test:** a clip where the face leaves frame for 500 ms and a *different* face returns produces a new trackId and a reset event.
**Done when:** continuity is observable and testable.
**Built (revised in Phase 11):** the gap is max(300 ms, 1.5 measured frame intervals) — `FaceTracker.gapFor`, used by `FramePipeline` and `SessionEventRouter`. The fixed 300 ms lost the track on every frame of a 3 fps stream.

### #21: Quality metrics
Priority: 🟥 CORE · Type: Prototype · Blocked by: #19

**Build:** Per-frame quality components from Bible Part 6: IOD (≥ 60 px liveness, ≥ 90 px capture), |yaw| and |pitch| ≤ 15°, Laplacian-variance sharpness, mean luminance in [60, 200], left/right uniformity ≤ 0.35, landmark-visibility occlusion. Combine as **Q = min(components)**.
**Watch out:** minimum, not mean. A mean lets a backlit face through on the strength of four good components.
**Test:** synthetic crops (dark, bright, blurred, rotated, half-shadowed) each drive the intended component to the floor and Q with it.
**Done when:** Q tracks what you can see, and each component is individually inspectable.

### #22: Guidance mapping
Priority: 🟥 CORE · Type: Prototype · Blocked by: #21

**Build:** Map each failing quality component to an actionable message id (lighting, distance, pose, glare, framing). Quality failures never produce a PAD verdict and never consume a retry.
**Watch out:** this is the inclusion fix (Part 6): without it, poor lighting is reported as an attack and BPCER inflates.
**Test:** a backlit bona fide clip yields the lighting guidance and **zero** PAD scores recorded.
**Done when:** the UI can be driven entirely from message ids.

### #23: Landmark provider (the desktop-Java spike)
Priority: 🟥 CORE · Type: Research · Blocked by: #21

**Build:** `LandmarkProvider` interface (478 points + blendshapes where available). Android uses MediaPipe Tasks; for desktop evaluate in this order: (a) convert MediaPipe's face-landmark and blendshape `.tflite` to ONNX with `tf2onnx` and run in ORT Java; (b) an alternative ONNX landmark model with a usable licence; (c) MediaPipe C++ via JNI.
**Watch out:** MediaPipe Tasks has **no desktop Java API** (Bible Part 20.1). Discovering this in Phase 6 costs days. Also check the converted model's licence against #7.
**Test:** the same face image through the desktop and Android providers gives landmark positions within a few pixels and identical blink/smile decisions.
**Done when:** one interface, two implementations, and a recorded decision in `docs/adr/0002-landmarks.md`.
**Built:** `OnnxFaceMeshLandmarker` (478 points) plus `OnnxBlendshapeLandmarker`, a decorator adding MediaPipe's 52
blendshape scores. `Blendshapes.SUBSET` (146 landmark indices) and `Blendshapes.NAMES` (52 output names) are copied
verbatim from MediaPipe's `face_blendshapes_graph.cc`; the model's own `[1,146,2]`/`[52]` signature confirms the shapes.
`StandardEngines` wires the decorator only when the operating-point table binds the blendshapes model, so a platform
without it still runs (ADR 0002 is updated with the wiring and the real-model test's numbers).

---

## Phase 3 — Passive Liveness

### #24: Passive scorer (MiniFASNet, ONNX)
Priority: 🟥 CORE · Type: Prototype · Blocked by: #23, #2

**Build:** Export MiniFASNet to ONNX in `tools/`; in `core`, crop at the model's two scales around the face box, normalise exactly as the original preprocessing does, and score every Nth frame.
**Watch out:** preprocessing mismatch (crop scale, BGR/RGB, mean/std) is the most common cause of a model that "runs" but outputs noise. Port the preprocessing from the reference implementation; don't guess it.
**Test:** on 20 bona fide and 20 print/replay crops the score distributions separate visibly (report both means).
**Done when:** the scorer gives stable, separable scores on your own recordings.

### #25: Temporal aggregation
Priority: 🟥 CORE · Type: Prototype · Blocked by: #24

**Build:** Trimmed mean (drop top and bottom 10%) of **logits** over a 1.5 s window of quality-passing frames; convert back with the sigmoid.
**Watch out:** average logits, not probabilities — one p = 0.999 frame otherwise dominates the window.
**Test:** a window containing one adversarial outlier moves the aggregate less than the same outlier moves a plain mean of probabilities.
**Done when:** single-frame noise no longer flips the session score.

### #26: Calibration
Priority: 🟥 CORE · Type: Prototype · Blocked by: #25

**Build:** Platt scaling fitted in `tools/` on harness data; ship `calibration.json` per model version and device class; `core` applies it to raw outputs.
**Watch out:** raw CNN outputs are not probabilities. Fusion (#37) is meaningless without this step.
**Test:** after calibration, predicted probabilities sit close to observed frequencies across 10 score buckets (a reliability check).
**Done when:** `p_passive` is a probability you can defend.

### #27: Golden-set regression test
Priority: 🟥 CORE · Type: Prototype · Blocked by: #26

**Build:** 50 bona fide + 50 attack crops committed as a fixture (consenting team members only), with expected score ranges per crop.
**Watch out:** this is the early-warning system for a model or preprocessing change that silently breaks scoring.
**Test:** the build fails if any crop moves outside its band.
**Done when:** model swaps are safe to attempt.

### #28: Frame pipeline and performance budget
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #24

**Build:** A scheduler running detection every frame, the passive CNN every Nth frame, and classical cues on the cheapest cadence that works; instrument per-stage latency and expose it in diagnostic mode.
**Watch out:** at 3 fps you cannot "skip frames" the way a 30 fps design does — the cadence must be fps-aware (#14).
**Test:** a 60 s session logs per-stage p50/p95 latency and total CPU on the demo laptop.
**Done when:** you have measured numbers, not budgeted ones, for Bible Part 20.4.

---

## Phase 4 — The PAD Cue Bank

### #29: Cue interface and abstention
Priority: 🟥 CORE · Type: Prototype · Blocked by: #26

**Build:** `PadCue { Set<Capability> requires(); CueResult evaluate(SessionWindow w); }` where `CueResult` is a calibrated probability **or** `ABSTAIN` with a reason. A registry builds the active cue list from policy + capabilities + measured fps.
**Watch out:** `ABSTAIN` must never be coerced to 0.5 or to "spoof". A missing capability is not evidence (Bible Part 8).
**Test:** with every capability off, all optional cues abstain and the session still reaches a decision on passive alone (plus a forced challenge from `minCues`).
**Done when:** adding a cue is a one-file change plus a policy entry.
**Built:** `LivenessEngine.CUES` is the `CueRegistry.Spec` list (passive, moire, colorTexture, flatness,
deformation, flash — needing RESIDENT_DISPLAY, rppg — needing UNCOMPRESSED_FRAMES), each with its required
capabilities. `LivenessEngine.IMPLEMENTED` names which of those actually run today; a cue the registry says is
runnable but that is not yet implemented abstains `NOT_APPLICABLE` rather than being silently dropped, and that
abstention is merged into the fused cue results, so the diagnostics recorder (#34) sees it like any other cue.

### #30: Colour-texture cue
Priority: 🟥 CORE · Type: Prototype · Blocked by: #29

**Build:** LBP histograms over YCbCr and HSV on the face crop, concatenated, classified by a small logistic model trained in `tools/`; calibrated per #26.
**Watch out:** train on *degraded* crops too (the gauntlet in #66) or it learns "sharp = live" and collapses on a 720p webcam. If you reach this ticket before #66 exists, degrade a handful of clips by hand rather than skipping it.
**Test:** on the golden set, print and screen crops score below bona fide crops with no overlap in the middle two deciles.
**Done when:** a classical cue exists that fails differently from the CNN.

### #31: Moiré cue
Priority: 🟥 CORE · Type: Prototype · Blocked by: #29

**Build:** 2-D FFT of a 128×128 greyscale window cut **at native resolution** from the face; peakiness = max/median magnitude in the annulus r₁ ≤ |f| ≤ r₂, **excluding axis bins and the JPEG block lattice** (multiples of N/8 on both axes), with a periodic Hann window.
**Watch out:** JPEG blocks put peaks on the axes *and* on the lattice where block edges cross; resizing the crop moves that lattice somewhere unknown. Excluding only the axes let a synthetic block grid score 4,754 (Bible 47.7).
**Test:** bona fide crops give peakiness ≈ 3–6; phone-screen recaptures ≈ 15–40 (record your measured numbers — they are device-specific).
**Done when:** the threshold comes from your own corpus, not from the Bible's example numbers.

### #32: Flatness cue (planar homography)
Priority: 🟥 CORE · Type: Prototype · Blocked by: #29, #23

**Build:** Take landmarks from a frontal frame and from a frame with ≥ 10° yaw change; fit H by normalised least squares (DLT), **not RANSAC** — RANSAC would fit the planar cheeks and hide the parallax being measured; residual `e = mean(||p1 − π(H·p0)|| / IOD)`.
**Watch out:** normalise by IOD or the threshold drifts with distance. Also require that the yaw change actually happened — with no motion the residual is meaningless and the cue must abstain.
**Test:** synthetic planar landmark sets rotated 0–30° give e < 0.012; a projected 3-D face model gives e > 0.03; a real print clip and a real face clip land on the right sides.
**Done when:** a printed photo is detected geometrically, without a model.

### #33: Non-rigid deformation cue
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #32

**Build:** Fit a similarity transform to rigid landmarks (eye corners, nose bridge); measure the residual of mouth and eyelid landmarks during a smile or eyes-closed action.
**Test:** a real smile produces a residual several times larger than a photo tilted to mimic one.
**Done when:** rigid attacks (masks, photos) are separable during challenges.

### #34: Cue diagnostics recorder
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #29

**Build:** Per-session record of every cue's raw value, calibrated probability, abstain reason and timing — written to the audit log and the harness output.
**Watch out:** this data never reaches the resident-facing UI (Bible Part 19).
**Done when:** you can explain any decision after the fact without re-running it.
**Built:** `LivenessSession.auditDecision(phase, cues, decision)`: a `CueDiagnostics` per cue (raw value, calibrated
probability, abstain reason, cue name) as its own audit rows, plus one `FUSION_DECISION` row carrying the zone, tier,
reasons and fusion numbers — tagged with `phase` ("passive" or "challenge") so a decision after a challenge is not
confused with the one before it. `LivenessEngine` calls it from `decidePassive` and `decideAfterChallenge`; none of
it reaches `CaptureScreenModel` (Part 19). `AccessibilityAndAttemptTest` covers the call directly.

---

## Phase 5 — Fusion, Zones and Policy

### #35: Log-odds fusion with asymmetric weights
Priority: 🟥 CORE · Type: Prototype · Blocked by: #34

**Build:** `L = b + Σ wᵢ·logit(pᵢ)` over available cues, with **two weights per cue** (`w⁺` for positive evidence, `w⁻` for negative) and `w⁻ = 0` for physiological cues in the resident profile (Bible Part 9).
**Watch out:** an abstaining cue contributes nothing — it must not enter the sum with any weight.
**Test:** table-driven: the same cue values under resident vs operator profiles produce the expected different decisions; adding an abstaining cue changes nothing.
**Done when:** absence of a reflex can never reject a resident.

### #36: Three-zone decision and tiers
Priority: 🟥 CORE · Type: Prototype · Blocked by: #35

**Build:** `P ≥ τ_accept → ACCEPT-candidate`, `P ≤ τ_reject → REJECT_PAD`, else `CHALLENGE`; map to WHITE / BLUE / RED tiers.
**Watch out:** a clear attack must be rejected, not challenged — challenges are free practice for an attacker.
**Test:** hand-built cue sets land in each zone; a strong-moiré set goes straight to RED without a challenge.
**Done when:** the hybrid flow has a reject zone as well as a challenge zone.

### #37: Operating-point table
Priority: 🟥 CORE · Type: Prototype · Blocked by: #36

**Build:** `operating-points.json`: model hash + device class → {τ_accept, τ_reject} per profile, generated by the harness (#69). Until #69 exists, hand-write the table from your own small corpus and mark it provisional. The loader refuses to run if the table's model hash doesn't match the loaded model.
**Watch out:** this is what stops thresholds silently drifting when a model changes.
**Test:** swapping the model without regenerating the table fails closed with a clear error.
**Done when:** no threshold exists anywhere except in this table.

### #38: Disagreement rule and minimum cue count
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #37

**Build:** If contributing cues point opposite ways (some live, some attack) and their (max − min) logit gap exceeds the table's gap (4) → an accept becomes BLUE; a reject stays RED. If fewer than `minCues` contributed → a would-be accept or reject becomes a challenge.
**Watch out:** measure the gap only between opposite-sign cues, or a weakly positive reflex widens it and forces residents into challenges (Bible 47.7).
**Test:** passive 0.95 with moiré 0.05 forces a challenge instead of accepting.
**Done when:** conflicting evidence never silently averages out.

### #39: Random audit challenge and timing jitter
Priority: 🟥 CORE · Type: Prototype · Blocked by: #36

**Build:** With probability q from policy, challenge even in WHITE; delay every displayed verdict by a random 200–600 ms.
**Watch out:** this is the anti-oracle measure — without it, "did I get a challenge?" tells an attacker their score.
**Test:** over 10,000 simulated WHITE sessions the audit rate is within q ± 1%; accept and reject latency distributions overlap.
**Done when:** step-up no longer leaks the passive score.

### #40: Device-level risk prior
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #35

**Build:** Bias term `b` lower for L0 than L1, from the operating-point table (#37) — it is a score parameter, and the policy file holds none.
**Test:** identical cue values on L0 and L1 devices produce the expected different zones at the boundary.
**Done when:** device assurance affects the decision, as Part 9 specifies.

---

## Phase 6 — Challenges

### #41: Challenge model, pool and generator
Priority: 🟥 CORE · Type: Prototype · Blocked by: #39, #14

**Build:** `ChallengeType` plugins with parameters (turn: direction × target angle; eyes-closed: hold duration; smile; gaze point), `SecureRandom` draw, per-session entropy accounting, and a seed commitment (SHA-256 of the seed) for the evidence package.
**Watch out:** `java.util.Random` or `Math.random` anywhere here is a security bug — add a build check.
**Test:** 100,000 draws pass a chi-square uniformity test per parameter; a 3-step sequence carries ≥ 13 bits **counting only verifiable outcomes** (in practice only with the flash — otherwise the plan must be flagged low-entropy); the committed seed reproduces the sequence exactly.
**Watch out (found while building):** a turn past the target passes every smaller target, so angles, blink counts and hold lengths add no entropy against a clip library (Bible 47.8). Do not scale step count with P(live) either — it leaks the score.
**Done when:** the sequence cannot be pre-recorded.

### #42: Accessibility profile filter
Priority: 🟥 CORE · Type: Prototype · Blocked by: #41

**Build:** Profiles `INFANT`, `NO_VISION`, `FACIAL_MOTOR`, `LIMITED_MOTION`, `FACE_COVERING_CONTEXT` filter the pool and relax thresholds (hold times, angles, timeouts) per Bible Part 16.
**Watch out:** when the filter empties the pool, fall through to no-action cues and the multimodal path — never to "reject".
**Test:** for each profile the generator never emits a forbidden type, and a session still completes on the remaining evidence.
**Done when:** every profile has a path to success.

### #43: Eyes-closed / blink detector (adaptive EAR)
Priority: 🟥 CORE · Type: Prototype · Blocked by: #41, #23

**Build:** EAR = (‖p2−p6‖ + ‖p3−p5‖) / (2‖p1−p4‖); per-person threshold θ = 0.7 × median EAR over the passive window; blink = EAR < θ for 2–12 frames then recovery; at low fps use the eyes-closed-hold variant (≥ 1 s).
**Watch out:** a fixed θ = 0.2 reports people with narrow eyes or ptosis as permanently blinking. The adaptive baseline is the inclusion fix.
**Test:** scripted EAR sequences (baseline 0.22 → θ = 0.154) detect exactly one blink; a person with baseline 0.18 is not read as always-closed.
**Done when:** blink works across eye shapes in your own recordings.

### #44: Smile detector
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #43

**Build:** Blendshape `mouthSmileLeft/Right` ≥ 0.5, or mouth-width/IOD up ≥ 12% over baseline **and** a non-rigid residual (#33) above threshold; both sides must move.
**Test:** real smiles pass; a photo tilted to fake one fails on the non-rigid term.
**Done when:** smile is verified by deformation, not by width alone.
**Built:** `SmileDetector.Reading.smiling()` passes on width/IOD gain ≥ 12% **or** the blendshape vote
(`mouthSmileLeft/Right` ≥ 0.5, each risen ≥ 0.3 over the baseline) — but the deformation tests (residual ≥ threshold,
both corners moved) always apply on top, so a photo the blendshape model scores as smiling still fails: it has no
non-rigid residual. `ActionDetectorsTest.theBlendshapeVoteStandsInForMouthWidthButNeverForDeformation` covers both
directions.

### #45: Head-turn detector and flatness trigger
Priority: 🟥 CORE · Type: Prototype · Blocked by: #43, #32

**Build:** Yaw **relative to the resident's own frontal 3-D landmarks** (Horn alignment over 468 MediaPipe points); canonical `solvePnP` only for absolute guidance — it is biased by face proportions (Bible 11.3). Pass when yaw reaches 75% of the commanded angle, holds ≥ 300 ms (150 ms for `LIMITED_MOTION`), then returns toward centre. Feed those frames to the flatness cue automatically.
**Watch out:** solvePnP sign and axis conventions are a classic generated-code failure. Verify against rendered faces at known angles.
**Test:** rendered faces at ±10/20/30° recover yaw within ±2° (canonical, noise-free within 0.5°); relative yaw stays within ±2° across face widths ×0.85–1.15 and depths ×0.7–1.3; a turn challenge produces a flatness verdict as a side effect.
**Done when:** the challenge that proves liveness also unlocks a cue.

### #46: Latency and wrong-action rules
Priority: 🟥 CORE · Type: Prototype · Blocked by: #45

**Build:** Onset latency Δt = action onset − prompt shown; accept [250 ms, timeout]; onset < 150 ms fails that step (physiologically implausible) unless fps is too low to measure; a wrong action fails immediately.
**Watch out:** disable the early-onset rule below ~10 fps (#14) — at 3 fps you cannot resolve 150 ms.
**Test:** synthetic onsets at 100 ms fail and 600 ms pass; at 3 fps the early rule is inert and says so in the log.
**Done when:** a replayed clip that happens to contain the right action still fails on timing.

### #47: Challenge prompt contract
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #42

**Build:** Prompts as message ids with parameters (direction, count, duration) plus an animation hint and an audio hint — no user-facing strings in `core`.
**Test:** every challenge type has an id, an icon hint and an audio hint; a missing one fails the build.
**Done when:** the UI and audio layers can be built without touching the engine.

---

## Phase 7 — Session State Machine

### #48: State machine skeleton
Priority: 🟥 CORE · Type: Prototype · Blocked by: #46, #8

**Build:** IDLE → ACQUIRING → PASSIVE → {DECIDED | CHALLENGING} → CAPTURING → BINDING → {ACCEPTED | FAILED}, with the injected clock and per-state timeouts from policy.
**Watch out:** one state machine, one place where decisions happen. Generated code tends to sprinkle `if (score > x)` into the UI layer — reject that. Re-fusion after a challenge must use `ZoneDecider.decideAfterChallenge`, or a profile with audit probability 1 loops forever (Bible 47.9).
**Test:** every transition is exercised with the fake clock, including all timeouts, in milliseconds of real time.
**Done when:** the whole lifecycle is testable headlessly.

### #49: Retries, backoff, lockout, rate limits
Priority: 🟥 CORE · Type: Prototype · Blocked by: #48

**Build:** Attempt counter, exponential backoff (2/4/8 s), max retries per profile, lockout, and rate limits per subject, operator and device per hour.
**Watch out:** infrastructure failures (device disconnect, engine error) must **not** consume a retry — only liveness and PAD outcomes do. But every attempt must count against the hourly limits, or non-consuming failures can be looped forever. Keep lockouts outside the session object, or reopening the window resets them.
**Test:** a disconnect mid-session leaves the attempt counter unchanged; three failed challenges reach lockout with the expected waits.
**Done when:** an attacker gets a handful of logged tries and a real person is not punished for a broken cable.

### #50: Exception flow
Priority: 🟥 CORE · Type: Prototype · Blocked by: #49

**Build:** On lockout for a resident: require a supervisor liveness session (strict profile) plus a reason code from a fixed list; mark the session `EXCEPTION`; enforce per-operator and per-centre daily limits.
**Watch out:** this is where fraud moves when liveness tightens (Bible Part 12, the Aadhaar case). It must be harder to abuse than the front door.
**Test:** an exception cannot complete without a passing supervisor verdict object; exceeding the daily limit blocks further exceptions.
**Done when:** the recovery path is audited, rate-limited and dual-controlled.

### #51: Error taxonomy wiring
Priority: 🟥 CORE · Type: Prototype · Blocked by: #50, #17

**Build:** Map every error in Bible Part 22 to a state transition, a message id, an audit event and a retry-consumption rule. Engine errors and invalid policy **fail closed**.
**Watch out:** never silently skip liveness when the engine fails — that is a bypass.
**Test:** each mock fault (#17) produces exactly the specified outcome; a corrupt model file prevents the session from starting.
**Built:** `LivenessError` (the table), `SessionEventRouter` (signals → transitions), `EngineBundle` (the only way to create a session). `FaultOutcomesTest` drives all seven mock faults through the real router.
**Done when:** Part 22's table is executable.

### #52: Face-loss reset and chain restart
Priority: 🟥 CORE · Type: Prototype · Blocked by: #48, #20

**Build:** On track loss > 300 ms, return to ACQUIRING and discard the accumulated hash chain and cue history. Built with the chain itself (#53's core), since there was nothing to discard without it; a frame from a different `trackId` also resets, even if nobody reported the loss.
**Test:** the swap clip from #20 cannot produce an ACCEPT that reuses pre-gap evidence.
**Done when:** evidence cannot span a discontinuity.

---

## Phase 8 — Capture Binding (do not skip)

### #53: Frame hash chain
Priority: 🟥 CORE · Type: Prototype · Blocked by: #52

**Build:** `H₀ = SHA256(nonce)`, `Hᵢ = SHA256(Hᵢ₋₁ ‖ SHA256(frameᵢ) ‖ tᵢ)` over every analysed frame; expose the head. **Done early with #52** (`FrameHashChain`; conventions in Bible Part 13).
**Watch out:** hash the decoded bytes you actually analysed, consistently — re-encoding before hashing makes the chain unverifiable later.
**Test:** dropping, reordering or substituting one frame changes the head; the same session replayed produces the same head.
**Done when:** the analysed stream is tamper-evident.

### #54: Nonce echo verification
Priority: 🟥 CORE · Type: Prototype · Blocked by: #53, #12

**Build:** Parse the rcapture JWS, verify the signature against the device certificate chain, then assert the payload `transactionId` equals the session nonce and the timestamp is fresh.
**Watch out:** verify the signature *before* trusting any field in the payload. Generated code frequently decodes and reads without verifying.
**Test:** a replayed older capture (valid signature, different transactionId) is rejected; an unsigned or tampered payload is rejected.
**Built:** `Locks.NonceLock` — the binder re-verifies the JWS itself before reading any field (`VerifiedCapture`).
**Done when:** replay of signed data fails.

### #55: SBI chain validation and final hash
Priority: 🟥 CORE · Type: Prototype · Blocked by: #54

**Build:** Validate the SBI `previousHash` chain across the session's captures (first = SHA-256 of the empty string), recompute each `hash` from the signed `bioValue`, and fold the capture into our chain: `H_final = SHA256(H_n ‖ SHA256(rcapture JWS))`.
**Test:** a session with a removed or reordered capture fails validation; a clean session yields a stable `H_final`.
**Built:** `SbiCaptureChain` + `Locks.ChainLock`.
**Done when:** our evidence and MOSIP's own chain are linked.

### #56: Identity continuity
Priority: 🟥 CORE · Type: Prototype · Blocked by: #55

**Build:** SFace (OpenCV Zoo, ONNX) embeddings; cosine similarity between the signed capture and the median embedding of the verified challenge frames ≥ θ_same; plus the same `trackId` with no gap.
**Watch out:** θ_same is a *low-false-reject* threshold — it catches swaps, it is not identification. Calibrate on your corpus and record the value.
**Test:** same-person capture ≈ 0.8; different-person capture ≈ 0.2; the boundary case is written down.
**Built:** `FaceAligner` + `SFaceEmbedder` (exact parity with OpenCV), `IsoFaceRecord` + desktop `IsoFaceDecoder` (JPEG 2000). Measured: the MockMDS genuine pair is **0.567**, not ≈ 0.8 — a real low-quality pair sits much nearer the threshold than the ticket assumed; θ_same provisional 0.363. Different-person scores wait for the corpus (#65).
**Done when:** a capture from a different face is rejected.

### #57: Binding result and hard-fail wiring
Priority: 🟥 CORE · Type: Prototype · Blocked by: #56

**Build:** `BindingLock` interface, four locks (nonce, chain, identity, time window Δ ≤ 2 s), `BindingResult` with per-lock outcomes; a failed lock makes the verdict `REJECT_BINDING` and emits a high-severity audit event.
**Watch out:** `ACCEPT` must be unreachable without a passing binding. Enforce it in the state machine, not in a comment.
**Test:** each lock fails individually and each produces `REJECT_BINDING`.
**Built:** `CaptureBinder`, `BindingLock`, `BindingResult` (no public constructor); `LivenessSession.bindingVerified(BindingResult)` — ACCEPT has no other way in.
**Done when:** the capture-swap attack is closed.

### #58: Binding negative-test suite
Priority: 🟥 CORE · Type: Prototype · Blocked by: #57

**Build:** Mock scenarios: pass the challenges then return a different face; return an old signed capture; drop a frame from the chain; delay the capture by 5 s; break the SBI chain.
**Test:** all five are rejected, each with the right reason code.
**Built:** `BindingAttacksTest` (harness) — through the mock device's real signatures, router, session and binder: different face → `identity:IDENTITY_MISMATCH`; old signed capture → `nonce:NONCE_MISMATCH`; dropped frame → `attempt:EVIDENCE_CHAIN_MISMATCH` (every lock passes on the edited evidence; the session refuses a result that is not on its own chain head); 5 s delay → `time:TIME_GAP`; restarted SBI chain → `chain:CHAIN_BROKEN`. Each asserts exactly one reason. To make the delay measurable, the session now issues the `CaptureRequest` itself and records when.
**Done when:** this suite is in CI and stays green.

---

## Phase 9 — Evidence and Server Re-check

### #59: Evidence schema and signer
Priority: 🟥 CORE · Type: Prototype · Blocked by: #58

**Build:** `pramaan.evidence/1` records from Bible Part 14 (nonce, workflow, device, model versions + hashes, policy version + hash, cue scores, fusion result, challenges with prompt and onset times, seed commitment, chain head, binding results, attempts, timestamps), serialised deterministically and signed as a compact JWS with a development key.
**Watch out:** deterministic serialisation (stable key order, fixed number formatting) or the signature won't verify after a round trip.
**Test:** flipping one byte of the payload fails verification; serialise → parse → serialise is byte-identical.
**Done when:** a session emits a verifiable evidence blob.
**Built:** `CanonicalJson`, `EvidenceRecord`, `EvidenceBuilder`, `EvidenceSigner` (`EvidenceTest`). A real accepted session's record: 1,277 B.

### #60: BIR `others` embedding and size budget
Priority: 🟥 CORE · Type: Prototype · Blocked by: #59

**Build:** Write the JWS as a stringified value under `PRAMAAN_LIVENESS` in the face BIR's `others` map; leave `bdb` untouched. Assert a size budget (~2–4 KB JSON + 15–25 KB of encrypted crops).
**Watch out:** don't restructure the BIR. Anything that changes `bdb` or `bdbInfo` risks breaking ABIS parsing (Bible Part 14).
**Test:** a BIR with and without the key parses identically in a standard CBEFF reader; the size budget is asserted in a test.
**Done when:** evidence rides along without disturbing the biometric record.
**Built:** `CbeffOthers` — MOSIP's own `cbeff.xsd` shape (`<Others><Key/><Value/></Others>`); BDBs byte-identical before and after; validates against the XSD; DOCTYPE/XXE refused.

### #61: Sample frame selection and encryption
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #60

**Build:** Pick 3–5 crops (best quality, one per challenge step), resize to 112×112, JPEG, encrypt to the server key; record their hashes in the evidence.
**Watch out:** data minimisation is a requirement, not a nicety — never the full stream, and honour the deployment's retention rules.
**Test:** the crops decrypt with the test key and their hashes match the evidence.
**Done when:** the server has enough to re-score and no more.
**Built:** `SampleSelector`, `SampleCrypto` (RSA-OAEP-256 + AES-256-GCM, index as AAD), desktop `ImageIoJpegEncoder` at quality **0.75** — measured: 0.85 put five real crops at 27.3 KB, over budget.

### #62: Server re-check verifier (CLI)
Priority: 🟥 CORE · Type: Prototype · Blocked by: #61

**Build:** `harness/verify` CLI: verify the evidence signature, the chain head, the nonce echo, the policy/model hashes against an allowed list, and re-score the sample crops with the local model; print agreement or disagreement.
**Watch out:** an unapproved model hash is a *finding*, not a crash — report it and route the packet for adjudication.
**Test:** fixtures for clean, tampered-chain, unapproved-model and client/server-disagreement cases each produce the expected route.
**Done when:** the "re-checkable evidence" claim is demonstrable on stage in one command.
**Built:** `RecheckVerifier` + `./gradlew :harness:verifyEvidence` (exit 0 CONFIRMED / 1 ADJUDICATE / 2 REJECT). `RecheckVerifierTest` routes clean → CONFIRMED, tampered chain head (even re-signed) → REJECT, another session's capture → REJECT, unapproved model → ADJUDICATE, client–server disagreement → ADJUDICATE, forged signature → REJECT.

### #63: Retroactive rescan
Priority: 🟦 STRETCH · Type: Prototype · Blocked by: #62

**Build:** Run the verifier over a directory of stored evidence with a newer model and produce a list of enrolments to review.
**Test:** deliberately accept an attack with a weak model, then rescan with the strong model and see it flagged.
**Done when:** the "attacks missed in 2026 can be found in 2028" story is a demo, not a slide.
**Built:** `RetroactiveRescan` + `./gradlew :harness:rescanEvidence` (packets as `<id>.evidence.jws` + `<id>.capture.jws`; exit 0 nothing to review / 1 review list). `RetroactiveRescanTest`: two enrolments the client accepted; the 2026 model confirms both; the 2028 model flags only the attack (CLIENT_SERVER_DISAGREEMENT).

### #64: Transparency log (local Merkle + STH)
Priority: 🟦 STRETCH · Type: Prototype · Blocked by: #62

**Build:** Append every attempt — including failures — as a leaf; sign a tree head per day; implement inclusion and consistency proofs.
**Test:** altering or deleting a past leaf breaks the consistency proof between two tree heads.
**Done when:** deleted failed attempts become detectable.
**Built:** `pramaan.core.transparency` — `MerkleLog` (RFC 6962 tree, inclusion and consistency proofs; RFC 9162 verification; file-backed append-only), `AttemptEntry` (hashes and coarse metadata, failures included), `SignedTreeHead` (size, root, time, monotonic counter), `TreeHeadChecker` (COUNTER_ROLLBACK / TREE_SHRANK / INCONSISTENT). Roots match the Certificate Transparency reference vectors; every proof for sizes 1-20 verifies; altering or deleting a past attempt breaks consistency.

---

## Phase 10 — Harness, Corpus and the Numbers

### #65: Corpus recording session
Priority: 🟥 CORE · Type: Research · Blocked by: #6, #16

**Build:** Record the plan from #6: bona fide clips per person per lighting condition, plus every attack instrument. Aim for enough attacks per species that the confidence interval means something (Part 21: zero failures in 60 attacks still permits ~5%).
**Watch out:** vary the phone models and print finishes. One phone and one printer trains a detector for that phone and that printer.
**Test:** the manifest loads, every clip plays through `MockFrameSource`, and the per-species counts are written down.
**Done when:** the corpus exists, is labelled, and is split into tuning and held-out halves.

### #66: Degradation gauntlet
Priority: 🟥 CORE · Type: Prototype · Blocked by: #65

**Build:** `tools/degrade.py`: FFmpeg passes at CRF 23/30/38, 1080p→720p→480p, added noise, gamma shifts and simulated 50 Hz flicker — each variant labelled as a derived clip.
**Watch out:** tune and evaluate on different halves; degrading the tuning half only is how you fool yourself.
**Test:** a bona fide clip at CRF 38 still parses and scores; the manifest grows with the derived variants.
**Done when:** the corpus reflects rural camera quality, not just your webcam.
**Built:** `tools/degrade.py` (standard library + FFmpeg 8.1.1): crf23/30/38 (a real H.264 encode and decode), 720p/480p (never an upscale), noise, gamma 0.7/1.4, dim, and a 50 Hz mains flicker (a 100 Hz brightness ripple sampled at the clip rate). It writes `manifest-derived.csv` in the corpus format, the variant named in the device column (`webcam~crf38`) and the person id kept, so a split by person carries over. Test: `DegradeToolTest` (a bona fide clip at CRF 38 still parses, is found and scored in at least 8 of 10 frames; the manifest grows by one row per applicable variant). Waiting only for #65 clips.

### #67: Harness runner
Priority: 🟥 CORE · Type: Prototype · Blocked by: #66, #58

**Build:** Run the whole corpus through the production engine via `MockFrameSource`, at configurable fps, writing one JSON row per session (label, PAIS, lighting, fps, cue values, zone, outcome, timings, abstentions).
**Watch out:** production code path only. A harness-only branch invalidates every number.
**Test:** a full corpus run completes unattended and the row count equals the clip count.
**Done when:** one command produces the raw results file.
**Built:** `harness/…/run` — `CorpusRunner` plays each clip through `MockFrameSource` into the engine `DesktopEngines.factory` builds (the verified bundle's models, the real binder and signatures), on a simulated clock, one attempt per clip; `ActionLibrary` (`actions.csv`: `person,action,clip`, actions TURN_LEFT/TURN_RIGHT/BLINK/EYES_CLOSED/SMILE) lets bona fide people answer prompts, played after a 600 ms reaction; attack clips never answer; a bona fide prompt with no recording is a flagged non-response, not a rejection. Scores come from the audited diagnostic channel (the flag is switched on in memory only, and `rows.jsonl.run.json` records both policy hashes, the table and the models). Rows also record the first zone, whether a challenge was shown, the failure, the last guidance, frames and engine time. The mock now signs ISO 19794-5 records like real devices (`IsoFaceRecord.encode`), and `MockFrameSource.playAt` plays any action. `./gradlew :harness:runCorpus --args="--manifest … --out …"`. Test: `CorpusRunnerTest` (an unattended run writes one row per clip; each says why it ended; the report code reads them). Waiting only for #65 clips.

### #68: APCER / BPCER with confidence intervals
Priority: 🟥 CORE · Type: Prototype · Blocked by: #67

**Build:** Per-PAIS APCER, overall BPCER, plus **APNRR/BPNRR** (non-responses), each with 95% Clopper–Pearson intervals; headline APCER = worst PAIS. ACER only as a secondary, clearly-labelled number.
**Watch out:** ISO/IEC 30107-3 deprecates averaging APCER and BPCER — report "BPCER at a stated APCER" (Bible Part 47.6).
**Test:** hand-computed toy data reproduces the tool's numbers exactly; 0/60 reports an upper bound near 5%, not "0%".
**Done when:** the headline claim comes with an interval.
**Built:** `Binomial` (exact Clopper–Pearson, checked against rational-arithmetic references), `PadReport`; `./gradlew :harness:evaluate`.

### #69: DET curves and operating-point export
Priority: 🟥 CORE · Type: Prototype · Blocked by: #68

**Build:** Sweep thresholds to plot APCER vs BPCER per PAIS; export `operating-points.json` (#37) for each profile's target, bound to the model hash.
**Test:** re-running the engine at an exported operating point reproduces the promised BPCER within the interval.
**Done when:** configuration is generated by evidence rather than guessed.
**Built:** `DetCurve`, `OperatingPointExporter` — `tauAccept` on the one-sided upper bound, so exported thresholds are demonstrated; undemonstrable targets keep the table provisional. Verified on seeded synthetic tuning/held-out halves.

### #70: Per-cue ablation report
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #69

**Build:** Re-score the corpus with each cue disabled in turn; tabulate APCER per PAIS per ablation.
**Watch out:** this is the evidence for the Bible's "the holes don't overlap" claim — if one cue carries everything, say so rather than hiding it.
**Test:** removing the moiré cue visibly worsens screen-replay APCER and barely moves print APCER.
**Done when:** the architecture argument has a table behind it.
**Built:** `CueAblation` through the production fusion and zone decision (passive decision only).

### #71: Fairness and non-response reporting
Priority: 🟥 CORE · Type: Prototype · Blocked by: #68

**Build:** BPCER and BPNRR broken down by the demographic attributes recorded in the corpus (skin tone via a documented scale, age band, eyewear, lighting), with intervals and a max/min differential.
**Watch out:** demographic labels exist in the evaluation corpus only — never inferred at runtime (Bible Part 16).
**Test:** the report renders with small-N warnings where a group is thin.
**Done when:** you can answer "does it work for everyone?" with numbers and honest error bars.
**Built:** `FairnessReport` — intervals, differential vs 1.5, small-N warnings, 0-error differential reported as undefined.

### #72: Injection test suite (IAM × IAI)
Priority: 🟦 STRETCH · Type: Prototype · Blocked by: #67, **#84 (the one deliberate forward dependency — build this after Phase 13)**

**Build:** Test matrix of injection methods (virtual camera, looped file, emulated device) × instruments (recorded replay, face swap, synthetic), reported separately from presentation attacks, in CEN/TS 18099's shape.
**Done when:** the injection claim has its own table.

### #73: Device benchmark task
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #67

**Build:** Run every model on each target device class and write the measured latency table into the report (replacing the budgets in Bible Part 20.4).
**Done when:** performance claims are measurements.

---

## Phase 11 — Desktop Integration (Mode C)

**Built first, because every option of #74 needs it — the engine loop** (`pramaan.core.engine`): `LivenessEngine` (a `FrameSink`: frames → perception → session, fusion, challenges, capture, binding), `FrameAnalyser` / `FrameObservation` with `PipelineFrameAnalyser` on the desktop models, `CaptureAnalyser` with `desktop.IsoCaptureAnalyser` (verify, decode ISO, YuNet, SFace), and `EngineListener` (states, guidance, message ids, prompts — never scores). Tested end to end in `harness/…/engine/LivenessEngineTest` with a simulated resident that renders 3-D landmarks and answers the prompts, against the mock device's real signatures: genuine at 10 and 3 fps, photo, still photo, wrong action, pre-timed response, identity swap, second face, track change, engine exception, unverifiable kinds. Findings in Bible 47.14.

### #74: Integration decision record
Priority: 🟥 CORE · Type: Discuss · Blocked by: #5

**Build:** `docs/adr/0003-integration-depth.md`: integrate into the real `registration-client` repo, or build a faithful scaffold implementing the same screens and interfaces and document the patch? Decide on day 1 with the effort and risk written down; confirm with the organisers whether depth is judged.
**Watch out:** the real client is a large JavaFX codebase. Choosing it late is the classic schedule killer.
**Done when:** the decision is recorded and the rest of Phase 11 follows it.
**Built:** `docs/adr/0003-integration-depth.md` — scaffold first, OpenJFX 11.0.2 (the client's own pin), and the patch into `registration-client` 1.2.0.x written against named classes (P1–P6). Whether depth is judged is Bible Q21.

### #75: Face capture screen
Priority: 🟥 CORE · Type: Prototype · Blocked by: #74, #57

**Build:** Live preview, face oval, guidance messages, "Checking face liveness…", challenge prompts with animation, real-time feedback, success state — all driven by engine events and message ids only.
**Watch out:** never display scores, never vary wording by score (Bible Part 19).
**Test:** a full resident capture runs end to end against MockMDS with no engine internals visible on screen.
**Done when:** the spine is demoable.
**Built:** `desktop/…/capture` — `CaptureSession` (device search, stream, one second of frame-rate measurement, then the engine; the engine asks for the capture), `CaptureScreenModel` (engine events to screens, headless), `FaceCaptureView` (JavaFX), `Messages` + `messages.properties`, `DesktopEngines` (the bundle's own models, exactly the table's set); `desktop/…/scaffold/RegistrationScaffold`, run with `./gradlew :desktop:runScaffold` against MockMDS. Tests: `CaptureScreenEndToEndTest` (a full resident capture through the scaffold's session and screen, nothing internal shown; a challenge shown as words, animation and "Action detected"; no device, no session), `CaptureScreenModelTest`, `FaceCaptureViewRenderTest` (real JavaFX, snapshots in `desktop/build/reports/scaffold/`), `DesktopEnginesTest` (the real models load and build an engine).

### #76: Operator authentication
Priority: 🟥 CORE · Type: Prototype · Blocked by: #75

**Build:** Liveness (strict profile) **before** the face match; binding required; failure blocks authentication.
**Watch out:** order matters — never run the match on a capture that failed liveness, or a failed attempt still leaks "this face matches".
**Test:** a photo of the operator fails; the live operator passes; the audit log shows both.
**Done when:** the highest-value workflow is protected.
**Built:** `core/…/auth/OperatorAuthentication` + `FaceMatcher`: liveness (OPERATOR profile, binding) first, the matcher only after, every outcome audited; a matcher error refuses. Scaffold: login screen, onboarding from a liveness-passed capture, SFace matcher at θ_same. Tests: `OperatorAuthenticationTest` (photo fails and the matcher is never called; live operator passes; impostor refused; matcher failure refused; audit shows both). Needed Part 10 cue-unlocking as a requirement (Bible 47.16).

### #77: Supervisor approval binding
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #76

**Build:** Supervisor approval requires a *fresh* liveness session bound to the specific packet id — no reuse of a login from hours earlier.
**Test:** reusing an old supervisor session for a new approval is rejected.
**Done when:** approvals can't be replayed.
**Built:** `EngineBundle.newSupervisorSession(supervisorId, approves, ...)`; `SupervisorVerdict.approves()`; `core/…/auth/PacketApproval`; the exception flow checks the binding too (`REJECTED_WRONG_PURPOSE`). Tests: `PacketApprovalTest` (reuse for another packet, a second use, a login verdict, a stale verdict, dual control), `ExceptionFlowTest`.

### #78: Configuration wiring and diagnostic mode
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #75, #9

**Build:** Read policy through the client's configuration framework; add a policy-gated diagnostic view (cue scores, capabilities, fps, timings) whose activation is itself audited.
**Test:** changing a policy value changes behaviour without a rebuild; diagnostic mode is off by default.
**Done when:** operators can troubleshoot a bad centre setup without a developer.
**Built:** `desktop/…/capture/ConfigurationSource` + `DirectoryConfiguration` (policy and table at run time; ADR 0003 P7 for the client's global parameters); `core/…/engine/DiagnosticGrant` + `DiagnosticSink` (policy-gated, audited, bound to the policy hash); `desktop/…/capture/DiagnosticsWindow`. Tests: `DiagnosticModeTest` (off by default and the refusal audited; flipping the policy value turns it on without a rebuild; the sink sees scores while the resident screen sees none; a grant under one policy does not open another).

---

## Phase 12 — Android (Mode D)

### #79: Engine packaging as an AAR
Priority: 🟥 CORE · Type: Prototype · Blocked by: #57

**Build:** Build `core` for Android (ORT Mobile or LiteRT, MediaPipe Tasks for landmarks) and publish an AAR with no UI and no MOSIP dependencies.
**Watch out:** anything in `core` that touched JavaFX, `java.awt` or desktop-only ONNX ops surfaces here. Keeping `core` clean in #1 is what makes this cheap.
**Test:** the AAR builds and a headless instrumentation test scores a bundled test image.
**Done when:** the engine runs on a phone.
**Built (device run pending):** `android/` — a separate Gradle build, so the main build never needs the Android SDK; it takes core as an included build. `pramaan-android` (AGP 8.5.2, compileSdk 34, minSdk 26 as MOSIP's Android mock SBI) swaps the JVM ONNX Runtime for `onnxruntime-android` at the same pinned 1.20.0 and adds `AndroidEngines`, `BitmapImageDecoder`, `BitmapFrameDecoder`, `Pixels`. Engine assembly moved into core (`StandardEngines`, `SFaceCaptureAnalyser`, `ImageDecoder`) so desktop and Android build the identical engine. `checkCoreAndroidApi` (on `check`) dexes core and each runtime library with the SDK's D8 at minSdk and looks up every remaining java.*/javax.* reference in the SDK API database: core has 373, all present at API 26 (`String.isBlank`, API 33, is backported by D8); jackson-databind and SnakeYAML reference `java.beans` on bean-introspection paths the policy parser should not reach. Published with core to `build/repo` (`pramaan:pramaan-android`, bringing core and onnxruntime-android). Tests: `PixelsTest` (JVM); `EngineOnDeviceTest` (instrumentation: loads the YAML policy, the table and the models from assets, detects and scores a face) compiles into a 117 MB test APK — **not yet run: this machine has no emulator image or connected phone.**

### #80: Mock SBI Android app with the engine inside
Priority: 🟥 CORE · Type: Prototype · Blocked by: #79

**Build:** Extend MOSIP's Android mock SBI: it already shows the capture UI in the foreground, so add the liveness session, challenge prompts and the engine, and return the result with the signed capture.
**Watch out:** this is the *only* way frame-based liveness works on Android (Bible Part 1.2). Don't build it as a Registration Client screen.
**Test:** the unmodified Android Registration Client triggers capture and receives a signed result whose liveness evidence verifies.
**Done when:** Mode D is demonstrated with a real client.

### #81: Result surfacing and evidence on Android
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #80

**Build:** Carry the liveness verdict and evidence back through the intent result and into the BIR `others` map.
**Test:** the CLI verifier (#62) accepts evidence produced on Android.
**Done when:** one verifier works for both platforms.

### #82: Desktop/Android conformance test
Priority: 🟥 CORE · Type: Prototype · Blocked by: #81, #67

**Build:** Run the same 200 recorded sessions through both platforms; require identical zone and outcome decisions and cue scores within ±0.005; identical challenge sequences from the same seed.
**Watch out:** differences usually come from image decoding and resize filters, not the model. Pin the resize algorithm on both sides.
**Test:** the conformance report shows 100% decision agreement, or names every disagreement.
**Done when:** "same engine, same decisions, both platforms" is a proven claim.

### #83: Low-end performance pass
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #82, #73

**Build:** INT8 quantisation, cadence tuning, ROI-only processing; re-run #27's golden set after quantisation to measure accuracy loss.
**Test:** the golden set stays within its bands and the device benchmark meets the budget.
**Done when:** it runs on the cheapest tablet you can find.

---

## Phase 13 — Differentiators (ship at least two)

### #84: Injection guard
Priority: 🟥 CORE · Type: Prototype · Blocked by: #51

**Build:** Virtual-camera enumeration against a signed denylist (OBS, ManyCam, v4l2loopback…), device-identity verification (digitalId signature and certification level), frame-timing statistics (`CV` of inter-frame intervals, duplicate-frame hashes). Hard veto on a virtual camera bound to the capture path.
**Watch out:** host-side timing on the SBI path is dominated by HTTP buffering — weight it low and say so (Bible Part 5).
**Test:** install OBS Virtual Camera, select it, and confirm `REJECT_INJECTION` with an audit event; a normal webcam with an odd driver name is not vetoed.
**Done when:** the attack the problem statement never names is demonstrably blocked.
**Built (partial):** `InjectionGuard.denylistMatch` (a plain in-code list of well-known virtual-camera products —
not the signed, externally maintained one Part 5 calls for) checked against the SBI device's own reported
make/model/code, wired into `LivenessEngine` as a hard `Veto.INJECTION` when `vetoOnBoundVirtualCamera` is set —
checked once per session against the device bound to the capture path, so it can never itself be bypassed by
timing. `InjectionGuard.timingCoefficientOfVariation`/`duplicateFrame` implement Part 5's frame-timing and
duplicate-frame checks but are **not wired into a veto or a fusion cue**: their false-positive rate against real
device jitter needs a real timing-distribution corpus (#65) to calibrate, which does not exist yet. Device-identity
certificate-chain verification (Part 5, point 1) and OS-level camera enumeration (point 2's actual mechanism, as
opposed to matching on reported metadata) remain not built; PRNU (point 4) remains explicitly STRETCH.
`InjectionGuardTest` and `InjectionGuardEngineTest` (a denylisted device is vetoed with `INJECTION_DETECTED`
before fusion runs; a genuine device is not).
**Built since:** OS camera enumeration (`HostCameraInventory`, `WindowsCameraInventory`, policy `vetoOnHostVirtualCamera`,
default off because an operator may have OBS installed for another reason), device-identity checking (`DeviceIdentity`,
`DeviceMetadata.identity`, policy `requireVerifiedDeviceIdentity`; a signed digitalId that fails is always a veto), and
`TimingWatch` into the evidence (`injection.timing`, `actedOn: false`). Tested in `DeviceIdentityTest`,
`TimingWatchTest`, `WindowsCameraInventoryTest` and `InjectionFindingsEngineTest`. The real PowerShell call and a real
OBS install are not in a unit test, but the inventory was run on a real Windows station with OBS installed: it found
"OBS Virtual Camera" (a DirectShow registration that Plug-and-Play never lists) in about half a second and left the
built-in webcam alone; the first version, which read only Plug-and-Play, missed it. The parse is tested on canned
output of both sources. Still not built: PRNU, a signed
externally maintained denylist.

### #85: Flash challenge and peripheral
Priority: 🟥 CORE · Type: Prototype · Blocked by: #41, #14

**Build:** Random colour sequence on a resident-facing light; measure the face-region chroma response; correlate with lag search (ρ ≥ 0.6, λ ≤ 2 frames); check nose-vs-cheek shading consistency; abstain when ambient light is too strong or no resident-facing source exists.
**Watch out:** **photosensitivity** — at most 3 transitions per second, moderate luminance steps, no saturated-red alternation (Bible Part 8.6). And the flash proves "a 3-D object is here now", not "live skin": add the red/green/blue response-ratio check for mask materials.
**Test:** a live face gives ρ ≈ 0.8; a phone replay gives ρ < 0.3; an injected stream gives ρ ≈ 0; with the light unplugged the cue abstains rather than failing anyone.
**Done when:** the standout demo beat works in the demo room's actual lighting.
**Built (partial):** the sequence generator already existed (`FlashSequence`, 6^8 colours, 350–500 ms steps, 700–1000 ms
at low fps). New: `FlashSchedule` (when each colour is on screen, with a 500 ms dark lead-in for the ambient
reference), `FlashSafety` (the independent photosensitivity gate: at most 3 changes in any second, steps of at
least 333 ms; all 900 generated sequences at 3, 10 and 30 fps pass), and `FlashReflectionCue` with `FlashTrace`
(per-frame normalised-rgb chroma against the *colour part* of the emitted light — white shifts no chroma — searched
over lag 0–2 frames; nose-vs-cheek gain ratio; the blue-to-red gain ratio is **reported, not fused**).
It abstains `CAPABILITY_MISSING` with no light, `NOT_APPLICABLE` when the face is washed out (mean brightness above
200) or the light never changed, `INPUT_TOO_SMALL` under 12 frames; a *weak* response in normal light is a vote
against, not an abstention — that is what an injected stream looks like. `FlashReflectionCueTest`: live ρ above
0.7 at the right lag, phone replay ρ below 0.5, injection |ρ| below 0.45, a 3-frame delay does not pass, a flat
print scores below a real nose. Thresholds are the Bible's worked example (ρ 0.83 / 0.21 / 0.07), **not measured**.
**Wired since:** `LivenessEngine.Builder.flashLight(FlashLight)` (and `Station`/`StandardEngines.create(..., station)`)
declares a resident-facing light; with it the flash is drawn from the policy pool, `FlashPhase` plays the plan's
sequence on it after the action steps — the session counts the flash as the plan's last step, so the phase passes
that step when it ends — samples the face's skin, nose and cheeks from every single-face frame (`FaceRegions`, fixed
proportions of the detector's box, taken even on frames the quality gate would not score), and the cue's vote joins
the post-challenge fusion. The sequence is re-checked with `FlashSafety` before the first colour; an unsafe one is
never shown and the cue abstains. The light is switched off whenever an attempt ends or is reset. Which accessibility
profiles skip light is decided by the existing filter (`AccessibilityAdjustments.allows(FLASH)`); no profile named
for photosensitivity exists, which is an open question for the operator-profile list. Desktop: `FlashWindow` (a
borderless window on the monitor named by `--flash-screen N`, hiding itself 1.5 s after the last colour; an index with
no monitor stops the start-up). Tests: `StationWiringEngineTest` (live face accepted with a flash vote above 0.8; an
injected stream votes below 0.5; no light means no flash and an abstention), `AttemptPhasesTest` (including 3 fps),
`FlashWindowTest`.
**Not built:** the real display has not been looked at — `FlashWindow` is untested on a screen; the spectral skin
check needs the corpus (#65) and a fixed white balance.
**Built since:** the device's signed emission log (profile addition 5) feeds the correlation: `EmissionLog`,
`DeviceIlluminator`, `DeviceFlashPhase`, preferred to the station's light; `EmissionLogTest` and four cases in
`StationWiringEngineTest` (live face, injected stream, a log that differs from the schedule, a device that fails).

### #86: Stereo depth cue
Priority: 🟦 STRETCH · Type: Prototype · Blocked by: #32

**Build:** Calibrate the auxiliary camera (`stereoCalibrate`), triangulate landmarks, fit a plane, report nose relief and plane residual; pair frames only within 20 ms.
**Test:** a real face gives ~20–30 mm relief; a print gives 1–2 mm; injecting into one camera only raises the reprojection error.
**Done when:** flat spoofs are refuted geometrically, in 3-D.
**Built (partial):** `StereoCalibration` (focal length, baseline, principal point of a *rectified* pair;
`Z = f b / d`, depth resolution, and `baselineFromKnownTarget`/`drift` for the Part 40 daily self-test) and
`StereoDepthCue`: triangulate the landmarks, fit a plane by least squares, report nose relief (mean cheek depth minus
nose-tip depth, mm), plane residual and the RMS row disagreement between the two views. Pairing is strict (frames
more than 20 ms apart abstain `NOT_APPLICABLE`); no calibrated second camera abstains `CAPABILITY_MISSING`; a row
error above 1.5 px (the views cannot be one scene — one camera injected) gives a vote of 0.02. `StereoDepthCueTest`
reproduces the Bible's worked example (f 900, b 80, Z 500 → d 144 px, 1.7 mm per half pixel) and, on synthetic
geometry, a real face at ≈25 mm relief, a flat print ≈0 mm, a curved print with no nose peak, and the injection case.
**Wired since:** `StereoSource` (core/source) is the auxiliary camera's interface — landmarks in the fixed order nose
tip, left cheek, right cheek, then at least five more — and `LivenessEngine.Builder.stereo(...)` adds the cue's vote on
every analysed frame; a frame with no pair adds nothing (not a failure). `StationWiringEngineTest`: a real-face pair
votes above 0.9 and a flat one below 0.1 through the real engine.
**Not built:** the calibration itself (`stereoCalibrate`/`stereoRectify` over checkerboard views — an installation
step needing an image library), a real `StereoSource` (a second camera and a landmark detector on its rectified
frames). Thresholds are the Bible's, **not measured**.

### #87: Scene and illumination consistency
Priority: 🟦 STRETCH · Type: Prototype · Blocked by: #31

**Build:** Fingerprint the empty centre backdrop at setup; compare the visible background during capture; compare face-highlight white point against the backdrop's.
**Test:** a clip recorded in another room scores low; a phone replay's white point differs measurably from the room's.
**Done when:** injected video has to match the room, not just the face.
**Built (partial):** `WhitePointConsistencyCue` (Bible Part 43.B.1 only): grey-world rg-chromaticity of a given
backdrop crop against the face crop's brightest pixels (its specular highlight), scored logistically around a
provisional Δ = 0.04 threshold — the Bible's own worked example reproduces exactly (live Δ ≈ 0.01, a bluish phone
replay Δ ≈ 0.09). **Not built:** the scene fingerprint itself (part A — ORB keypoints, an installation-time
empty-room capture, person segmentation to find "the visible background") and light-direction consistency
(part B.2 — a spherical-harmonics shading fit); it is not wired into `LivenessEngine`'s fusion, because that would
need the backdrop-capture subsystem part A is (an operational decision — where and when each centre's empty room
gets photographed — not a code decision). `WhitePointConsistencyCueTest`.
**Built since (parts A and B.2; wired below):** `SceneFingerprint` (colour histogram plus up to 150 Harris corners
with 256-bit BRIEF descriptors at 160x120 — the ORB family without the rotation a fixed camera does not need — with
an exclusion box for the resident) and `SceneConsistencyCue` (`S_bg = 0.5 HistCorr + 0.5 matched/keypoints`). A
consistent offset shared by at least 60 % of the matches is read as a bumped camera (the Bible's FOV proxy): the cue
abstains and reports `cameraMoved` so the caller can ask for an audited re-fingerprint instead of rejecting anyone.
A blank wall (too few keypoints) abstains `CAPABILITY_MISSING`; a resident filling the frame abstains
`INPUT_TOO_SMALL`. `SceneConsistencyCueTest` uses procedural rooms: the same room with a resident and a brightness
change scores above 0.65, another room below 0.35. `LightDirectionConsistencyCue` is the 2-D, first-order form of B.2
(a luminance plane fitted to the face and to the backdrop, compared by the cosine of the gradient directions; even
lighting abstains) — **not** the spherical-harmonics fit on a 3-D face, and it cannot separate shading from
albedo. `LightDirectionConsistencyCueTest` uses ramps of known direction.
**Wired since:** `SceneContext` (engine) holds the station's stored fingerprint and runs the three cues on every fifth
frame inside `PipelineFrameAnalyser`, excluding a box grown from the face box and cutting the backdrop from the wider
side margin. The fingerprint compares colour histograms **per grid cell over the cells visible in both images**
(comparing the live frame's visible part with the whole empty room scored a matching room down for the resident standing
in it) and counts only corners whose whole descriptor patch is visible background. It has a file format
(`toBytes`/`fromBytes`, magic `PRSF`, format 2, bit-packed mask and per-cell counts); `BackdropTool`
(`./gradlew :desktop:captureBackdrop --args="--image empty-room.jpg --out build/scaffold/backdrop.bin"`) writes it from
one photograph of the empty room and refuses a blank wall; the scaffold's `--backdrop` reads it and declares
`KNOWN_BACKDROP`, and a bumped camera is audited as `BACKDROP_CAMERA_MOVED`. `PROVISIONAL_SCORE` moved from 0.45 to
0.55 after a test showed a room with a similar palette and no matching corners sitting exactly on the old threshold.
Tests: `SceneContextTest`, `BackdropToolTest`, `StationWiringEngineTest` (without a backdrop all three abstain
`CAPABILITY_MISSING`). **Still not built:** person segmentation (an exclusion box stands in), MOG2-style removal of
people walking behind. All thresholds are provisional.
**Built since:** the audited in-app re-fingerprint. The scaffold's **Re-take backdrop…** button asks for an
administrator id, a reason and the operator's word that the room is empty, takes a photograph of it, and
`BackdropRetake` writes the new fingerprint, keeps the old one beside it as `.previous`, and audits
`BACKDROP_RETAKEN` (with the hash of the old and new fingerprint) or `BACKDROP_RETAKE_REFUSED`. It applies from the next
registration. Tested in `BackdropRetakeTest`; the JavaFX dialogs were not exercised, and nothing checks that the photograph
really is an empty room.

### #88: Fraud analytics signals
Priority: 🟦 STRETCH · Type: Prototype · Blocked by: #10

**Build:** Per-operator counters (PAD fails, exceptions, accessibility-profile use, overrides), binomial z-scores against the centre baseline, a funnel plot, and the operator-face cross-check (a resident capture matching the logged-in operator is blocked).
**Test:** a synthetic operator with 6 fails in 40 sessions against a 2% baseline is flagged (z ≈ 5.9); normal operators are not.
**Done when:** the insider threat has a visible control.
**Built:** `FraudAnalytics.zScore`/`funnelLimits` (binomial z against the operator's own centre rate, ±3 SD funnel
limits) over four signals (PAD_FAIL, BINDING_FAIL, ACCESSIBILITY, EXCEPTION); `harness:fraudReport` reads
`ATTEMPT_ENDED` and `EXCEPTION_REQUEST` from the audit log and writes a markdown table plus a funnel-plot SVG per
signal, flagging the audit chain itself if it is broken. The operator-face cross-check is `Locks.OperatorLock`
(Bible Part 13, 15.3, 18), set with `LivenessSession.guardAgainstOperatorFace`: a resident capture matching the
logged-in operator's own face fails binding with `OPERATOR_FACE`, independent of every other lock.
`FraudAnalyticsTest.theBiblesWorkedExample` reproduces z ≈ 5.87 for 6 fails in 40 at a 2% baseline;
`AccessibilityAndAttemptTest` and `CaptureBinderTest` cover the operator guard.

### #89: Synthetic attack factory
Priority: 🟦 STRETCH · Type: Prototype · Blocked by: #66

**Build:** Simulate screen recapture (display grid resampling → moiré, glass specular, refresh banding) and print (halftone, paper texture, gloss, curvature) from bona fide clips.
**Watch out:** synthetic attacks expand *training* and stress tests; headline APCER always comes from real presentations.
**Test:** simulated recaptures show a spectral peak near the predicted beat frequency, and a KS test compares their cue statistics against real attacks.
**Done when:** attack data is no longer the bottleneck.
**Built (partial):** `pramaan.harness.factory` — `ScreenRecapture` (display pixels as R/G/B stripes with leakage
and a dark gap, area-sampled onto a sensor grid so moiré *emerges* from aliasing at `predictedBeat = |f − round(f)|`,
a cool gamut shift, a glass highlight, and refresh banding of amplitude `m |sinc(f E)|`, which vanishes when the
exposure spans whole ripple periods), `PrintSimulator` (bend, CMYK separation, cosine halftone screens at 15/75/0/45
degrees, paper tooth, gloss), `TwoSampleKs` and `SimToRealReport` (KS statistic and asymptotic p per cue, labelled
SYNTHETIC). `SyntheticAttackFactoryTest`: a simulated screen shows a spectral peak within 0.01 cycles/pixel of the
predicted beat; the existing `MoireCue` scores a recapture at more than three times its bona fide source's
peakiness; a halftoned mid-grey has period `cellPx x sqrt(2)`; coverage tracks ink; the same seed reproduces the
same pixels. Noise is an integer hash, not a random generator, so every attack is reproducible from its parameters.
**Not built:** the replay simulator (H.264 re-encode chains — `tools/degrade.py` already makes ffmpeg degradations),
the digital attacks (face swap needs consented source footage), camera ISP/JPEG on the simulated output, and the
KS comparison against *real* attacks (none exist until #65) — so no sim-to-real gap has been measured.

### #90: Pupil light reflex (hardware permitting)
Priority: 🟦 STRETCH · Type: Prototype · Blocked by: #85

**Build:** With an iris device or NIR camera plus a visible flash, fit pupil radius over time; pass on amplitude ≥ 6% with latency 150–700 ms, time-locked to randomly scheduled flashes; abstain otherwise.
**Watch out:** absence is never evidence against a person (medication, age, some blindness) — positive-only weighting (Bible Part 9).
**Test:** synthetic radius curves recover latency and amplitude within tolerance; a printed iris gives ~0 amplitude.
**Done when:** the no-cooperation-needed liveness proof works for infants and non-cooperative residents.
**Built (partial):** `PupilLightReflexCue`: for each randomly timed flash, a grid fit of latency (100–800 ms) and time
constant with the amplitude solved in closed form, then `A ≥ 0.06`, latency 150–700 ms, `R² ≥ 0.7`; a trial with
more than 20 % missing radii (a blink) is discarded; fewer than two valid trials abstains; the reflex counts only if
**every** valid trial passes, so a response not time-locked to each flash does not. It is **positive-only**: a
present reflex votes 0.97, an absent one *abstains* — it never votes against anyone (medication, age, some
blindness; Bible Part 32). `PupilLightReflexCueTest`: the Bible's worked example (280 ms, 26 %) is recovered
within 40 ms and 0.04; a printed iris (amplitude ≈ 0), a constriction at the wrong time, and a reflex answering one
flash of three all abstain; a sluggish old eye at 520 ms with 12 % passes. Honest limit found in testing: latency and
time constant trade off by about ±50 ms, so an eye genuinely near 650–700 ms can fall just outside the bound and
then abstains rather than being penalised.
**Wired since:** `PupilSource` (core/source: `stimulate()` and `radii(from, to)`) and
`LivenessEngine.Builder.pupil(...)`: `PupilPhase` runs after the flash step, fires three flashes at CSPRNG-random
gaps, reads the radii over the whole window and gives them to the cue, whose result joins the post-challenge fusion
(positive-only: it can raise the score, an absent reflex abstains). It is skipped when the resident's accessibility
profile disallows light. The session grants the extra time through an audited, once-per-attempt, capped
`LivenessSession.extendDecisionDeadline` (event `CHALLENGE_PHASES_STARTED`). **One deviation from the Bible:** the gap
is 2.0-3.5 s, not 1.5-3 s — a pupil constricted for one flash is still partly constricted 1.5 s later, which depresses
the baseline the next flash is measured against and shrinks its apparent amplitude (found in `StationWiringEngineTest`
with a physiologically plausible re-dilation). Tests: `AttemptPhasesTest`, `StationWiringEngineTest`.
**Not built:** pupil segmentation from NIR frames (the cue takes a radius series), a real `PupilSource`, the
consensual-reflex bonus for dual-iris devices, and illuminator control through the Liveness Profile (or the Part 40 kit).

---

## Phase 14 — UX and Accessibility

### #91: Message catalogue, icons and audio
Priority: 🟥 CORE · Type: Prototype · Blocked by: #47

**Build:** One catalogue of every user-facing string keyed by message id, with at least two languages, an animated icon per challenge, and text-to-speech hooks; PAD, injection and binding failures all share one generic message.
**Watch out:** identical wording for every security failure — differing text is an oracle (Bible Part 19).
**Test:** a snapshot test asserts that all four security failure types render the same string.
**Done when:** an illiterate or non-English-speaking resident can complete a session.
**Built:** `Messages`/`Text` over `messages.properties` (English) and `messages_hi.properties` (Hindi, flagged for
native-speaker review), with a `#value` special-case form (`pramaan.status.wait#1` = "1 second", not "1 seconds").
`PromptIcon` gives every animation hint a vector icon; `Speech.forPlatform()` speaks each new headline via Windows'
`System.Speech` (PowerShell, `-EncodedCommand`) except while CONNECTING. Every `Severity.HIGH` `LivenessError` in
the same message-id group renders one fixed string, identical in every language: `HindiCatalogueTest` confirms the
Hindi catalogue is complete (no key silently falls back to English) and that this security invariant holds in both
languages; `PromptIconTest` confirms every hint the engine can send has an icon.

### #92: Accessibility profiles end to end
Priority: 🟥 CORE · Type: Prototype · Blocked by: #91, #42

**Build:** Operator-selectable profile at session start, recorded in the evidence, driving the challenge filter, thresholds and prompts.
**Test:** a scripted `NO_VISION` session completes with audio prompts and no gaze challenge; the profile appears in the evidence.
**Done when:** the inclusion story is executable, not aspirational.
**Built:** `RegistrationScaffold`'s resident screen offers a `CheckBox` per policy-listed `AccessibilityProfile`;
`LivenessSession.useAccessibilityProfiles` accepts them only from IDLE, attempt 0, only profiles the policy lists,
and audits `ACCESSIBILITY_PROFILES` with the operator's id (over-use is itself a fraud signal, #88).
`EvidenceBuilder.forSession` writes `accessibilityProfiles` into the evidence.
`AccessibilityAndAttemptTest` covers acceptance, rejection of an unlisted profile, and that the choice is ignored
once an attempt is under way.

### #93: Retry, lockout and exception screens
Priority: 🟨 SUPPORTING · Type: Prototype · Blocked by: #92, #50

**Build:** Attempt counter display, cooldown, the recovery message, and the supervisor approval screen for exceptions.
**Test:** the full failure path is walkable in the UI without reading logs.
**Done when:** a stuck resident is never left at a dead end.
**Built:** `CaptureScreenModel.cooldown(waitMillis)` sets the RETRY stage's notice (`pramaan.status.wait`, rounded
up to whole seconds, never to zero) from `LivenessSession.retryWaitMillis()` — the wait follows the attempt count,
never a score — and is cleared the moment the session leaves RETRY. `RegistrationScaffold`'s exception screen adds
an `ExceptionReason` picker, a supervisor face check, and calls
`session.requestException(reason, verdict, photo)`, mapping every `ExceptionOutcome` to a message.
`CaptureScreenModelTest` covers the cooldown notice, including its clearing.

### #94: Offline and stale-policy indicators
Priority: ⬜ DETACHABLE · Type: Prototype · Blocked by: #78

**Build:** An "offline — working normally" badge and a stale-policy indicator when the bundle is older than the configured maximum age.
**Done when:** the offline claim is visible during the demo.
**Built:** `RegistrationScaffold`'s badge label always shows "Offline — working normally" (the scaffold never syncs)
and, when `bundle.stale(clock.instant())` — older than `stalePolicyMaxAgeDays` — turns red and adds the
stale-policy sentence. `EngineBundle.stale`: an unsigned bundle has no trusted issue time and is never stale.

---

## Phase 15 — Proof and Demo (do not skip)

### #95: Red-team rehearsal day
Priority: 🟥 CORE · Type: Research · Blocked by: #84, #85, #58

**Build:** A day spent attacking your own system with everything you have: prints, screens, replays, masks, a virtual camera, a deepfake, a capture swap. Log every success and fix or document it.
**Watch out:** do this **before** the report is generated, not after. Anything that works is either fixed or disclosed as a known limit.
**Test:** every attack you can build is recorded with its outcome.
**Done when:** you know your own weaknesses better than the judges do.
**Built (the log, not the day):** `docs/red-team-log.md` — the rules for the day, the equipment checklist, all fourteen
attacks of Bible Part 2.2 mapped to their controls and to the tests that cover them (`TEST-BACKED` rows), an empty
attempt log, a findings template (fixed or disclosed — no third state) and a summary table with Clopper–Pearson
intervals. **The physical attacks have not been run**; every `NOT RUN` row is blank on purpose.

### #96: Evaluation report generator
Priority: 🟥 CORE · Type: Prototype · Blocked by: #70, #71, #95

**Build:** One command producing an HTML/Markdown report: per-PAIS APCER with intervals, BPCER, non-response rates, DET curves, the ablation table, the fairness table, the device benchmark, and the corpus description (counts per species and condition).
**Test:** the report regenerates from raw results with no manual editing.
**Done when:** the evidence is one artefact you can hand to anyone.
**Built:** `evaluate --html report.html` (alongside the existing markdown) writes one self-contained page (no
external scripts): a corpus summary by class and by every recorded attribute (lighting, eyewear, device, workflow,
...), a DET plot with one polyline per PAIS on normal-deviate axes (Acklam's rational approximation for the probit),
and the markdown report's own tables rendered as HTML. `EvaluationTest` covers the CLI end to end and the page's
shape (one title, a corpus row, `<svg>`, `<table>`, no `<script>`); its own unit tests cover the probit axis and the
markdown-to-HTML conversion directly.

### #97: Demo script
Priority: 🟥 CORE · Type: Discuss · Blocked by: #96

**Build:** The nine scenes from Bible Part 28, each with the exact click path, the expected on-screen text and the fallback if hardware misbehaves. Rehearse it end to end at least three times.
**Watch out:** scene 4 (phone replaying a video of a teammate) is the one that separates you from every passive-only team — rehearse it most.
**Done when:** the demo runs without you narrating from memory.
**Built (the script, not the rehearsals):** `docs/demo-script.md` — nine scenes, each tagged LIVE / HARNESS / HARDWARE,
with the click path, the exact on-screen text from the message catalogue, what to say, and a fallback; two scenes carry
**NOT YET** notes (flash in scene 5, OS-level camera enumeration in scene 6) so nothing unbuilt is demoed as built.
Blank rehearsal logs, with a separate one for scene 4. **No rehearsal has been done.**

### #98: Claim discipline sheet
Priority: 🟥 CORE · Type: Discuss · Blocked by: #96

**Build:** One page: every claim you will make, the number behind it, its interval, and the matching limitation. Include the ones that hurt (flash needs a resident-facing light; rPPG is off on compressed streams; projection-on-mask is unquantified; the corpus is small).
**Watch out:** a judge who finds an overstated claim discounts everything else. Stating limits first is the stronger move.
**Done when:** you can answer "how do you know?" for every sentence in the pitch.
**Built:** `docs/claim-sheet.md` — sixteen limits stated first (L1: no measured APCER/BPCER; L2: synthetic never
headline; L4/L5: flash and 3-D masks; L9: the injection guard's partial scope; L11: provisional thresholds; L13:
nothing run on Android...), then eleven reproducible claims (C1–C11), each with its number, precision, command and
matching limitation, then the answers to the five questions a judge will ask, and a pre-talk checklist. Every number in it
comes from a test or from arithmetic; none comes from a real corpus, because there is none.

### #99: Deck and recorded video
Priority: 🟥 CORE · Type: Prototype · Blocked by: #97, #98

**Build:** Slides following the Bible's structure (threat model → the gap nobody names → architecture → the numbers → inclusion → the SBI proposal), plus a recorded run of the demo as insurance against hardware failure on the day.
**Done when:** you could present with no laptop of your own.
**Built (the content, not the artefacts):** `docs/deck-outline.md` — fifteen slides in the Bible's order with the line
to say, the on-slide evidence and the claim-sheet reference for each (the limits slide comes before the numbers
slide), the five questions to rehearse, and a ten-shot plan for the recorded demo. **No slides were made and no video
was recorded.**

### #100: MOSIP contribution package
Priority: 🟦 STRETCH · Type: Prototype · Blocked by: #99

**Build:** The SBI Liveness Profile proposal (Bible Part 41) as a short spec with JSON schemas, a reference implementation in the mock device, and a threat table — posted to the MOSIP community forum.
**Watch out:** no public RFC process was found (Bible Q12) — ask mentors where it should go before posting.
**Done when:** the work outlives the hackathon.
**Built (not posted):** `docs/sbi-liveness-profile.md` (the six additions, the wire formats, the client procedure, a
ten-row threat table, open questions O1–O5), `docs/schemas/sbi-liveness-profile.schema.json` (eight definitions; requests
use string scalars as the SBI does), and a working reference implementation. Core: `StreamTagChain` (HMAC-SHA256
chain from the nonce), `StreamKeyAgreement` (ephemeral P-256 ECDH with a device-signed answer that names the client
key it answered, HKDF-SHA256 — checked against RFC 5869 test case 1; P-256 rather than X25519 to stay on every
Android API level), `SignedStreamLock` (a `BindingLock`: a dropped, reordered, replaced or retimed frame breaks the
chain for good; a capture naming a stale or foreign head, or none, is refused; a stream run too long without a signed
checkpoint is untrusted) and `BindingLevel` (`HEURISTIC` vs `CRYPTOGRAPHIC`). Harness: `MockLivenessProfile`, the
device side, whose outputs `MockDeviceProfileConformanceTest` validates against the schema. **Not done:** nothing posts
it — Bible Q12 (how MOSIP accepts proposals) must be asked of the mentors first; no real device implements the profile.
**Wired since:** `Frame` carries the tag, time and checkpoint; `MjpegStreamReader` reads `X-SBI-LP-Tag`/`-Time`/
`-Checkpoint`; `DiscoveredDevice` reads the device-info `livenessProfile` and declares its capabilities;
`SbiFrameSource` runs the key agreement on the stream request (answer in the `X-SBI-LP-Answer` header), refuses a
session whose device advertises the profile but cannot prove it, and observes every frame in its reader thread;
`FaceFrameSource.streamLock()` hands the lock to the engine, `CaptureBinder` adds the `signedStream` lock (with a 5-frame
slack for frames in flight at the moment of capture), `BindingResult.level()` is `HEURISTIC` or `CRYPTOGRAPHIC` and
goes into the evidence's `binding.level`, and the policy flag `injection.requireSignedStream` turns a missing profile
into `STREAM_REQUIRED`. `MockFrameSource.livenessProfile(true)` runs the whole path with injection and wrong-head
attacks. Tests: `SbiLivenessProfileTest` (stub device incl. tampered tag, no answer, untrusted answer, plain device),
`StationWiringEngineTest` (cryptographic binding; injected frame; foreign head; hidden profile).
**Since built:** `illuminate` (`ILLUMINATE /illuminate`, `SbiFrameSource.illuminator()`) and `captureBurst` (`CAPTUREBURST /captureburst`, verified like a capture) have client calls, tested against the stub device. **Burst continuity since built:** `BurstSource`, `BurstEvidence`, a `burst` lock in `CaptureBinder` (three frames, every usable one must match; a failing burst fails closed). **Still open:** MockMDS has not been patched to speak any of this.

---

## Risk Register

| # | Risk | Early signal | Mitigation |
|---|---|---|---|
| R1 | Corpus too small or too uniform → meaningless numbers | Confidence intervals wider than the claims | #65 early, more phones and print finishes, synthetic factory (#89) |
| R2 | Desktop landmark path (#23) turns into a multi-day conversion rabbit hole | Day 1 of conversion produces wrong landmark ordering | Time-box it; fall back to an ONNX landmark model; worst case, demo Android (Mode D) |
| R3 | Real Registration Client integration eats the schedule | Build issues on the client repo in the first hours | ADR #74 decides scaffold vs real, early, in writing |
| R4 | Flash cue fails in the demo room's lighting | Low correlation during rehearsal | Abstain path already exists; lead with the injection demo instead |
| R5 | Model licence problem discovered late | A model with no LICENSE file in `models/` | #7 gate; MiniFASNet-only fallback |
| R6 | 3 fps device makes challenges unusable | Challenge timeouts on real hardware | #14 gating with eyes-closed-hold; test at 3 fps from Phase 1 onward |
| R7 | Over-claiming in the pitch | A number without an interval on a slide | #98 claim sheet; every claim cites a report section |
| R8 | Scope creep into extensions before the spine works | Phase 13 tickets started before #58 passes | Cut lines table; binding before differentiators, always |

---

## Future Work (out of scope for this build)

Certification testing (iBeta, FIDO); federated model improvement across centres; on-device model updates with anti-rollback in production; the national attack map with real-scale scan statistics; corneal reflection and eye-movement challenges beyond a research spike; PRNU sensor fingerprinting at scale; morph detection on the enrolled image.
