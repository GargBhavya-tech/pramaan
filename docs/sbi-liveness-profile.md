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

The client adds an ephemeral P-256 public key (X.509 SubjectPublicKeyInfo, base64) to the stream-start request
alongside the `transactionId` it already sends:

```json
{ "version": "0.1", "transactionId": "0123456789abcdef0123456789abcdef", "clientEphemeralKey": "MFkw..." }
```

The device answers with its own ephemeral key in a compact JWS signed with its device key (RS256, certificate chain
in `x5c`, exactly like a capture). The payload names the transaction and the hash of the client key it answered:

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
computed over the frames it received.

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

* No real device implements this; **no certified SBI device has been tested against it**.
* The client integration is the lock and its primitives. `SbiFrameSource` does not yet read the extra stream
  headers, and `CaptureBinder` does not yet call `SignedStreamLock` — wiring needs a device (or MockMDS patched with
  the profile) to exercise end to end.
* The JSON schemas are checked against the mock by a small in-test validator, not by a full JSON Schema engine.
