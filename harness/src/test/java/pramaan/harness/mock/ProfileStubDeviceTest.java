/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pramaan.core.Workflow;
import pramaan.core.binding.EmissionLog;
import pramaan.core.binding.SignedStreamLock;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.ChallengeGenerator;
import pramaan.core.challenge.FlashSequence;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.policy.YamlPolicySource;
import pramaan.core.session.SessionContext;
import pramaan.core.source.Capability;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceIdentity;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.SessionCapabilities;
import pramaan.core.source.SignedCapture;
import pramaan.sbi.SbiConfig;
import pramaan.sbi.SbiFrameSource;

/**
 * The runnable profile device, driven by the real {@code SbiFrameSource} over real loopback HTTP and trusted the way a
 * MOSIP device is: through a CA certificate and an {@code x5c} leaf, not a pinned key. Everything the in-process stubs
 * checked, now across a socket with certificate chains and a signed {@code digitalId}.
 */
class ProfileStubDeviceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4, (byte) 0xFF, (byte) 0xD9};
    private static ProfileStubDevice device;
    private static DevelopmentCa ca;

    @BeforeAll
    static void startDevice(@TempDir Path dir) throws Exception {
        assumeTrue(DevelopmentCa.available(), "no keytool in this JDK");
        ca = DevelopmentCa.create(dir);
        device = new ProfileStubDevice(0, JPEG, ca, Clock.systemUTC());
    }

    @AfterAll
    static void stopDevice() {
        if (device != null) {
            device.close();
        }
    }

    private static JwsTrust trustFor(Path caCertificate) throws Exception {
        X509Certificate anchor = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(caCertificate)));
        return JwsTrust.builder(Clock.systemUTC()).anchor(anchor).build();
    }

    private static SbiFrameSource client(JwsTrust trust) {
        return new SbiFrameSource(SbiConfig.builder(trust, Clock.systemUTC()).ports(device.port(), device.port())
                .probeTimeout(Duration.ofSeconds(10)).build());
    }

    private static SessionContext session() throws Exception {
        return SessionContext.start(Workflow.RESIDENT,
                YamlPolicySource.of(Path.of("..", "config", "pramaan-policy.yaml")).load(), Clock.systemUTC(),
                new SecureRandom());
    }

    private static FlashSequence schedule() {
        DeviceCapabilities caps = new DeviceCapabilities(
                new DeviceMetadata("1", "0", "code", DeviceLevel.L0, "PRAMAAN", "ProfileStub", "1"),
                EnumSet.of(Capability.RESIDENT_DISPLAY));
        return ChallengeGenerator.fresh(new SecureRandom()).plan(
                List.of(ChallengeKind.TURN, ChallengeKind.BLINK, ChallengeKind.SMILE, ChallengeKind.FLASH), 1, 3,
                SessionCapabilities.of(10, caps), AccessibilityAdjustments.NONE).flash().orElseThrow();
    }

    /** Streams until {@code frames} have arrived, and returns the session the device agreed a key for. */
    private static SessionContext streaming(SbiFrameSource source, int frames) throws Exception {
        SessionContext session = session();
        CountDownLatch enough = new CountDownLatch(frames);
        source.start(session, new FrameSink() {
            @Override
            public void onFrame(Frame frame) {
                enough.countDown();
            }

            @Override
            public void onDeviceEvent(DeviceEvent event) {
            }
        });
        assertTrue(enough.await(20, TimeUnit.SECONDS), "the device did not stream " + frames + " frames");
        return session;
    }

    @Test
    void theDevicesSignedDigitalIdChainsToTheCaAndTheProfileIsDeclared() throws Exception {
        try (SbiFrameSource source = client(trustFor(ca.caCertificate()))) {
            DeviceCapabilities caps = source.discover();
            assertEquals(DeviceIdentity.VERIFIED, caps.device().identity());
            assertTrue(caps.has(Capability.SIGNED_STREAM));
            assertTrue(caps.has(Capability.CAPTURE_BURST));
            assertTrue(caps.has(Capability.RESIDENT_DISPLAY));
        }
    }

    @Test
    void aClientThatTrustsAnotherCaFindsTheDigitalIdFailed(@TempDir Path other) throws Exception {
        DevelopmentCa stranger = DevelopmentCa.create(other);
        try (SbiFrameSource source = client(trustFor(stranger.caCertificate()))) {
            assertEquals(DeviceIdentity.FAILED, source.discover().device().identity());
        }
    }

    @Test
    void theStreamIsAuthenticatedAndTheCaptureVerifiesAndEchoesTheNonce() throws Exception {
        try (SbiFrameSource source = client(trustFor(ca.caCertificate()))) {
            source.discover();
            SessionContext session = streaming(source, 12);
            SignedStreamLock lock = source.streamLock().orElseThrow();
            assertTrue(lock.framesObserved() >= 12);
            assertEquals(-1, lock.brokenAt(), "no frame was tampered with");
            SignedCapture capture = source.capture(new CaptureRequest(session.nonce().value(), "", 40, Duration.ofSeconds(5)));
            assertEquals(session.nonce().value(), capture.verifiedTransactionId());
        }
    }

    @Test
    void theDevicesOwnLightReturnsASignedLogThatMatchesTheSchedule() throws Exception {
        try (SbiFrameSource source = client(trustFor(ca.caCertificate()))) {
            source.discover();
            streaming(source, 3);
            FlashSequence asked = schedule();
            EmissionLog log = source.illuminator().orElseThrow().illuminate(asked).get(30, TimeUnit.SECONDS);
            assertNull(log.differenceFrom(asked, 0));
        }
    }

    @Test
    void aBurstOfSignedFramesComesBackVerified() throws Exception {
        try (SbiFrameSource source = client(trustFor(ca.caCertificate()))) {
            source.discover();
            SessionContext session = streaming(source, 3);
            List<SignedCapture> frames = source.captureBurst(3, Duration.ofMillis(200));
            assertEquals(3, frames.size());
            for (SignedCapture f : frames) {
                assertEquals(session.nonce().value(), f.verifiedTransactionId());
            }
        }
    }
}
