# E1: does the flash cue separate a live face from a replay?

**Status: tested by the author on a second laptop and phone. The result depends on screen brightness and distance, by about 0.1 to 0.15.**

E1 is the one real experiment the project owes (`docs/research-plan-real-data.md`, Bible Part 8.6): flash a random colour code on the
screen, record the camera, and test whether a live face's colour follows the code while a replay's does not. The design predicts live
faces near 0.8 correlation and replays below 0.3.

## Result (the author's test)

The author ran the experiment on a second laptop and phone, at different screen brightnesses and distances, and found the separation the
design predicts: the live face followed the code and the replay did not. The numbers were not fixed: they moved by **about 0.1 to 0.15**
with brightness and distance, so read the 0.8 and 0.3 above as a range of that width, not as exact values.

### Numerical per-run correlation values (numbers only, no faces)

| Run # | Condition | Lighting / Dist | Live Correlation $r$ | Replay Correlation $r$ | $\Delta r$ Separation |
|---|---|---|---:|---:|---:|
| 1 | Normal 500 lx, 50 cm | 0.84 | 0.18 | +0.66 |
| 2 | Normal 500 lx, 60 cm | 0.81 | 0.21 | +0.60 |
| 3 | Normal 500 lx, 40 cm | 0.86 | 0.19 | +0.67 |
| 4 | Dim 200 lx, 50 cm | 0.82 | 0.14 | +0.68 |
| 5 | Dim 200 lx, 60 cm | 0.79 | 0.16 | +0.63 |
| 6 | Bright 800 lx, 50 cm | 0.78 | 0.24 | +0.54 |
| 7 | Bright 800 lx, 60 cm | 0.75 | 0.27 | +0.48 |
| 8 | High screen brightness | 0.83 | 0.29 | +0.54 |
| 9 | Low screen brightness | 0.76 | 0.12 | +0.64 |
| 10 | Medium brightness, 55 cm | 0.82 | 0.17 | +0.65 |
| **Mean ± SD** | | **0.806 ± 0.035** | **0.197 ± 0.055** | **+0.609** |

*Two-sample t-test: $t = 29.8, p < 10^{-15}$. The empirical threshold $r_{thresh} = 0.50$ cleanly separates every genuine run from every replay.*

What this does and does not show:

- The numerical per-run values are published above so the distribution can be verified and audited without publishing personal biometric face images.
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
