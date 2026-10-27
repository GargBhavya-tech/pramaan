# Patch for MOSIP's Android Registration Client

PS04 asks for face liveness detection and presentation attack detection across both desktop and mobile registration clients. This folder holds a **real patch against MOSIP's Android Registration Client codebase**:
[`0001-pramaan-android-liveness-hooks.patch`](0001-pramaan-android-liveness-hooks.patch), 10 files, about +220 / -4 lines.

| | |
|---|---|
| Repository | https://github.com/mosip/android-registration-client |
| Branch and commit | `release-1.2.0.x` / `1.2.0.2` |
| Apply | `git clone --branch release-1.2.0.x https://github.com/mosip/android-registration-client && cd android-registration-client && git apply --whitespace=nowarn <path>/0001-pramaan-android-liveness-hooks.patch` |
| Behaviour with nothing plugged in | **Unchanged.** The hooks default to a no-op singleton (`LivenessHooks.NONE`), so the patched client behaves exactly like the original. |

## Status, stated first

- **The patch compiles.** The patch touches Android Java code in `app/src/main/java/io/mosip/registration/client/`, compiling against Android SDK 34 (minSdk 26) with Java 11 bytecode compatibility.
- **The PRAMAAN side is written and tested.** `android-regclient-stub/` contains compile-time stand-in classes matching the patch; `StubMatchesAndroidPatchTest` proves line-for-line parity with the patch file. `android-regclient-adapter/` holds `PramaanAndroidLivenessHook`, which implements the five hook methods against `pramaan:core` and `pramaan:android` (`AndroidEngines`).
- **Fail-closed guarantees.** Without configuration, missing hooks, or upon timeout/spoof detection, every scan is refused (`LivenessRefusedException`). Capture proceeds only when cryptographic clearance (`CaptureTicket`) is granted.
- **Hardware acceleration.** On low-resource Android devices, PRAMAAN supports CPU, XNNPACK, and NNAPI acceleration via `InferenceOptions` (see [`docs/android-low-resource.md`](../../docs/android-low-resource.md)).

## What the patch changes (Patches P1 to P5)

| Patch | Where | Change |
|---|---|---|
| P1 | `FaceCaptureActivity`, capture task | Before asking the device for the signed capture, the face scan waits for `LivenessHook.awaitCaptureClearance()`. Originally, the Android client launched the capture request simultaneously with the stream. |
| P2 | `Streamer` | Every decoded preview frame (JPEG bytes and arrival timestamp) is forwarded to `LivenessHooks.frame(...)` on a background handler; it never throws, so the camera preview cannot stutter or drop frames. |
| P3 | `MDMRequestDto`, `MosipDeviceSpecification_SBI_1_0_ProviderImpl` | The capture request carries `transactionId` and `previousHash` from the `CaptureTicket`; when present they replace random UUIDs so the device signs the engine's session nonce and frame hash chain. |
| P4 | `FaceCaptureActivity.onCaptureCompleted`, `BIRBuilder` | Keeps the raw SBI response element in `BiometricsDto.sbiResponse`, verifies device signature against CA, and binds it via `LivenessHook.afterCapture(...)`. The resulting signed JWS evidence travels in the BIR `others` map under key `PRAMAAN_LIVENESS`. |
| P5 | `activity_face_capture.xml`, `FaceCaptureActivity` | Challenge prompt and guidance overlay (`liveness_overlay_container`, `liveness_prompt_text`, `liveness_guidance_text`) wired via `LivenessHook.setPromptListener(...)` to display unpredictable active challenges ("please turn head left", "please blink") directly on the mobile screen. |

New classes added to the Android client:
- `io.mosip.registration.client.liveness.LivenessHook`: The hook interface (the five methods).
- `io.mosip.registration.client.liveness.CaptureTicket`: The clearance ticket carrying `transactionId` and `previousHash`.
- `io.mosip.registration.client.liveness.LivenessHooks`: Discovers hook via `ServiceLoader`; defaults to safe no-op `NONE`; fails closed if installed hook cannot load.
- `io.mosip.registration.client.liveness.LivenessRefusedException`: Thrown to cancel capture and abort registration on attack or failure.
- `io.mosip.registration.client.liveness.PromptListener`: Callback for real-time challenge prompt and guidance rendering.

## How PRAMAAN plugs into Android

On Android, MOSIP supports two deployment architectures:
1. **Mode C (Client integration):** The Android Registration Client links `pramaan:android-regclient-adapter` and `pramaan:pramaan-android` (the AAR). The hook runs in-process with the mobile UI, showing live prompts and computing liveness before firing the SBI `rCapture` intent.
2. **Mode D (Device SBI integration):** The liveness engine runs inside the Android SBI device service app itself ([`docs/android-low-resource.md`](../../docs/android-low-resource.md)), returning the signed capture with device signature.

Both modes share the identical core detection algorithms, models, and cryptographic evidence signing.
