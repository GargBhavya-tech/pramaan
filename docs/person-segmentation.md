# Person segmentation for the scene cues (Bible Part 43; Build Map #87)

The scene and illumination cues compare the live background with the stored backdrop, so every person pixel counted as
scenery is an error. Until now a single rectangle round the resident stood in for the person. This document records the
two ways of finding the actual shape, what each costs, and how far the numbers can be trusted.

**Honest status.** Everything measured here is on **synthetic** scenes (`SyntheticScene`, labelled SYNTHETIC in
`docs/claim-sheet.md`, L2). No real room, no real people, no real camera. The no-model route is built, wired
(`--segmenter backdrop`) and tested. The model route is built and tested with a fake network; **no model has been
chosen, pinned or licensed**, so it is not wired into the engine.

## The two routes

| | Rectangle (before) | Route A: backdrop subtraction | Route B: segmentation network |
|---|---|---|---|
| Class | `SceneContext` box | `BackdropSubtractionSegmenter` | `OnnxPersonSegmenter` |
| Needs | nothing | the stored backdrop (colour counts per grid cell, never a picture) | a model file: licence, hash pin, shipping |
| How | face grown to a body | a pixel whose colour the room never had *at that place* is a person; exposure matched by the gain that makes most pixels fit; cleaned; face and the column below it always person | per-pixel person probability, thresholded, cleaned; face always person |
| Finds a walker behind the resident | no | yes | yes (if the model sees a person) |
| Finds clothes the colour of the wall | whole box | only the face and the column under it | yes, in principle |
| Fails by | throwing away ~11% of the room, missing walkers | over-masking when lighting or the room changed a lot; missing wall-coloured clothes | wrong on people it was not trained on; one more model to certify |
| Cost per evaluated frame | nothing | about 15 x 19 200 table lookups | one network run |

## Measured on synthetic scenes

Three held-out rooms (seeds 101, 202, 303), eight situations each. `./gradlew :harness:segmentationBench`.
*recall* is the share of person pixels caught, *room kept* the share of real room pixels left usable.

| Situation | Rectangle recall / kept | Route A recall / kept |
|---|---|---|
| contrasting shirt, centred | 0.97 / 0.89 | 1.00 / 0.95 |
| person off to the left | 0.97 / 0.89 | 1.00 / 0.95 |
| far away (smaller) | 0.94 / 0.96 | 0.99 / 0.98 |
| someone walking behind | **0.64** / 0.87 | **0.99-1.00** / 0.94 |
| 15% brighter than at set-up | 0.97 / 0.89 | 1.00 / 0.93-0.95 |
| 25% darker than at set-up | 0.97 / 0.89 | 1.00 / 0.93-0.95 |
| a poster moved since set-up | 0.97 / 0.89 | 1.00 / 0.87-0.93 |
| shirt the colour of the wall | 0.97 / 0.89 | **0.82-0.91** / 0.95 |

Route A beats the rectangle everywhere except wall-coloured clothes, where it is a little worse on recall (the
rectangle masks the whole box whatever the colour, at the price of throwing away the room around it).

**What these numbers do not say.**
* The first version of Route A matched exposure by averaging the frame and kept only 8-40% of the room, because a blue
  shirt dragged the average. It was fixed (choose the gain that makes the most pixels fit the stored room) after seeing
  that result on the same kind of scenes, so the table is optimistic; the likelihood floor (0.012) was a first guess and
  was not tuned.
* Synthetic people are flat-coloured shapes on a flat wall. Real skin, hair, shadows, fabric texture, auto-exposure and
  auto-white-balance are all harder. Nothing here predicts the real-room error rate.
* Route B was run with **U²-Net-P** (`u2netp.onnx`, 4.4 MB; see below), the only model tried.

## Route B measured: U²-Net-P, same scenes

`./gradlew :harness:segmentationBench --args="--model models/u2netp.onnx --spec u2net320"`. Typical room, recall / kept:

| Situation | Rectangle | Route A (no model) | Route B (U²-Net-P) |
|---|---|---|---|
| contrasting shirt, centred | 0.97 / 0.89 | 1.00 / 0.95 | 1.00 / 0.95 |
| person off to the left | 0.97 / 0.89 | 1.00 / 0.95 | 1.00 / 0.82-0.96 |
| far away (smaller) | 0.94 / 0.96 | 0.99 / 0.98 | 0.99-1.00 / 0.95-0.99 |
| **someone walking behind** | 0.64 / 0.87 | **0.99-1.00** / 0.94 | **0.66** / 0.96 |
| 15% brighter / 25% darker | 0.97 / 0.89 | 1.00 / 0.93-0.95 | 1.00 / 0.95-0.96 |
| a poster moved since set-up | 0.97 / 0.89 | 1.00 / 0.87-0.93 | 1.00 / 0.83-0.93 |
| **shirt the colour of the wall** | 0.97 / 0.89 | **0.82-0.88** / 0.95 | **0.81-1.00** / 0.92-0.96 |

Cost per frame on this machine (640 x 480, CPU): Route A **4.5 ms**, Route B **about 430 ms**. The scene cues run on every
fifth frame, so B would spend a tenth of a second of every second of a 10 fps stream; on a slow station it matters.

What the run shows:
* **Wall-coloured clothes:** the model catches them in two of three rooms (1.00) and not in the third (0.81); the
  no-model route stays at 0.82-0.88. The model is better here, but not reliably, and it was not trained on this kind of
  picture.
* **A person walking behind:** U²-Net-P finds only the main subject, so it misses the second person completely (0.66,
  no better than the rectangle). **It is a salient-object network, not a person detector.** Route A finds the walker
  because anything the room never had is a person.
* **Moved poster / person off to one side:** the model sometimes takes a vivid object for the subject, which costs
  background (kept 0.82-0.87 in some rooms); Route A over-masks only the changed patch.
* **On a real photograph** (`MockMDS` face image, one person): the model cuts out head, shoulders and torso cleanly and
  leaves the wall and monitor alone. Route A cannot be tried on a single photograph; it needs the empty room.

Not tried: a true person-segmentation network (PP-HumanSeg, MODNet, selfie segmenters), which would probably find a
walker that U²-Net-P cannot. The model is on disk (gitignored) for evaluation only: it is not in the verified bundle,
and its training-data terms were not checked (`docs/licences.md`).

## Verdict

| | Route A, no model | Route B, U²-Net-P |
|---|---|---|
| Better at | speed (100x cheaper), walkers behind, no new file to ship, no model to license, adapts to any room it has a fingerprint of | wall-coloured clothes (often), real photographs without any stored room |
| Worse at | wall-coloured clothes; lighting or furniture changes beyond its exposure gain read as people | a second person; vivid objects mistaken for the subject; 430 ms per frame; a model to pin, certify and license |
| Needs | nothing beyond the backdrop already stored | model file in the bundle, hash pin, licence answer for training data |

**Recommendation:** use Route A. It is faster, cheaper to ship, and was better on the case that matters most to the scene
cue's honesty (a second person in the frame), and its weakness (wall-coloured clothes) is partly covered by the torso
column. The model route would only be worth its cost with a true person-segmentation network, and only if real-room
tests show wall-coloured clothes are common at the station. Both conclusions rest on synthetic scenes and one real photo.

## Using it

* `--segmenter backdrop` on the scaffold turns Route A on (default `none`: the rectangle). It needs `--backdrop`.
* `SceneContext.lastPeople()` returns the last mask, for diagnostics.
* Route B needs a model in the verified bundle (hash-pinned in `config/operating-points.json`, a row in
  `docs/licences.md`) and a `Spec` for its input and output. None is chosen yet.
