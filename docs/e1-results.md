# E1: does the flash cue separate a live face from a replay?

**Status: inconclusive. The cue is not shown to work, and not shown to fail.**

E1 is the one real experiment the project owes (`docs/research-plan-real-data.md`, Bible Part 8.6): flash a random colour code on the
screen, record the camera, and test whether a live face's colour follows the code while a replay's does not. The design predicts live
faces near 0.8 correlation and replays below 0.3. Those are **predictions**, not measurements.

## The first run, on 2026-10-25

One laptop (its integrated webcam), one person, one phone; 5 live recordings and 5 phone-replay recordings, made with the person's
agreement. **The replay recordings were set up badly, so they are not a valid test and their numbers are not used as evidence:**

- The phone was propped low in the picture, so the face on it was about 40 pixels wide against about 120 for the live face. The cue was
  measuring a tiny face region.
- The phone's screen was at full brightness. The glass reflected the room and the laptop's own flash, so the camera saw reflection and
  not a clear face.
- The recorder records frames without the engine's quality gate. A replay whose face is this small or washed out would be rejected by
  the engine's own face-quality check before the flash ever mattered, so the run did not model an attack that would get that far.

The live recordings were fine: the face was found in every analysed frame and its colour followed the code, with a steady 100 to 200 ms
delay from screen to camera. That shows the physics works on a real face; it does not show how well it separates from a replay.

## Reported by a second person (not verified)

The author reports that a friend, on a different laptop and phone, tried the same experiment at different screen brightnesses and
distances and found results consistent with the design's predictions, with an error of about 0.1 to 0.15 depending on distance and
brightness. **We do not have those recordings and have not seen the numbers, so this is reported, not verified, and nothing in this
repository relies on it.**

## What is needed to settle it

A proper re-run, at least 10 live and 10 replay recordings, with the replay's face about the same size and position in the picture as a
live face, the phone at moderate brightness (not full), more than one person and more than one laptop and phone, with each run's setup
written down (screen type, brightness, distances, light). Even then it is ten runs, not a measured error rate: zero errors in ten would
still allow a true rate above 25%.

## What this changes in the project

The flash cue is described as **designed to** separate replays, not as having been shown to. The 20.7-bit unpredictability of the colour
code is a property of the generator and does not depend on E1. The cue is one of six, and none of the other cues depends on it.

## Reproduce

```bash
./gradlew :desktop:flashExperiment --args="--list-cameras"            # do not pick the OBS Virtual Camera
./gradlew :desktop:flashExperiment --args="--camera '<camera name>' --label genuine --out build/e1/g01 --consent-given"
./gradlew :desktop:flashExperiment --args="--camera '<camera name>' --label replay  --out build/e1/r01 --consent-given"
./gradlew :harness:analyseFlash --args="--dir build/e1"               # add --latency-ms 200 to declare a steady delay
```

The recordings are biometric data. They stay in `build/e1/` (git-ignored), are not published, and are deleted when the person says so.
