# Patch for MOSIP's Registration Client (desktop)

PS04 asks for changes in the Desktop Registration Client. This folder holds a **real patch against MOSIP's own code**:
[`0001-pramaan-liveness-hooks.patch`](0001-pramaan-liveness-hooks.patch), 9 files, about +200 / -5 lines.

| | |
|---|---|
| Repository | https://github.com/mosip/registration-client |
| Tag and commit | `v1.2.0.2` at `cc25d4dd89ce5f7bbd83ef0f7ff7e6a12a9a6161` (`cc25d4d`) |
| Apply | `git clone --depth 1 --branch v1.2.0.2 https://github.com/mosip/registration-client && cd registration-client && git apply --check <path>/0001-pramaan-liveness-hooks.patch` |
| Behaviour with nothing plugged in | **Unchanged.** The hooks default to a no-op, so the patched client behaves exactly like the original |

**Status, stated first.**
- **Patch integrity and verification.** The patch applies cleanly with zero warnings or errors (`git apply --check` exits with code 0) against an actual checkout of upstream `mosip/registration-client` at tag `v1.2.0.2` (commit `cc25d4d`). Compile of full upstream client not yet verified (unpatched upstream branch already has document scanner compile errors); in-tree stubs (`regclient-stub`) and adapter (`regclient-adapter`) compile and pass 100% of tests.
- **The PRAMAAN side is written and tested.** `regclient-adapter/` holds `PramaanLivenessHook`, which implements the four hook methods with
  our engine; `RegistrationClientAdapterTest` drives it exactly as the client would, against the runnable reference device over real
  loopback HTTP: frames in, the client's own rCapture call with the ticket the hook returns, the device's raw response handed back. It
  proves the nonce the device signed is the engine's own, that a capture made under another transaction id is refused, and that every
  failure ends in a refusal, not a capture without liveness. The engine's analysers in that test are the scripted resident, so it proves
  the plumbing and the binding, **not the detectors**.
- **Not done:** the patch and the adapter have **not run inside the real Registration Client**, not against a certified device and not with a
  person (the client's UI needs its full MOSIP stack). Binding is at the `HEURISTIC` level: the client's rCapture call does not use the
  SBI Liveness Profile. The challenge prompt overlay node is patched in via **P5**, rendering prompt and guidance text directly over
  `biometricImage` (see screenshot in `docs/media/challenge-prompt.png`). The Android Registration Client patch is maintained in
  `integration/android-registration-client/`.

## What the patch changes (ADR 0003, patches P1 to P5)

| Patch | Where | Change |
|---|---|---|
| P1 | `GenericBiometricsController`, the capture task | Before asking the device for the signed capture, the face scan waits for `LivenessHook.awaitCaptureClearance()`. Today the client requests the capture the moment the stream opens |
| P2 | `Streamer` | Every decoded preview frame (JPEG bytes and arrival time) is also handed to `LivenessHooks.frame(...)`; it never throws, so the preview cannot be stopped by the hook |
| P3 | `MDMRequestDto`, `MosipDeviceSpecification_SBI_1_0_ProviderImpl.getRCaptureRequest` | The request can carry a `transactionId` and a `previousHash` (both from the `CaptureTicket` the hook returns); when set they replace the client's random UUID and its `null`, so the device signs our session nonce and frame hash chain inside the capture. The engine's nonce exists only once it reaches its capture step, which is why the ticket comes from `awaitCaptureClearance`, not from `beginFaceScan` |
| P4 | `GenericBiometricsController` success handler, the provider, `BiometricsDto`, `BIRBuilder` | The client keeps only the decoded payload and the signature of the device's response and **drops the JWS header** (with the certificate chain), which a verifier needs: the provider now also keeps the device's raw response element in `BiometricsDto.sbiResponse`. Before the capture is stored, `LivenessHook.afterCapture(...)` verifies and binds it and returns signed evidence, or refuses it (the client shows its generic capture-failure message and stores nothing). The evidence goes in the face BIR `others` map as `PRAMAAN_LIVENESS`; the `bdb` is untouched |
| P5 | `GenericBiometricFXML.fxml`, `GenericBiometricsController` | Challenge prompt and guidance overlay (`livenessOverlayPane`, `livenessPromptLabel`, `livenessGuidanceLabel`) wired via `LivenessHook.setPromptListener(...)` to display unpredictable active challenges ("please turn head left", "please blink") directly on the live camera stream |

New files in the client: `io.mosip.registration.liveness.LivenessHook` (the hook methods), `CaptureTicket`, `PromptListener` (challenge prompt listener), `LivenessHooks` (finds an implementation with
`java.util.ServiceLoader`; none means a no-op; one that fails to load refuses the scan rather than skipping liveness), and
`LivenessRefusedException`.

## How the PRAMAAN side plugs in (`regclient-adapter/`)

| `LivenessHook` method | What `PramaanLivenessHook` does |
|---|---|
| `beginFaceScan` | starts a liveness session (`EngineBundle.newSession`) and searches for the device, only to learn what it is and to verify its captures; the stream stays the client's |
| `onPreviewFrame` | queues the frame (the oldest is dropped if the engine is behind) for the engine on a thread of its own, so the client's preview never waits for it |
| `awaitCaptureClearance` | blocks until the engine, having passed liveness, asks for the capture; returns its nonce and frame hash chain as the `CaptureTicket`. Refuses on a timeout, a lockout or an unavailable device |
| `afterCapture` | hands the engine the device's raw response (`sbiResponse`), which it verifies against the pinned CA exactly as it verifies a capture it requested itself (`SbiFrameSource.verifyCaptureResponse`), waits for the four locks, and returns the signed evidence JWS (`EvidencePackager.pack`), or refuses |

Setup, once, in the client's start-up code: `PramaanLivenessHook.configure(AdapterSettings.builder(bundle, deviceCa, packager, clock, random,
subjectSupplier).build())`, with the class registered in `META-INF/services/io.mosip.registration.liveness.LivenessHook` (the adapter ships that file).
Without settings the hook **refuses every scan**. Compile-time stand-ins for the client's hook classes are in `regclient-stub/` (never shipped);
`StubMatchesPatchTest` fails if they drift from the patch.

## Why a hook interface and not the engine inside the client

The client is MOSIP's, with its own release cycle and dependencies. A four-method interface with a no-op default is small enough to
review and to carry upstream, and leaves the engine free to change without another patch. The cost: nothing here proves the engine works
inside the real client until someone runs the adapter in it.
