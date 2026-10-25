# E1: does the flash cue separate a live face from a replay?

**Status: tested by the author on a second laptop and phone. The result depends on screen brightness and distance, by about 0.1 to 0.15.**

E1 is the one real experiment the project owes (`docs/research-plan-real-data.md`, Bible Part 8.6): flash a random colour code on the
screen, record the camera, and test whether a live face's colour follows the code while a replay's does not. The design predicts live
faces near 0.8 correlation and replays below 0.3.

## Result (the author's test)

The author ran the experiment on a second laptop and phone, at different screen brightnesses and distances, and found the separation the
design predicts: the live face followed the code and the replay did not. The numbers were not fixed: they moved by **about 0.1 to 0.15**
with brightness and distance, so read the 0.8 and 0.3 above as a range of that width, not as exact values.

What this does and does not show:

- The recordings show faces, so they are **not in this repository**. This is the author's reported result; a reviewer cannot re-check it
  from the repository alone, and the per-run values are not listed here.
- It is a small number of runs on one pair of devices. It is not an error rate, and it says nothing about other phones, screens or rooms.
- The cue needs a light facing the resident. It is one of six cues, and none of the others depends on it.

## Setup notes that matter

- Screen brightness matters twice. At full brightness a phone screen reflects the room and the laptop's own flash, and the camera sees
  glare, not a face. At low brightness the colour is too weak to measure. A middle setting works.
- Distance matters: the replay's face has to be about the same size and position in the picture as a live face (about 120 pixels wide at
  the camera's resolution), or the cue measures a tiny region.
- The recorder records frames without the engine's own face-quality gate, so a recording the engine would reject can still be analysed:
  check the face is found in every frame.

## Reproduce

```bash
./gradlew :desktop:flashExperiment --args="--list-cameras"            # do not pick the OBS Virtual Camera
./gradlew :desktop:flashExperiment --args="--camera '<camera name>' --label genuine --out build/e1/g01 --consent-given"
./gradlew :desktop:flashExperiment --args="--camera '<camera name>' --label replay  --out build/e1/r01 --consent-given"
./gradlew :harness:analyseFlash --args="--dir build/e1"               # add --latency-ms 200 to declare a steady delay
```

The recordings are biometric data. They stay in `build/e1/` (git-ignored), are not published, and are deleted when the person says so.
