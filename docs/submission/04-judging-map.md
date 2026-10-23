# Judging map: every criterion, the evidence, the honest weak spot

I could not read the official 2026 judging page. A secondary summary of the Unstop listing says submissions are judged on
**relevance, creativity, usability and impact**; the 2024-25 edition used **functionality, quality, integration with MOSIP
and problem complexity**. Both sets are covered below, so the pitch does not depend on which one applies. Verify on
Unstop, then lead with the rows that match.

Rule for this table: every "evidence" cell points at something a judge can open or run. Anything not measured is said
to be unmeasured.

## Set A (Unstop 2026 listing, per a secondary source)

| Criterion | Our case | Evidence a judge can open | Honest weak spot | How to say it |
|---|---|---|---|---|
| **Relevance** | It answers the statement's two named deliverables literally (the frame-streaming interface and the liveness/PAD interface) and the clients it names (desktop and Android). It also covers what the statement does not name: injection and insider fraud | `PRAMAAN_Final_Bible.md` Part 4 (`FaceFrameSource`, `LivenessEngine`); `core/` and `sbi/` modules | The desktop app is a scaffold, not the real Registration Client (ADR 0003). Android never ran on a phone | "Same engine, two clients. The desktop is a faithful scaffold; Android needs the engine inside the SBI app, which is the design" |
| **Creativity** | (1) Binding the unsigned preview to the signed capture with four locks. (2) Evidence as a signed, re-checkable, re-scorable package instead of a verdict. (3) Physics cues that ask nothing of the resident (flash reflection with a random colour code, scene consistency). (4) An SBI Liveness Profile proposal with a runnable reference device. (5) A red team that attacks our own cues and publishes the result | Bible Parts 13, 14, 31-46; `docs/sbi-liveness-profile.md`; `docs/red-team-log.md` | Several cues are synthetic-tested only; the flash cue has never run on real hardware | "Each idea is built and tested; none is claimed beyond what `docs/claim-sheet.md` allows" |
| **Usability** | Plain messages, no scores or causes on the resident's screen; guidance for quality problems and silence for security problems; Hindi, icons, text-to-speech; larger-text and high-contrast modes; an operator step rail; exception flow with dual control | `docs/media/*.png` (screens rendered from the real code); `desktop/.../ui`; Bible Part 19 | **No operator or resident has used it.** Hindi is machine-drafted and unreviewed; no screen-reader trial | "Built to the accessibility checklist, not yet tried with people; that is the next test" |
| **Impact** | Works against **unmodified SBI**: the session nonce is echoed inside the device-signed capture (MDS spec), so the capture-swap lock needs no spec change. MPL-2.0 like MOSIP. Evidence lets the server re-score later. Inclusion rules keep it from locking out the residents MOSIP most wants to reach | `docs/claim-sheet.md` C6, C7, C11; `docs/licences.md` | No real-attack numbers, so the size of the benefit is unmeasured | "The design removes a class of attack and an audit gap. We have not measured error rates and say so first" |

## Set B (2024-25 edition's criteria)

| Criterion | Our case | Evidence | Honest weak spot |
|---|---|---|---|
| **Functionality** | The full path runs: device, frames, cues, fusion, challenge, binding, evidence, server-side verifier. Fails closed | The full test suite (`./gradlew build`); `./gradlew :harness:verifyEvidence`; `:desktop:runScaffold` | Runs against MockMDS (replays an image); no live sensor |
| **Quality** | `-Xlint:all -Werror`; thresholds only in a hash-bound table; strict policy parsing; property tests; a 20,000-input fuzz of the JPEG parser; red-team fuzzers; every claim tied to a reproduction command | `docs/claim-sheet.md`; `core/src/test`; `harness/src/test` | CI has never run on GitHub; Android check passes but not on a device |
| **Integration with MOSIP** | Speaks SBI 1.x (discovery, stream, rcapture, signature chain); JavaFX 11 matches the Registration Client; face evidence travels in the BIR `others` map leaving `bdb` untouched; model licences recorded | Bible Parts 4, 14, 20; `docs/sbi-notes.md`; ADR 0003; `docs/licences.md` | Not patched into the real client; the profile verbs are our proposal, untested on a certified device |
| **Problem complexity** | Statement is rated High. Eight layers, two clients, injection and insider threat models, per-group calibration with a guarantee, an SBI standard proposal | Bible Parts 0-48 | Breadth over depth in places: several cues are provisional |

## What to lead with in 60 seconds

1. The gap nobody names (unsigned preview, signed capture, nothing links them), in one sentence.
2. The two interfaces and the four locks.
3. The limits, before any number: no real corpus, nothing on a real device, a scaffold.
4. Our own red team's two findings (R-01, R-03), as proof the claims are checked.
5. The ask: how MOSIP takes spec proposals (Q12), and the model licences (Q16).

## What not to say

| Do not say | Say instead |
|---|---|
| "It stops deepfakes / masks / all screen replays" | "Injection is vetoed when the device or the OS identifies a virtual camera; a flash cue would catch a stream that cannot answer random colours, built and tested on synthetic data" |
| "X% accuracy" | "We have not measured error rates on real attacks; zero failures in 60 would still allow about 6%" |
| "Works on Android" | "The engine builds as an Android library and passes its checks; it has not run a session on a phone" |
| "Fair across skin tones" | "Physiological cues only ever vote for life, because they fail some residents for innocent reasons; per-group error rates need a real corpus" |
| "Production ready" | "A working, tested engine and scaffold with its limits listed" |
