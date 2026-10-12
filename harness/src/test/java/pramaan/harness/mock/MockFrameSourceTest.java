/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.mock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.VerifiedJws;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.session.SessionNonce;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.PixelFormat;
import pramaan.core.source.SignedCapture;
import pramaan.core.testing.TestClock;

/** Build Map #15-#17: replay, challenge responses, faults, and signed captures — deterministic under a test clock. */
class MockFrameSourceTest {

    static final byte[] NEUTRAL = {1};
    static final byte[] TURN = {2};
    static final byte[] BLINK = {3};

    /** Records everything the mock delivers. */
    static final class Recorder implements FrameSink {
        final List<Frame> frames = new ArrayList<>();
        final List<DeviceEvent> events = new ArrayList<>();

        @Override
        public void onFrame(Frame frame) {
            frames.add(frame);
        }

        @Override
        public void onDeviceEvent(DeviceEvent event) {
            events.add(event);
        }
    }

    private static MockFrameSource.Builder mock(TestClock clock) {
        return MockFrameSource.builder(clock).delayer(clock::advance)
                .neutral(List.of(NEUTRAL))
                .action(ChallengeKind.TURN, List.of(TURN, TURN, TURN))
                .action(ChallengeKind.BLINK, List.of(BLINK, BLINK));
    }

    private static Recorder run(MockFrameSource source, int steps) {
        Recorder r = new Recorder();
        source.start(null, r);
        for (int i = 0; i < steps; i++) {
            source.step();
        }
        return r;
    }

    @Test
    void threeFpsReplayIsSpacedAThirdOfASecondApart() throws Exception {
        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        Recorder r = run(mock(clock).fps(3).build(), 4);
        assertEquals(4, r.frames.size());
        for (int i = 1; i < r.frames.size(); i++) {
            long gapMs = Duration.between(r.frames.get(i - 1).hostArrival(), r.frames.get(i).hostArrival()).toMillis();
            assertEquals(333, gapMs);
        }
        assertEquals(DeviceEvent.Type.CONNECTED, r.events.get(0).type());
    }

    @Test
    void aCorrectResponseStartsAfterAHumanReactionDelay() throws Exception {
        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        MockFrameSource source = mock(clock).fps(10).reactionDelay(Duration.ofMillis(600)).build();
        Recorder r = new Recorder();
        source.start(null, r);
        source.onChallenge(ChallengeKind.TURN, clock.instant());
        for (int i = 0; i < 10; i++) {
            source.step();
        }
        // Frames at +100..+500 ms are neutral; the turn starts at +600 ms.
        for (int i = 0; i < 5; i++) {
            assertArrayEquals(NEUTRAL, r.frames.get(i).copyBytes(), "frame " + i);
        }
        assertArrayEquals(TURN, r.frames.get(5).copyBytes());
        assertArrayEquals(TURN, r.frames.get(7).copyBytes());
        assertArrayEquals(NEUTRAL, r.frames.get(8).copyBytes());
    }

    @Test
    void aReplayAnswersWithTheWrongActionOrImpossiblyEarly() throws Exception {
        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        MockFrameSource wrong = mock(clock).fps(10).responseMode(MockFrameSource.ResponseMode.WRONG_ACTION).build();
        Recorder r = new Recorder();
        wrong.start(null, r);
        wrong.onChallenge(ChallengeKind.TURN, clock.instant());
        for (int i = 0; i < 8; i++) {
            wrong.step();
        }
        assertTrue(r.frames.stream().anyMatch(f -> f.copyBytes()[0] == BLINK[0]), "asked to turn, it blinked");
        assertTrue(r.frames.stream().noneMatch(f -> f.copyBytes()[0] == TURN[0]));

        TestClock clock2 = TestClock.at("2026-09-23T10:00:00Z");
        MockFrameSource early = mock(clock2).fps(10).responseMode(MockFrameSource.ResponseMode.EARLY).build();
        Recorder e = new Recorder();
        early.start(null, e);
        early.onChallenge(ChallengeKind.TURN, clock2.instant());
        early.step();
        assertArrayEquals(TURN, e.frames.get(0).copyBytes(), "onset 100 ms after the prompt: faster than a human");
    }

    @Test
    void captureIsSignedVerifiableEchoesTheNonceAndChainsLikeMockMds() throws Exception {
        TestClock clock = TestClock.at("2026-09-23T10:00:00Z");
        MockFrameSource source = mock(clock).build();
        run(source, 2);
        String nonce = SessionNonce.generate(new SecureRandom()).value();
        SignedCapture capture = source.capture(new CaptureRequest(nonce, "", 40, Duration.ofSeconds(5)));

        VerifiedJws verified = Jws.verify(capture.signedData(), source.trust());
        assertTrue(verified.payloadText().contains(nonce));
        assertEquals(nonce, capture.verifiedTransactionId());
        String bioValue = new com.fasterxml.jackson.databind.ObjectMapper().readTree(verified.payloadText()).path("bioValue").asText();
        byte[] iso = pramaan.core.binding.IsoFaceRecord.encode(pramaan.core.binding.IsoFaceRecord.ImageFormat.JPEG, NEUTRAL, 0, 0);
        assertArrayEquals(iso, Base64.getUrlDecoder().decode(bioValue), "an ISO 19794-5 record, as SBI devices send");
        assertEquals(MockFrameSource.chainHash("", iso), capture.chainHash());

        SignedCapture second = source.capture(new CaptureRequest(nonce, capture.chainHash(), 40, Duration.ofSeconds(5)));
        assertEquals(MockFrameSource.chainHash(capture.chainHash(), iso), second.chainHash(), "chains across captures");
    }

    @Test
    void faultsReproduceTheDeviceErrorTable() throws Exception {
        TestClock c1 = TestClock.at("2026-09-23T10:00:00Z");
        Recorder disconnect = run(mock(c1).faults(MockFaults.builder().disconnectAfterFrames(3).build()).build(), 6);
        assertEquals(3, disconnect.frames.size());
        assertEquals(DeviceEvent.Type.DISCONNECTED, disconnect.events.get(disconnect.events.size() - 1).type());

        TestClock c2 = TestClock.at("2026-09-23T10:00:00Z");
        Recorder corrupt = run(mock(c2).faults(MockFaults.builder().corruptEvery(2).build()).build(), 4);
        assertArrayEquals(NEUTRAL, corrupt.frames.get(0).copyBytes());
        assertEquals(64, corrupt.frames.get(1).length(), "every second frame is garbage");

        TestClock c3 = TestClock.at("2026-09-23T10:00:00Z");
        Recorder format = run(mock(c3).faults(MockFaults.builder().wrongFormatAtFrame(1).build()).build(), 2);
        assertEquals(PixelFormat.GRAY8, format.frames.get(1).format());
        assertTrue(format.events.stream().anyMatch(e -> e.type() == DeviceEvent.Type.FORMAT_CHANGED));

        TestClock c4 = TestClock.at("2026-09-23T10:00:00Z");
        Recorder spike = run(mock(c4).fps(10).faults(MockFaults.builder().latencySpike(2, Duration.ofMillis(500)).build()).build(), 4);
        assertEquals(600, Duration.between(spike.frames.get(1).hostArrival(), spike.frames.get(2).hostArrival()).toMillis());

        TestClock c5 = TestClock.at("2026-09-23T10:00:00Z");
        Recorder empty = run(mock(c5).faults(MockFaults.builder().emptyStream().build()).build(), 5);
        assertTrue(empty.frames.isEmpty());

        MockFrameSource failing = mock(TestClock.at("2026-09-23T10:00:00Z"))
                .faults(MockFaults.builder().captureError().build()).build();
        DeviceException e = assertThrows(DeviceException.class,
                () -> failing.capture(new CaptureRequest("abcd", "", 40, Duration.ofSeconds(1))));
        assertEquals(DeviceException.Reason.DEVICE_ERROR, e.reason());
    }

    @Test
    void realTimeModeDeliversFramesOnItsOwnThread() throws Exception {
        MockFrameSource source = MockFrameSource.builder(java.time.Clock.systemUTC()).fps(50).realTime(true)
                .neutral(List.of(NEUTRAL)).build();
        List<Frame> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
        source.start(null, new FrameSink() {
            @Override
            public void onFrame(Frame frame) {
                frames.add(frame);
            }

            @Override
            public void onDeviceEvent(DeviceEvent event) {
            }
        });
        Thread.sleep(300);
        source.stop();
        assertTrue(frames.size() >= 5, "got " + frames.size());
    }
}
