# Test data

**There is no real biometric data in this repository, and none is needed to build it, run its tests or try its tools.** No
account, password, token or API key exists anywhere in the project. What stands in for them is below.

Limits first: because no real corpus exists, nothing here supports an error-rate claim. Test data is for checking that the code
does what it says, never for reporting how well it catches real attacks (`docs/claim-sheet.md`, L1, L2).

## Accounts and credentials

| What | Value | Where it comes from |
|---|---|---|
| Operator ID (scaffold default) | `operator-1` | `ScaffoldConfig` default; change with `--operator` |
| Supervisor ID (scaffold default) | `supervisor-1` | pre-filled in the supervisor fields |
| Resident registration ID (default) | `10001100010000120260923` | `ScaffoldConfig` default; change with `--subject` |
| Centre ID | `10001` | `--centre` |
| First sign-in | **Enrol the operator's face** with the "First time? Enrol my face" button; the face template is stored under `build/scaffold/operators` (gitignored) | scaffold |
| Passwords, tokens, API keys | **none exist.** Trust is by signature: a device's responses verify against a CA certificate pinned from configuration | `docs/api/README.md`, section B |
| Device CA for MockMDS | `sbi/testdata/mockmds-rootca.cer` (MockMDS's public development root; certificate only, no private key) | exported from MockMDS's public keystore |
| Device CA for the reference device | made fresh by `DevelopmentCa` with the JDK's `keytool` into `build/...`; nothing is committed | `harness/.../mock/DevelopmentCa.java` |

## What is committed

| File | What it is | Biometric? |
|---|---|---|
| `sbi/testdata/discovery.json`, `deviceinfo.json`, `rcapture-face.request.json`, `capture-meta.json` | real responses and a request captured from MOSIP's MockMDS (2026-09-22) | no |
| `sbi/testdata/mockmds-rootca.cer` | MockMDS's development root CA certificate | no |
| `core/src/test/resources/models/smoke_affine.onnx` | a synthetic model, y = 2x + 1, made by `tools/make_smoke_model.py` | no |
| `config/pramaan-policy.yaml`, `config/operating-points.json` | the shipped policy and the provisional operating-point table | no |
| `docs/schemas/sbi-liveness-profile.schema.json` | JSON schemas for the Liveness Profile messages | no |
| `docs/api/examples/profile-device-session.json` | a decoded real exchange with the reference device (synthetic photograph) | no |
| `docs/media/*.png` | screens rendered offscreen from the UI code | no (a camera placeholder only) |

## What is deliberately **not** committed (and why)

| File | Why |
|---|---|
| `sbi/testdata/rcapture-face.json`, `sbi/testdata/stream-sample.mjpeg` | they hold **MOSIP's public test photograph of a real person**; gitignored on purpose. Regenerate with `python tools/capture_sbi_fixtures.py` against a running MockMDS. Tests that need them skip themselves |
| `models/*.onnx` | licensing; see `models/README.md` |
| `/corpus/`, `/data/`, `harness/out/` | an evaluation corpus is biometric data and must never be committed (`docs/corpus-plan.md`, `docs/consent-form.md`) |

## Where the test inputs come from

Every test input that is not a file above is **generated in the test or by a tool**, so it is reproducible and carries no person:

| Generator | Makes | Used by |
|---|---|---|
| `harness/.../factory/SyntheticScene` | a room with flat-coloured stand-in people, a known backdrop and exposure | the scene, light and segmentation tests and the station self-test |
| `harness/.../factory/ScreenRecapture`, `PrintSimulator`, `SyntheticNoise` | a simulated screen replay (moire from the display pitch), a simulated print (halftone, blur), sensor noise | the red-team fuzzers and the synthetic attack factory |
| `harness/.../mock/MockFrameSource`, `MockFaults` | a device that replays frames and can inject faults: a dropped frame, a delayed capture, an old capture, a swapped face, a broken chain | `BindingAttacksTest`, the engine tests |
| `harness/.../mock/MockLivenessProfile`, `ProfileStubDevice` | the Liveness Profile's keys, tags, checkpoints, emission logs and bursts; a runnable device that serves one still photograph | `SbiLivenessProfileTest`, `ProfileStubDeviceTest`, `docs/api` |
| `core` test fixtures (`testFixtures`) | a fake clock (`TestClock`) so time-dependent code is tested by simulating time | everywhere |

Synthetic attacks come from `harness/factory` and are labelled **SYNTHETIC** wherever they appear in a report; they are never a
headline result. Matching a formula is not matching real phones (claim C9).

## Trying the tools with no data of your own

```bash
./gradlew :desktop:showcase          # the screens with a simulated session (no camera, device or models)
./gradlew :harness:fusedRedTeam      # which synthetic display pitches the whole fusion accepts, with and without the flash cue
./gradlew :harness:runProfileDevice --args="--port 4533 --frame any.jpg"   # a device that speaks the Liveness Profile; any JPEG will do
bash docs/api/examples/try-the-device.sh                                   # calls every endpoint of it and decodes the answers
```

## If you do collect real data

Follow `docs/consent-form.md` and `docs/corpus-plan.md`: nobody is recorded without a person present and their consent, nothing is
committed, and `docs/research-plan-real-data.md` (experiment E1) is the first protocol: about 20 six-second runs, a laptop camera
and the screen as the flash light, with the recorder and the analyser already built.
