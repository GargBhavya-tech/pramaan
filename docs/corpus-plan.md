# Corpus plan (Build Map #6)

Every number PRAMAAN reports — APCER per attack species, BPCER, fairness, operating points — comes from this corpus.
It is the riskiest dependency in the build: record it early, record it varied, and keep it out of git.

## 1. What gets recorded

### Bona fide (real people)

| Dimension | Values | Why |
|---|---|---|
| People | Team + volunteers, as varied as possible in skin tone, age, sex, eyewear | Fairness reporting (Bible Part 16, Build Map #71) needs every group represented |
| Lighting | Good (ring light) · dim room · backlit (window behind) · fluorescent (50 Hz) | Quality gate and BPCER under field conditions |
| Eyewear | None · glasses · (optional) sunglasses as a negative case | Glare is a known false-reject cause |
| Actions | 10 s neutral · head turn left · head turn right · one blink · eyes closed **at least 2.5 s** · smile · look at 4 corners | Passive window + every challenge type. The harness answers prompts from these (`actions.csv`, below); holds of up to 2 s are asked, so shorter closures cannot answer them |
| Devices | Laptop webcam · second webcam · phone front camera | Domain shift between cameras |

**Target:** at least 10 people × 4 lighting conditions × 2 eyewear states, ~60 s each.

### Attacks — one row per PAI species (label them exactly like this)

| PAIS id | Instrument | How to make it | Notes |
|---|---|---|---|
| `print-matte-a4` | Matte A4 photo print | Office printer, full-face photo | |
| `print-glossy-4x6` | Glossy 4×6 photo | Photo shop | Specular highlights |
| `print-cutout-eyes` | Print with eye holes, real eyes behind | Matte print + craft knife | Defeats naive blink checks |
| `print-curved` | Print bent around a cylinder | Matte print + bottle | Tests the flatness cue's known weak spot |
| `screen-phone-photo` | Phone showing a still photo | At least 2 different phones | Moiré varies by phone |
| `screen-phone-video` | Phone replaying a video of the person | Include a video of them blinking/turning | The headline attack (demo scene 4) |
| `screen-tablet-video` | Tablet replay | | Larger screen, fewer bezel cues |
| `screen-laptop-video` | Laptop screen replay | | |
| `mask-paper` | Printed paper mask, eyes cut out | Cardstock print | |
| `inject-virtual-cam` | Recorded video via OBS Virtual Camera | OBS | Injection, reported separately (IAM × IAI) |

**Target:** ≥ 60 presentations per species to start (then 0 errors still only bounds APCER at ~5%); ≥ 300 per species
before claiming APCER ≤ 1% (Bible Part 21.4).

### Action clips (`corpus/actions.csv`)

The harness runner (#67) answers each challenge prompt with the matching recording of that person, so record
each action as its own clip and list it:

```
person,action,clip
P01,TURN_LEFT,clips/P01/turn-left
P01,TURN_RIGHT,clips/P01/turn-right
P01,BLINK,clips/P01/blink
P01,EYES_CLOSED,clips/P01/eyes-closed
P01,SMILE,clips/P01/smile
```

Left and right are the person's own sides. Clips are JPEG directories or M-JPEG files. A person without a
recording for a prompt shows up as a non-response in the report, never as a false rejection.

### Held-out split

Split **by person**, not by clip: everyone in the evaluation half is someone the thresholds were never tuned on.

## 2. Storage rules

- Stored encrypted (BitLocker/VeraCrypt volume or equivalent), outside the repository. `corpus/` and `data/` are
  gitignored — never override that.
- Manifest (`corpus/manifest.csv`): clip path, label (`bona_fide`/`attack`), PAIS id, person id (pseudonymous, e.g.
  `P07`), lighting, eyewear, device, fps. **No names** in the manifest.
- The key linking person ids to names lives on paper or in a separate encrypted file held by one person.
- Delete raw recordings after the event, or at the date promised on the consent form, whichever is earlier.

## 3. Consent

Everyone recorded signs `docs/consent-form.md` first, including team members. Faces are biometric data: in India this
is personal data under the Digital Personal Data Protection Act, 2023, which requires free, specific, informed consent
for a stated purpose. *This plan is not legal advice — check with your institution.*

## 4. Shopping list (approximate prices, check locally)

| Item | Qty | Approx. cost (INR) | For |
|---|---|---|---|
| Matte A4 colour prints | 20 | ₹10–20 each | `print-matte-a4`, `print-cutout-eyes`, `print-curved` |
| Glossy 4×6 photo prints | 10 | ₹15–30 each | `print-glossy-4x6` |
| A4 cardstock | 10 sheets | ₹50–100 | `mask-paper` |
| Craft knife + cutting mat | 1 | ₹150–300 | Eye cut-outs |
| USB ring light (flicker-free if possible) | 1 | ₹500–1,000 | Good-light condition; later the reference kit (Bible Part 40) |
| Second 1080p webcam | 1 | ₹1,000–1,800 | Device diversity; later stereo (Build Map #86) |
| Phones / tablets / laptops | — | ₹0 (team devices) | Screen attacks; use at least 2 phone models |
| **Total** | | **≈ ₹2,500–4,500** | |

## 5. Schedule

- Recording date: **____________** (must be before Phase 10 starts — not during it).
- Consent forms collected: ☐
- Instruments built and tested on camera: ☐
- Manifest filled and every clip plays through `MockFrameSource` (Build Map #65): ☐
