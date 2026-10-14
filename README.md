# PRAMAAN

Face liveness detection and Presentation Attack Detection (PAD) for the MOSIP Registration Client —
MOSIP Decode 2026, problem statement 04.

A liveness decision is only worth as much as the proof behind it, so PRAMAAN produces **signed,
re-checkable evidence** rather than a one-off pass or fail on the client:

- passive checks first, with unpredictable challenges only when needed, and no-action physics checks
  preferred over asking people to perform;
- detection of injected video and virtual cameras, which ISO/IEC 30107 does not cover;
- cryptographic binding of the liveness result to the signed biometric capture;
- an evidence package the server can re-score later with stronger models;
- inclusion by design, so elderly, disabled and infant residents are not excluded, with a second language
  (Hindi), icons and text-to-speech for residents who cannot read either one;
- an evaluation harness that reports APCER and BPCER per attack type, with confidence intervals, a DET plot
  per attack and a corpus summary — as one HTML page or as markdown;
- insider-fraud analytics: per-operator z-scores against the centre's own baseline, and a hard block on a
  resident capture that matches the logged-in operator's face;
- signed policy/model bundles with anti-rollback, so an operator cannot edit a threshold or replay an
  expired bundle offline.

## Status

Phases 0–11 are built: the liveness engine (`core`), the SBI client (`sbi`), the evaluation toolkit (`harness`)
and a registration scaffold with the face capture screen, operator login, supervisor approval and diagnostics
(`desktop`, ADR 0003). The Android AAR (Phase 12, #79) builds but has not yet run on a device. Phase 13 differentiators are built as
tested cues and tools but are **not yet wired into the engine's fusion**: the flash-reflection cue (#85), stereo depth
(#86), scene and illumination consistency (#87), the synthetic attack factory (#89), the pupil light reflex (#90),
and a reference implementation of the SBI Liveness Profile (#100). Phase 15's documents are written — red-team log,
demo script, claim sheet, deck outline — but the red-team day, rehearsals and slides need people and hardware.
**There is no real corpus yet, so there are no measured error rates**; `docs/claim-sheet.md` says what is and is not claimed.
See `PRAMAAN_Build_Map.md` for what each ticket built and `PRAMAAN_Final_Bible.md` for the design (Parts 0–48).

```
./gradlew build                      # everything, with tests
./gradlew :desktop:runScaffold       # the capture screen against MockMDS on 127.0.0.1:4501
```

`runScaffold` needs the models in `models/` (gitignored; see `docs/licences.md`) and a running MockMDS.

The Android engine (Mode D) is a separate build that needs the Android SDK (`android/local.properties` with
`sdk.dir`, or `ANDROID_HOME`):

```
./gradlew -p android :pramaan-android:check               # unit tests, lint, core's Android API check
./gradlew :core:publish && ./gradlew -p android :pramaan-android:publish   # AAR + core to build/repo
./gradlew -p android :pramaan-android:connectedDebugAndroidTest            # on a phone or emulator
```

## Licence

MPL-2.0 (to match the MOSIP ecosystem).

## Files

- `PRAMAAN_Final_Bible.md` — the design: why, with the math, the threat model and the research log.
- `PRAMAAN_Build_Map.md` — the build: 100 tickets in strict order, each with Build / Watch out / Test / Done when.
- `CLAUDE.md` — working conventions and the constraints that are easy to get wrong.
