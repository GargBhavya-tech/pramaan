# Patch for MOSIP's Registration Client (desktop)

PS04 asks for changes in the Desktop Registration Client. This folder holds a **real patch against MOSIP's own code**:
[`0001-pramaan-liveness-hooks.patch`](0001-pramaan-liveness-hooks.patch), 9 files, about +200 / -5 lines.

| | |
|---|---|
| Repository | https://github.com/mosip/registration-client |
| Branch and commit | `1.2.0.2` at `7b111dae019b6a23fcaa715c08df188ab53378e6` |
| Apply | `git clone --branch 1.2.0.2 https://github.com/mosip/registration-client && cd registration-client && git apply --whitespace=nowarn <path>/0001-pramaan-liveness-hooks.patch` |
| Behaviour with nothing plugged in | **Unchanged.** The hooks default to a no-op, so the patched client behaves exactly like the original |

**Status, stated first.**
- **The patch compiles.** `registration-services` (P3, P4 and the four new classes) **builds with the patch**, under JDK 17, released kernel
  1.2.0.1 (the branch's `-SNAPSHOT` dependencies no longer exist) and Lombok 1.18.30 (1.18.16 does not run on JDK 17): environment changes, not
  code changes. `registration-client` (P1, P2) reports 3 errors, **all in document-scanning code the patch does not touch**
  (`DocScannerFacade.scanDocument` is called with one argument where the module's own source wants two), and none in `Streamer` or
  `GenericBiometricsController`. **The unpatched branch has the same three errors**: a second checkout of the same commit (`7b111da`), built the same way, reports them at the identical lines (`DocumentScanController.java` 139 and 166, `ScanPopUpViewController.java` 658), so they predate the patch.
- **The PRAMAAN side is written and tested.** `regclient-adapter/` holds `PramaanLivenessHook`, which implements the four hook methods with
  our engine; `RegistrationClientAdapterTest` drives it exactly as the client would, against the runnable reference device over real
  loopback HTTP: frames in, the client's own rCapture call with the ticket the hook returns, the device's raw response handed back. It
  proves the nonce the device signed is the engine's own, that a capture made under another transaction id is refused, and that every
  failure ends in a refusal, not a capture without liveness. The engine's analysers in that test are the scripted resident, so it proves
  the plumbing and the binding, **not the detectors**.
- **Not done:** the patch and the adapter have **not run inside the real Registration Client**, not against a certified device and not with a
  person (the client's UI needs its full MOSIP stack). The client has **no screen for challenge prompts** ("please turn your head"): the
  host must show them from the listener passed to `AdapterSettings` (the scaffold's capture screen does), and that screen is not
  patched in. Binding is at the `HEURISTIC` level: the client's rCapture call does not use the SBI Liveness Profile. The Android
  Registration Client is a different repository and is not patched: on Android the engine runs inside the SBI device app (deployment
  Mode D, `docs/android-low-resource.md`).

## What the patch changes (ADR 0003, patches P1 to P4)

| Patch | Where | Change |
|---|---|---|
| P1 | `GenericBiometricsController`, the capture task | Before asking the device for the signed capture, the face scan waits for `LivenessHook.awaitCaptureClearance()`. Today the client requests the capture the moment the stream opens |
| P2 | `Streamer` | Every decoded preview frame (JPEG bytes and arrival time) is also handed to `LivenessHooks.frame(...)`; it never throws, so the preview cannot be stopped by the hook |
| P3 | `MDMRequestDto`, `MosipDeviceSpecification_SBI_1_0_ProviderImpl.getRCaptureRequest` | The request can carry a `transactionId` and a `previousHash` (both from the `CaptureTicket` the hook returns); when set they replace the client's random UUID and its `null`, so the device signs our session nonce and frame hash chain inside the capture. The engine's nonce exists only once it reaches its capture step, which is why the ticket comes from `awaitCaptureClearance`, not from `beginFaceScan` |
| P4 | `GenericBiometricsController` success handler, the provider, `BiometricsDto`, `BIRBuilder` | The client keeps only the decoded payload and the signature of the device's response and **drops the JWS header** (with the certificate chain), which a verifier needs: the provider now also keeps the device's raw response element in `BiometricsDto.sbiResponse`. Before the capture is stored, `LivenessHook.afterCapture(...)` verifies and binds it and returns signed evidence, or refuses it (the client shows its generic capture-failure message and stores nothing). The evidence goes in the face BIR `others` map as `PRAMAAN_LIVENESS`; the `bdb` is untouched |

New files in the client: `io.mosip.registration.liveness.LivenessHook` (the four methods), `CaptureTicket`, `LivenessHooks` (finds an implementation with
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
