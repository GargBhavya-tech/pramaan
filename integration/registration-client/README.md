# Patch for MOSIP's Registration Client (desktop)

PS04 asks for changes in the Desktop Registration Client. This folder holds a **real patch against MOSIP's own code**:
[`0001-pramaan-liveness-hooks.patch`](0001-pramaan-liveness-hooks.patch), 9 files, about +200 / -5 lines.

| | |
|---|---|
| Repository | https://github.com/mosip/registration-client |
| Branch and commit | `1.2.0.2` at `7b111dae019b6a23fcaa715c08df188ab53378e6` |
| Apply | `git clone --branch 1.2.0.2 https://github.com/mosip/registration-client && cd registration-client && git apply --whitespace=nowarn <path>/0001-pramaan-liveness-hooks.patch` |
| Behaviour with nothing plugged in | **Unchanged.** The hooks default to a no-op, so the patched client behaves exactly like the original |

**Status, stated first.** **Compile check:** `registration-services` (P3, P4 and the three new classes) **builds with the patch**, under JDK 17, released kernel
1.2.0.1 (the branch's `-SNAPSHOT` dependencies no longer exist) and Lombok 1.18.30 (1.18.16 does not run on JDK 17); those are
environment changes, not code changes. `registration-client` (P1, P2) reports 3 errors, **all in document-scanning code the patch does
not touch** (`DocScannerFacade.scanDocument` is called with one argument where the module's own source wants two), and none in `Streamer` or
`GenericBiometricsController`; I did not build the unpatched branch to prove those three are pre-existing. The patch has **not been run inside the Registration Client**, not against a device and not with
a person: the client's UI needs its full MOSIP stack. The PRAMAAN side of the hook (an implementation of `LivenessHook` that calls our
engine) is **not written**; the table below says what each method would do. The Android Registration Client is a different
repository and is not patched: on Android the engine runs inside the SBI device app (deployment Mode D, `docs/android-low-resource.md`).

## What the patch changes (ADR 0003, patches P1 to P4)

| Patch | Where | Change |
|---|---|---|
| P1 | `GenericBiometricsController`, the capture task | Before asking the device for the signed capture, the face scan waits for `LivenessHook.awaitCaptureClearance()`. Today the client requests the capture the moment the stream opens |
| P2 | `Streamer` | Every decoded preview frame (JPEG bytes and arrival time) is also handed to `LivenessHooks.frame(...)`; it never throws, so the preview cannot be stopped by the hook |
| P3 | `MDMRequestDto`, `MosipDeviceSpecification_SBI_1_0_ProviderImpl.getRCaptureRequest` | The request can carry a `transactionId`; when set it replaces the client's random UUID, so the device signs our session nonce inside the capture |
| P4 | `GenericBiometricsController` success handler, `BiometricsDto`, `BIRBuilder` | Before the capture is stored, `LivenessHook.afterCapture(...)` binds it and returns signed evidence, or refuses it (the client shows its generic capture-failure message and stores nothing). The evidence goes in the face BIR `others` map as `PRAMAAN_LIVENESS`; the `bdb` is untouched |

New files in the client: `io.mosip.registration.liveness.LivenessHook` (the four methods), `LivenessHooks` (finds an implementation with
`java.util.ServiceLoader`; none means a no-op; one that fails to load refuses the scan rather than skipping liveness), and
`LivenessRefusedException`.

## How PRAMAAN would plug in (not yet written)

| `LivenessHook` method | What the PRAMAAN implementation does |
|---|---|
| `beginFaceScan` | `EngineBundle.newSession(profile)`; returns the session nonce (32 hex characters, valid as an SBI `transactionId`) |
| `onPreviewFrame` | hands the frame to the session |
| `awaitCaptureClearance` | blocks until the session reaches `CAPTURING`; throws `LivenessRefusedException` if it ends in a failure |
| `afterCapture` | `CaptureBinder` runs the four locks on the signed capture; `EvidencePackager.pack` returns the signed JWS, or the capture is refused |

It would be a class in the client's classpath, registered in `META-INF/services/io.mosip.registration.liveness.LivenessHook`, calling the
engine's jar (`core` and `sbi`). The scaffold in `desktop/` already does all four steps in one object (`CaptureSession`), so the mapping is direct.

## Why a hook interface and not the engine inside the client

The client is MOSIP's, with its own release cycle and dependencies. A four-method interface with a no-op default is small enough to
review and to carry upstream, and leaves the engine free to change without another patch. The cost: nothing here proves the engine works
inside the real client until someone builds the adapter and runs it.
