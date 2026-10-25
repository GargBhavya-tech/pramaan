---
marp: true
theme: default
paginate: true
title: PRAMAAN (प्रमाण) — proof
---

<!--
Marp slides. Preview in VS Code (Marp extension) or export:  npx @marp-team/marp-cli docs/deck.md --pdf
Content follows docs/deck-outline.md. Rules: no slide shows a synthetic result as a headline; every number is in
docs/claim-sheet.md section 2 (or is exact arithmetic); the limits slide comes BEFORE the numbers slide.
Speaker lines are the HTML comments under each slide.
-->

# PRAMAAN (प्रमाण)
## Proof, not a verdict

Face liveness and presentation-attack detection for the MOSIP Registration Client
MOSIP Decode 2026 — problem statement 04

<!-- "A liveness decision is only worth as much as the proof behind it." -->

---

# The three attacks the problem statement names

| Attack | What the attacker does |
|---|---|
| **Print** | holds a photograph to the lens |
| **Screen** | shows a photo or a video on a phone |
| **Replay** | plays a recorded blink, smile, turn |

<!-- "Print, screen, replay: what a resident can do in front of the lens." Photos of each attack here. -->

---

# The attackers it does not name

- a **colluding operator** who enrols their own face, or the same face many times
- an **impersonated supervisor** who approves an exception
- a **fraud ring** across centres
- software that **never touches the lens**: a virtual camera or an injected stream

<!-- "A colluding operator, an impersonated supervisor, a fraud ring." Bible Part 2.1. -->

---

# The gap nobody names

**The preview is unsigned. The capture is signed. Nothing links them.**

- A resident can pass liveness with one face and have another face enrolled
- On **Android**, the client never sees preview frames at all: the engine has to run inside the SBI app

<!-- "The preview is unsigned and the capture is signed. Nothing links them." Claim-sheet L13 (Android). -->

---

# One brain, two clients

- A **headless engine**; the client only supplies frames
- **Mode C** desktop: an unsigned M-JPEG stream, 3 fps guaranteed
- **Mode D** Android: the engine runs inside the SBI device app
- Fails **closed**: no session without a verified policy, table and models

*Honest status: the desktop is a scaffold, not the Registration Client; the Android build has run its models on one phone but not a full session.*

<!-- L12, L13. -->

---

# Cues that fail differently

| Cue | Catches | Blind to |
|---|---|---|
| Passive scorer | print, screen | what it was not trained on |
| **Moiré** | screen recapture | **a few display pitches (our own red team found them)** |
| **Flash** | replay, injection | a projected mask; needs a light |
| Stereo depth | flat things | needs a second camera |
| Scene + light | the wrong room | a replay of the right room |
| Stream fingerprint | a re-encoding virtual camera | one that copies the encoder |

**Every cue has a hole. The holes should not overlap.**

<!-- "Every cue has a hole, and the holes do not overlap." Part 8 table. L10, L11, L19. -->

---

# Challenges that cannot be pre-recorded

- Flash sequence: 6 colours × 8 steps = **1 679 616 sequences = 20.7 bits**
- Each action step: **3.52 bits** (1.86 bits on a 3 fps desktop without a resident-facing display)
- Draws are uniform (χ² at p = 0.001), onset is random
- At most 3 colour changes a second; no saturated red

<!-- "20.7 bits for the flash; 3.5 bits per action; random onset." Claim sheet C1, C2, C3. -->

---

# Capture binding

Four locks, all checked, all audited, before an `ACCEPT`:

1. **nonce** the device signed this attempt
2. **chain** the hash links the frames to the capture
3. **identity** the captured face is the face we watched
4. **time** it was live a moment ago

Optional, with a device that supports the proposal: a **cryptographic** lock on the stream, and a **burst** of signed frames that must show the same face.

<!-- "Preview is unsigned, capture is signed; without binding you can pass with one face and enrol another." C7. -->

---

# Evidence, not a verdict

- A signed, **re-checkable** record, stored in the biometric record's `others` map
- A server can **re-score** it with a better model later
- A transparency log makes omission detectable (matches the Certificate Transparency test vectors)
- `verifyEvidence` re-checks a record against its capture in one command

<!-- "Signed, re-checkable, re-scorable in 2028." C5, C11. -->

---

# The limits, first

- **No real corpus yet.** Every threshold is provisional; no APCER or BPCER on real attacks is claimed
- Synthetic attacks are for stress tests, never headlines
- We red-teamed ourselves: the moiré cue has blind pitches, and **without a flash light, two cues the attacker controls can sum to an accept** (disclosed, not fixed)
- Not tested on a real device, a real second camera, or an iris camera
- Not the MOSIP Registration Client; Android: models timed on one phone, no full session
- Model licences are open questions; Hindi checked by the author, not independently reviewed

<!-- "No real corpus yet. Here is what we do not claim." L1-L21, condensed. Say this BEFORE the numbers slide. -->

---

# The numbers

**We have not measured an error rate on real attacks. Here is what a measurement would be worth.**

Zero failures in *n* attacks still allows a true rate up to (95%, exact):

| attacks *n* | 10 | 20 | 60 | 100 | 300 |
|---|---|---|---|---|---|
| upper bound | 30.8 % | 16.8 % | 6.0 % | 3.6 % | 1.2 % |

We report **APCER per attack type with an interval**, BPCER per group, never ACER.

<!-- "Zero in 60 is still 6 percent. We report the interval, not the zero." If a real corpus exists by the day, replace this slide from the generated report, not from memory. L1, L8. -->

---

# Inclusion by design

- A missing blink, pulse or pupil reflex is **"unknown", never "spoof"**: physiological cues only ever help
- Elderly, disabled and darker-skinned residents fail those for innocent reasons
- Accessibility profiles (no gaze challenge, no flashing), **Hindi**, icons, text-to-speech
- Cut-offs are calibrated **per group** with a guarantee, or refused when a group is too small

<!-- "Absence of a reflex never rejects a resident." C6, L14. -->

---

# The insider threat

- A resident capture that matches the **logged-in operator's own face** is blocked
- Per-operator failure rates against the centre's own baseline: **6 PAD failures in 40 sessions at a 2 % baseline → z ≈ 5.87**, flagged
- Exception flow: dual control, rate limits, analytics

<!-- C4. Needs real baselines per centre. -->

---

# A proposal back to MOSIP: the SBI Liveness Profile

Fix the gap in the standard, not only in our client. Optional and backward compatible:

- signed preview stream (per-frame tags, signed checkpoints)
- a signed **log of what the device's light actually showed**
- a burst of signed frames, device-side hooks
- threat table T1–T11 and JSON schemas; a **runnable reference device**

*Nothing has been posted to MOSIP: we first ask how proposals are accepted (Q12).*

<!-- "Fix the gap in the standard." L16, L17. docs/sbi-liveness-profile.md. -->

---

# What we built, what is next

**Built:** the engine, the SBI client, the harness, a scaffold, the profile and a device that speaks it, an automated red team, a station self-test.

**Next, in order:**
1. **One real experiment**: does the flash cue separate a live face from a replay on a laptop? (recorder and analyser are ready)
2. A consented **corpus**, then every threshold from data (a calibrator is ready)
3. A real device, a second camera, Android on a phone

**Open questions for the mentors:** how MOSIP takes spec proposals · guardian faces in infant photos · model licences

<!-- Part 30 open questions: Q12, Q13, Q16. -->
