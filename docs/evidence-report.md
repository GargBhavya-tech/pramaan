# PRAMAAN evidence report

What has been run, what it produced, and exactly what each number is a measurement of. Written 2026-10-25. Every row says how it was
obtained, because a judge who finds one number that means less than it seems discounts all of them.

| Kind | Meaning | Sections |
|---|---|---|
| **Measured** | a real device, camera or phone ran the real code | 1 |
| **Simulated** | the real fusion and cue code ran on a rendered model of an attack | 2 |
| **Generated** | rows produced by a program from chosen score distributions, then fed to the real calibration and reporting code | 3 |

The first kind is evidence about the system. The second is evidence about the cues' behaviour on a model. The third shows that the
calibration and reporting pipeline works end to end; its error rates are properties of the generator (see 3.4).

---

## 1. Measured on real hardware

### 1.1 One real phone (2026-10-25)

Xiaomi 22071219AI, Android 14, 8 cores, arm64. `./gradlew -p android :pramaan-android:connectedDebugAndroidTest`, the default low-resource
profile (CPU path, at most 2 threads), one still face image (model costs, not a camera pipeline).

| Stage per frame | Median | 95th percentile |
|---|---:|---:|
| Face detection (YuNet) | 190 ms | 269 ms |
| Passive liveness (MiniFASNet, both models) | 21 ms | 36 ms |
| Java heap in use | 16 MB | |

About 210 ms per frame, 4.8 frames a second, against the 3 fps the SBI stream guarantees. Accelerator parity: XNNPACK and NNAPI both really
ran and agree with the CPU to 5.4e-7 and 1.4e-6 on the passive model's output. A second run reported by the author gave about 198 ms,
23 ms and 16 MB: the same order of magnitude, one device, and the run-to-run spread is real. Not measured: a full session on a phone, a
camera in the loop, battery, other phones. Detail: `docs/android-low-resource.md`.

### 1.2 A real virtual-camera veto (2026-10-25)

`RealStationInjectionTest` read this machine's real Windows camera list with OBS Virtual Camera registered. Under the shipped policy the
camera was recorded and the session accepted; with `vetoOnHostVirtualCamera: true` the session was refused (`INJECTION_DETECTED /
REJECT_VETO`). Evidence image: `docs/media/injection-veto.png`.

### 1.3 The first run on a real webcam (2026-10-25, this laptop, the author in front of it)

The reference device was pointed at the integrated webcam (`--camera 'Integrated Camera'`) and the desktop scaffold signed in against it.
It found **four faults that no still photograph could show**, all fixed in commit `b767bc2`:

| # | Fault | Cause | Fix |
|---|---|---|---|
| 1 | "Face verification is unavailable" (`ENGINE_ERROR`) | `FaceGate` keeps reporting "one face" for a short debounce after the face drops out, with no box; `FramePipeline` then passed the missing box to the quality check | a frame with no box is a no-face frame; regression test in `GateAndTrackerTest` |
| 2 | "Camera not detected" | `CaptureSession` starts the stream before the engine exists, but `SbiFrameSource.start` read the session's nonce | the stream gets its own fresh 32-hex id when no session exists |
| 3 | The screen showed the MOSIP sample photograph, not the person | the webcam only offers 30 fps modes; the device asked for 15, ffmpeg refused, and the device quietly kept serving the still photograph | request 30 fps; the device now waits for a real camera frame and refuses to start without one |
| 4 | The face was declared "lost" every half second and the evidence wiped | the full per-frame analysis took about 390 ms, longer than the stream interval, so the engine saw frame gaps over its 300 ms limit | the device's stream rate is now a setting (`--stream-fps`, 3 for this laptop) and the SFace embedding runs on one scored frame in three |

Per-frame cost on this laptop (i7-1355U, medians): decode, detect, quality and passive 124 ms; landmarks 86 ms; SFace embedding 120 ms;
moire about 10 ms. Before the fix the whole step was a median 390 ms (p90 461 ms): **about 2.5 frames a second, below the 3 fps the SBI
guarantees**. After it, a median 183 ms. The earlier "9 to 14 frames a second of headroom" in `docs/performance.md` covers only decode,
detection and passive scoring; it does not cover the whole pipeline.

**What did not pass, and why it matters.** With the engine keeping up, the session reached its decision and did not accept the author:

| Cue | Result on the live author |
|---|---|
| passive (MiniFASNet) | P(live) 0.95 to 0.997 |
| moire | P(live) **0.0002 to 0.0014** (a confident "screen") |
| fused, operator-strict profile | 0.06 to 0.33, reason `SCORE,TOO_FEW_CUES` |

Fusion asked for a challenge each time; the challenges timed out and three timeouts triggered the 15-minute operator lockout, which is
the designed behaviour. So on this webcam the moire cue reads a live face as a screen. That is a false-reject risk for genuine users, not a
security hole, and it is exactly the case the project says it must report: moire thresholds are marked PROVISIONAL and were calibrated on
simulated recaptures only. The weights were **not** changed to make a demo pass.

**Cause found (same day).** Eight instants of the live author were captured from the webcam three ways at once (lossless PNG, JPEG q:3 as
the device sends, JPEG q:1) and the moire cue's peakiness was computed on each, face crops 135 to 168 px wide, threshold 10.0:

| Version | Peakiness per frame | Mean |
|---|---|---:|
| Lossless PNG, straight from the camera | 19.1, 16.1, 12.5, 10.5, 11.4, 11.3, 13.1, 11.2 | 13.1 |
| JPEG q:3 (the device's encoding) | 19.1, 16.2, 14.9, 12.0, 11.6, 10.9, 13.0, 11.4 | 13.6 |
| JPEG q:1 (near lossless) | 20.1, 16.3, 13.1, 11.3, 12.0, 11.0, 13.5, 11.6 | 13.6 |

So the re-encoding is **not** the cause: every lossless frame is already above the threshold, and re-encoding barely moves it. The webcam's
own spectrum (sensor noise, sharpening, hair and beard texture) gives a live face a peakiness of about 10.5 to 20, against the 3 to 6 the
Bible assumed. (The live sign-in implied 17 to 24; the live session's frames differ a little from these.) The threshold of 10.0 is
PROVISIONAL and `MoireCue` says it is device-specific; this is the device-specific case. One person, one camera, one sitting: it is a
diagnosis, not a distribution.

**What would fix it properly.** A per-camera calibration that measures peakiness on live faces *and* on screen replays from the same
camera, then sets the threshold between them (`docs/research-plan-real-data.md`). Without replay data from this camera, moving the
threshold would only make the cue blind. Until then the cue should not carry a strong vote on an uncalibrated camera: its calibrated
probability here is not calibrated.

### 1.4 Flash cue against a real replay (E1)

Reported by the author from a test on a second laptop and phone at several brightnesses and distances: the live face followed the flash
code and the replay did not, with values moving by about 0.1 to 0.15 with brightness and distance. The recordings show faces and are not
in the repository. Detail: `docs/e1-results.md`.

---

## 2. Simulated: screen-replay red team

Run on 2026-10-25 against the simulated screen renderer (`SimulatedScreen`) and the real fusion code. No phone, no camera, no person.

### 2.1 Screen replay across 85 display pitches (`:harness:fusedRedTeam`)

One genuine face replayed across 85 grid pitches (a stand-in for phone pixel densities and distances). Accept at p >= 0.85, reject at
p <= 0.20, in between a challenge.

| Condition | Pitches | Accepted | Challenged | Rejected | Accept rate |
|---|---:|---:|---:|---:|---:|
| Without the flash cue | 85 | 4 (pitches 0.80, 1.00, 1.20, 2.00) | 0 | 81 | 4.7% |
| With the flash cue | 85 | 0 | 3 | 82 | 0.0% |

On the simulator, four alias pitches get past the moire cue and the flash cue closes them. This is the same set of pitches disclosed as
claim-sheet L19 (about 0.80, 1.00, 1.20, 1.60, 2.00).

### 2.2 Evolutionary fuzzer against the moire cue (`:harness:redTeam`)

61 generations over screen pitch, pixel aperture gap, backlight leak and motion blur.

| Attacker | Parameters | Peakiness (threshold 10.0) | P(live) | Outcome |
|---|---|---:|---:|---|
| Naive | pitch 1.15, leak 0.12, gap 0.08 | 11.2 | 0.326 | caught |
| Best evolved | pitch 1.00, leak 0.18, gap 0.12 | 3.3 | 0.982 | **finding R-01** |

Disclosed as claim-sheet L19. The moire cue is never the sole gate: it is fused with the passive model, flatness and the flash cue.

**Limits of section 2.** A rendered screen is not a phone: no real pixel structure, optics, glare or compression. The result says the
flash cue defeats the alias pitches in the model; whether it does on a real screen is E1 (1.4), reported by the author and not
independently reproducible from the repository.

---

## 3. Generated corpus, conformal calibration and the ISO/IEC 30107-3 report

### 3.1 How the corpus is made

`:harness:generateBenchmarkCorpus` (`GenerateBenchmarkCorpus.java`, `Random(42)`) writes `build/results/rows.jsonl`: **1,600 session
rows for 400 generated persons**, two bona fide and two attack rows each. The persons are labels, not people. Attributes are assigned by
rule: skin tone cycles through five Monk bands, glasses for every third person, device level L0 for every fourth, lighting drawn at random
from four conditions. Cue scores are drawn from logistic-normal distributions chosen by hand (for example genuine passive logit
2.4 +/- 0.8, screen-attack moire logit -2.2 +/- 0.8).

| | Bona fide | Attack |
|---|---|---|
| Sessions | 800 | 800, 200 each of `print-matte-a4`, `screen-phone-photo`, `screen-phone-video`, `mask-paper` |
| Final P(live) | `sigmoid(2.5 + 0.6 g)` | `sigmoid(-2.0 + 0.8 g)` |
| Device split | L0 and L1 | flash cue present at L1 only |

### 3.2 Conformal calibration (`:harness:calibrate`, output `build/calibration/`)

Split by person: train 816 sessions (204 persons), calibration 392 (98), held-out 392 (98), alpha 0.05, seed 1, groups by device level.
The tool labels its output **"CALIBRATION PROPOSAL (not applied)"**; nothing was written to `config/operating-points.json`.

| Measure (held-out) | Result | 95% Clopper-Pearson | N |
|---|---:|---|---:|
| BPCER, L0, rejected | 0.00% | 0.00% to 7.40% | 0 / 48 |
| BPCER, L1, rejected | 4.10% | 1.50% to 8.60% | 6 / 148 |
| BPCER, L1, not accepted first time | 0.00% | 0.00% to 2.50% | 0 / 148 |
| APCER mask-paper | 2.00% | 0.10% to 10.60% | 1 / 50 |
| APCER print-matte-a4 | 4.20% | 0.50% to 14.30% | 2 / 48 |
| APCER screen-phone-photo | 0.00% | 0.00% to 7.10% | 0 / 50 |
| APCER screen-phone-video | 0.00% | 0.00% to 7.40% | 0 / 48 |

Proposed cut-offs: a genuine resident is rejected with probability at most 0.0392 (L0) and 0.0476 (L1), against alpha 0.05; accept
cut-off -1.69 (99% quantile of 196 calibration attacks, no guarantee).

### 3.3 ISO/IEC 30107-3 report (`:harness:evaluate`, `docs/reports/evaluation-report.md` and `.html`)

All 1,600 rows. Worst-PAIS APCER 0.00% (0.00% to 1.83%, 0/200); BPCER 0.00% (0.00% to 0.46%, 0/800); BPCER20 and BPCER100 0.00%.
Fairness strata, all 0 errors: glasses 0/268, no glasses 0/532; good light 0/180, dim 0/190, backlit 0/208, fluorescent 0/222.

### 3.4 What these numbers are, and are not

The ISO report counts a session as accepted or rejected from the row's final P(live). In this corpus that P(live) is drawn **independently of
the cue scores and of any PRAMAAN model**. A bona fide row is rejected only at P(live) <= 0.20, about 6 standard deviations from its mean;
an attack row is accepted only at P(live) >= 0.85, about 4.7 deviations from its mean. Zero errors in 800 and 200 rows are therefore a
property of the constants in the generator, not a measurement of the system, and "zero demographic disparity" holds because the generator
gives every stratum the same distribution (skin tone, glasses and lighting do not change the draw). The calibration table is the one place
errors appear (4.1% at L1) because the calibrator refits cue weights and re-cuts the threshold on its own rows.

So the whole of section 3 shows the pipeline works: person-level splits, Clopper-Pearson intervals, per-stratum tables, BPCER20 and
BPCER100, the "proposal, not applied" rule. It does **not** show that PRAMAAN achieves these error rates. Statements for judges:

- OK: "we built a person-split conformal calibration and an ISO 30107-3 report generator, and ran them end to end on a generated corpus".
- Not OK: "0.00% BPCER", "zero APCER", "zero demographic disparity", or any 1,600-session or 400-person benchmark of real people.

To turn section 3 into evidence, replace `build/results/rows.jsonl` with rows from real sessions (the consented corpus in
`docs/research-plan-real-data.md`) and rerun the same three tasks. The pipeline needs no change.

---

## 4. Reproduce

```bash
./gradlew :harness:fusedRedTeam :harness:redTeam                 # section 2
./gradlew :harness:generateBenchmarkCorpus                       # 3.1, writes build/results/rows.jsonl
./gradlew :harness:calibrate :harness:evaluate                   # 3.2 and 3.3
./gradlew :harness:runProfileDevice --args="--port 4533 --camera 'Integrated Camera' --stream-fps 3"   # 1.3 (needs consent, the camera free)
./gradlew :desktop:runScaffold --args="--device-ca build/profile-device/device-ca.cer --ports 4533"
```

## 5. Open items

| Item | Why it is open |
|---|---|
| Moire false-reject on the real webcam (1.3) | the cue scores a live face as a screen on this camera; find the cause before any claim about real BPCER |
| Full-pipeline speed | 183 ms per frame on a laptop after the fixes; the phone figure covers detection and passive scoring only |
| Section 3 on real rows | needs the consented corpus; until then no APCER or BPCER is claimed |
| Section 2 on a real screen | needs a real phone replay: E1 repeated by more people and devices |
