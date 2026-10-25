# Performance: measured on one laptop

PS04 names performance testing. These are **measured numbers from one machine, CPU only, one 63-frame clip**. They are not
a phone and not a real camera; nothing here has run on Android (see [`android-low-resource.md`](android-low-resource.md)).

| | |
|---|---|
| Machine | 13th Gen Intel Core i7-1355U, 10 cores / 12 threads, 15.6 GB RAM, Windows 11 |
| Runtime | JDK 24 (Java 11 bytecode), ONNX Runtime 1.20.0, CPU execution provider, default threads |
| Clip | `sbi/testdata/stream-sample.mjpeg`: 63 JPEG frames from the MOSIP MockMDS (not committed: it shows a real person) |
| Command | `./gradlew :harness:pipelineBench` |

## Per-stage latency (milliseconds per frame, one thread of the pipeline)

Three quiet-machine runs; each cell is the p50 and p95:

| Stage | Run A p50 / p95 | Run B p50 / p95 | Run C p50 / p95 |
|---|---|---|---|
| JPEG decode | 22.8 / 35.9 | 16.8 / 29.0 | 24.1 / 42.8 |
| Face detection (YuNet) | 43.5 / 55.7 | 29.5 / 41.3 | 43.2 / 54.2 |
| Quality gate | 2.7 / 5.7 | not captured | not captured |
| Passive liveness (MiniFASNet, forced on a detected face) | 40.5 / 101.6 | 43.9 / 97.6 | 48.7 / 155.0 |

Reading it: a frame that goes through decode, detection and the quality gate costs about 70 to 110 ms at the median. Passive
liveness is run on every second frame (`passive every : 2 frame(s)`), so the passive model adds about 20 to 25 ms per frame on
average. That is roughly **9 to 14 frames per second of headroom on this laptop**, against the **3 fps the SBI stream
guarantees**. The headroom is real on a laptop and says nothing about a low-end phone.

## What is not in these numbers

- **Noise.** The same command on a busy machine gave 3 to 10 times slower results (for example detection p50 440 to 570 ms)
  while two stray search processes were running. Quote the quiet-machine numbers only with that caveat, and re-run on the
  machine you demo on.
- **The other models.** The landmark, blendshape and SFace models are not in this benchmark. SFace is the largest and runs
  only for the identity lock and the operator-face check.
- **Memory.** The benchmark prints Java heap in use (about 7 MB), but ONNX Runtime keeps the models in native memory, so that
  figure understates the process. Use Task Manager or `Get-Process` for the real working set.
- **End to end.** There is no measurement of a whole session (passive, challenge, capture, binding) on a real device.
- **Accelerators and Android.** Measured on one phone, see "On a phone" below; the accelerated latency is not measured.

## How to reproduce

```bash
./gradlew :harness:pipelineBench --no-daemon          # close heavy programs first; run it three times and use the quiet ones
```

Needs the six model files in `models/` and the MockMDS clip (`python tools/capture_sbi_fixtures.py` regenerates it from a
running MockMDS). Without them the benchmark cannot run.

## On a phone

Measured on one real phone on 2026-10-25 (a Xiaomi 22071219AI, Android 14, 8 cores): detection about 190 ms and passive liveness about
21 ms per frame at the median, about 4.8 frames a second, against the 3 fps the SBI stream guarantees, with accelerator parity of about
10^-6 for both XNNPACK and NNAPI. The table, the profile used and what is not measured are in
[`android-low-resource.md`](android-low-resource.md#measured-on-a-real-phone-2026-10-25-one-device-one-run).
