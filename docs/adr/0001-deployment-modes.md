# ADR 0001 — Deployment modes: one engine, two places it runs

- **Status:** Accepted
- **Date:** 2026-09-23
- **Build Map:** #5 · **Bible:** Part 1.2, Part 47.5 · **Evidence:** `docs/sbi-notes.md`

## Context

Liveness needs frames. Whether the Registration Client gets frames depends on the platform, and the SBI
specification decides it, not us.

| Platform | What the spec says | What the client receives |
|---|---|---|
| Desktop | "Live Video stream with a quality of 3 frames per second or more using M-JPEG." · "HTTP Chunk of frames to be displayed." | An **unsigned** M-JPEG stream over loopback HTTP, then a **signed** rcapture |
| Android | "A separate stream intent is not raised" · "On receiving rCapture request MDS must show the stream within its UI in the foreground." | **No frames at all** — only the signed rcapture result returned through the intent |

Verified on 2026-09-22 against MOSIP's MockMDS 1.3.0 (desktop): stream is `multipart/x-mixed-replace`, ~21 fps from
the mock; the specification only guarantees 3 fps.

## Decision

1. **One engine (`core`)**, headless, with no UI, device or network code. Enforced by `CoreIsolationTest` and
   `checkCoreDependencies` (Build Map #1).
2. **Desktop = Mode C.** The engine runs inside the Registration Client and analyses the SBI preview stream, then binds
   its verdict to the signed rcapture (Phase 8).
3. **Android = Mode D.** The engine runs **inside the SBI device app**, which is the only process that sees frames. It
   ships as an AAR that device vendors embed; our reference is MOSIP's Android mock SBI (Phase 12). The verdict travels
   back with the signed capture.
4. **Mode H (hybrid)** — device computes passive/physics cues, client runs challenges and binding — stays possible
   through the same interfaces but is not built first.
5. **The engine never assumes more than 3 fps.** It measures the real rate and gates cues on it (Build Map #14).

## Alternatives rejected

| Alternative | Why rejected |
|---|---|
| Android client opens its own camera for liveness | That camera is not the certified SBI device: frames and the signed capture come from different sensors, which is exactly the capture-swap hole (Bible Part 13) |
| Run liveness only on the final signed capture (one image) | Single-image passive PAD only; no challenges, no temporal or geometric cues, no injection defence |
| Ask vendors for a new SBI stream API before building | Blocks the whole project on a spec change; we propose it separately (SBI Liveness Profile, Bible Part 41) |
| Two engines, one per platform | Guarantees drift; the conformance claim (Build Map #82) becomes impossible |

## Consequences

- Two integration tracks share one engine: desktop (Build Map Phase 11) and Android (Phase 12). Desktop comes first.
- Everything platform-specific stays outside `core`. A leak shows up as a failing build, not a surprise in Phase 12.
- Mode D puts PAD **inside** the device trust boundary with raw frames — the strongest deployment, and the argument for
  the SBI Liveness Profile proposal.
- Judges and mentors may ask "why not just use the tablet camera?" — the first rejected alternative is the answer.
