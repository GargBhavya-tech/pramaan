# PRAMAAN red-team log (Build Map #95)

Purpose: spend a day attacking our own system with everything we have, log every success, and **fix it or disclose
it** before the evaluation report is generated — never after. Build Map #96 reads this log; `docs/claim-sheet.md`
quotes its limits.

**Honest status of this file.** The attack list and the procedure are ready. The *physical* attacks have **not been
run**: that needs people, prints, phones, a mask and a camera (`docs/hardware-testing-guide.md`). Rows marked
`NOT RUN` are blank on purpose. Rows marked `TEST-BACKED` are code-level controls whose tests pass in
`./gradlew test`; that proves the control behaves as designed on synthetic inputs, not that a real attacker fails.

## 1. Rules for the day

1. One attack per row, one attempt per line. **Log every attempt, successes first.**
2. Run each attack against the **resident** policy and the **operator** policy (Bible Part 9: they differ).
3. Record the generic message the attacker saw **and** what Diagnostics/audit showed. If the two differ in a way an
   attacker could exploit (an oracle), that is a finding.
4. A success is either **FIXED** (commit hash, re-run result) or **DISCLOSED** (the claim sheet gets a limit).
   There is no third state.
5. Do not change a threshold to make an attack fail unless the change is justified by the corpus. A threshold tuned
   on the day's attacks is not held-out evidence.
6. Take photographs or short clips of each setup (no faces of third parties without consent: `docs/consent-form.md`).

## 2. Setup checklist

| Item | Have it? | Notes |
|---|---|---|
| Printed photo, matte | | |
| Printed photo, glossy | | |
| Printed photo, curved (bent) | | |
| Printed photo, cut-out eyes with a live eye behind | | |
| Phone/tablet showing a still | | |
| Phone/tablet replaying a video of the target blinking, smiling, turning | | |
| 3-D mask (paper / resin / silicone), if obtainable | | |
| OBS Virtual Camera (or ManyCam) installed | | |
| A deepfake / face-reenactment tool and the target's consented source footage | | |
| A second webcam (stereo cue), a resident-facing light (flash cue) | | |
| MockMDS patched to return a different signed face (capture swap) | | |
| A supervisor's photo and video (operator-impersonation) | | |

## 3. Attack catalogue (Bible Part 2.2) and what is already covered

| # | Attack | Control | Code-level status | Physical result |
|---|---|---|---|---|
| A1 | Printed photo (matte, glossy, cut-out eyes, curved) | Passive scorer, colour-texture\*, flatness, challenges | Cue and challenge logic: `CueBankTest`, `ActionDetectorsTest`, `LivenessEngineTest`. \*Colour-texture abstains `UNTRAINED` until the corpus exists. | **NOT RUN** |
| A2 | Screen showing a photo | Moiré, colour cues, flatness, challenge | Moiré: `CueBankTest`; synthetic recapture scores above bona fide: `SyntheticAttackFactoryTest` | **NOT RUN** |
| A3 | Screen replaying a video | Moiré, unpredictable challenge, flash | Challenge entropy and uniformity: `ChallengeGeneratorTest`; wrong-action / latency: `ActionDetectorsTest`. Flash is wired (needs `--flash-screen`) but only tested with a fake light. | **NOT RUN** |
| A4 | 3-D mask | Passive, rPPG\*\*, deformation, flash shading, stereo | Deformation and stereo cues tested on synthetic data only. \*\*rPPG abstains on the SBI stream. Projection-on-mask is **unquantified**. | **NOT RUN** |
| A5 | Virtual camera / injected feed | Injection guard (denylist veto), flash, stream tag chain | `InjectionGuardTest`, `InjectionGuardEngineTest`. The denylist matches the device's *reported* metadata and the Windows camera list by name; a virtual camera that names itself like a webcam is not caught. The tag chain needs a device with the Liveness Profile: `SignedStreamLockTest`. | **NOT RUN** |
| A6 | Real-time deepfake | Flash correlation, latency, artefact cue | Flash cue on synthetic traces: `FlashReflectionCueTest`, through the engine with a fake light: `StationWiringEngineTest`. No deepfake-artefact cue exists. | **NOT RUN** |
| A7 | Capture swap | Four binding locks | `BindingAttacksTest`, `CaptureBinderTest` | **TEST-BACKED** — re-run physically with a patched MockMDS |
| A8 | Replay of signed SBI data | Nonce as `transactionId`, timestamp window | `CaptureBinderTest`, `BindingAttacksTest`, `SessionNonceTest` | **TEST-BACKED** |
| A9 | Challenge pre-recording | High-entropy, CSPRNG-seeded challenges | `ChallengeGeneratorTest`, `NoInsecureRandomTest` | **TEST-BACKED** (entropy only; the attacker's actual library cost is not measured) |
| A10 | Oracle probing | Random audit challenges, generic messages, rate limits | `RiskFusionTest`, `ErrorTaxonomyTest`, `HindiCatalogueTest` (one string for every security failure in both languages), `AccessibilityAndAttemptTest` | **TEST-BACKED** — probe by hand: retry 20 times at the edge of the threshold and look for any difference in message or timing |
| A11 | Config/model tampering, clock rollback | Signed bundles, anti-rollback | `BundleManifestTest`, `EngineBundleSignedTest`, `SignBundleTest` | **TEST-BACKED** — edit a threshold in the shipped YAML by hand and confirm startup refuses |
| A12 | Adversarial patches / makeup / glasses against the CNN | Cue ensemble, server re-check | `RecheckVerifierTest`, `RetroactiveRescanTest` (re-scoring works); no adversarial robustness test exists | **NOT RUN** |
| A13 | Operator enrols own face / same face across residents | Operator-face lock, fraud analytics | `CaptureBinderTest` (operator lock), `FraudAnalyticsTest`, `FraudReportTest` | **TEST-BACKED** |
| A14 | Exception-path abuse | Dual control, rate limits, analytics | `ExceptionFlowTest`, `AccessibilityAndAttemptTest`, `PacketApprovalTest` | **TEST-BACKED** |

## 4. Attempt log (fill in on the day)

| Time | Attack # | Variant (print finish, phone model, mask type...) | Policy (resident / operator) | Attempts | Successes | Generic message seen | Diagnostics showed | Outcome (FIXED / DISCLOSED) | Commit / claim-sheet line |
|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | |
| | | | | | | | | | |
| | | | | | | | | | |
| | | | | | | | | | |

## 5. Findings (one block per success or oracle)

**Finding found by the automated red team before the physical day (SYNTHETIC):**

```
Finding R-01
Attack:        A2/A3 - screen recapture whose display grid aliases where the moire cue does not look
How it worked: `RedTeamFuzz` (./gradlew :harness:redTeam) searched the recapture simulator's parameters against
               MoireCue. In 481 evaluations it found pitch 1.00, leak 0, gap 0.15: peakiness 3.2, pLive 0.98.
               `--map 0.01` swept the pitch: the cue calls a clean recapture live at about 0.80, 0.96-1.01, 1.20, 1.60
               and 1.98-2.03 (display pixels per sensor pixel). Everywhere else it catches it. Blur does NOT help the
               attacker (it closes the hole), so no sharpness check is needed to cover it - and none would.
Why the controls missed it: MoireCue examines 12-60 cycles per 128 px and ignores bins on the 8-px JPEG lattice. A grid
               whose beat is near 0, or on the lattice (bins 16, 32, 48), or above 60, is invisible to it. The holes are
               narrow (a percent or so of pitch) but a live camera preview lets an attacker walk the phone to one.
Decision:      DISCLOSED (claim-sheet L19). The moire cue must not be the only control against screen replay: the
               challenge (A3), the flash cue and the passive scorer are independent of it.
Fix / limit:   not fixed. Widening the band to catch low beats would flag natural illumination gradients on real faces,
               and that false-positive rate cannot be set without the corpus (#65). Re-run against real phones on the day.
Re-run result: pending the physical attacks; synthetic only so far.
```

```
Finding R-03 (R-01 against the whole decision)
Attack:        A2/A3 - screen replay of the right room at one of R-01's blind pitches (0.80, 1.00, 1.20, 2.00)
How it worked: `FusedRedTeam` (./gradlew :harness:fusedRedTeam) fused the scene and moire votes of 85 simulated replays with the
               real RiskFusion and the shipped table. With the passive scorer assumed fooled too and no flash cue, 4 of 85
               pitches are ACCEPTED outright (p 0.95-0.99 against the 0.85 accept threshold): the scene cue (w 0.6) and the moire cue
               (w 0.8), both controlled by the attacker, sum to an acceptance. With a flash cue the replay never answers, none is
               accepted: three fall to the CHALLENGE zone and one is rejected.
Why the controls missed it: weights are positive evidence that adds up, and nothing requires one of the cues in an ACCEPT to be
               independent of what the attacker shows to the camera. The ACCEPT zone issues no challenge.
Decision:      FIXED for operators and supervisors, DISCLOSED for residents (claim-sheet L21). Fix: policy field `requireUnfakeableVote`
               (on for OPERATOR and SUPERVISOR) and `ZoneDecider`: a first-decision ACCEPT needs a positive contribution from `flash`
               or `stereo`, else CHALLENGE with reason NO_DISPLAY_PROOF; a passed challenge is the live vote, so the re-decision is not
               blocked again. `ZoneDeciderUnfakeableVoteTest`, 8 cases. Not re-measured on a corpus.
               Original text of the suggestion follows. Conditional on the passive CNN being fooled (not modelled:
               it is likely not) and on no flash light. Suggested rule, to be set on the corpus: an ACCEPT needs at least one live
               vote from a cue the display cannot fake (flash, stereo, a passed challenge), else the session goes to the
               challenge. Not applied to residents, for two reasons. It would change BPCER at every station without a light, which cannot be priced without #65.
               And it turns an accept into a challenge: a resident who ticked an accessibility profile (infant, cannot see the screen,
               limited movement) is asked fewer or gentler checks by design, so a rule that forces a challenge on them could lock out
               exactly the people the inclusion rules protect. The stand-down is now built: the engine passes whether the session has an
               accessibility profile (`ZoneDecider.decide(..., accessibilityAdjusted)`), and the rule does not apply to such a session
               (`ZoneDeciderUnfakeableVoteTest`). Residents stay OFF by default because switching it on sends every resident at a station
               without a flash light through a challenge, which is friction that cannot be priced without #65; turning it on is the one
               line `requireUnfakeableVote: true` under RESIDENT in `config/pramaan-policy.yaml`.
Re-run result: pending real phones and the passive scorer on real replays.
```

```
Finding R-02 (a false rejection, not an attack)
Attack:        none - an honest resident in the same room under different light
How it worked: `StationSelfTest` (./gradlew :harness:stationSelfTest) scored the scene cue 0.35 for the same room 15% brighter
               or 25% darker: below the 0.55 threshold, so a resident was voted against by the afternoon sun.
Why the controls missed it: the cue compared colour histograms, which move with exposure; only the person segmenter matched
               exposure.
Decision:      FIXED. `SceneFingerprint.exposureGain` picks the one gain that makes the most pixels fit the stored room (a
               person's pixels fit at no gain, so a blue shirt cannot drag it) and the cue applies it first.
Fix / limit:   this commit. Scene cue now 0.97-1.00 at 0.75x-1.25x; another room stays below 0.3 at both
               (`SceneContextTest`). Synthetic; a real camera's auto white balance is a different change from a gain.
Re-run result: self-test controls all live.
```

```
Finding R-01 (template for the next one)
Attack:        A? ...
How it worked: ...
Why the controls missed it: ...
Decision:      FIXED  |  DISCLOSED
Fix / limit:   commit ...  |  claim-sheet line L...
Re-run result: ...
```

## 6. Summary (write last)

| Attack | Attempts | Successes | Success rate (95 % Clopper–Pearson) | Status |
|---|---|---|---|---|
| | | | | |

Use `Binomial` from the harness for the interval. A small number of attempts gives a wide interval: say it. "0 of 10"
still allows a true rate above 25 %.

## 7. Done when

- [ ] Every row of section 3 has a physical result, or an explicit "cannot run: reason".
- [ ] Every success is FIXED with a re-run, or DISCLOSED in `docs/claim-sheet.md`.
- [ ] The summary table is filled in with intervals.
- [ ] You know your own weaknesses better than the judges do.
