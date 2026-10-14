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
* No real model has been run, so there is no Route B column.

## Using it

* `--segmenter backdrop` on the scaffold turns Route A on (default `none`: the rectangle). It needs `--backdrop`.
* `SceneContext.lastPeople()` returns the last mask, for diagnostics.
* Route B needs a model in the verified bundle (hash-pinned in `config/operating-points.json`, a row in
  `docs/licences.md`) and a `Spec` for its input and output. None is chosen yet.
