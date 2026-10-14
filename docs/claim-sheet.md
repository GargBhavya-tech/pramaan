# PRAMAAN claim discipline sheet (Build Map #98)

One page. Every claim the pitch may make, the number behind it, its interval, how to reproduce it, and the
matching limitation. **Limits come first**, because a judge who finds one overstated claim discounts everything
else. A claim that has no number here is not made.

## 1. What we do not claim (read aloud before the numbers)

| # | We do **not** claim | Why | Where it is recorded |
|---|---|---|---|
| L1 | Any measured APCER, BPCER or ACER on real presentations | **No corpus exists yet.** `config/operating-points.json` says `"provisional": true` and "NOT MEASURED". Every threshold and weight is hand-written from the Bible. | `docs/corpus-plan.md`, Build Map #65 |
| L2 | That headline numbers come from synthetic attacks | They never do. The synthetic factory (`harness/factory`) is for training and stress tests; reports label its rows SYNTHETIC. | Bible Part 45 |
| L3 | ACER | ISO/IEC 30107-3 deprecated it. We report APCER per PAIS and BPCER separately, each with a confidence interval. | `CLAUDE.md` |
| L4 | That flash catches a projected face on a 3-D mask | Projection onto a mask is 3-D and reflects the flash physically. It is **unquantified** against flash. | Bible Part 8.6 (R4) |
| L5 | That the flash cue works without a resident-facing light | Desktop resident capture has none unless a second monitor is named with `--flash-screen`; without one the flash is never drawn and the cue abstains. The wiring is tested with a **fake light** and synthetic faces; the real window has not been looked at on a screen. | Bible 8.6; `FlashPhase`, `FlashWindow` |
| L6 | rPPG on the SBI stream | Compressed M-JPEG erases the pulse; rPPG abstains by default, and is never evidence against a resident. | Bible Part 8.5 |
| L7 | That abstention is acceptance | A cue that cannot run says "unknown". Physiological cues (blink, pulse, pupil reflex) **never count against a resident**. | Bible Part 9 |
| L8 | That the corpus is representative | It will be small. Intervals will be wide, and per-group intervals wider. "Zero failures in 60 attacks" still allows a true rate near 6% (two-sided 95%, Clopper–Pearson). | `Binomial` |
| L9 | A device-level injection defence | The veto matches the device's **reported** make/model/code, and (on Windows) the OS's camera list, against a built-in denylist; a signed digitalId that fails its chain is a veto. A driver can pick any name, no registered device was tried, and there is no PRNU. Timing statistics are recorded in the evidence but **never acted on** (they need a real timing corpus to calibrate). | Build Map #84 |
| L10 | That the colour-texture cue votes | It abstains `UNTRAINED` until its weights are trained on the corpus. | Bible 8.1 |
| L11 | That the newer cues are calibrated | Stereo, pupil reflex, scene fingerprint, light direction, white point and flash all carry `PROVISIONAL` thresholds taken from the Bible's worked examples. They are wired into the engine's fusion, but each runs only when the station has what it needs (a resident-facing light, a second camera, an iris camera, a stored backdrop) — and **no station has been built with any of them**: the wiring is tested with fakes. The fusion weights in `operating-points.json` are hand-written. | the cue classes; `StationWiringEngineTest` |
| L12 | That this is the MOSIP Registration Client | The desktop app is a **scaffold** (ADR 0003). Integration depth into the real client is a decision record, not a done thing. | `docs/adr/0003-integration-depth.md` |
| L13 | Anything on Android | The AAR builds and has unit/on-device smoke tests; it has **not run a liveness session on a device**. Android clients never see preview frames, so Mode D (inside the SBI app) is required. | `README.md`, `CLAUDE.md` |
| L14 | Hindi quality | The Hindi catalogue is complete but **not reviewed by a native speaker**. | Build Map #91 |
| L15 | Model licences are settled | FLIP and PSCF licences are open (Part 30 Q16). The models in use are listed in `docs/licences.md`. | Part 30 |
| L17 | That the SBI Liveness Profile works with any real device | The client and a mock device implement it and agree; **no certified device does**, and MockMDS has not been patched. Until one does, every real capture is `HEURISTIC`. | `docs/sbi-liveness-profile.md` |
| L16 | That MOSIP will accept the SBI Liveness Profile | How MOSIP takes spec proposals is unknown (Part 30 Q12). We ask the mentors before posting. | `docs/sbi-liveness-profile.md` |

## 2. What we do claim, with the number that backs it

Each of these is a property of the code, reproducible with one command, not a claim about real-world error rates.

| # | Claim | Number | Interval / precision | Reproduce | Matching limitation |
|---|---|---|---|---|---|
| C1 | Challenges cannot be pre-recorded as a clip library | Flash sequence: 6 colours × 8 steps = 1 679 616 sequences = **20.7 bits**; action steps **3.52 bits** each with every kind, **1.86 bits** on a 3 fps desktop without a resident-facing display; policy floor 13 bits | exact (combinatorics) | `./gradlew :core:test --tests '*ChallengeGeneratorTest'` | Entropy is counted over outcomes a verifier can tell apart; the number of steps is deliberately **not** tied to the score (Bible 47.8). L5 applies to flash. |
| C2 | Challenge draws are uniform | χ² uniformity over 100 000 draws, every parameter | critical value at p = 0.001 | same test | Uniform draws say nothing about attacker success rates (L1). |
| C3 | Photosensitivity is respected | At most 3 colour changes per second; steps ≥ 333 ms; no saturated red in the palette; checked on 900 generated sequences at 3, 10 and 30 fps | exact | `./gradlew :core:test --tests '*FlashScheduleTest'` | WCAG 2.3.1 is a general-flash rule; it is not medical advice. |
| C4 | The insider-fraud signal is statistically calibrated | 6 PAD failures in 40 sessions at a 2 % centre baseline → **z ≈ 5.87**, flagged; ±3 SD funnel limits | exact | `./gradlew :core:test --tests '*FraudAnalyticsTest'` | Needs real baselines per centre; small centres have wide limits by design. |
| C5 | The transparency log follows the standard | Merkle tree heads and proofs match the Certificate Transparency test vectors | exact | `./gradlew :core:test --tests '*MerkleLogTest'` | A log makes omission detectable; it does not prove a decision was right. |
| C6 | Absence of a reflex never rejects a resident | Physiological cues have `wNeg` ignored for residents; the pupil reflex cue **only ever votes for life** | by construction | `RiskFusionTest`, `PupilLightReflexCueTest` | Positive-only evidence is weak evidence by itself. |
| C7 | The capture-swap attack is closed | Nonce echo, frame hash chain, identity continuity and the SBI chain are all checked before `ACCEPT`; each negative case is a test | by construction | `./gradlew :harness:test --tests '*BindingAttacksTest'` | Closes the swap for the **desktop SBI** path; Android needs Mode D. The preview remains unsigned. |
| C8 | Liveness fails closed | A session exists only via `EngineBundle.newSession`, which refuses a bad policy, table or model | by construction | `./gradlew :core:test --tests '*EngineBundleSignedTest'` | Failing closed means a broken install stops registrations (the exception flow covers it, audited and dual-controlled). |
| C9 | The recapture simulator is physically grounded | A simulated screen shows a spectral peak within 0.01 cycles/pixel of the predicted alias frequency; a halftone shows its screen period | ±0.01 cycles/pixel | `./gradlew :harness:test --tests '*SyntheticAttackFactoryTest'` | Matching a formula is not matching real phones. The KS comparison against real attacks (`SimToRealReport`) is **not yet run** — no real attacks exist (L1). |
| C10 | The stereo cue separates flat from 3-D | On synthetic geometry with the Bible's camera (f = 900 px, b = 80 mm, Z = 500 mm): real-face relief ≈ 25 mm, flat print ≈ 0 mm | ±4 mm in the test | `./gradlew :core:test --tests '*StereoDepthCueTest'` | Synthetic landmarks, no real second camera, no corpus (L1, L11). |
| C11 | The evidence is re-checkable | A stored evidence record verifies against its capture, allow-list and sample crops with one CLI command (`:harness:verifyEvidence`); a newer model can re-score stored crops (`:harness:rescanEvidence`) | exact | `./gradlew :harness:test --tests '*EvidencePackagerTest'` | The server re-check needs the encrypted sample crops, which the server must be allowed to keep. |

## 3. How to answer "how do you know?"

| If asked | Answer | Do **not** say |
|---|---|---|
| "What is your APCER?" | "We have not measured one on real attacks yet. Here is the plan, the consent form and the interval we would report." | Any percentage. |
| "Does it stop deepfakes?" | "Injection is vetoed when the device identifies as a virtual camera. The flash correlation would catch a stream that cannot answer our random colours, but that cue is built and tested on synthetic data only and needs a resident-facing light." | "It stops deepfakes." |
| "Is it fair across skin tones?" | "Physiological cues are positive-only precisely because they fail darker-skinned residents for innocent reasons. We report BPCER per group with intervals; the corpus is too small to claim parity yet." | "It is unbiased." |
| "Does it work on Android?" | "The engine compiles to an AAR and passes its unit tests. It has not run a session on a device. Android needs the engine inside the SBI app." | "Yes." |
| "Can a mask pass?" | "A projected-face mask is a known, unquantified gap for flash; texture and scene cues are the mitigation. It is on the red-team list." | "No." |

## 4. Before every talk

- [ ] Every number on a slide appears in section 2, with its interval or "exact".
- [ ] Section 1 is the first thing said, or on the slide before the numbers.
- [ ] No slide shows a synthetic result as a headline.
- [ ] `config/operating-points.json` still says `provisional`, and the deck says so.
- [ ] If a real corpus now exists, L1, L8, L9 and L11 are rewritten from the report, not from memory.
