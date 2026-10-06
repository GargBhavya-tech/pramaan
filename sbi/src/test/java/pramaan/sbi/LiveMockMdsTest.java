/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.UnverifiedJws;
import pramaan.core.policy.YamlPolicySource;
import pramaan.core.session.SessionContext;
import pramaan.core.session.SessionNonce;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameRateMeter;
import pramaan.core.source.FrameSink;
import pramaan.core.source.SignedCapture;

/**
 * Build Map #12, #13, #18 against the real MOSIP MockMDS on port 4501. Skipped (not passed) when it is
 * not running. Start it as described in docs/sbi-notes.md.
 */
class LiveMockMdsTest {

    @BeforeAll
    static void requireMockMds() {
        boolean up;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 4501), 300);
            up = true;
        } catch (java.io.IOException e) {
            up = false;
        }
        assumeTrue(up, "MockMDS is not running on 127.0.0.1:4501");
    }

    /**
     * MockMDS ships its own development CA. In production the anchor is MOSIP's device CA, configured by
     * the deployment; here we pin the mock's root, taken from one of its own responses, as the anchor.
     */
    private static JwsTrust mockTrust(Clock clock) throws Exception {
        // The anchor is pinned from a separate file, never taken from the response being verified: that
        // would trust whatever certificate the device chose to send. MockMDS's x5c carries only its leaf
        // (CN=MosipFace, issued by CN=RootCA); RootCA was exported from the mock's keystore (docs/sbi-notes.md).
        byte[] rootDer = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("testdata", "mockmds-rootca.cer"));
        X509Certificate anchor = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(rootDer));
        return JwsTrust.builder(clock).anchor(anchor).build();
    }

    @Test
    void realCaptureVerifiesAgainstTheMockCaAndEchoesOurNonce() throws Exception {
        Clock clock = Clock.systemUTC();
        try (SbiFrameSource source = new SbiFrameSource(SbiConfig.builder(mockTrust(clock), clock).ports(4501, 4501).build())) {
            source.discover();
            SecureRandom random = new SecureRandom();
            for (int i = 0; i < 3; i++) {
                String nonce = SessionNonce.generate(random).value();
                SignedCapture capture = source.capture(new CaptureRequest(nonce, "", 40, Duration.ofSeconds(10)));
                assertEquals(nonce, capture.verifiedTransactionId(), "MockMDS must echo the nonce inside the signature");
                assertTrue(capture.chainHash().matches("[0-9A-F]{64}"));
            }
        }
    }

    @Test
    void realStreamRunsAtOrAboveTheSpecFloor() throws Exception {
        Clock clock = Clock.systemUTC();
        List<Frame> frames = new CopyOnWriteArrayList<>();
        FrameRateMeter meter = new FrameRateMeter();
        try (SbiFrameSource source = new SbiFrameSource(SbiConfig.builder(mockTrust(clock), clock).ports(4501, 4501).build())) {
            source.discover();
            SessionContext session = SessionContext.start(Workflow.RESIDENT,
                    YamlPolicySource.of(java.nio.file.Path.of("..", "config", "pramaan-policy.yaml")).load(), clock, new SecureRandom());
            source.start(session, new FrameSink() {
                @Override
                public void onFrame(Frame frame) {
                    frames.add(frame);
                    synchronized (meter) {
                        meter.onFrame(frame.hostArrival());
                    }
                }

                @Override
                public void onDeviceEvent(DeviceEvent event) {
                }
            });
            TimeUnit.MILLISECONDS.sleep(2_500);
        }
        double fps;
        synchronized (meter) {
            fps = meter.fps().orElse(0);
        }
        assertTrue(frames.size() > 5, "frames: " + frames.size());
        assertTrue(fps >= 3.0, "spec floor is 3 fps, measured " + fps);
    }
}
