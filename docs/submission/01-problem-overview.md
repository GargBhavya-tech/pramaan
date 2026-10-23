# Problem overview: MOSIP Decode 2026, problem statement 04

**Face Liveness Detection and Presentation Attack Detection**, rated High complexity. The task, as the Unstop listing
summarises it: enable the **Desktop and Android Registration Clients** to process **face image streams from biometric
devices**, detect liveness and presentation attacks, and guide the resident through **active facial challenges**. The
Bible quotes the two things the statement asks to be defined: *"Define the face frame streaming interface"* and *"Define
the liveness/PAD processing interface"* (Bible Part 4). The exact wording is on the Unstop page.

## Where this sits in MOSIP

MOSIP is an open-source platform countries use to build national ID systems. In a registration centre an operator uses
the **Registration Client** (JavaFX on desktop, Flutter with native plugins on Android) to enrol a resident: demographics,
fingerprints, iris and a face photo, packaged into a signed, encrypted **registration packet** and sent to the server.
The face is captured through a certified **Secure Biometric Interface (SBI)** device service. The device exposes a live
preview **stream** and a signed **rcapture**.

## What can go wrong

| Attack | Example | Named in the statement? |
|---|---|---|
| Print | A photograph held to the lens | Yes |
| Screen | A phone or tablet showing a photo or video | Yes |
| Replay | A recorded blink, smile or head turn | Yes |
| Mask | Paper, resin or silicone | Implied |
| **Injection** | A virtual camera (OBS), a hacked driver or a real-time deepfake feeding frames straight into software; no lens involved | **No.** Outside ISO/IEC 30107, which covers presentation attacks only (CEN/TS 18099 covers injection) |
| **Insider** | A corrupt operator logs in with a photo of the supervisor, or enrols their own face for a resident | **No** |

## The gap nobody names

On a desktop the SBI stream is an **unsigned** M-JPEG feed (guaranteed only 3 frames per second), and the capture that is
stored is **signed**. Nothing links them. An attacker who controls the preview can show one face to the liveness check
and have another face captured and signed. On **Android** the client never sees preview frames at all: the device app
shows the stream in its own foreground screen and returns only the signed capture, so frame-based liveness there has to
run inside the SBI app. And a verdict made on the client today cannot be re-checked by the server later, or re-scored when
better models exist.

## What a good answer has to do

1. Define the two interfaces so the same engine runs against a real SBI device and a mock one.
2. Catch print, screen and replay, and do it without punishing honest residents: poor light, age, disability, darker
   skin and a missing blink are not evidence of an attack.
3. Bind the liveness result to the signed capture, so a capture swap fails.
4. Detect injected video, which a camera-only approach cannot see.
5. Leave proof behind: evidence a server or auditor can re-check years later.
6. Say honestly what was measured. ISO/IEC 30107-3 reports APCER per attack type and BPCER, each with a confidence
   interval; it deprecated the single ACER number. Zero failures in 60 attacks still allows a true rate near 6%.

PRAMAAN's answer to each is in the 2-page document (`PRAMAAN-solution-2page.pdf`) and the Bible, Parts 3-14.
