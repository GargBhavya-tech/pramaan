# PRAMAAN deck and recorded-demo plan (Build Map #99)

Structure follows the Bible: **threat model → the gap nobody names → architecture → the numbers → inclusion → the
SBI proposal.** Every slide names its evidence so nothing is said that `docs/claim-sheet.md` does not back. The
slides themselves are made in whatever tool you present from; this file is the content, order and timing.

Target: 7 minutes of talk plus the demo, 15 slides. The deck must work **with no laptop of your own**: export to PDF,
and keep the recorded demo (section 3) on a USB stick and in a cloud link.

## 1. Slides

| # | Slide | One line to say | Evidence on the slide | Claim-sheet ref | Min |
|---|---|---|---|---|---|
| 1 | PRAMAAN (प्रमाण) — proof | "A liveness decision is only worth the proof behind it." | Title, team, problem statement 04 | — | 0.3 |
| 2 | The three attacks the problem statement names | "Print, screen, replay — what a resident can do in front of the lens." | Photos of each attack | — | 0.4 |
| 3 | The attackers it does not name | "A colluding operator, an impersonated supervisor, a fraud ring." | Part 2.1 table (five attackers) | — | 0.5 |
| 4 | **The gap nobody names** | "The preview is unsigned and the capture is signed. Nothing links them." | The two arrows; Android clients never see preview frames | L13 | 0.7 |
| 5 | Architecture: one brain, two clients | "A headless engine; the client only supplies frames." | Part 3 pipeline diagram; Mode C vs Mode D | L12, L13 | 0.7 |
| 6 | Cues that fail differently | "Every cue has a hole, and the holes do not overlap." | The Part 8 table (cue, catches, blind to) | L10, L11 | 0.6 |
| 7 | Challenges that cannot be pre-recorded | "20.7 bits for the flash; 3.5 bits per action; random onset." | C1, C2 numbers | C1, C2 | 0.5 |
| 8 | Capture binding | "Nonce, hash chain, identity continuity, time — and now an optional cryptographic lock." | The four locks + `SignedStreamLock` | C7 | 0.6 |
| 9 | Evidence, not a verdict | "Signed, re-checkable, re-scorable in 2028." | Evidence blob, transparency log (C5), `verifyEvidence` | C5, C11 | 0.6 |
| 10 | **The limits, first** | "No real corpus yet. Here is what we do not claim." | Section 1 of the claim sheet (L1–L16, condensed to six bullets) | L1–L16 | 0.8 |
| 11 | The numbers | "APCER per attack type with an interval; BPCER per group. Zero in 60 is still 6 %." | The report page. **If no real corpus: this slide says so and shows the interval arithmetic, not a number.** | L1, L8 | 0.8 |
| 12 | Inclusion by design | "Absence of a reflex never rejects a resident." | Asymmetric weights, accessibility profiles, Hindi + icons + speech | C6, L14 | 0.6 |
| 13 | Insider threat | "z = 5.87 for 6 failures in 40 sessions; the operator face is blocked." | C4 | C4 | 0.4 |
| 14 | A proposal back to MOSIP: the SBI Liveness Profile | "Fix the gap in the standard, not just in our client." | Profile summary, threat table T1–T6, reference implementation | L16 | 0.7 |
| 15 | What we built, what is next | "Open questions: how MOSIP takes spec proposals; model licences; real corpus; a device." | Build Map status; Part 30 open questions | L1, L12, L13, L15 | 0.5 |

Rules: no slide shows a synthetic result as a headline (L2); no slide shows a number that is not in section 2 of the
claim sheet; slide 10 comes **before** slide 11.

## 2. Anticipated questions

Answers are in `docs/claim-sheet.md`, section 3. Rehearse these five: *What is your APCER? Does it stop deepfakes?
Is it fair across skin tones? Does it work on Android? Can a mask pass?*

## 3. Recorded demo (insurance against hardware failure on the day)

Record the full run of `docs/demo-script.md` **after** the third clean rehearsal, once per scene so a scene can be
re-recorded alone. Capture the screen and the camera view side by side.

| Shot | Scene | Length | Must show | Must not show |
|---|---|---|---|---|
| S1 | Honest resident | 40 s | The status line sequence ending `Good, face captured successfully`; the captured photo; Diagnostics | A face that has not consented |
| S2 | Bad light | 30 s | Guidance text, attempt counter unchanged | — |
| S3 | Printed photo | 40 s | Generic message; Diagnostics cue view | A score on the resident screen (there is none) |
| S4 | Phone replay | 60 s | The challenge issued, the video failing it, the generic message, the cue view | — |
| S5 | Operator login with a photo | 30 s | The failed login and its audit entry | — |
| S6 | Virtual camera | 40 s | `INJECTION_DETECTED` in the audit log; a normal webcam *not* vetoed | A claim that OS-level enumeration exists (L9) |
| S7 | Capture swap | 40 s | `BindingAttacksTest` output and the named lock | — |
| S8 | Accessibility | 40 s | `NO_VISION` ticked, Hindi prompts with icons, no gaze challenge | — |
| S9 | The report | 60 s | The HTML report, intervals, the synthetic label if synthetic | A synthetic number as a headline (L2) |
| S10 | The limits | 30 s | Claim sheet section 1 | — |

Total about 6.5 minutes. Add a voice-over only if live narration is not possible; the on-screen text already tells the
story.

## 4. Done when

- [ ] The deck exports to PDF and runs on a machine that is not yours.
- [ ] The recorded demo plays from a USB stick and from a link.
- [ ] Each of the five anticipated questions has been answered aloud, once, to someone who is not on the team.
- [ ] Every number on every slide can be found in `docs/claim-sheet.md` section 2.
