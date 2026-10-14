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

    private final SbiConfig config;
    private final SbiHttp http;
    private DiscoveredDevice face;
    private String subId;
    private volatile InputStream stream;
    private volatile DropOldestQueue<Frame> queue;
    private volatile String endReason = "stream ended";
    private volatile boolean stopping;
    private volatile SignedStreamLock streamLock;
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
        RCaptureResponse.Entry entry = parsed.entries().get(0);

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
        return new SignedCapture(verified.compact(), entry.hash() == null ? "" : entry.hash(), request.previousHash(),
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
