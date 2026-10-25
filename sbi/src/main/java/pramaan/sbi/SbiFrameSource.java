/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import pramaan.core.binding.SignedStreamLock;
import pramaan.core.binding.StreamKeyAgreement;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.crypto.VerifiedJws;
import pramaan.core.session.SessionContext;
import pramaan.core.binding.EmissionLog;
import pramaan.core.challenge.FlashSequence;
import pramaan.core.source.DeviceIlluminator;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import pramaan.core.source.BurstSource;
import pramaan.core.source.Capability;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;
import pramaan.core.source.DropOldestQueue;
import pramaan.core.source.FaceFrameSource;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.SignedCapture;
import pramaan.sbi.capture.RCaptureResponse;

/**
 * {@link FaceFrameSource} over a real MOSIP SBI device service (Build Map #12, #13).
 *
 * <p>Stream frames go reader thread → {@link DropOldestQueue} → delivery thread → sink, so a slow engine
 * drops old frames instead of blocking the device or growing memory. Captures are signature-verified
 * before they are returned.
 */
public final class SbiFrameSource implements FaceFrameSource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.concurrent.Executor ILLUMINATE_POOL = r -> {
        Thread t = new Thread(r, "sbi-illuminate");
        t.setDaemon(true);
        t.start();
    };

    private final SbiConfig config;
    private final SbiHttp http;
    private DiscoveredDevice face;
    private String subId;
    private volatile InputStream stream;
    private volatile DropOldestQueue<Frame> queue;
    private volatile String endReason = "stream ended";
    private volatile boolean stopping;
    private volatile SignedStreamLock streamLock;
    private volatile String transactionId;
    private Thread reader;
    private Thread delivery;

    public SbiFrameSource(SbiConfig config) {
        this.config = config;
        this.http = new SbiHttp(config.host(), config.probeTimeout());
    }

    @Override
    public synchronized DeviceCapabilities discover() throws DeviceException {
        List<DiscoveredDevice> devices = SbiDiscovery.discover(http, config.firstPort(), config.lastPort(),
                config.probeTimeout(), config.trust());
        face = null;
        for (DiscoveredDevice d : devices) {
            if (d.isFace()) {
                face = d;
                break;
            }
        }
        if (face == null) {
            throw new DeviceException(DeviceException.Reason.UNAVAILABLE, devices.isEmpty()
                    ? "no SBI device service answered on ports " + config.firstPort() + "-" + config.lastPort()
                    : "device services found, but none offers a Face device");
        }
        if (!face.isReady()) {
            throw new DeviceException(DeviceException.Reason.BUSY, "face device status is '" + face.status() + "'");
        }
        subId = face.deviceSubIds().isEmpty() ? "0" : face.deviceSubIds().get(0);
        // Plain SBI declares none of the optional capabilities; a device that offers the SBI Liveness Profile
        // (Bible Part 41) declares the ones its profile block names. The block is unsigned, so this only makes the
        // client *try* the handshake; trust comes from the device-signed answer, never from this advertisement.
        Set<Capability> declared = EnumSet.noneOf(Capability.class);
        declared.addAll(face.profileCapabilities());
        return new DeviceCapabilities(face.metadata(subId), declared);
    }

    /** The authenticated-stream lock of the running session, if the device agreed a key (Bible Part 41). */
    @Override
    public Optional<SignedStreamLock> streamLock() {
        return Optional.ofNullable(streamLock);
    }

    /**
     * The device's own light, when its profile offers a visible illuminator (addition 5). The schedule goes out as an
     * {@code ILLUMINATE} request on the session's transaction id and the signed emission log comes back, verified
     * against the same device trust as the capture.
     */
    @Override
    public synchronized Optional<DeviceIlluminator> illuminator() {
        if (face == null || !face.canIlluminate()) {
            return Optional.empty();
        }
        return Optional.of(sequence -> CompletableFuture.supplyAsync(() -> {
            try {
                return illuminate(sequence);
            } catch (DeviceException e) {
                throw new CompletionException(e);
            }
        }, ILLUMINATE_POOL));
    }

    /** The device's burst call, when its profile offers one (addition 4). */
    @Override
    public synchronized Optional<BurstSource> burst() {
        if (face == null || !face.profileCapabilities().contains(Capability.CAPTURE_BURST)) {
            return Optional.empty();
        }
        return Optional.of(this::captureBurst);
    }

    private EmissionLog illuminate(FlashSequence sequence) throws DeviceException {
        DiscoveredDevice device;
        String txn;
        synchronized (this) {
            requireDiscovered();
            device = face;
            txn = transactionId;
        }
        if (txn == null) {
            throw new DeviceException(DeviceException.Reason.PROTOCOL, "illuminate before the stream started");
        }
        long total = 0;
        for (int d : sequence.durationsMs()) {
            total += d;
        }
        byte[] response = http.call(device.port(), "ILLUMINATE", "/illuminate", SbiRequests.illuminate(txn, sequence),
                Duration.ofMillis(total).plusSeconds(10));
        try {
            return EmissionLog.verify(new String(response, StandardCharsets.UTF_8).trim(), config.trust(), txn);
        } catch (JwsVerificationException e) {
            throw new DeviceException(DeviceException.Reason.UNTRUSTED, "emission log rejected: " + e.getMessage(), e);
        }
    }

    /**
     * Several signed frames during a challenge (addition 4). Each comes back in the same shape as one RCAPTURE entry
     * and is verified exactly like a capture, so a swapped or unsigned frame never reaches the caller. Requires the
     * device's profile to offer {@code captureBurst}.
     */
    public List<SignedCapture> captureBurst(int count, Duration interval) throws DeviceException {
        DiscoveredDevice device;
        String sub;
        String txn;
        synchronized (this) {
            requireDiscovered();
            device = face;
            sub = subId;
            txn = transactionId;
        }
        if (!device.profileCapabilities().contains(Capability.CAPTURE_BURST)) {
            throw new DeviceException(DeviceException.Reason.PROTOCOL, "the device does not offer captureBurst");
        }
        if (txn == null) {
            throw new DeviceException(DeviceException.Reason.PROTOCOL, "captureBurst before the stream started");
        }
        if (count < 1 || count > 99 || interval.toMillis() < 10 || interval.toMillis() > 9999) {
            throw new IllegalArgumentException("burst of " + count + " frames every " + interval.toMillis() + " ms is out of range");
        }
        byte[] response = http.call(device.port(), "CAPTUREBURST", "/captureburst",
                SbiRequests.captureBurst(txn, count, interval.toMillis()),
                interval.multipliedBy(count).plusSeconds(10));
        RCaptureResponse parsed;
        try {
            parsed = RCaptureResponse.parse(response);
        } catch (IOException | IllegalArgumentException e) {
            throw new DeviceException(DeviceException.Reason.PROTOCOL, "captureBurst answer is malformed: " + e.getMessage(), e);
        }
        if (parsed.entries().size() != count) {
            throw new DeviceException(DeviceException.Reason.PROTOCOL,
                    "asked for " + count + " frames and got " + parsed.entries().size());
        }
        List<SignedCapture> out = new java.util.ArrayList<>();
        String previous = "";
        for (int i = 0; i < count; i++) {
            SignedCapture c = verified(parsed.entries().get(i), previous, device, sub);
            if (!txn.equals(c.verifiedTransactionId())) {
                throw new DeviceException(DeviceException.Reason.UNTRUSTED, "burst frame " + i + " is for another transaction");
            }
            out.add(c);
            previous = c.chainHash();
        }
        return out;
    }

    @Override
    public synchronized void start(SessionContext session, FrameSink sink) throws DeviceException {
        requireDiscovered();
        if (reader != null) {
            throw new IllegalStateException("already started");
        }
        // A source restarted after stop() must report a real disconnect again.
        stopping = false;
        KeyPair clientKey = null;
        String transactionId = session.nonce().value();
        this.transactionId = transactionId;
        byte[] request = SbiRequests.stream(face.deviceId(), subId, config.streamTimeout().toMillis());
        if (face.signedStream()) {
            clientKey = StreamKeyAgreement.newEphemeral();
            request = SbiRequests.streamWithProfile(face.deviceId(), subId, config.streamTimeout().toMillis(),
                    transactionId, Base64.getEncoder().encodeToString(clientKey.getPublic().getEncoded()));
        }
        streamLock = null;
        HttpResponse<InputStream> response = http.openStream(face.port(), request, config.streamTimeout());
        if (clientKey != null) {
            // The device said it speaks the profile: it must prove it. No signed answer, or one we cannot verify,
            // ends the session here - falling back to the plain stream would let a downgrade look like a quirk.
            String answer = response.headers().firstValue("X-SBI-LP-Answer").orElse(null);
            try {
                if (answer == null) {
                    throw new JwsVerificationException("the device offered the liveness profile but sent no signed answer");
                }
                byte[] ks = StreamKeyAgreement.acceptDeviceAnswer(answer, config.trust(), clientKey, transactionId);
                streamLock = new SignedStreamLock(ks, transactionId.getBytes(StandardCharsets.UTF_8));
            } catch (JwsVerificationException e) {
                closeQuietly(response.body());
                throw new DeviceException(DeviceException.Reason.UNTRUSTED,
                        "liveness-profile key agreement rejected: " + e.getMessage(), e);
            }
        }
        final SignedStreamLock lock = streamLock;
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
            closeQuietly(response.body());
            throw new DeviceException(DeviceException.Reason.PROTOCOL, "stream is not multipart: '" + contentType + "'");
        }
        stream = response.body();
        queue = new DropOldestQueue<>(config.queueCapacity());
        MjpegStreamReader mjpeg = new MjpegStreamReader(stream, contentType, config.clock(), face.metadata(subId));
        DropOldestQueue<Frame> q = queue;

        reader = new Thread(() -> readLoop(mjpeg, q, sink, lock), "sbi-stream-reader");
        delivery = new Thread(() -> deliveryLoop(q, sink), "sbi-stream-delivery");
        reader.setDaemon(true);
        delivery.setDaemon(true);
        sink.onDeviceEvent(new DeviceEvent(DeviceEvent.Type.CONNECTED, face.metadata(subId).toString(),
                config.clock().instant()));
        reader.start();
        delivery.start();
    }

    private void readLoop(MjpegStreamReader mjpeg, DropOldestQueue<Frame> q, FrameSink sink, SignedStreamLock lock) {
        String reason = "stream ended";
        try {
            Frame frame;
            while ((frame = mjpeg.next()) != null) {
                if (lock != null) {
                    // Every frame the device sent, as it arrives and before the drop-oldest queue can lose one: a
                    // frame dropped for being slow is still part of the chain the device tagged.
                    lock.observe(frame.copyBytes(), frame.streamTimeMillis(), frame.streamTag());
                    if (frame.streamCheckpoint() != null) {
                        lock.acceptCheckpoint(frame.streamCheckpoint(), config.trust());
                    }
                }
                q.offer(frame);
            }
            if (mjpeg.truncated()) {
                reason = "stream ended mid-frame";
            }
        } catch (IOException e) {
            reason = "stream failed: " + e.getMessage();
        } finally {
            // The delivery thread announces the end, after the frames still queued: a consumer must never see
            // DISCONNECTED and then more frames from the same stream (Bible Part 22).
            endReason = reason;
            q.close();
        }
    }

    private void deliveryLoop(DropOldestQueue<Frame> q, FrameSink sink) {
        long reportedDrops = 0;
        try {
            while (true) {
                Frame frame = q.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    sink.onFrame(frame);
                }
                long drops = q.droppedCount();
                if (drops > reportedDrops) {
                    sink.onDeviceEvent(new DeviceEvent(DeviceEvent.Type.FRAMES_DROPPED,
                            (drops - reportedDrops) + " frame(s) dropped", config.clock().instant()));
                    reportedDrops = drops;
                }
                if (frame == null && q.isClosed() && q.size() == 0) {
                    if (!stopping) {
                        sink.onDeviceEvent(new DeviceEvent(DeviceEvent.Type.DISCONNECTED, endReason, config.clock().instant()));
                    }
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public SignedCapture capture(CaptureRequest request) throws DeviceException {
        DiscoveredDevice device;
        String sub;
        synchronized (this) {
            requireDiscovered();
            device = face;
            sub = subId;
        }
        String spec = device.specVersions().isEmpty() ? "0.9.5" : device.specVersions().get(0);
        byte[] body = SbiRequests.rcaptureFace(request, device.deviceId(), sub, config.env(), spec, config.clock());
        byte[] response = http.call(device.port(), "RCAPTURE", "/capture", body,
                request.timeout().plusSeconds(5));
        return verifyCaptureResponse(response, request.previousHash());
    }

    /**
     * Parses and verifies an rCapture response body that someone else obtained: the response is checked exactly as
     * {@link #capture} checks its own (the signature against the pinned trust, the signed payload read as JSON). This is
     * how the Registration Client integration (docs, integration/registration-client) verifies the capture the client
     * itself requested from the device. {@link #discover()} must have run, so the device's metadata is known.
     *
     * @param previousHash the hash the capture chains to, as in {@link CaptureRequest#previousHash()}
     */
    public SignedCapture verifyCaptureResponse(byte[] response, String previousHash) throws DeviceException {
        DiscoveredDevice device;
        String sub;
        synchronized (this) {
            requireDiscovered();
            device = face;
            sub = subId;
        }
        RCaptureResponse parsed;
        try {
            parsed = RCaptureResponse.parse(response);
        } catch (IOException e) {
            throw new DeviceException(DeviceException.Reason.DEVICE_ERROR, "rcapture failed: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            // Signed data that is not a compact JWS: the device answered in the wrong shape. Without this it escaped
            // as an unchecked exception and the engine reported its own failure instead of the device's.
            throw new DeviceException(DeviceException.Reason.PROTOCOL, "rcapture data is not a JWS: " + e.getMessage(), e);
        }
        return verified(parsed.entries().get(0), previousHash, device, sub);
    }

    private SignedCapture verified(RCaptureResponse.Entry entry, String previousHash, DiscoveredDevice device, String sub)
            throws DeviceException {
        VerifiedJws verified;
        try {
            verified = Jws.verify(entry.data().compact(), config.trust());
        } catch (JwsVerificationException e) {
            throw new DeviceException(DeviceException.Reason.UNTRUSTED, "capture signature rejected: " + e.getMessage(), e);
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(verified.payloadText());
        } catch (IOException e) {
            throw new DeviceException(DeviceException.Reason.PROTOCOL, "signed payload is not JSON", e);
        }
        return new SignedCapture(verified.compact(), entry.hash() == null ? "" : entry.hash(), previousHash,
                payload.path("transactionId").asText(""), payload.path("timestamp").asText(null), verified.signer(),
                device.metadata(sub), config.clock().instant());
    }

    @Override
    public synchronized void stop() {
        // A stop we asked for is not a device fault: no DISCONNECTED for it.
        stopping = true;
        if (queue != null) {
            queue.close();
        }
        closeQuietly(stream);
        if (reader != null) {
            reader.interrupt();
        }
        if (delivery != null) {
            delivery.interrupt();
        }
        reader = null;
        delivery = null;
        stream = null;
    }

    private void requireDiscovered() {
        if (face == null) {
            throw new IllegalStateException("call discover() first");
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing is best-effort during shutdown.
            }
        }
    }
}
