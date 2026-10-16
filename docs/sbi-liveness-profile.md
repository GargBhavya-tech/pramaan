# SBI Liveness Profile, version 0.1 — a proposal to MOSIP

Status: **draft proposal, not yet shared.** How MOSIP accepts specification changes is unknown (PRAMAAN Bible
Part 30, open question Q12). Ask the mentors where this should go before posting it anywhere.

Reference implementation: the mock device (`harness/.../mock/MockLivenessProfile.java`) and the client side
(`core/.../binding/StreamTagChain`, `StreamKeyAgreement`, `SignedStreamLock`). Machine-readable schemas:
[`docs/schemas/sbi-liveness-profile.schema.json`](schemas/sbi-liveness-profile.schema.json).

## 1. The problem in one paragraph

The Secure Biometric Interface lets a Registration Client show a **preview** and then ask for a **signed capture**.
The preview is unsigned; the capture is signed; nothing links them. A liveness engine that analyses the preview
therefore proves nothing about the biometric that is finally enrolled: an attacker can pass with a live face and
let a different face be captured, or inject frames between the device and the client. Today a client can only
*work around* this with heuristics (a nonce echoed in `transactionId`, identity continuity between preview and
capture, timing). A small, optional extension to the standard removes the workaround and benefits every client,
vendor and country that adopts it.

## 2. What the profile adds

Everything is **optional and backward compatible**. A device that does not implement it behaves exactly as today;
a client that does not know it ignores it. Requests keep the SBI convention that **every scalar is a JSON string**.

| # | Addition | Where | Purpose |
|---|---|---|---|
| 1 | `livenessProfile` capability block | device-info response | The client learns what the device can do, once, at discovery. |
| 2 | Authenticated preview stream | per-frame headers on the M-JPEG stream, plus a key agreement at stream start | Frames cannot be dropped, reordered, replaced or retimed without detection. |
| 3 | Capture bound to the stream | `streamTagHead` inside the signed capture data | The signed capture names the exact preview the client analysed. |
| 4 | `captureBurst(count, intervalMs)` | new request | Several signed frames during a challenge. |
| 5 | `illuminate(schedule)` | new request | The device drives its own illuminator on the client's random schedule and returns a signed emission log. |
| 6 | On-device PAD result (optional) | signed capture data | A vendor's PAD score, algorithm and certification travel as one more cue. |

## 3. Message formats

### 3.1 Capability block (addition 1)

```json
"livenessProfile": {
  "version": "0.1",
  "signedStream": true,
  "captureBurst": true,
  "illuminatorControl": ["VISIBLE_WHITE"],
  "sensorTimestamps": true,
  "depth": false,
  "focusControl": false,
  "onDevicePad": { "algorithm": "vendor-pad", "version": "2.1", "certification": "iBeta-L2" }
}
```

### 3.2 Key agreement (addition 2, at stream start)

The client adds a `livenessProfile` object to the stream-start request, holding an ephemeral P-256 public key
(X.509 SubjectPublicKeyInfo, base64) and the transaction id — for PRAMAAN the session nonce, which is also the
`transactionId` the first capture echoes (the stream is session-long; each retry's capture carries its own):

```json
"livenessProfile": { "version": "0.1", "transactionId": "0123456789abcdef0123456789abcdef", "clientEphemeralKey": "MFkw..." }
```

The device answers in the stream response's **`X-SBI-LP-Answer` header** with its own ephemeral key in a compact JWS
signed with its device key (RS256, certificate chain in `x5c`, exactly like a capture). The payload names the
transaction and the hash of the client key it answered:

```json
{ "version": "0.1", "transactionId": "0123456789abcdef0123456789abcdef",
  "deviceEphemeralKey": "MFkw...", "clientEphemeralKeyHash": "9f86d0..." }
```

Both ends compute

```
transcript = SHA-256( "PRAMAAN-SBI-LP-0.1" || len||transactionId || len||devicePublic || len||clientPublic )
Ks         = HKDF-SHA256( ECDH(own, peer), salt = transcript, info = "PRAMAAN-SBI-LP-0.1stream-key", L = 32 )
```

(`len` is a 4-byte big-endian length.) The label would be renamed from PRAMAAN to a neutral name if MOSIP adopts it.

### 3.3 Frame tags (addition 2, per frame)

Each part of the multipart stream carries two extra headers, and every N-th part (N ≤ 30) a third:

```
X-SBI-LP-Time: 1760436000123            sensor time, milliseconds
X-SBI-LP-Tag: 3c1f...e9                 64 hex characters
X-SBI-LP-Checkpoint: eyJhbGciOiJSUzI1NiIs...   compact JWS, every N frames
```

```
tag_0 = HMAC-SHA256( Ks, nonce )                         nonce = the transactionId bytes (UTF-8)
tag_i = HMAC-SHA256( Ks, tag_{i-1} || SHA-256(frame_i) || t_i )       t_i = 8 bytes, big-endian
```

The checkpoint's JWS payload is the string `"<i>:<tag_i in hex>"`, signed with the device key. An HMAC per frame
costs microseconds; the expensive signature is needed only at checkpoints.

### 3.4 Capture binding (addition 3)

`rcapture` already takes the nonce as `transactionId`. The device adds one optional claim to the signed capture data:

```json
{ "streamTagHead": "3c1f...e9", "onDevicePad": { "algorithm": "vendor-pad", "version": "2.1", "score": "0.93" } }
```

`streamTagHead` is the head of the chain at the moment of capture. A client checks it against the chain it
computed over the frames it received. Because the device tags a frame the moment it captures it, up to
`HEAD_SLACK_FRAMES` = 5 frames may still be in flight to the client when the capture happens, so the client accepts
the head if it equals any of its last six heads — never an older one (a capture signed against a stale part of the
stream is refused). The client reads every frame as it arrives, before any drop-oldest queue, so a frame dropped for
being slow is still part of the chain.

### 3.5 `captureBurst` and `illuminate` (additions 4 and 5)

```json
{ "transactionId": "0123456789abcdef0123456789abcdef", "count": "3", "intervalMs": "200" }
```
```json
{ "transactionId": "0123456789abcdef0123456789abcdef",
  "schedule": [ { "colour": "GREEN", "durationMs": "400" }, { "colour": "BLUE", "durationMs": "450" } ] }
```

`illuminate` returns a compact JWS whose payload is the **emission log** — what the illuminator actually showed
and when, relative to the first emission. A client can then correlate the face's response against attested timing
instead of trusting its own display. Schedules must respect photosensitivity limits: at most three colour changes
per second, steps of at least 333 ms, no saturated-red alternation. A device refuses a schedule that does not.

## 4. What a client does

1. At discovery, read `livenessProfile`. If absent, behave as today; the binding level is `HEURISTIC`.
2. At stream start, send the client key; verify the signed answer (`StreamKeyAgreement.acceptDeviceAnswer`),
   derive `Ks`.
3. For every frame, compute the tag over what was **received** and compare (`SignedStreamLock.observe`). The first
   mismatch breaks the chain for good.
4. On every checkpoint, verify the signature and compare the tag (`acceptCheckpoint`).
5. At capture, read `streamTagHead` from the signed data, bind it and check (`SignedStreamLock.check`). Pass means
   the binding level is `CRYPTOGRAPHIC`; the evidence record says which level was reached, and the server weights it.

Failure codes: `STREAM_TAG_MISMATCH`, `STREAM_HEAD_MISMATCH`, `STREAM_HEAD_MISSING`, `STREAM_UNSIGNED`. All map to
the one generic security message at the user interface — differing text would be an oracle.

## 5. Threat table

| # | Threat | Without the profile | With the profile | Residual risk |
|---|---|---|---|---|
| T1 | A local proxy or virtual camera replaces preview frames | Undetectable at the protocol level; only heuristics | The tag chain breaks at the first replaced frame | The attacker would need `Ks` |
| T2 | A frame is dropped, reordered or retimed to hide an action | Undetectable | Chain breaks (tests: drop, reorder, replace, retime) | None known |
| T3 | A recorded stream from an earlier session is replayed | Nonce echo in the capture only | `tag_0` is keyed on this transaction's nonce and `Ks`; the first frame fails | None known |
| T4 | Capture swap: live preview, different signed face | Closed only by identity-continuity heuristics | The capture must name this stream's head; a capture from another stream is refused | A device that signs the wrong head (a compromised device) |
| T5 | Man in the middle on the key agreement | n/a | The device signs the answer, naming the client key it answered; a swapped client key or device key fails | A stolen device key |
| T6 | Downgrade: strip `livenessProfile` from device info | n/a | A client policy can **require** the profile for a workflow; absence then fails closed | If the policy does not require it, the binding stays `HEURISTIC` and the server sees that |
| T7 | Malware inside the client process, which holds `Ks` | Same | Same: it can forge tags | Out of scope. This profile protects the path between device and client, not the client itself |
| T8 | A compromised or counterfeit device | Same | Same: a signed capture from a bad device is still a signed capture | Device certification is the control |
| T9 | Forged emission log to fake a flash response | n/a | The log is signed by the device | A device that lies about its own light |
| T10 | A device hides `captureBurst` so the client takes no burst and the `burst` lock never runs | n/a | The main capture still faces the identity lock; the burst is an extra check, not the only one | A device that wants to skip only the burst check. **Not closed:** unlike `requireSignedStream` there is no policy flag that makes the burst mandatory. A device that advertises it and then fails is refused (`BURST_FAILED`); one that never advertises it is not. |
| T10 | Server cannot verify HMAC (it never had `Ks`) | n/a | Checkpoints are device-signed, so the evidence record can carry a verifiable head | The server trusts the client's report of per-frame verification; it can verify the checkpoints only |

## 6. Compatibility and cost

* A device without the profile is unchanged. Extra multipart headers and one extra claim in signed data are the
  only wire changes; both are ignorable by a client that does not know them (to be confirmed — see O2, O3).
* Cost: one ECDH and one signature at stream start, one HMAC-SHA256 and one SHA-256 per frame, one signature every
  N frames. All are cheap on a device that already signs captures with RSA.

## 7. Open questions for MOSIP

| # | Question |
|---|---|
| O1 | Where does an SBI extension proposal go, and who reviews it (Bible Q12)? |
| O2 | May multipart stream parts carry extra headers without breaking existing Registration Client parsers? |
| O3 | Does the Registration Client's validation of capture `data` tolerate an extra claim? |
| O4 | Should the label, the version string and the `X-SBI-LP-*` names be renamed to a MOSIP-neutral scheme? |
| O5 | Should the profile mandate P-256 and HMAC-SHA256, or allow an algorithm list for post-quantum migration? |

## 8. Verification

| Claim | Test |
|---|---|
| The mock's outputs satisfy the schemas | `MockDeviceProfileConformanceTest` |
| A full round trip reaches `CRYPTOGRAPHIC` binding | same |
| Drop / reorder / replace / retime break the chain; stale or missing capture heads are refused; forged checkpoints are refused | `SignedStreamLockTest` |
| The key agreement derives one key on both ends; a swapped key or a foreign transaction is refused; HKDF matches RFC 5869 | `StreamKeyAgreementTest` |

## 9. Not done

* No real device implements this; **no certified SBI device has been tested against it**, and MockMDS has not been
  patched with the profile.
* The client side is wired: `SbiFrameSource` reads `livenessProfile` from device info, runs the key agreement on the
  stream request (refusing the session if the device advertises the profile but sends no verifiable answer),
  authenticates every frame in its reader thread, and exposes the lock through `FaceFrameSource.streamLock()`;
  `LivenessEngine` hands it to `CaptureBinder`, which adds the `signedStream` lock, reports `BindingLevel`, and writes
  the level into the evidence. The policy flag `injection.requireSignedStream` makes a device without the profile
  fail binding with `STREAM_REQUIRED` (threat T6). Tested against an in-process stub device
  (`SbiLivenessProfileTest`) and the mock source end to end (`StationWiringEngineTest`).
* `illuminate` and `captureBurst` have client calls. `SbiFrameSource.illuminator()` sends an `ILLUMINATE /illuminate`
  request (body: the `IlluminateRequest`; answer: the compact JWS, as text) and returns the log through
  `EmissionLog.verify`, which refuses a stranger's signature, another transaction, overlapping or numeric emissions.
  `LivenessEngine` prefers the device's light to the station's: `DeviceFlashPhase` keeps frames through a dark lead-in,
  sends the schedule, and lines the frames up against the log rather than against what was asked. A log that differs
  from the request by more than 150 ms per step makes the flash cue abstain and writes `FLASH_DEVICE_LOG_PROBLEM` to
  the audit trail (threat T9: it is never held against the resident). `captureBurst` is `CAPTUREBURST /captureburst`;
  the answer has the same shape as an RCAPTURE answer, every entry is verified like a capture, and a frame for another
  transaction or a wrong count is refused. The verbs, paths and answer shapes are this draft's own proposal, since
  the profile text above names the calls but not their transport. Tested against the stub device
  (`SbiLivenessProfileTest`), `EmissionLogTest`, and end to end in `StationWiringEngineTest`.
* The log gives times relative to the first emission, not the host clock, so the client anchors them to the moment it
  sent the request. The flash cue's lag search (up to two frames) absorbs the device's start-up delay; at 3 fps that is
  about 670 ms of slack. A device that also gives sensor timestamps could tighten the anchor; nothing does yet.
* The burst is used: after the main capture the engine takes three frames 200 ms apart (`FaceFrameSource.burst()`),
  embeds each, and the binder adds a `burst` lock - every usable frame must match the session's verified face at the
  same threshold as the main capture, and at least two must show a face. A device that advertises the burst and
  then fails to deliver it fails the lock (`BURST_FAILED`), like a hidden signed stream. Tested in
  `StationWiringEngineTest` (same face; one swapped frame; a failing burst; no burst offered).
* A runnable device exists: `./gradlew :harness:runProfileDevice --args="--port 4501 --frame some-face.jpg"` starts
  `ProfileStubDevice`, which speaks the whole profile over loopback HTTP - a signed `digitalId`, the key agreement,
  the tagged and checkpointed stream, signed captures naming the stream head, `ILLUMINATE` and `CAPTUREBURST` - and
  signs through a throwaway CA made with the JDK's `keytool` (nothing is checked in), printing the CA file to give
  a client as `--device-ca`. Its "camera" is one still photograph, so a flash never gets an answer: that is the right
  verdict on a photograph, and it means this is a reference for the transport and the cryptography, not a sensor.
  `ProfileStubDeviceTest` drives it with the real `SbiFrameSource` over a socket and a certificate chain.
* The JSON schemas are checked against the mock by a small in-test validator, not by a full JSON Schema engine.
