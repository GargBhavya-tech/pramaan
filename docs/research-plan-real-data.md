# Research plan: the first real data, and the challenge-conditioned verifier

**Status: a plan, not a result.** Nothing in this file has been run on a real face. The point of writing it down is that
every claim below is *falsifiable*: it says what would show it wrong.

## 1. Why the next step is data, not another model

Everything measured so far is synthetic or hand-set (`docs/claim-sheet.md`, L1, L8, L11, L18-L20). A bigger model trained on
our own synthetic attacks would learn the simulator. The cheapest honest evidence is **five minutes on one laptop**, which
needs no new hardware: the laptop has a screen (a light, `--flash-screen primary`) and a webcam.

### Experiment E1: the flash cue on a real face (laptop, ~5 minutes, needs one person)

| Step | What happens | What is recorded |
|---|---|---|
| 1 | Resident sits 40-60 cm from the screen in normal room light, looks at the screen | webcam frames, with the host time of each |
| 2 | The screen plays the engine's own random colour sequence (`FlashSchedule`, 6 colours, safe rate) | the colour shown at each frame time (host's word: the screen is not attested) |
| 3 | Repeat 10 times, different random sequences | 10 genuine runs |
| 4 | Hold a phone playing a recorded video of the same person to the webcam, same 10 sequences | 10 replay runs |
| 5 | Run `FlashReflectionCue` on each | rho (correlation), lag, nose-vs-cheek ratio, pLive per run |

**Predictions (the Bible's worked examples, Part 8.6):** genuine rho around 0.8; phone replay rho below 0.3.
**What would falsify it:** genuine runs not separating from replays (rho overlapping), or a laptop screen being too weak in
ordinary room light (the cue abstains on most genuine runs). Either is a useful result: it says the screen is not enough
and a dedicated light is needed.

**What it cannot show:** any error rate. Ten runs each give an APCER/BPCER interval wider than 25%
(`Binomial`: 0 of 10 still allows a true rate above 25%). It shows whether the physics works at all, once.

**What is needed:** a capture path from the webcam to the engine. `SbiFrameSource` reads an SBI device; the laptop camera
is not one. Options, cheapest first: (a) record with the OS camera app while the screen flashes and feed the file to
`harness:runCorpus`; (b) run `ProfileStubDevice` fed by a capture tool; (c) a small DirectShow/Media Foundation reader.
`ffmpeg` is installed on the development machine (checked), so a webcam recording needs no new capture code:
`ffmpeg -f dshow -i video="<camera name>" ...` records while the screen flashes. What does not exist yet is the small runner
that plays the colours, logs the time each one started, and starts and stops the recording together, and the analyser that
turns the recording back into per-frame face regions for `FlashReflectionCue`. Both are a few hours of work, and the
experiment still needs a person in front of the camera, who must agree to be recorded (`docs/consent-form.md`): the
assistant will not record anyone's camera unasked.

## 2. The challenge-conditioned verifier (idea 5)

**Idea.** Today the flash cue computes a correlation between *the colours we emitted* and *the chroma the face returned*.
That is a hand-built detector. A learned verifier would take the challenge itself as an input, together with the frame
sequence, and answer "is this video consistent with *this* light sequence?", like checking a one-time pad on light. A
real-time deepfake cannot know the sequence in advance, and a replay of a recording is consistent with a different one.

**What it adds over the correlation.** Spatial structure (nose brighter than cheeks, specular highlights moving with the
colour, the hairline), temporal structure beyond a lag search, and non-linear response (skin tones, makeup).

**What it costs.** A labelled corpus of real flash recordings with attacks, which does not exist; the synthetic factory
has no face-light renderer, so synthetic training would teach it the simulator.

**Why not start with it.** The hand-built cue has not yet been shown to work on a real face (E1). If E1 fails, a learned
verifier would be learning from the same weak signal. If E1 succeeds, the next question is whether a learned version beats
the correlation on a held-out set, and that needs the corpus.

**Falsifiable claim, for when there is data:** on a held-out set of at least 60 genuine and 60 replay/injection runs per
device class, the learned verifier's APCER at BPCER 5% (conformal cut-off, `ConformalThreshold`) is lower than the
correlation cue's, with non-overlapping 95% intervals. If the intervals overlap, the extra model is not justified.

## 3. What already exists to make the above cheap

* `PseudoLabeler`: flash-confident sessions become labels, disagreements go to a human, the teacher's error is estimated
  from a random audit with an interval, and a set is not called trainable until that error is bounded.
* `FusionLearner` and `ConformalThreshold`: weights learned in the engine's own shape, and a cut-off with a guarantee.
* `RedTeamFuzz`, `StationSelfTest`: find the cue's holes (R-01) and the false rejections (R-02) before the real day.
* `docs/corpus-plan.md`, `docs/consent-form.md`, `docs/hardware-testing-guide.md`: how to record people properly.
