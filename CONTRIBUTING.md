# Contributing to PRAMAAN

PRAMAAN is licensed under the **Mozilla Public License 2.0** (`LICENSE`), the same licence as MOSIP's own repositories, so
the source can be provided to MOSIP, and contributions come back under the same terms.

We follow the **MOSIP Code of Conduct** (https://docs.mosip.io/ , the community section). Be kind; assume good faith.

## Build and test

Requirements: JDK 24 (Temurin in CI), Gradle via the wrapper, `ffmpeg` on the PATH for some harness tests.

```bash
./gradlew build                  # everything, with tests (a few minutes)
./gradlew :desktop:test          # the station screens, rendered offscreen (needs a graphics toolkit)
./gradlew :harness:test          # the evaluation harness, the red team, the calibrator
```

Tests that need files that are not in the repository (face models, the MockMDS fixtures) skip themselves. Do not make a
test pass by deleting it, and do not loosen `-Xlint:all -Werror`.

## Rules that are easy to get wrong

These are in `CLAUDE.md` in full; the ones a contributor most often breaks:

- **No thresholds in code or in the policy YAML.** Every fusion number lives in `config/operating-points.json`, bound to the
  SHA-256 of each loaded model. Add or swap a model and the engine refuses to start until the table is regenerated.
- **Liveness fails closed.** A session exists only via `EngineBundle.newSession`. Never add a path that skips liveness on
  an engine error, and never let evidence survive a face loss or a change of `trackId`.
- **The resident never sees a score or a cause of rejection.** Guidance for quality problems, silence for security problems.
  New operator or resident strings go in `messages.properties` **and** `messages_hi.properties` (a test fails if one is
  missing) and must not contain score-like words.
- **Physiological cues never count against a resident.** Absence is "unknown", not "spoof".
- **Core is headless and has no wall clock.** Time-dependent code takes an explicit timestamp so tests can simulate time.
- **Java 11 bytecode in `core`**, so it runs on Android and inside the Registration Client's runtime. No records, no
  Java 12+ APIs there (`./gradlew -p android :pramaan-android:check` enforces the Android API surface).
- **ONNX Runtime is pinned to 1.20.0** on purpose; read `tools/versions.md` before bumping.
- **SBI requests: every scalar is a JSON string.** Numbers are rejected by the device.

## Never commit

Biometric samples, corpora, model binaries, keys or certificates with private keys (see `.gitignore`). Test fixtures that
show a real person are gitignored on purpose. If you collect real data, follow `docs/consent-form.md` and
`docs/corpus-plan.md`: nobody is recorded without consent.

## Claims

A claim in a document needs a number, an interval or "exact", a command that reproduces it, and its limit, in
`docs/claim-sheet.md`. Synthetic results are labelled SYNTHETIC and are never headlines. If your change moves a claim,
change the sheet in the same commit.

## Reporting a weakness

Open an issue, or add an entry to `docs/red-team-log.md` with the attack, the result, and whether it is fixed, disclosed or
open. We would rather find our own holes than have someone else do it.
