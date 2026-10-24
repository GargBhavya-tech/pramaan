# Android: low-resource tuning and hardware acceleration

PS04 bonus tasks: optimise liveness for low-resource Android devices, and support hardware acceleration where available.

**Status, stated first:** the code is written, compiles in the Android build and is unit-tested on the desktop JVM (where the
accelerators are unavailable, so the fallback path is what the tests exercise). **Nothing here has run on a phone.** The numbers
a phone will give (latency, memory, whether NNAPI or XNNPACK is present and faster) are unmeasured.

## What it does

| Piece | Where | Behaviour |
|---|---|---|
| `InferenceOptions` | `core/.../inference/InferenceOptions.java` | threads, sequential execution, and a requested accelerator: `CPU`, `NNAPI`, `XNNPACK`, `DIRECTML`, `COREML` |
| `OnnxModel.load(bytes, options)` | `core/.../inference/OnnxModel.java` | tries the accelerator; if it is not in the runtime or the device has no driver, loads on the CPU and records why (`provider()`, `fallbackReason()`) |
| `AndroidEngines.defaultOptions()` | `android/.../AndroidEngines.java` | low-resource profile on the **CPU** path: at most 2 intra-op threads, 1 inter-op thread, sequential execution |
| `AndroidEngines.loadFromAssets(..., options)` | same | the host app opts in to an accelerator |
| `AcceleratorParity.compare` | `core/.../inference/AcceleratorParity.java` | runs one model on the CPU and on the candidate with the same input and reports the largest output difference and which provider really ran |

## Why the accelerator is opt-in

`config/operating-points.json` (weights, device bias, gap, τ) was calibrated on the CPU path. An accelerator may compute in half
precision and move a score slightly, which could move a decision near a threshold. A fallback to the CPU is safe for security (same
model, same hashes, same decision logic) and the code never skips liveness because an accelerator is missing.

So the order is:

1. Run with the default profile (CPU, few threads). This is the path that was evaluated.
2. On the phone, run `AcceleratorParity.compare` for each model with a few real crops. Look at `provider()` (did it really use
   the accelerator?) and `maxAbsDifference()`.
3. Only if the difference is small against the margin your thresholds need, enable the accelerator, then **re-run the evaluation
   harness with it on** before relying on the operating points.

```java
// default: calibrated CPU path, tuned for a low-end phone
EngineBundle bundle = AndroidEngines.loadFromAssets(getAssets(), "pramaan");

// opt in after the parity check on this device
InferenceOptions fast = InferenceOptions.lowResource(Runtime.getRuntime().availableProcessors(),
        InferenceOptions.Accelerator.XNNPACK);
EngineBundle bundle2 = AndroidEngines.loadFromAssets(getAssets(), "pramaan", fast);
```

## Which accelerator, when

| Accelerator | Good for | Watch out |
|---|---|---|
| `XNNPACK` | low-end ARM phones: faster CPU kernels, same precision | needs a runtime build with XNNPACK |
| `NNAPI` | phones with a GPU, DSP or NPU driver | drivers vary a lot by vendor and Android version; may be fp16; some devices are slower than the CPU |
| `CPU` (default) | everything; the calibrated path | slowest on big models |

The detector (YuNet), the passive model (MiniFASNet) and the landmark model are small. The embedding model (SFace) is the largest;
the engine runs it only for the identity lock and the operator-face guard.

## To test on a phone (next step, yours to run)

```bash
./gradlew -p android :pramaan-android:connectedDebugAndroidTest   # runs EngineOnDeviceTest on a connected device
```

Then add, in the on-device test or your app: the parity check above, a timing loop around one frame through the engine, and a
record of `provider()` for every model. Report latency per frame at the camera's real frame rate: the SBI stream guarantees only
3 fps, so cues that need more are switched off at session start.
