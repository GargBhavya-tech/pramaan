# Q&A preparation: 25 hard questions

Every answer matches `docs/claim-sheet.md`. If a question is not here, answer from the claim sheet or say "we have not
measured that." Rehearse these aloud to someone who is not on the team (`docs/deck-outline.md` section 4 requires it).

Format: the question, the short answer to say, then the evidence if they push.

## The five the claim sheet already lists

**1. What is your APCER?**
Say: "We have not measured one on real attacks. There is no real corpus yet, so every threshold is provisional. Here is the
plan, the consent form and the interval we would report."
Evidence: `docs/corpus-plan.md`, `docs/consent-form.md`, `config/operating-points.json` says `"provisional": true`.
Never give a percentage. Zero failures in 60 attacks still allows about 6% (exact binomial, 95%).

**2. Does it stop deepfakes?**
Say: "A virtual camera is vetoed when the device or the OS identifies it. A flash correlation would catch a stream that
cannot answer our random colours, but that cue is built and tested on synthetic data and needs a resident-facing light."
Never say "it stops deepfakes."

**3. Is it fair across skin tones?**
Say: "Physiological cues only ever vote for life, because they fail darker-skinned, elderly and disabled residents for
innocent reasons. We would report BPCER per group with intervals; the corpus is too small to claim parity yet."

**4. Does it work on Android?**
Say: "The engine compiles to an Android library and passes its unit tests and lint. It has not run a session on a device.
Android clients never see preview frames, so the engine has to run inside the SBI app."

**5. Can a mask pass?**
Say: "A projected-face mask is a known, unquantified gap for the flash cue; texture and scene cues are the mitigation. It is on
the red-team list."

## Design

**6. Why not just a strong passive model?**
Passive models fail on what they were not trained on (NIST's 2023 PAD evaluation: no single model covers all attack types),
and they cannot see injection. So PRAMAAN uses cues that fail differently and combines them with per-cue abstention; "the
holes should not overlap" (`docs/claim-sheet.md`, Bible Part 8).

**7. What is the actual gap you found?**
The SBI preview is unsigned, the capture is signed, and nothing links them (confirmed in MDS spec 1.2.0). On Android the
client does not even receive the preview. Bible Part 1.2 has the confirmed-facts table with sources.

**8. How does the binding work, and does it need device changes?**
Four locks before any ACCEPT: the session nonce (our 32-hex-character `transactionId`) echoed inside the device-signed
capture; a frame hash chain linking into the SBI `previousHash`; identity continuity (the stored face is the face we
watched); and recency. These work on **unmodified SBI**. The cryptographic stream lock and burst need our proposed profile.
Test: `./gradlew :harness:test --tests '*BindingAttacksTest'`.

**9. What if the device itself is malicious (an L0 key in host software)?**
Then whoever owns the host can sign anything. L1 devices (key in secure hardware) are the answer; L0 is allowed only for
registration, and fusion carries a risk prior for it (Bible Part 1.2, Part 9). We do not claim to defend against a fully
compromised L0 host.

**10. What happens when something is missing or broken?**
It **fails closed**. A session exists only via `EngineBundle.newSession`, which refuses a bad policy, table or model; swap
a model and the engine refuses to start until the operating-point table is regenerated. A broken install stops
registrations, and the audited, dual-controlled exception flow covers that (claim C8).

**11. Why are the thresholds not in the code?**
Every fusion number lives in `config/operating-points.json`, bound to the SHA-256 of each loaded model, so an operator
cannot edit a threshold and a model swap cannot go unnoticed. Signed bundles with anti-rollback cover the offline case.

**12. What does an honest resident with bad light experience?**
Guidance ("Please improve the lighting"), not a rejection; the attempt counter does not advance on a quality problem. The
resident never sees a score or a cause of failure.

**13. What is in the evidence package, and is it a privacy risk?**
A signed JWS in the face BIR `others` map: the decision, the cues' votes, the model hashes, the binding result, and
encrypted sample crops the server may keep to re-score later. One honest note: the evidence lists the registration PC's
installed camera names and encoder summaries, which tells the server what software is installed. A deployment that
treats that as sensitive should keep the counts and drop the names (`docs/red-team-log.md`, the review pass).

## Evidence and measurement

**14. What is actually verified, then?**
Properties of the code, each with a command: 20.7 bits of flash-sequence entropy (exact), photosensitivity limits (exact,
900 sequences), the insider-fraud z-score (z about 5.87 for 6 failures in 40 at a 2% baseline), the transparency log
against the Certificate Transparency test vectors, the capture-swap negative tests. None is an error rate on real attacks
(`docs/claim-sheet.md` section 2).

**15. How many tests, and are they meaningful?**
See the test count in the README (all passing on the last full build). The meaningful ones are the negative tests (each
capture-swap case, each policy-parsing failure), the 20,000-input fuzz of the JPEG parser, and the red-team fuzzers; a
count by itself proves little and we do not offer it as proof of accuracy.

**16. You say you red-teamed yourselves. What did it find?**
Two real weaknesses, both logged and neither fixed yet. R-01: the moiré cue is blind at certain display pitches (about
0.80, 1.00, 1.20, 1.60 and 2.00 sensor pixels per display pixel). R-03: with the passive scorer assumed fooled and no flash
cue, the shipped fusion can accept a synthetic screen replay because two attacker-controlled cues (scene, moiré) sum past
the threshold; with a flash cue none is accepted. A fix is proposed (an accept needs a vote from a cue the display cannot
fake) but not turned on, because it would raise false rejections at stations without a light and that cannot be priced
without a corpus (`docs/red-team-log.md`, `docs/claim-sheet.md` L19, L21). A fixed R-02 (a scene-cue false rejection after
exposure changes) is in the same log.

**17. How much of the evidence is synthetic?**
All attack evidence is. Reports label synthetic rows SYNTHETIC and never use them as headlines (claim L2). The synthetic
attack factory is for stress tests and training; the simulator's physics matches its formulas to 0.01 cycles per pixel, but
matching a formula is not matching real phones (claim C9).

**18. What is the performance on a low-end machine?**
We have budgets (Bible Part 20.4), not measurements on target hardware. `./gradlew :harness:pipelineBench` measures this
machine; no low-end Android number exists.

## Integration and standards

**19. Is this the Registration Client?**
No. It is a JavaFX scaffold that mirrors the flows we protect (login, resident capture, supervisor approval, diagnostics),
labelled as a scaffold on every screen. ADR 0003 records the integration depth and the patches the real client would need.

**20. What is the SBI Liveness Profile, and will MOSIP accept it?**
An optional, backward-compatible extension: signed preview stream, a signed log of what the device's light showed, a signed
burst, capability discovery, a threat table T1-T10, JSON schemas and a runnable reference device. We do not know how MOSIP
takes spec proposals; we are asking the mentors before posting anything (Part 30, Q12). The two verbs `ILLUMINATE` and
`CAPTUREBURST` are our own proposal, not part of the standard.

**21. Which standards do you follow?**
ISO/IEC 30107-1/-3 (vocabulary, APCER and BPCER per attack type with confidence intervals, ACER not reported), 19794-5 for
the face image, 29794-5 and OFIQ for quality, 19795-10 for demographic differentials, CEN/TS 18099 for injection (Bible
Part 1.3). We follow them as design targets; we are not certified (no iBeta or FIDO test).

**22. What are the licences and what must go to MOSIP?**
Code: MPL-2.0, matching MOSIP. Dependencies and every model have a row in `docs/licences.md`. Two are open questions:
SFace's training data is not stated (development use only until answered), and the JPEG 2000 library has a conditional
licence, so inside the Registration Client we would use the copy MOSIP already ships. The rules say the source must be
provided to MOSIP on request; the licence permits that.

## People and process

**23. What would you do with one more month?**
(1) The one real experiment: does the flash cue separate a live face from a replay on a laptop (the recorder and analyser are
built, `docs/research-plan-real-data.md`). (2) A consented corpus and the calibrator that is already written. (3) A real
device and a phone. (4) A native-speaker review of the Hindi, and a trial of the screens with real operators.

**24. How was this built? (answer this one truthfully, in your own words)**
The code was written with an AI coding assistant (Claude) working under my direction. Say what is true for you in these
blanks, and nothing more: *I decided [the scope, the threat model, the priorities]; I reviewed [which parts]; I ran and
checked [what]; I did not [anything you did not do].* Do not claim it was hand-written if it was not, and do not hide the
assistant if the rules ask for disclosure. If the 2026 rules forbid AI-generated work, raise that with the organisers
(decode@mosip.io or the Unstop board) before submitting, not after.

**25. Why should this win?**
"Because it names the gap in the standard that makes preview-based liveness weaker than it looks, closes it on unmodified
SBI, leaves proof a server can re-check, and tells you exactly what it has and has not measured."

## Questions to put to MOSIP (post once, with the answer you want written down)

| # | Question | Why it matters |
|---|---|---|
| Q12 | How does MOSIP accept an extension proposal to the SBI standard (a GitHub issue, a spec PR, the community forum)? | We hold a draft Liveness Profile and will not post it blind |
| Q13 | May a guardian's face appear in an infant's photo when the infant cannot be positioned alone? | Decides the infant mode's capture rules |
| Q16 | What are the licence terms of the FLIP and PSCF anti-spoofing models (no licence file found)? | We use only their architecture ideas; weights need clear terms |
