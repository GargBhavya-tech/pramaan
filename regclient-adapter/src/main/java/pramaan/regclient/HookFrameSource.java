/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.regclient;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import pramaan.core.session.SessionContext;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceException;
import pramaan.core.source.DeviceMetadata;
import pramaan.core.source.FaceFrameSource;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.SignedCapture;
import pramaan.sbi.SbiFrameSource;

/**
 * A {@link FaceFrameSource} whose frames and capture both come from the Registration Client, not from a device connection of
 * its own. The client already holds the stream and makes the rCapture call; this object lets the engine see the frames the
 * client decoded, and gives the engine the signed capture the client obtained, after verifying it exactly as the engine
 * verifies one it requested itself ({@link SbiFrameSource#verifyCaptureResponse}). The engine's {@code capture} request is
 * how the hook learns it is time for the client to capture: it carries the nonce and the hash chain.
 */
final class HookFrameSource implements FaceFrameSource {

    private final SbiFrameSource sbi;
    private final CompletableFuture<CaptureRequest> requested = new CompletableFuture<>();
    private final CompletableFuture<byte[]> response = new CompletableFuture<>();
    private volatile FrameSink sink;
    private volatile DeviceMetadata metadata;

    HookFrameSource(SbiFrameSource sbi) {
        this.sbi = Objects.requireNonNull(sbi, "sbi");
    }

    /** Finds the device (only to learn what it is, and to verify its captures); the stream is the client's. */
    @Override
    public DeviceCapabilities discover() throws DeviceException {
        DeviceCapabilities found = sbi.discover();
        metadata = found.device();
        return found;
    }

    @Override
    public void start(SessionContext session, FrameSink frames) {
        this.sink = Objects.requireNonNull(frames, "frames");
    }

    /** What the device is, to stamp on frames; null before {@link #discover}. */
    DeviceMetadata metadata() {
        return metadata;
    }

    /** One decoded frame from the client's preview. */
    void deliver(Frame frame) {
        FrameSink s = sink;
        if (s != null) {
            s.onFrame(frame);
        }
    }

    /** The engine's capture request once it has asked for one, else null. */
    CaptureRequest pendingRequest() {
        return requested.getNow(null);
    }

    /** The client's rCapture response body, handed over after it made the call with the transaction id from the request. */
    void respond(byte[] responseBody) {
        response.complete(Objects.requireNonNull(responseBody, "responseBody"));
    }

    @Override
    public SignedCapture capture(CaptureRequest request) throws DeviceException {
        requested.complete(request);
        byte[] body;
        try {
            body = response.get(request.timeout().toMillis() + 5_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceException(DeviceException.Reason.DEVICE_ERROR, "interrupted while waiting for the client's capture", e);
        } catch (TimeoutException e) {
            throw new DeviceException(DeviceException.Reason.DEVICE_ERROR, "the client did not return a signed capture in time", e);
        } catch (ExecutionException | CancellationException e) {
            throw new DeviceException(DeviceException.Reason.DEVICE_ERROR, "the client's capture was abandoned", e);
        }
        return sbi.verifyCaptureResponse(body, request.previousHash());
    }

    @Override
    public void stop() {
        sink = null;
        response.cancel(true);
        sbi.stop();
    }
}
