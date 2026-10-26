/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.binding.IsoFaceRecord;
import pramaan.core.binding.SbiCaptureChain;
import pramaan.core.binding.VerifiedCapture;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.session.SessionContext;
import pramaan.core.source.Capability;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceException;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.FaceFrameSource;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.ModeDDevice;
import pramaan.core.source.ModeDDevice.PayloadSigner;
import pramaan.core.source.PixelFormat;
import pramaan.core.source.SignedCapture;
import pramaan.harness.mock.DevelopmentCa;

/**
 * Deployment Mode D: the app that owns the camera signs its own captures. The capture must verify under the device's own trust, carry
 * the request's transaction id and the SBI hash chain over the ISO face record of the last frame, and be refused by any trust that does
 * not know the device's key.
 */
class ModeDDeviceTest {

    private static final byte[] FRAME = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, (byte) 0xFF, (byte) 0xD9};
    private static final Clock CLOCK = Clock.systemUTC();

    /** A camera that delivers one JPEG frame when started and records what it was told. */
    private static final class FakeCamera implements FaceFrameSource {
        final DeviceMetadata meta = new DeviceMetadata("cam", "0", "MODE_D", DeviceLevel.L0, "Test", "Phone", "none");
        final List<Frame> delivered = new ArrayList<>();
        boolean stopped;
        boolean deliver = true;

        @Override
        public DeviceCapabilities discover() {
            return new DeviceCapabilities(meta, EnumSet.of(Capability.SENSOR_TIMESTAMPS));
        }

        @Override
        public void start(SessionContext session, FrameSink sink) {
            if (deliver) {
                Frame f = new Frame(FRAME, PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP, Instant.now(CLOCK), 0, meta);
                delivered.add(f);
                sink.onFrame(f);
            }
        }

        @Override
        public SignedCapture capture(CaptureRequest request) {
            throw new UnsupportedOperationException("the camera only streams");
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    private static final class Sink implements FrameSink {
        final List<Frame> frames = new ArrayList<>();

        @Override
        public void onFrame(Frame frame) {
            frames.add(frame);
        }

        @Override
        public void onDeviceEvent(pramaan.core.source.DeviceEvent event) {
        }
    }

    private static final String KID = "mode-d-test";

    /** A device signing with its bare key (id KID) that a verifier holding {@code trusted}'s public key accepts. */
    private static ModeDDevice pinned(FaceFrameSource camera, DevelopmentCa signer, DevelopmentCa trusted, int quality) {
        return new ModeDDevice(camera, PayloadSigner.withKeyId(KID, signer.leafKey()),
                ModeDDevice.trustForPinnedKey(KID, trusted.leafCertificate().getPublicKey(), CLOCK), CLOCK, "pramaan-test", "Staging",
                () -> quality);
    }

    private static X509Certificate authority(DevelopmentCa ca) throws Exception {
        try (InputStream in = Files.newInputStream(ca.caCertificate())) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static CaptureRequest request(String txn, String previous) {
        return new CaptureRequest(txn, previous, 40, Duration.ofSeconds(5));
    }

    @Test
    void aCaptureVerifiesUnderTheDevicesPinnedKeyAndCarriesTheRequestAndTheLastFrame(@TempDir Path dir) throws Exception {
        DevelopmentCa ca = DevelopmentCa.create(dir);
        ModeDDevice device = pinned(new FakeCamera(), ca, ca, 61);
        device.discover();
        Sink sink = new Sink();
        device.start(null, sink);
        assertEquals(1, sink.frames.size(), "the stream passes through to the engine");

        SignedCapture capture = device.capture(request("0123456789abcdef0123456789abcdef", ""));
        assertEquals("0123456789abcdef0123456789abcdef", capture.verifiedTransactionId());
        assertTrue(capture.verifiedTimestamp() != null && !capture.verifiedTimestamp().isEmpty());
        byte[] iso = IsoFaceRecord.encode(IsoFaceRecord.ImageFormat.JPEG, FRAME, 0, 0);
        JwsTrust trust = ModeDDevice.trustForPinnedKey(KID, ca.leafCertificate().getPublicKey(), CLOCK);
        assertArrayEquals(iso, VerifiedCapture.verify(capture, trust).isoRecord(), "the signed record holds the last frame");
        assertEquals(SbiCaptureChain.expectedHash("", iso), capture.chainHash());
        assertEquals("", capture.previousHash());
        assertEquals(DeviceLevel.L0, capture.device().level());
    }

    @Test
    void aCertificateIssuedUnderAnAuthorityVerifiesThroughItsChain(@TempDir Path dir) throws Exception {
        DevelopmentCa ca = DevelopmentCa.create(dir);
        JwsTrust trust = ModeDDevice.trustFor(authority(ca), CLOCK);
        ModeDDevice device = new ModeDDevice(new FakeCamera(), PayloadSigner.withCertificate(ca.leafCertificate(), ca.leafKey()), trust,
                CLOCK, "pramaan-test", "Staging", () -> 0);
        device.discover();
        device.start(null, new Sink());
        SignedCapture capture = device.capture(request("txn-chain-0001", ""));
        assertEquals("txn-chain-0001", capture.verifiedTransactionId());
        assertTrue(VerifiedCapture.verify(capture, trust).isoRecord().length > 0);
    }

    @Test
    void aCertificateThatIsItselfTheTrustAnchorIsRefused(@TempDir Path dir) throws Exception {
        DevelopmentCa ca = DevelopmentCa.create(dir);
        ModeDDevice device = new ModeDDevice(new FakeCamera(), PayloadSigner.withCertificate(ca.leafCertificate(), ca.leafKey()),
                ModeDDevice.trustFor(ca.leafCertificate(), CLOCK), CLOCK, "pramaan-test", "Staging", () -> 0);
        device.discover();
        device.start(null, new Sink());
        DeviceException refused = assertThrows(DeviceException.class, () -> device.capture(request("txn-anchor-0001", "")));
        assertEquals(DeviceException.Reason.UNTRUSTED, refused.reason(), "an anchor cannot sign data directly");
    }

    @Test
    void theCaptureHashChainsOntoThePreviousCapture(@TempDir Path dir) throws Exception {
        DevelopmentCa ca = DevelopmentCa.create(dir);
        ModeDDevice device = pinned(new FakeCamera(), ca, ca, 0);
        device.discover();
        device.start(null, new Sink());
        SignedCapture first = device.capture(request("txn-one-0001", ""));
        SignedCapture second = device.capture(request("txn-two-0002", first.chainHash()));
        assertEquals(first.chainHash(), second.previousHash());
        assertNotEquals(first.chainHash(), second.chainHash(), "the chain moves on");
        assertEquals(SbiCaptureChain.expectedHash(first.chainHash(), IsoFaceRecord.encode(IsoFaceRecord.ImageFormat.JPEG, FRAME, 0, 0)),
                second.chainHash());
    }

    @Test
    void aTrustHoldingADifferentKeyRefusesTheCaptureAsUntrusted(@TempDir Path dir) throws Exception {
        DevelopmentCa ca = DevelopmentCa.create(dir);
        DevelopmentCa other = DevelopmentCa.create(Files.createDirectory(dir.resolve("other")));
        ModeDDevice device = pinned(new FakeCamera(), ca, other, 0);
        device.discover();
        device.start(null, new Sink());
        DeviceException refused = assertThrows(DeviceException.class, () -> device.capture(request("txn-three-0003", "")));
        assertEquals(DeviceException.Reason.UNTRUSTED, refused.reason());
    }

    @Test
    void thereIsNothingToCaptureBeforeTheFirstFrame(@TempDir Path dir) throws Exception {
        DevelopmentCa ca = DevelopmentCa.create(dir);
        FakeCamera camera = new FakeCamera();
        camera.deliver = false;
        ModeDDevice device = pinned(camera, ca, ca, 0);
        device.discover();
        device.start(null, new Sink());
        DeviceException none = assertThrows(DeviceException.class, () -> device.capture(request("txn-four-0004", "")));
        assertEquals(DeviceException.Reason.DEVICE_ERROR, none.reason());
    }

    @Test
    void stoppingTheDeviceStopsTheCamera(@TempDir Path dir) throws Exception {
        DevelopmentCa ca = DevelopmentCa.create(dir);
        FakeCamera camera = new FakeCamera();
        pinned(camera, ca, ca, 0).stop();
        assertTrue(camera.stopped);
    }
}
