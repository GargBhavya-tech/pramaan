# ADR 0003 — Integration depth: a faithful scaffold first (Build Map #74)

- **Status:** Accepted (2026-09-23)
- **Date:** 2026-09-23
- **Still to confirm with the organisers:** whether depth of integration into the real client is judged (Bible Q21).

## Question

Integrate PRAMAAN into MOSIP's real `registration-client` repository now, or build a faithful scaffold — the same
capture flow and the same service seams — and write down the patch that moves it into the real client?

## What the real client does today (read from source, `release-1.2.0.x`)

| Step | Where | What it does |
|---|---|---|
| Scan pressed | `controller/device/GenericBiometricsController.scan(ActionEvent)` | Device search task: `MosipDeviceSpecificationFactory.getDeviceInfoByModality("FACE_FULLFACE")`, `isDeviceAvailable` |
| Stream | same, on success | `bioService.getStream(device, "FACE_FULLFACE")`, then `streamer.startStream(urlStream, imageView, imageView)` (`controller/device/Streamer`, an M-JPEG reader thread painting an `ImageView`) |
| Capture | same, **immediately before** starting the stream | `rCaptureTaskService()` → `rCapture(...)` → `bioService.captureModality(MDMRequestDto)` → `MosipDeviceSpecification_SBI_1_0_ProviderImpl.rCapture` |
| Transaction id | `MosipDeviceSpecificationHelper.generateMDMTransactionId()` | `UUID.randomUUID().toString()` |
| Result | `rCaptureTaskService.setOnSucceeded` | quality check, local de-dup, stores `BiometricsDto` in the registration DTO, shows the image and score |

Three facts decide the patch:

1. **Capture is requested at the same moment streaming starts.** The device captures on its own schedule; nothing can
   run between "the preview shows a face" and "the capture is taken". Liveness needs to run first (Bible Part 12).
2. **The transaction id is a random UUID.** Capture binding needs it to be the session nonce (Part 13, lock 1), and
   the nonce is 32 hex characters because SBI allows only letters, digits and hyphens.
3. **The preview is only painted.** `Streamer` decodes frames for the `ImageView` and discards them; they need to
   reach the engine as well.

## Options

| | Real client now | Scaffold first (chosen) |
|---|---|---|
| Effort to a demo | High: Spring, H2/Derby local DB, master data sync, a MOSIP server or mocks for login and packet upload | Low: one JavaFX window over the same SBI device service |
| Schedule risk | Build Map R3: the classic schedule killer | Contained |
| Proves | That the patch applies to 1.2.x | That the flow works end to end against MockMDS; the patch is written down against named classes |
| Version risk | Must match its JavaFX and Java | Uses the same: OpenJFX **11.0.2**, Java 11 bytecode |

## Decision

1. **Scaffold first**, in `desktop` (`pramaan.desktop.capture`, `pramaan.desktop.scaffold`): a registration
   biometrics screen for the face modality that follows the real client's steps — device search, stream, capture,
   result — with the liveness engine between stream and capture.
2. **JavaFX 11.0.2**, the version `registration/pom.xml` pins (`openjfx.version`), in `desktop` only. `core` stays
   UI-free (Build Map #1).
3. **The patch into the real client**, to apply when depth is confirmed:

| # | Class in `registration-client` 1.2.0.x | Change |
|---|---|---|
| P1 | `GenericBiometricsController.scan` | Do not call `rCaptureTaskService()` before streaming. Start the stream, hand frames to `LivenessEngine`; the engine asks for the capture when the session reaches CAPTURING |
| P2 | `Streamer` | Pass every decoded frame (bytes + arrival time) to the engine's `FrameSink` as well as the `ImageView` |
| P3 | `MDMRequestDto`, `MosipDeviceSpecification_SBI_1_0_ProviderImpl.getRCaptureRequest` | Carry a `transactionId` in the request DTO and use it instead of `generateMDMTransactionId()` when present (the session nonce) |
| P4 | `rCaptureTaskService.setOnSucceeded` | Before storing the `BiometricsDto`: `CaptureBinder` must have passed (the engine's ACCEPTED); put the signed evidence JWS in the face BIR `others` (`PRAMAAN_LIVENESS`, Part 14) |
| P5 | `GenericBiometricFXML.fxml` / `GenericBiometricsController` | Face oval, guidance and challenge prompt nodes, driven by `EngineListener` message ids — no scores (Part 19) |
| P6 | Operator login (face authentication path) | Strict liveness **before** the match (#76) |
| P7 | Global parameters (`ApplicationContext` map, synced from the server) | Two keys holding the policy YAML and the operating-point table, read through a `ConfigurationSource`, so a centre's policy changes with a configuration push, never a rebuild (#78) |

The patch itself is in `integration/registration-client/` (against `registration-client` 1.2.0.2).

In the scaffold these seams are the same objects: `CaptureSession` is P1 + P2 (it owns the stream and hands
frames to both the preview and the engine), `SbiFrameSource.capture` already sends the nonce (P3), and the
capture screen is P5. **P4 is built:** `EvidencePackager.pack` refuses anything but an ACCEPTED session,
assembles the signed record (`EvidenceBuilder` + `EvidenceSigner`) and hands it to `CbeffOthers` to embed as
`PRAMAAN_LIVENESS`; `EvidenceStore` is the scaffold's own storage for the resulting packet
(`<sessionId>-a<attempt>.cbeff.xml` + `.evidence.jws` + `.capture.jws`), plus the transparency-log append (Part 42)
that P4's real-client patch would also need to trigger.

## Consequences

- The demo runs against MockMDS or any SBI 0.9.5/1.0 face device, without a MOSIP server.
- Nothing is claimed about the real client that the table above does not name. The patch is a design until it is
  applied and built there.
- If the organisers judge depth, P1–P4 are the minimum change, and all four sit in two classes and one DTO.
