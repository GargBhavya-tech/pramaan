# Demo video: a 3-minute storyboard

Everything below can be shown truthfully with what exists today. It deliberately avoids anything that needs a live sensor,
a phone or a certified device, because those were never run (`docs/claim-sheet.md` L5, L13, L17). The longer scene-by-scene
script for a live demo is `docs/demo-script.md`; this one is the recorded submission video.

**Rules for the recording**
- Nobody appears on camera without having agreed to it, and nobody is recorded unasked.
- The scaffold runs against **MockMDS**, which replays an image file. Say so on screen. Never describe it as a live sensor.
- Show the limits slide before any number. Never show a synthetic result as a headline.
- Screen capture at 1920x1080, 30 fps; record one scene at a time so a scene can be redone alone.
- Close anything private (mail, chat, browser tabs) before recording. Hide your username in terminal prompts.

| Time | Scene | On screen | Say (about 150 words a minute; this is the whole script) |
|---|---|---|---|
| 0:00-0:15 | **Title and problem** | Pitch slide 1, then slide 2 | "MOSIP enrols a resident's face through a certified device. Liveness is judged on the live preview. But the preview is unsigned and the capture that is stored is signed, and nothing links the two." |
| 0:15-0:40 | **The gap** | Pitch slide 2 diagram: unsigned preview arrow, signed capture arrow, the swap | "So an attacker who controls the preview can pass liveness with one face and have another enrolled. On Android the client never even sees the preview. PRAMAAN closes that gap, and leaves proof a server can re-check later." |
| 0:40-1:20 | **The station** | Run `./gradlew :desktop:runScaffold` with MockMDS up, or show `docs/media/capture-screen.png` and `docs/media/hindi-accessibility.png` if it is not. Show: the step rail, the Offline chip, the "not the MOSIP Registration Client" strip, the capture screen with the oval, one plain message, the language switch to Hindi, larger text | "This is the registration scaffold. It is labelled as a scaffold on every screen. The resident sees one plain sentence and an icon. Never a score, never the reason a check failed. Quality problems get guidance; security problems get silence. Hindi, large text and high contrast are one click." |
| 1:20-1:55 | **The capture swap fails** | Terminal: `./gradlew :harness:cleanTest :harness:test --tests '*BindingAttacksTest' -i`. Scroll the test names: `passingTheChallengesThenReturningADifferentFaceIsRejected`, `delayingTheCaptureBy5SecondsIsRejected`, `droppingAFrameFromTheChainIsRejected`, `returningAnOldSignedCaptureIsRejected`, `breakingTheSbiCaptureChainIsRejected` | "Four locks run before any accept: the session nonce echoed inside the device-signed capture, a hash chain over the frames, identity continuity, and time. Each attack is a test. Pass the challenge, then hand back a different face: rejected. These locks work on unmodified SBI." |
| 1:55-2:25 | **We attacked ourselves** | Terminal: `./gradlew :harness:fusedRedTeam -q`. Highlight the last line: `of 85 pitches: accepted without the flash cue 4, accepted with it 0, left to the challenge with it 3`, and the word `SYNTHETIC` in the first line | "Our own red team searched for what fools us. On synthetic screen replays, with the passive model assumed fooled and no flash light, four of eighty-five display pitches were accepted. With the flash cue, none. We logged it, we proposed the fix, and we did not turn it on, because it would raise false rejections that we cannot price without real data." |
| 2:25-2:50 | **The limits, first** | `docs/claim-sheet.md` section 1 on screen (L1, L12, L13, L17 highlighted) | "What we do not claim. No real attack corpus exists, so no APCER or BPCER is reported and every threshold is provisional. Zero failures in sixty attacks would still allow a true rate near six percent. It has not run on a real device or a phone. The desktop app is a scaffold." |
| 2:50-3:00 | **Close** | Pitch slide 7 with the repo link | "665 tests pass. The code is MPL-2.0 like MOSIP. Next: one real experiment, a consented corpus, a device. We would like to ask MOSIP how an SBI extension should be proposed. Thank you." |

## Before you record

- [ ] `./gradlew build` is green (it was on 2026-10-24: 665 tests, 0 failures; 2 skipped here, 20 on a fresh clone without the model files).
- [ ] The commands in scenes 4 and 5 were run once, today, so you know how long each takes (`fusedRedTeam` about 10 seconds,
      the binding tests about 6 seconds once Gradle is warm).
- [ ] For the station scene with MockMDS: start MockMDS (`docs/hardware-testing-guide.md` section 2.2), models in `models/`,
      then `./gradlew :desktop:runScaffold`. If the camera step stalls, "Camera not connected. Please check the device." is
      correct behaviour; show it rather than hiding it.
- [ ] Captions: add English subtitles (the on-screen text is small). Add Hindi subtitles only if a native speaker has
      checked them.
- [ ] Length: under 3:00 unless the form asks for 2-5 minutes.

## What not to show or say

| Do not | Why |
|---|---|
| Show a live blink or smile test as if the engine had been tuned for it | No real corpus; thresholds provisional |
| Hold up a phone replay as a result | It was never run on hardware (`docs/demo-script.md` scene 4 is HARDWARE) |
| Say "stops deepfakes", "works on Android", "production ready" | Claims L2, L13, L17 |
| Show `sbi/testdata/*.mjpeg` or `rcapture-face.json` | They hold MOSIP's test photo of a real person and are gitignored on purpose |
| Show the Diagnostics window with a resident present | It shows scores and is policy-gated and audited, "not for residents" |
