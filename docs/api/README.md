# PRAMAAN API reference

Three surfaces, in the order a developer meets them:

| Surface | For | Where |
|---|---|---|
| **A. Java API** | A Registration Client (desktop) or an SBI device app (Android, Mode D) that embeds the engine | `core/` and `sbi/` |
| **B. The device interface over HTTP** | A device vendor, or anyone writing a device to talk to PRAMAAN: the standard SBI calls plus the optional Liveness Profile | `docs/sbi-liveness-profile.md`, the reference device in `harness/` |
| **C. Command-line tools and files** | A server operator re-checking evidence, an evaluator, a centre administrator | `./gradlew :harness:...`, `config/` |

PRAMAAN has **no network service of its own**. It is a library plus tools: it speaks HTTP only as a *client* of a device
service on `127.0.0.1`, as the SBI standard defines. So there are no endpoints to call on PRAMAAN; surface B documents the
endpoints it calls on a device, and what a device must answer. Everything quoted below from surface B is a **real
exchange** with the reference device (`./gradlew :harness:runProfileDevice`), captured on 2026-10-24; the full decoded
captures are in [`examples/profile-device-session.json`](examples/profile-device-session.json) and the commands that made
them in [`examples/try-the-device.sh`](examples/try-the-device.sh).

Limits first: the reference device serves **one still photograph**, so it is a reference for the transport and the
cryptography, not a sensor. No certified SBI device implements the Liveness Profile, and the verbs `ILLUMINATE` and
`CAPTUREBURST` are this project's own proposal (`docs/claim-sheet.md`, L17).

---

## A. Java API

Java 11 bytecode, so it runs on Android and in the Registration Client's runtime. Package `pramaan.core.*`. The headless
engine has no UI, no wall clock (time is passed in) and no thresholds in code.

### A.1 `FaceFrameSource`: the frame-streaming interface

The first of the two interfaces the problem statement asks to define. A device (real or mock) implements it. The signed
capture is **on the same interface as the stream on purpose**: the preview and the capture must come from one source in
one session, or the capture-swap hole is built in.

```java
public interface FaceFrameSource extends AutoCloseable {
    DeviceCapabilities discover() throws DeviceException;                 // find the device, report what it can do
    void start(SessionContext session, FrameSink sink) throws DeviceException;   // frames + device events for one session
    SignedCapture capture(CaptureRequest request) throws DeviceException; // one signed capture, signature verified
    void stop();                                                          // safe to call twice

    // optional, only when the device implements the Liveness Profile (empty for a plain SBI device):
    Optional<SignedStreamLock> streamLock();      // authenticated stream; binding level CRYPTOGRAPHIC instead of HEURISTIC
    Optional<DeviceIlluminator> illuminator();    // the device's own light, with a signed emission log
    Optional<BurstSource> burst();                // several signed frames after the main capture
}

public interface FrameSink {
    void onFrame(Frame frame);                    // called on the source's reader thread
    void onDeviceEvent(DeviceEvent event);        // CONNECTED, DISCONNECTED, ERROR, FORMAT_CHANGED
}
```

`CaptureRequest(transactionId, previousHash, requestedScore, timeout)` validates its inputs and throws
`IllegalArgumentException` otherwise:

| Field | Rule | Why |
|---|---|---|
| `transactionId` | 4 to 50 characters from `[A-Za-z0-9-]` (PRAMAAN uses a 128-bit nonce as **32 lowercase hex characters**) | SBI allows only letters, digits and hyphens; base64url would put an `_` in it |
| `previousHash` | empty, or 64 hex characters | the previous capture's chained hash; empty for the first |
| `requestedScore` | 0 to 100 | passed to the device |
| `timeout` | a `Duration` | |

`SignedCapture` accessors: `signedData()` (the device's JWS), `chainHash()`, `previousHash()`,
`verifiedTransactionId()`, `verifiedTimestamp()`, `signer()`, `device()` (a `DeviceMetadata`), `receivedAt()`.
**Errors:** `DeviceException` (no device, timeout, bad signature, wrong transaction). The engine turns any of them into a
failed attempt; it never proceeds to a capture it could not verify.

Implementations: `SbiFrameSource` (a real SBI device over loopback HTTP, `sbi/`), `MockFrameSource` (replays corpus clips,
`harness/`), `ProfileStubDevice` (a runnable device, section B).

### A.2 `EngineBundle` and `LivenessSession`: nothing starts without a verified bundle

```java
// unsigned, for development: policy YAML + operating-point table JSON + the exact bytes of every model
EngineBundle EngineBundle.load(byte[] policyYaml, byte[] operatingPointsJson, Map<String, byte[]> modelBytes)

// signed, for a deployment: the manifest's signature, then every file's hash, then the version against anti-rollback
EngineBundle EngineBundle.loadSigned(String manifestJws, JwsTrust bundleTrust, byte[] policyYaml,
        byte[] operatingPointsJson, Map<String, byte[]> modelBytes, RollbackGuard rollback)

LivenessSession bundle.newSession(Workflow workflow /* RESIDENT or OPERATOR */, String subjectId, String operatorId,
        String deviceId, String centreId, SessionRegistry registry, Consumer<AuditEvent> audit, Clock clock, SecureRandom random)

LivenessSession bundle.newSupervisorSession(String supervisorId, String approves /* a packet id, or a resident session */,
        String deviceId, String centreId, SessionRegistry registry, Consumer<AuditEvent> audit, Clock clock, SecureRandom random)
```

**Fails closed.** `load`/`loadSigned` throw `EngineUnavailableException` (with `LivenessError.BUNDLE_INVALID` or
`ENGINE_ERROR`) for: an unparseable or unknown-field policy, `pramaan.enabled: false`, an operating-point table whose model
hashes do not match the bytes given or that does not cover every profile, a model that will not load, a bad manifest
signature, a missing file hash, or a bundle older than one already run on this machine. A host must then refuse face capture;
there is no path that skips liveness. `bundle.stale(Instant)` reports a signed bundle older than `stalePolicyMaxAgeDays`
(the client keeps working and says so). `bundle.policySha256()` and `operatingPointsSha256()` are what the evidence records and
the server allow-lists. Close the bundle to free the models.

A supervisor's approval is bound to *what it approves* before the face is checked, so a verdict cannot be carried to anything
else (`newSupervisorSession` rejects a blank `approves`).

### A.3 `LivenessEngine`: the processing interface

The second of the two interfaces. One engine per session; it is a `FrameSink`. It owns no decision rule: zones come from
`ZoneDecider`, transitions from `LivenessSession`, locks from `CaptureBinder`.

```java
LivenessEngine engine = LivenessEngine.builder(bundle, session)
        .device(frameSource, deviceTrust, DeviceLevel.L0)        // the FaceFrameSource and the pinned trust for its signatures
        .analysers(frameAnalyser, captureAnalyser)               // perception: decode, detect, landmarks, passive, face embedding
        .capabilities(sessionCapabilities)                       // measured frame rate and what the station has
        .listener(screen)                                        // an EngineListener: what a UI may know
        .requestedScore(40)
        // optional hardware, each switches its cue on only when given: .flashLight(..) .deviceIlluminator(..) .stereo(..)
        // .pupil(..) .knownBackdrop(true) .hostCameras(..) .streamSignature(..) .diagnostics(sink, grant)
        .build();

engine.start();                   // begin the first attempt
frameSource.start(session, engine);   // frames now arrive as engine.onFrame(Frame)
engine.tick();                    // call periodically (about every 250 ms): timeouts, onsets, cooldowns
// ... until engine.session().state() is ACCEPTED, LOCKED_OUT, EXCEPTION_FLOW ...
Optional<SignedCapture> accepted = engine.acceptedCapture();     // present only after all four binding locks passed
```

Other accessors: `session()`, `decision()` (the fused decision, for evidence and diagnostics), `bindingResult()`,
`samples()` (crops for the evidence), `challengePlan()`, `injectionFindings()`, `boundVirtualCameraDetected()`.

**Contract.** Calls are serialised; `capture` blocks the calling thread by design. Anything thrown while analysing or deciding
fails the attempt as an engine error, and nothing falls through to capture. `ACCEPTED` has no other way in than the binding
locks. A face loss or a change of `trackId` discards the attempt's evidence.

### A.4 `EngineListener`: what a screen may know

A screen sees states, guidance, message ids and prompts, **never a score, a zone, a cue or a threshold**; a screen that
cannot see them cannot leak them or vary its wording by them. All methods are default no-ops, called on the engine's thread:

| Method | Meaning |
|---|---|
| `stateChanged(SessionState)` | the session moved (`ACQUIRING`, `PASSIVE`, `CHALLENGING`, `CAPTURING`, `ACCEPTED`, `FAILED`, `RETRY_WAIT`, `LOCKED_OUT`...) |
| `guidance(Guidance)` | framing advice (`MOVE_CLOSER`, `MORE_LIGHT`...), on change only; a quality problem, never a security finding |
| `message(String messageId)` | a message id for a failure or lockout; ids resolve through `messages.properties` and are generic by design |
| `prompt(ChallengePrompt, step, steps)` | show a challenge now (an icon hint, a spoken prompt id) |
| `stepCompleted(step, steps)` | "Action detected"; also the cue for the tone an eyes-closed prompt promises |
| `attempt(n, max)` | "Attempt 2 of 3" |
| `cooldown(waitMillis)` | the wait before the next attempt; depends only on how many attempts were used, never on a score |

`DiagnosticSink` is a **separate** channel that carries per-cue scores and timings; an engine accepts one only together with
an audited `DiagnosticGrant`, which the policy flag `diagnosticMode` gates and which is itself audited.

### A.5 Evidence and verification (server side)

`EvidencePackager` builds a signed JWS (the *liveness evidence*) for the face BIR's `others` map, leaving `bdb` untouched. A
server re-checks it with `RecheckVerifier`, or in one command (section C). Binding levels: `HEURISTIC` (plain SBI) or
`CRYPTOGRAPHIC` (the device implements the Liveness Profile and the stream chain held).

---

## B. The device interface over HTTP

A device service listens on `127.0.0.1`, ports 4501 to 4600. The HTTP **method** is the call. The standard SBI calls are
`MOSIPDISC` (discover), `MOSIPDINFO` (device info), `STREAM` and `RCAPTURE`; the Liveness Profile adds `ILLUMINATE` and
`CAPTUREBURST` (this project's proposal). Observed against MOSIP's MockMDS and recorded in `docs/sbi-notes.md`.

**Conventions (every call):**

- **Every scalar in a JSON request is a string** (`"timeout": "10000"`, `"count": "3"`). A number is rejected by real devices.
- `transactionId`: 4 to 50 characters from `[A-Za-z0-9-]`. PRAMAAN sends a 32-hex nonce.
- **Authentication is by signature, not by credential.** A response's signed part is a compact JWS, `RS256`, whose header
  carries the device's certificate in `x5c`. The client verifies it against a **CA certificate pinned from configuration**
  (`--device-ca`), never against a certificate the device sends. No password, token or API key exists anywhere in the protocol.
- Loopback only; nothing is sent beyond the machine.

| Call | Method | Request body | Response |
|---|---|---|---|
| Discover | `MOSIPDISC` | none | `200`, JSON array of devices |
| Stream | `STREAM` | JSON; with the profile, a `livenessProfile` object | `200`, `multipart/x-mixed-replace; boundary=--BoundaryString`, one JPEG per part |
| Capture | `RCAPTURE` | JSON | `200`, `{"biometrics":[{specVersion, data, hash}]}` |
| Illuminate (profile) | `ILLUMINATE` | JSON | `200`, `application/jose`, the emission log as a compact JWS |
| Burst (profile) | `CAPTUREBURST` | JSON | `200`, same shape as `RCAPTURE` with several entries |

### B.1 Discover: `MOSIPDISC`

```bash
curl -s -X MOSIPDISC http://127.0.0.1:4533/device
```

Real response (the signed `digitalId` is shortened; the decoded header and payload are shown below it):

```json
[{
  "deviceId": "1", "deviceStatus": "Ready", "certification": "L0", "purpose": "Registration",
  "digitalId": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsIng1...<2042 chars>",
  "deviceCode": "pramaan-profile-stub", "specVersion": ["0.9.5"], "deviceSubId": ["0"],
  "livenessProfile": {
    "version": "0.1", "signedStream": true, "captureBurst": true, "illuminatorControl": ["VISIBLE_WHITE"],
    "sensorTimestamps": true, "depth": false, "focusControl": false,
    "onDevicePad": { "algorithm": "mock-pad", "version": "0.1", "certification": "none" }
  }
}]
```

`digitalId` header `{"alg":"RS256","typ":"JWT","x5c":[<leaf certificate>]}`; payload:

```json
{ "serialNo": "PRAMAAN-PROFILE-0001", "make": "PRAMAAN", "model": "ProfileStub", "type": "Face",
  "deviceProvider": "PRAMAAN development", "dateTime": "2026-10-24T05:08:19.492463600Z" }
```

The `livenessProfile` block is **optional**: absent means a plain SBI device and the binding level stays `HEURISTIC`. The
client reads `digitalId` to learn make, model and type, checks its chain against the pinned CA (`VERIFIED`, `UNSIGNED` or
`FAILED`; a `FAILED` identity is always a veto), and matches the make and model against its denylist of virtual cameras.

### B.2 Stream with key agreement: `STREAM`

Request (with the profile; without a `livenessProfile` object the stream is plain SBI and unsigned):

```json
{ "deviceId": "1", "deviceSubId": "0", "timeout": "2000",
  "livenessProfile": { "version": "0.1", "transactionId": "0123456789abcdef0123456789abcdef",
                       "clientEphemeralKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...<124 chars: P-256, X.509 SPKI, base64>" } }
```

Real response headers: `HTTP/1.1 200 OK`, `Content-type: multipart/x-mixed-replace; boundary=--BoundaryString`, and

```
X-SBI-LP-Answer: eyJhbGciOiJSUzI1NiIs...        (a compact JWS signed with the device key)
```

whose payload names the transaction and the hash of the client key it answered:

```json
{ "version": "0.1", "transactionId": "0123456789abcdef0123456789abcdef",
  "deviceEphemeralKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEOdtg...<124 chars>",
  "clientEphemeralKeyHash": "309365fadfa5f8215d8892ccfcb62b3e35ece55f5041b54312a93f5dfaac96b7" }
```

Both ends derive the stream key `Ks` (ECDH on P-256, HKDF-SHA256 over a transcript hash; formula in
`docs/sbi-liveness-profile.md` section 3.2). Each part of the body then carries (real first part):

```
--BoundaryString
Content-Type:image/jpeg
Content-Length:11850
X-SBI-LP-Time:0
X-SBI-LP-Tag:74e1084d9d54aedf9bd21552ebe052e1b506f8f28d38d7f7660bef33b305c09b
<JPEG bytes>
```

`tag_i = HMAC-SHA256(Ks, tag_{i-1} || SHA-256(frame_i) || t_i)`, and every 30th part also carries an
`X-SBI-LP-Checkpoint` (a device-signed JWS of `"<i>:<tag>"`). A dropped, reordered, replaced or retimed frame breaks the
chain at the first difference. The stream ends when the client closes the connection or the timeout (capped at 15 minutes by
the reference device) passes. A device that advertises the profile but sends no verifiable `X-SBI-LP-Answer` makes the client
refuse the session.

### B.3 Capture: `RCAPTURE`

Request:

```json
{ "env": "Staging", "purpose": "Registration", "specVersion": "0.9.5", "timeout": "10000",
  "captureTime": "2026-10-24T05:08:30.000Z", "transactionId": "0123456789abcdef0123456789abcdef",
  "bio": [{ "type": "Face", "count": "1", "bioSubType": [], "requestedScore": "40", "deviceId": "1", "deviceSubId": "0",
            "previousHash": "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855" }] }
```

(`previousHash` for the first capture is SHA-256 of the empty string.) Real response entry, `data` decoded:

```json
{ "bioType": "Face", "bioSubType": "", "purpose": "Registration", "env": "Staging",
  "transactionId": "0123456789abcdef0123456789abcdef",
  "timestamp": "2026-10-24T05:08:47.763411300Z",
  "bioValue": "RkFDADAzMAAAAC6OAAEAAAAAAC59AAAAAAAAAAAA...<15891 chars: an ISO/IEC 19794-5 face record, base64url>",
  "qualityScore": "80", "deviceCode": "pramaan-profile-stub", "deviceServiceVersion": "pramaan-profile-stub",
  "streamTagHead": "158b9f4cdc6ca35c7d22a3bd7b05359221737d5a71589f9509cb413365e13e73" }
```

with `"hash": "7851FD0A3D208545734B26AD2F2021EBCE5CB006290079E4632E608BB9D58BDC"` beside it (outside the signature):
`UPPERHEX(SHA256(prev || SHA256(base64url-decode(bioValue))))`, raw 32-byte digests concatenated, not hex strings.
`transactionId` inside the signed data is what proves the capture was made for **this** session; `streamTagHead`
(profile only) names the stream the client analysed.

### B.4 Illuminate: `ILLUMINATE` (profile)

```json
{ "transactionId": "0123456789abcdef0123456789abcdef",
  "schedule": [ { "colour": "GREEN", "durationMs": "400" }, { "colour": "BLUE", "durationMs": "450" } ] }
```

Real response (a compact JWS; payload decoded): the **emission log**, what the light actually showed and when:

```json
{ "version": "0.1", "transactionId": "0123456789abcdef0123456789abcdef",
  "emissions": [ { "colour": "GREEN", "atMs": "0", "durationMs": "400" },
                 { "colour": "BLUE",  "atMs": "400", "durationMs": "450" } ] }
```

The reference device answers after the schedule has finished (a real light takes that long). Schedules must respect the
photosensitivity limits: at most three colour changes a second, steps of at least 333 ms, no saturated-red alternation; the
reference device accepts 1 to 16 steps and at most 8 seconds in all.

### B.5 Burst: `CAPTUREBURST` (profile)

```json
{ "transactionId": "0123456789abcdef0123456789abcdef", "count": "3", "intervalMs": "200" }
```

Real response: three entries shaped like `RCAPTURE` ones, each signed, each carrying `burstIndex` (`"0"`, `"1"`, `"2"`) and its own chain
`hash`, all for the same `transactionId`. The reference device allows 1 to 99 frames, 10 to 9999 ms apart.

### B.6 Errors

Real SBI devices answer with a JSON error object (observed from MockMDS: a generic error code such as `123` or `610`). The
**reference device** is deliberately minimal and answers with a bare status and no body, so a client can never learn more than
the status. Observed, and covered by tests (`ProfileStubDeviceTest`):

| Situation | Status |
|---|---|
| Unknown HTTP method (for example `GET`) | `405` |
| Body is not JSON | `400` (an earlier build dropped the connection with no answer; fixed and tested on 2026-10-24) |
| `ILLUMINATE` for a transaction that never agreed a key | `400` |
| `ILLUMINATE` with no schedule, more than 16 steps, or more than 8 s | `400` |
| `CAPTUREBURST` outside 1 to 99 frames or 10 to 9999 ms | `400` |

On the client side every one of these becomes a generic message on screen; the specific cause goes to the audit log only
(Bible Part 19.1: guidance for quality problems, silence for security problems).

### B.7 Versioning

`livenessProfile.version` is `"0.1"` (a draft proposal). Everything in the profile is optional and backward compatible: a
device without it is unchanged, and a client that does not know it ignores the extra headers and the extra signed claim
(to be confirmed with MOSIP, open questions O2 and O3 in `docs/sbi-liveness-profile.md`). The labels may be renamed to a
neutral scheme if MOSIP adopts it.

---

## C. Command-line tools and files

All are Gradle tasks (`./gradlew :module:task --args="..."`), run from the repository root. Arguments marked * are required.

| Task | Purpose | Arguments |
|---|---|---|
| `:harness:verifyEvidence` | **The server re-check in one command**: a stored evidence record against its capture, allow-list and sample crops | `--evidence*` the JWS, `--capture*`, `--evidence-key*` (RSA public, X.509), `--allow*` (allow-list JSON), and one of `--device-ca` or `--device-key*`; optional `--server-key`, `--models`, `--evidence-kid`, `--device-kid` |
| `:harness:rescanEvidence` | Re-score a directory of stored evidence with a newer model | `--dir*`, `--model*`, `--server-key*`, `--evidence-key*`, `--allow*`, and `--device-ca` or `--device-key` |
| `:harness:signBundle` | Sign the policy, table and models into a manifest | `--config`, `--models`, `--key` (PKCS#8), `--version` |
| `:harness:runCorpus` | Run recorded clips through the production engine, one JSON row per clip | `--manifest*`, `--out*`, `--actions`, `--config`, `--models` |
| `:harness:evaluate` | ISO/IEC 30107-3 report: APCER per attack type and BPCER, with confidence intervals, fairness and ablation | `--rows*`, `--table*`, `--policy*`, `--out*`, `--html`, `--export` |
| `:harness:calibrate` | A calibration **proposal** from session rows, split by person, with held-out intervals; applies nothing | `--rows`, `--alpha`, `--group-by` |
| `:harness:fraudReport` | Per-operator z-scores against the centre's baseline, from the audit log | `--audit`, `--out` |
| `:harness:fusedRedTeam` / `redTeam` | Which display pitches the whole fusion / the moire cue accepts (SYNTHETIC) | none / `--seed`, `--generations` |
| `:harness:stationSelfTest` | Install-time attacks made from this station's empty-room photo | `--photo` |
| `:harness:runProfileDevice` | The reference device of section B | `--port`, `--frame*` (a JPEG), `--work` |
| `:desktop:runScaffold` | The registration scaffold against a device | `--device-ca`, `--ports`, `--config`, `--models`, `--gallery`, `--audit`, `--evidence`, `--operator`, `--subject`, `--centre`, `--lang`, `--speech`, `--flash-screen`, `--backdrop`, `--stream-signature`, `--segmenter`, `--bundle-key`, `--rollback-file` |
| `:desktop:showcase` | The station screens with a **simulated** session; needs no camera, device or models | none (SPACE pauses, arrows step) |
| `:desktop:showcaseRender` | The same, rendered to PNG and `beats.json` | fixed output `build/showcase` |

### C.1 `config/pramaan-policy.yaml`

Strict: an unknown field, a duplicate key or a wrong type is an error and the engine refuses to start. It holds **no score
thresholds on purpose**: each profile names an operating point whose thresholds live in the table, bound to the models' hashes.
Top level under `pramaan:`: `policyVersion`, `enabled`, `stalePolicyMaxAgeDays`, `diagnosticMode`, `profiles` (`RESIDENT`,
`OPERATOR`, `SUPERVISOR`: `operatingPoint`, `activeEnabled`, `auditChallengeProbability`, `minCues`, `challenge{pool, minSteps,
maxSteps, stepTimeoutMs, onsetMinMs}`, `retries{max, backoffBaseMs}`, `onLockout`), `accessibilityProfiles`, `exceptionFlow`
(`requireSupervisor`, `maxPerOperatorPerDay`, `maxPerCentrePerDay`, `supervisorVerdictMaxAgeMs`), `session` (the timeouts), and
`injection` (`requireSignedStream`, `vetoOnHostVirtualCamera`, `requireVerifiedDeviceIdentity`, `vetoOnStreamSignatureMismatch`;
all default `false`).

### C.2 `config/operating-points.json`

`tableVersion`; `provisional` (currently `true`: **every number is hand-written from the Bible and not measured**); `models`
(file name to SHA-256; the engine refuses to start if a loaded model's hash differs); `fusion.cues` (per cue: `wPos`, `wNeg`,
`physiological`); the operating points (`resident-default`, `operator-strict`, `supervisor-strictest`, each with `tauAccept`,
`tauReject` and a `target` string that says "not yet measured"); and `thetaSame`, the face-match threshold. **Every threshold
lives here and nowhere else.** The calibrator proposes a replacement; it never edits this file.

---

## D. Three worked use cases

**1. A client developer integrates the engine.** Load a bundle (A.2), build the engine with the device source and a listener
(A.3), feed frames, call `tick()`, and on `ACCEPTED` store `acceptedCapture()` plus `EvidencePackager`'s record in the face
BIR `others` map. Render only what `EngineListener` gives you. The desktop scaffold (`RegistrationScaffold`, `CaptureSession`)
is a complete worked example.

**2. A server re-checks a stored enrolment.** Keep the evidence JWS, the capture, the sample crops and the allow-list; run
`:harness:verifyEvidence` with the evidence public key and the device CA. Years later, run `:harness:rescanEvidence` with a
stronger model over the same directory and review the enrolments it lists.

**3. A device vendor adds the Liveness Profile.** Advertise `livenessProfile` in the discovery response (B.1); answer the key
agreement in `X-SBI-LP-Answer` and tag every frame (B.2); add `streamTagHead` to the signed capture (B.3); optionally implement
`ILLUMINATE` and `CAPTUREBURST` (B.4, B.5). Test against `:harness:runProfileDevice` for the expected shapes, and against
`SbiLivenessProfileTest` and `ProfileStubDeviceTest` for the client's behaviour. The schemas are in
`docs/schemas/sbi-liveness-profile.schema.json`.
