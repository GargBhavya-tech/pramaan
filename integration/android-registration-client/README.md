# Patch for MOSIP's Android Registration Client

PS04 asks for face liveness detection and presentation attack detection across both desktop and mobile registration clients. This folder holds a **real patch generated from an actual checkout of MOSIP's Android Registration Client codebase**:
[`0001-pramaan-android-liveness-hooks.patch`](0001-pramaan-android-liveness-hooks.patch), 8 files, +249 insertions, -0 deletions.

| | |
|---|---|
| Repository | https://github.com/mosip/android-registration-client |
| Architecture | Flutter UI with native Java services under `android/app` and `android/clientmanager` |
| Apply | `git clone https://github.com/mosip/android-registration-client && cd android-registration-client && git apply --check <path>/0001-pramaan-android-liveness-hooks.patch` |
| Behaviour with nothing plugged in | **Unchanged.** The hooks default to a no-op singleton (`LivenessHooks.NONE`), so the patched client behaves exactly like the original. |

## Status, stated first

- **Patch integrity and verification.** The patch applies cleanly with zero warnings or errors (`git apply --check` exits with code 0) against an actual checkout of upstream `mosip/android-registration-client`. This verification runs automatically in CI on every push.
- **The PRAMAAN side is written and tested.** `android-regclient-stub/` contains compile-time stand-in classes matching the patch; `StubMatchesAndroidPatchTest` proves 100% line-for-line parity with the patch file. `android-regclient-adapter/` holds `PramaanAndroidLivenessHook`, which implements the five hook methods against `pramaan:core` and `pramaan:android`.
- **Fail-closed guarantees.** Without configuration, missing hooks, or upon timeout/spoof detection, every scan is refused (`LivenessRefusedException`). Capture proceeds only when cryptographic clearance (`CaptureTicket`) is granted.
- **Hardware acceleration.** On low-resource Android devices, PRAMAAN supports CPU, XNNPACK, and NNAPI acceleration via `InferenceOptions` (see [`docs/android-low-resource.md`](../../docs/android-low-resource.md)).

## What the patch changes

The upstream repository is a Flutter application with native Android Java code in `android/app` (Flutter Pigeon bridges & UI) and `android/clientmanager` (biometrics management & SBI intent messaging). The patch instruments the native Java layer:

| Where | Change |
|---|---|
| `android/app/.../api_services/BiometricsDetailsApi.java` | In `discoverSBI`, calls `LivenessHooks.get().beginFaceScan(currentModality.name())`. In `rcapture`, waits for clearance via `LivenessHooks.get().awaitCaptureClearance()` and sets `transactionId` and `previousHash` on the capture request from the `CaptureTicket`. In `parseRCaptureResponse`, calls `LivenessHooks.get().afterCapture(biometricsDtoList)` to cryptographically verify device response and attaches the signed JWS evidence to each `BiometricsDto`. |
| `android/clientmanager/.../dto/registration/BiometricsDto.java` | Adds `livenessEvidence` (signed JWS token) and `sbiResponse` (raw device capture element) fields with getters and setters. |
| `android/clientmanager/.../service/Biometrics095Service.java` | Preserves the device's raw response element (`bio.getData()`) in `biometricsDto.setSbiResponse(...)` so the liveness verifier has cryptographic attestation headers. |

New classes added to the Android client under `android/app/src/main/java/io/mosip/registration_client/liveness/`:
- `io.mosip.registration_client.liveness.CaptureTicket`: The clearance ticket carrying `transactionId` and `previousHash`.
- `io.mosip.registration_client.liveness.LivenessHook`: The hook interface (the five methods).
- `io.mosip.registration_client.liveness.LivenessHooks`: Discovers hook via `ServiceLoader`; defaults to safe no-op `NONE`; fails closed if installed hook cannot load.
- `io.mosip.registration_client.liveness.LivenessRefusedException`: Thrown to cancel capture and abort registration on attack or failure.
- `io.mosip.registration_client.liveness.PromptListener`: Callback for real-time challenge prompt and guidance rendering.

## How PRAMAAN plugs into Android

On Android, MOSIP supports two deployment architectures:
1. **Mode C (Client integration):** The Android Registration Client links `pramaan:android-regclient-adapter` and `pramaan:pramaan-android` (the AAR). The hook runs in-process with the mobile app, showing live prompts and computing liveness before firing the SBI `rCapture` intent.
2. **Mode D (Device SBI integration):** The liveness engine runs inside the Android SBI device service app itself ([`docs/android-low-resource.md`](../../docs/android-low-resource.md)), returning the signed capture with device signature.

Both modes share the identical core detection algorithms, models, and cryptographic evidence signing.
