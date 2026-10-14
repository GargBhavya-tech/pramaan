/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import pramaan.core.binding.IsoFaceRecord;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.crypto.VerifiedJws;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.session.SessionContext;
import pramaan.core.source.Capability;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.FaceFrameSource;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.PixelFormat;
import pramaan.core.source.SignedCapture;

/**
 * A {@link FaceFrameSource} that replays recorded clips instead of reading a camera (Build Map #15-#17).
 *
 * <p>It drives the <em>same</em> engine code path as a real device, which is what makes harness numbers
 * mean anything. It plays a neutral clip, answers challenges from per-action clips — correctly, with the
 * wrong action, or impossibly early, as a replay attack would — injects device faults, and signs
 * captures with its own RSA key so binding can be exercised offline.
 *
 * <p>Two ways to drive it: {@link #start} with {@code realTime(true)} runs a paced thread; otherwise the
 * caller calls {@link #step()} and time moves only through the configured delayer (a test clock).
 */
public final class MockFrameSource implements FaceFrameSource {

    /** Key id of the mock's signing key; trust it with {@link #trust()}. */
    public static final String KEY_ID = "pramaan-mock";

    /** How the mock answers a challenge. */
    public enum ResponseMode {
        /** The requested action, after a human reaction delay. */
        CORRECT,
        /** A different action — the signature of a replayed recording. */
        WRONG_ACTION,
        /** The right action, but starting the instant the prompt appears — faster than any human (Bible Part 11.5). */
        EARLY,
        /** No reaction: a photo or a person who did not understand. */
        NONE
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Clock clock;
    private final Consumer<Duration> delayer;
    private final Duration interval;
    private final boolean realTime;
    private final DeviceMetadata device;
    private final Set<Capability> capabilities;
    private final List<byte[]> neutral;
    private final Map<ChallengeKind, List<byte[]>> actions;
    private final ResponseMode mode;
    private final Duration reactionDelay;
    private final MockFaults faults;
    private final KeyPair keys;

    private FrameSink sink;
    private volatile boolean running;
    private long emitted;
    private int neutralCursor;
    private final Deque<byte[]> pendingAction = new ArrayDeque<>();
    private Instant actionStartsAt;
    private byte[] lastFrame;
    private Thread pacer;
    private final boolean livenessProfile;
    private final long injectFromFrame;
    private final boolean wrongStreamHead;
    private MockLivenessProfile profile;
    private pramaan.core.binding.SignedStreamLock streamLock;

    private MockFrameSource(Builder b) throws GeneralSecurityException {
        this.clock = b.clock;
        this.delayer = b.delayer != null ? b.delayer : MockFrameSource::sleep;
        this.interval = Duration.ofNanos(Math.round(1e9 / b.fps));
        this.realTime = b.realTime;
        this.device = new DeviceMetadata("mock-face", "0", "mock-face-code", b.level, "PRAMAAN", "MockFace", "MOCK-0001");
        this.capabilities = EnumSet.copyOf(b.capabilities);
        if (b.neutral.isEmpty()) {
            throw new IllegalArgumentException("a mock needs at least one neutral frame");
        }
        this.neutral = new ArrayList<>(b.neutral);
        this.actions = new EnumMap<>(b.actions);
        this.mode = b.mode;
        this.reactionDelay = b.reactionDelay;
        this.faults = b.faults;
        this.livenessProfile = b.livenessProfile;
        this.injectFromFrame = b.injectFromFrame;
        this.wrongStreamHead = b.wrongStreamHead;
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keys = generator.generateKeyPair();
    }

    public static Builder builder(Clock clock) {
        return new Builder(clock);
    }

    /** The mock device's signing key, for tools that take a public key rather than a trust object. */
    public java.security.PublicKey trustedPublicKey() {
        return keys.getPublic();
    }

    /** Trust configuration that accepts exactly this mock's signatures. */
    public JwsTrust trust() {
        return JwsTrust.builder(clock).key(KEY_ID, keys.getPublic()).build();
    }

    @Override
    public DeviceCapabilities discover() {
        Set<Capability> declared = capabilities.isEmpty() ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(capabilities);
        if (livenessProfile) {
            declared.add(Capability.SIGNED_STREAM);
            declared.add(Capability.CAPTURE_BURST);
            declared.add(Capability.SENSOR_TIMESTAMPS);
        }
        return new DeviceCapabilities(device, declared);
    }

    /** The authenticated-stream lock, when this mock was built to speak the SBI Liveness Profile (Bible Part 41). */
    @Override
    public synchronized java.util.Optional<pramaan.core.binding.SignedStreamLock> streamLock() {
        return java.util.Optional.ofNullable(streamLock);
    }

    @Override
    public synchronized void start(SessionContext session, FrameSink frameSink) {
        if (running) {
            throw new IllegalStateException("already started");
        }
        this.sink = Objects.requireNonNull(frameSink, "sink");
        if (livenessProfile) {
            startProfile(session);
        }
        running = true;
        sink.onDeviceEvent(new DeviceEvent(DeviceEvent.Type.CONNECTED, device.toString(), clock.instant()));
        if (realTime) {
            pacer = new Thread(() -> {
                while (running) {
                    step();
                }
            }, "mock-frame-source");
            pacer.setDaemon(true);
            pacer.start();
        }
    }

    /**
     * Waits one frame interval (plus any latency fault) through the delayer, then emits one frame.
     *
     * @return false once the stream has ended
     */
    public boolean step() {
        Duration wait;
        synchronized (this) {
            if (!running) {
                return false;
            }
            wait = emitted == faults.latencySpikeAtFrame ? interval.plus(faults.latencySpike) : interval;
        }
        delayer.accept(wait);
        synchronized (this) {
            if (!running) {
                return false;
            }
            if (faults.disconnectAfterFrames >= 0 && emitted >= faults.disconnectAfterFrames) {
                running = false;
                sink.onDeviceEvent(new DeviceEvent(DeviceEvent.Type.DISCONNECTED, "mock fault: device disconnected",
                        clock.instant()));
                return false;
            }
            if (faults.emptyStream) {
                return true;
            }
            byte[] data = nextBytes();
            PixelFormat format = PixelFormat.JPEG;
            if (faults.corruptEvery > 0 && (emitted + 1) % faults.corruptEvery == 0) {
                data = garbage(emitted);
            }
            if (emitted == faults.wrongFormatAtFrame) {
                sink.onDeviceEvent(new DeviceEvent(DeviceEvent.Type.FORMAT_CHANGED, "mock fault: switched to GRAY8",
                        clock.instant()));
                format = PixelFormat.GRAY8;
                data = new byte[16];
            }
            lastFrame = data;
            Instant now = clock.instant();
            if (profile == null) {
                sink.onFrame(new Frame(data, format, 0, 0, Frame.NO_SENSOR_TIMESTAMP, now, emitted++, device));
                return true;
            }
            // The device tags the frame it captured; an injector between device and client then alters what arrives.
            long t = now.toEpochMilli();
            byte[] tag = profile.tag(data, t);
            byte[] arriving = data;
            if (emitted >= injectFromFrame) {
                arriving = data.clone();
                arriving[arriving.length - 1] ^= 0x01; // the device's own capture still comes from the original frame
            }
            streamLock.observe(arriving, t, tag);
            String checkpoint = null;
            if ((emitted + 1) % CHECKPOINT_EVERY == 0) {
                checkpoint = profile.checkpoint();
                streamLock.acceptCheckpoint(checkpoint, trust());
            }
            sink.onFrame(new Frame(arriving, format, 0, 0, Frame.NO_SENSOR_TIMESTAMP, now, emitted++, device, tag, t,
                    checkpoint));
            return true;
        }
    }

    /**
     * Tells the mock a challenge prompt was shown, so it can answer according to its {@link ResponseMode}.
     * In the real system the engine calls nothing on the device; the harness calls this to play the
     * part of the person (or the attacker) in front of the camera.
     */
    public synchronized void onChallenge(ChallengeKind kind, Instant promptShownAt) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(promptShownAt, "promptShownAt");
        switch (mode) {
            case CORRECT:
                schedule(kind, promptShownAt.plus(reactionDelay));
                break;
            case WRONG_ACTION:
                schedule(otherThan(kind), promptShownAt.plus(reactionDelay));
                break;
            case EARLY:
                schedule(kind, promptShownAt);
                break;
            case NONE:
            default:
                break;
        }
    }

    /**
     * Plays these frames, in order, from {@code startsAt} on, in place of the neutral clip: the harness uses it
     * to answer a prompt with the recorded action that matches it (a turn to the prompted side, a blink played
     * as many times as asked). Replaces anything still pending.
     */
    public synchronized void playAt(List<byte[]> frames, Instant startsAt) {
        Objects.requireNonNull(startsAt, "startsAt");
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("nothing to play");
        }
        pendingAction.clear();
        pendingAction.addAll(frames);
        actionStartsAt = startsAt;
    }

    @Override
    public SignedCapture capture(CaptureRequest request) throws DeviceException {
        if (faults.captureError) {
            throw new DeviceException(DeviceException.Reason.DEVICE_ERROR, "mock fault: rcapture reported an error");
        }
        byte[] image;
        synchronized (this) {
            image = lastFrame != null ? lastFrame : neutral.get(0);
        }
        // Like MockMDS and every SBI device: bioValue is an ISO 19794-5 face record, and the chain hash covers it.
        byte[] iso = IsoFaceRecord.encode(IsoFaceRecord.ImageFormat.JPEG, image, 0, 0);
        try {
            ObjectNode payload = JSON.createObjectNode();
            payload.put("bioType", "Face");
            payload.put("bioSubType", "");
            payload.put("purpose", "Registration");
            payload.put("env", "Staging");
            payload.put("transactionId", request.transactionId());
            payload.put("timestamp", clock.instant().toString());
            payload.put("bioValue", Base64.getUrlEncoder().withoutPadding().encodeToString(iso));
            payload.put("qualityScore", "80");
            payload.put("requestedScore", Integer.toString(request.requestedScore()));
            payload.put("deviceCode", device.deviceCode());
            payload.put("deviceServiceVersion", "pramaan-mock");
            if (profile != null) {
                // Profile addition 3: the signed capture names the head of the stream it was taken from.
                byte[] head = profile.streamTagHead();
                if (wrongStreamHead) {
                    head = head.clone();
                    head[0] ^= 0x55;
                }
                payload.put("streamTagHead", MockLivenessProfile.hex(head));
            }
            String jws = Jws.signRs256(JSON.writeValueAsBytes(payload), KEY_ID, keys.getPrivate());

            // Behave like a real source: verify before returning, and read claims only from the verified payload.
            VerifiedJws verified = Jws.verify(jws, trust());
            JsonNode claims = JSON.readTree(verified.payloadText());
            return new SignedCapture(jws, chainHash(request.previousHash(), iso), request.previousHash(),
                    claims.path("transactionId").asText(), claims.path("timestamp").asText(), verified.signer(), device,
                    clock.instant());
        } catch (GeneralSecurityException | JwsVerificationException | IOException e) {
            throw new DeviceException(DeviceException.Reason.DEVICE_ERROR, "mock capture failed: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (pacer != null) {
            pacer.interrupt();
            pacer = null;
        }
    }

    /** Frames between signed checkpoints (the Bible's N = 30). */
    static final int CHECKPOINT_EVERY = 30;

    /** The key agreement of profile addition 2, run in-process: this mock is both the device and (for its own tests) the client. */
    private void startProfile(SessionContext session) {
        String transactionId = session != null ? session.nonce().value() : "00000000000000000000000000000000";
        profile = new MockLivenessProfile(keys.getPrivate(), KEY_ID);
        java.security.KeyPair client = pramaan.core.binding.StreamKeyAgreement.newEphemeral();
        String answer = profile.answerKeyAgreement(transactionId, client.getPublic().getEncoded());
        try {
            byte[] ks = pramaan.core.binding.StreamKeyAgreement.acceptDeviceAnswer(answer, trust(), client, transactionId);
            streamLock = new pramaan.core.binding.SignedStreamLock(ks,
                    transactionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (JwsVerificationException e) {
            throw new IllegalStateException("the mock's own key agreement failed: " + e.getMessage(), e);
        }
    }

    /** Frames delivered so far. */
    public synchronized long emitted() {
        return emitted;
    }

    private byte[] nextBytes() {
        if (!pendingAction.isEmpty() && !clock.instant().isBefore(actionStartsAt)) {
            return pendingAction.pollFirst();
        }
        byte[] frame = neutral.get(neutralCursor % neutral.size());
        neutralCursor++;
        return frame;
    }

    private void schedule(ChallengeKind kind, Instant startsAt) {
        List<byte[]> clip = actions.get(kind);
        if (clip == null || clip.isEmpty()) {
            throw new IllegalStateException("mock has no clip for challenge " + kind);
        }
        pendingAction.clear();
        pendingAction.addAll(clip);
        actionStartsAt = startsAt;
    }

    private ChallengeKind otherThan(ChallengeKind kind) {
        for (ChallengeKind candidate : actions.keySet()) {
            if (candidate != kind) {
                return candidate;
            }
        }
        throw new IllegalStateException("WRONG_ACTION needs clips for at least two different challenges");
    }

    /**
     * The SBI chain hash, computed as MockMDS does and as verified in docs/sbi-notes.md:
     * UPPERHEX(SHA256(prev || SHA256(image))), prev = SHA256("") for the first capture.
     */
    static String chainHash(String previousHash, byte[] image) {
        try {
            byte[] prev = previousHash.isEmpty()
                    ? MessageDigest.getInstance("SHA-256").digest(new byte[0])
                    : hexToBytes(previousHash);
            byte[] current = MessageDigest.getInstance("SHA-256").digest(image);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(prev);
            sha.update(current);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : sha.digest()) {
                hex.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static byte[] garbage(long seed) {
        byte[] b = new byte[64];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (seed * 131 + i * 7);
        }
        return b;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis(), (int) (d.toNanos() % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Configures a mock. */
    public static final class Builder {

        private final Clock clock;
        private Consumer<Duration> delayer;
        private double fps = 10;
        private boolean realTime;
        private DeviceLevel level = DeviceLevel.L0;
        private final Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        private final List<byte[]> neutral = new ArrayList<>();
        private final Map<ChallengeKind, List<byte[]>> actions = new EnumMap<>(ChallengeKind.class);
        private ResponseMode mode = ResponseMode.CORRECT;
        private Duration reactionDelay = Duration.ofMillis(600);
        private MockFaults faults = MockFaults.NONE;
        private boolean livenessProfile;
        private long injectFromFrame = Long.MAX_VALUE;
        private boolean wrongStreamHead;

        /** Speak the SBI Liveness Profile (Bible Part 41): tag every frame and name the stream head in each capture. */
        public Builder livenessProfile(boolean enabled) {
            this.livenessProfile = enabled;
            return this;
        }

        /** Attack: from this frame on, an injector alters what the client receives (the device's tag is untouched). */
        public Builder injectFromFrame(long frame) {
            this.injectFromFrame = frame;
            return this;
        }

        /** Attack: the signed capture names a stream head that is not this stream's. */
        public Builder wrongStreamHead(boolean wrong) {
            this.wrongStreamHead = wrong;
            return this;
        }

        private Builder(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        public Builder fps(double framesPerSecond) {
            if (!(framesPerSecond > 0)) {
                throw new IllegalArgumentException("fps must be positive");
            }
            this.fps = framesPerSecond;
            return this;
        }

        /** How time passes between frames: {@code clock::advance} for a test clock; real sleeping by default. */
        public Builder delayer(Consumer<Duration> timePasses) {
            this.delayer = Objects.requireNonNull(timePasses, "timePasses");
            return this;
        }

        public Builder realTime(boolean paced) {
            this.realTime = paced;
            return this;
        }

        public Builder level(DeviceLevel deviceLevel) {
            this.level = Objects.requireNonNull(deviceLevel, "deviceLevel");
            return this;
        }

        public Builder capability(Capability capability) {
            capabilities.add(Objects.requireNonNull(capability, "capability"));
            return this;
        }

        public Builder neutral(List<byte[]> frames) {
            neutral.clear();
            neutral.addAll(frames);
            return this;
        }

        public Builder action(ChallengeKind kind, List<byte[]> frames) {
            actions.put(Objects.requireNonNull(kind, "kind"), new ArrayList<>(frames));
            return this;
        }

        public Builder responseMode(ResponseMode responseMode) {
            this.mode = Objects.requireNonNull(responseMode, "responseMode");
            return this;
        }

        public Builder reactionDelay(Duration delay) {
            this.reactionDelay = Objects.requireNonNull(delay, "delay");
            return this;
        }

        public Builder faults(MockFaults mockFaults) {
            this.faults = Objects.requireNonNull(mockFaults, "mockFaults");
            return this;
        }

        public MockFrameSource build() throws GeneralSecurityException {
            return new MockFrameSource(this);
        }
    }
}
