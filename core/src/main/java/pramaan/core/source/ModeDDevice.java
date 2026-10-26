/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.function.IntSupplier;
import pramaan.core.binding.IsoFaceRecord;
import pramaan.core.binding.SbiCaptureChain;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.crypto.VerifiedJws;
import pramaan.core.session.SessionContext;

/**
 * Deployment Mode D (Bible Part 2): the liveness engine runs <em>inside</em> the SBI app, which also owns the camera, so the app is
 * its own face device. This class makes that possible: it wraps a frame source (the phone's camera) and answers {@link #capture}
 * itself, producing the same signed RCapture entry an SBI face device would (the SBI payload as an RS256 JWS, the ISO 19794-5 face
 * record, the SBI capture hash chain), signed with a key the app holds.
 *
 * <p>The stream is passed through unchanged; the last frame is what a capture carries. The signature proves which app key signed
 * the capture, not that the app is a certified MOSIP device. Two ways to sign are offered: a certificate chain (the production
 * route: the app's certificate issued under the MOSIP trust chain, {@link PayloadSigner#withCertificate}), and a bare key pinned by
 * id ({@link PayloadSigner#withKeyId} with {@link #trustForPinnedKey}), which is what an app with only its own key can do and which
 * a client accepts only if it was told to trust that key. A certificate that is itself the trust anchor is refused by the verifier,
 * by design. The device reports no quality score of its own, so {@code qualityScore} comes from the supplier given.
 */
public final class ModeDDevice implements FaceFrameSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Signs the SBI capture payload as a compact JWS. */
    public interface PayloadSigner {

        String sign(byte[] payload) throws GeneralSecurityException;

        /** RS256 with the certificate in the header ({@code x5c}); the verifier must chain it to a trust anchor above it. */
        static PayloadSigner withCertificate(X509Certificate certificate, PrivateKey key) {
            Objects.requireNonNull(certificate, "certificate");
            Objects.requireNonNull(key, "key");
            return payload -> Jws.signRs256(payload, certificate, key);
        }

        /** RS256 naming the key by id ({@code kid}); the verifier must hold the matching public key pinned under the same id. */
        static PayloadSigner withKeyId(String kid, PrivateKey key) {
            Objects.requireNonNull(kid, "kid");
            Objects.requireNonNull(key, "key");
            return payload -> Jws.signRs256(payload, kid, key);
        }
    }

    private final FaceFrameSource camera;
    private final PayloadSigner signer;
    private final JwsTrust trust;
    private final Clock clock;
    private final String deviceCode;
    private final String environment;
    private final IntSupplier qualityScore;

    private volatile byte[] lastJpeg;
    private volatile DeviceMetadata metadata;

    /**
     * @param camera the stream this device owns, for example the phone's camera
     * @param signer signs each capture; the key behind it should be hardware-backed where the platform has one
     * @param trust what verifies this device's own captures: {@link #trustForPinnedKey} or {@link #trustFor}
     * @param deviceCode the code the app reports in each capture
     * @param environment the SBI environment name ("Staging", "Developer", ...)
     * @param qualityScore the quality score to report, 0 to 100
     */
    public ModeDDevice(FaceFrameSource camera, PayloadSigner signer, JwsTrust trust, Clock clock, String deviceCode, String environment,
            IntSupplier qualityScore) {
        this.camera = Objects.requireNonNull(camera, "camera");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deviceCode = Objects.requireNonNull(deviceCode, "deviceCode");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.qualityScore = Objects.requireNonNull(qualityScore, "qualityScore");
    }

    /** A trust anchored at a certificate authority above the device's certificate (never at the device's own certificate). */
    public static JwsTrust trustFor(X509Certificate authority, Clock clock) {
        return JwsTrust.builder(clock).anchor(authority).build();
    }

    /** A trust that holds exactly this public key under this id: the verifier for captures an app signs with its own bare key. */
    public static JwsTrust trustForPinnedKey(String kid, PublicKey key, Clock clock) {
        return JwsTrust.builder(clock).key(kid, key).build();
    }

    @Override
    public DeviceCapabilities discover() throws DeviceException {
        DeviceCapabilities found = camera.discover();
        metadata = found.device();
        return found;
    }

    @Override
    public void start(SessionContext session, FrameSink sink) throws DeviceException {
        camera.start(session, new FrameSink() {
            @Override
            public void onFrame(Frame frame) {
                lastJpeg = frame.copyBytes();
                sink.onFrame(frame);
            }

            @Override
            public void onDeviceEvent(DeviceEvent event) {
                sink.onDeviceEvent(event);
            }
        });
    }

    @Override
    public SignedCapture capture(CaptureRequest request) throws DeviceException {
        byte[] jpeg = lastJpeg;
        if (jpeg == null) {
            throw new DeviceException(DeviceException.Reason.DEVICE_ERROR, "no frame has been seen yet, so there is nothing to capture");
        }
        if (metadata == null) {
            throw new IllegalStateException("call discover() first");
        }
        byte[] iso = IsoFaceRecord.encode(IsoFaceRecord.ImageFormat.JPEG, jpeg, 0, 0);
        ObjectNode payload = JSON.createObjectNode();
        payload.put("bioType", "Face");
        payload.put("bioSubType", "");
        payload.put("purpose", "Registration");
        payload.put("env", environment);
        payload.put("transactionId", request.transactionId());
        payload.put("timestamp", clock.instant().toString());
        payload.put("bioValue", Base64.getUrlEncoder().withoutPadding().encodeToString(iso));
        payload.put("qualityScore", Integer.toString(qualityScore.getAsInt()));
        payload.put("deviceCode", deviceCode);
        payload.put("deviceServiceVersion", "pramaan-mode-d");
        String compact;
        try {
            compact = signer.sign(JSON.writeValueAsBytes(payload));
        } catch (GeneralSecurityException | IOException e) {
            throw new DeviceException(DeviceException.Reason.DEVICE_ERROR, "the capture could not be signed: " + e.getMessage(), e);
        }
        // The same verification an SBI client applies to any device's answer, so a key the trust does not know never reaches the engine.
        VerifiedJws verified;
        JsonNode signed;
        try {
            verified = Jws.verify(compact, trust);
            signed = JSON.readTree(verified.payloadText());
        } catch (JwsVerificationException | IOException e) {
            throw new DeviceException(DeviceException.Reason.UNTRUSTED, "this device's own capture was rejected: " + e.getMessage(), e);
        }
        String previous = request.previousHash() == null ? "" : request.previousHash();
        return new SignedCapture(verified.compact(), SbiCaptureChain.expectedHash(previous, iso), previous,
                signed.path("transactionId").asText(""), signed.path("timestamp").asText(null), verified.signer(), metadata,
                clock.instant());
    }

    @Override
    public void stop() {
        camera.stop();
    }

    @Override
    public java.util.Optional<pramaan.core.binding.SignedStreamLock> streamLock() {
        return camera.streamLock();
    }
}
