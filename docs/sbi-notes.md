# SBI contract notes (Build Map #3, #4)

What the MOSIP Secure Biometric Interface actually guarantees, from the specification and from real responses captured
from MOSIP's mock device service. Every later ticket that touches devices relies on this page, so each claim is either a
spec quote or an observation you can re-run.

Source: MDS specification 1.2.0 —
<https://github.com/mosip/documentation/blob/1.2.0/docs/id-lifecycle-management/supporting-components/biometrics/mds-specification.md>

## Transport

| Fact | Spec quote | Used by |
|---|---|---|
| Loopback only, ports 4501–4600 | MockMDS `application.properties`: `server.minport=4501`, `server.maxport=4600`, `server.serveripaddress=127.0.0.1` | #12 discovery |
| Custom HTTP verbs | CORS config allows `RCAPTURE, CAPTURE, MOSIPDINFO, MOSIPDISC, STREAM` | #12, #13 |
| Stream is M-JPEG, unsigned, ≥ 3 fps | "Live Video stream with a quality of 3 frames per second or more using M-JPEG." · "HTTP Chunk of frames to be displayed. Minimum frames 3 per second." | #13, #14 |
| Stream is for registration devices only | "This API is visible only for the devices that are registered for the purpose of 'Registration'." | #5 |
| Android shows the stream in the device app | "On receiving rCapture request MDS must show the stream within its UI in the foreground." | #5 (Mode D) |

## rcapture request

Fields: `env`, `purpose`, `specVersion`, `timeout`, `captureTime`, `transactionId`, and `bio[]` with `type`, `count`,
`bioSubType`, `exception`, `requestedScore`, `deviceId`, `deviceSubId`, `previousHash`.

> "The transactionId can have minimum lenght as 4 and max length as 50. Allowed characters in transactionId are
> alphabets, numbers and hyphens (-)."

**Consequence:** the PRAMAAN session nonce is 128 bits encoded as **32 lowercase hex characters** (#18). Base64url is not
allowed — it can contain `_`.

## rcapture response

Each `biometrics[]` entry has a `data` field that is a device-signed JWS, plus a `hash` **outside** the signature.

| Inside the signed JWS payload | Beside it |
|---|---|
| `digitalId`, `bioType`, `deviceCode`, `deviceServiceVersion`, `bioSubType`, `purpose`, `env`, `bioValue`, **`transactionId`**, `timestamp`, `requestedScore`, `qualityScore` | `hash` — "sha256 in hex format in upper case (previous 'hash' + sha256 hash of the current biometric ISO data)" |

**Consequences:**

1. `transactionId` is signed by the device → echoing our nonce proves the capture was made for *this* session. That is
   the replay lock (#54) and it needs no spec change.
2. `hash` is not signed, and doesn't need to be: the verifier recomputes it from the signed `bioValue` and the previous
   hash. Do not add a signature over it (#55).

## previousHash chain

> "For the first capture the previousHash is the SHA256 hash of an empty UTF-8 string."
> "From the second capture the previous capture's 'hash' is used as input. This is used to chain all the captures
> across modalities so all captures have happened for the same transaction and during the same time."

SHA-256 of the empty string: `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855`.

## L0 vs L1

> "For L0 devices, the digital id will be signed using the device key. For L1 devices, the digital id will be signed
> using the FTM key."

Registration capture accepts L0 and L1. PRAMAAN applies a lower prior to L0 (#40).

## What the specification does not have

No liveness, PAD, illuminator-control, capture-burst or capability fields, in 1.2.0 or in the SBI 2.0 changes. This is the
gap the SBI Liveness Profile proposal (Bible Part 41, Build Map #100) addresses.

## Observed from MockMDS (2026-09-22, MockMDS 1.3.0, Registration mode)

Captured with `python tools/capture_sbi_fixtures.py` against MOSIP's `mosip-mock-services/MockMDS`, built and run on
Java 21. The face image in the fixtures is MOSIP's public test photo of a real person, so `rcapture-face.json` and
`stream-sample.mjpeg` are gitignored; the other fixtures carry no biometrics.

| Observation | Detail | Consequence |
|---|---|---|
| Port and devices | Port 4501; three devices: finger slap (id 2), **face (id 3, subId 0)**, iris (id 1); all **L0** | Discovery reads the device type from the base64 `digitalId`, not from ids |
| **Every scalar in a request must be a JSON string** | `timeout`, `count` and `requestedScore` as numbers are rejected ("timeout must be a string, not a number"), then the mock fails with a generic error 123 / 610 | The SBI client (#12) serialises all scalars as strings |
| Nonce echo | The 32-hex `transactionId` we sent came back **inside** the signed JWS payload | Replay lock (#54) confirmed on real data — `RealRCaptureFixtureTest` |
| Signed payload fields | `bioType, bioValue, deviceCode, deviceServiceVersion, digitalId, env, purpose, qualityScore, requestedScore, timestamp, transactionId` | Everything binding needs is signed |
| `hash` | Outside the JWS; upper-case hex | As the spec says |
| **Exact hash formula** | `UPPERHEX(SHA256(prev ‖ SHA256(base64url-decode(bioValue))))` — raw 32-byte digests concatenated; `prev` = SHA-256("") for the first capture, else hex-decoded request `previousHash`. Implemented in `SBIServiceResponse` (lines ~2081–2098) | The verifier (#55) must use raw-digest concatenation, not hex-string concatenation. The spec's prose ("previous 'hash' + sha256 hash of the current biometric ISO data") is ambiguous on this point |
| Chain across calls | The handler takes `previousHash` from the request, so chaining across calls works as specified | #55 can test multi-capture chains against the mock |
| `bioValue` | An ISO/IEC 19794-5 face record (magic `FAC\0`), 67,087 bytes | The capture is an ISO record, not a JPEG — decode accordingly in #56 |
| rcapture latency | ~100–160 ms | Well inside the binding time window (Δ ≤ 2 s, #57) |
| Stream | `multipart/x-mixed-replace; boundary=--BoundaryString`; **~21 fps** (63 JPEG frames in 3 s), 640×360-class frames with a quality overlay drawn by the mock | Real devices may be as slow as 3 fps; the mock is not evidence about fps (#14) |

**A correction worth keeping visible:** a first reading of the mock's source looked at `org.biometric.provider.CaptureRequest`, which
suggested the hash covered the JWS string and restarted every call. That class is not on the rcapture path; the log shows
`io.mosip.mock.sbi.service.SBIServiceResponse` handles it. The formula above is the one verified byte-for-byte.

### Stream quality (Build Map #67, 2026-09-23)

The stock MockMDS face stream (490 × 360) never passes PRAMAAN's quality gate: the face is too small
(inter-ocular distance below 60 px) and lit strongly from one side. A demo against unmodified MockMDS therefore
stays on framing guidance. MockMDS streams images from its own profile folder; for a demo, give it a larger,
evenly lit face. `./gradlew :harness:pipelineBench --args="clip 10"` prints the per-component quality breakdown.

### Round 2 observations (Build Map #11-#18, 2026-09-23)

| Observation | Detail | Consequence |
|---|---|---|
| **Signing chain is incomplete** | The rcapture JWS header's `x5c` holds **one** certificate — the leaf `CN=MosipFace`, issued by `CN=RootCA` (valid 2021-02-08 to 2031-02-08). The root is not sent | Verification needs the issuing CA **pinned as a trust anchor** from configuration. `sbi/testdata/mockmds-rootca.cer` is the mock's root, exported from its public keystore; production pins MOSIP's device CA the same way |
| Never derive trust from the response | A first version of the live test took "the last x5c certificate" as the anchor — that trusts whatever the device sends | `LiveMockMdsTest` now loads the anchor from the pinned file only |
| **Stream delimiter quirk** | Header says `boundary=--BoundaryString`; body uses `--BoundaryString` as the delimiter, not the RFC 2046 form `----BoundaryString` | `MjpegStreamReader` accepts both forms |
| Parts carry `Content-Length` | Every part: `Content-Type:image/jpeg`, `Content-Length:n` (no space after the colon), blank line, JPEG, CRLF CRLF | Fast path reads exactly n bytes; the no-length path trims separator CR/LF |
| Live results | 3 real captures verified via PKIX to the pinned root, each echoing a fresh 32-hex nonce; stream measured at or above 3 fps | #12, #13, #18 proven against the real device service |
