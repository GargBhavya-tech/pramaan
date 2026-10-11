/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;
import pramaan.core.source.FaceFrameSource;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameRateMeter;
import pramaan.core.source.FrameSink;
import pramaan.core.source.SessionCapabilities;
import pramaan.core.source.SignedCapture;

/**
 * One face scan, as the Registration Client's scan button runs it — device search, stream, capture — with the
 * liveness engine between the stream and the capture (ADR 0003, patch points P1 and P2):
 *
 * <ol>
 *   <li>{@link #start()} discovers the face device and opens its stream. Every frame goes to the preview.</li>
 *   <li>For the first second the frame rate is measured (Bible Part 4: the stream guarantees only 3 fps, so
 *       what the session can ask for depends on what actually arrives). Then the engine is built for that rate
 *       and started, and from the next frame on every frame also goes to the engine.</li>
 *   <li>The engine asks the device for the capture itself, with the session nonce, once liveness allows it —
 *       never in parallel with the stream, as the unpatched client does.</li>
 * </ol>
 *
 * Frames arrive on the device's delivery thread, which the engine may block while it captures; the device
 * drops the oldest frames meanwhile, and nothing needs them.
 */
public final class CaptureSession implements AutoCloseable {

    /** Builds the session's engine once the frame rate is known. */
    public interface EngineFactory {
        LivenessEngine create(SessionCapabilities capabilities, DeviceCapabilities device) throws Exception;
    }

    /** What went wrong before an engine existed. */
    public interface Problems {
        /** No face device, or it would not stream: "Camera not connected" (Part 22). */
        void deviceUnavailable(Exception cause);

        /** The engine could not be built: liveness is unavailable, and never skipped (fail closed). */
        void engineUnavailable(Exception cause);
    }

    private final FaceFrameSource source;
    private final EngineFactory factory;
    private final FrameSink preview;
    private final Problems problems;
    private final FrameRateMeter meter = new FrameRateMeter();
    private DeviceCapabilities device;
    private volatile LivenessEngine engine;
    private ScheduledExecutorService ticker;
    private boolean failed;
    private volatile java.util.function.LongConsumer engineTimer = nanos -> { };

    /** @param preview receives every frame, for display only */
    public CaptureSession(FaceFrameSource source, EngineFactory factory, FrameSink preview, Problems problems) {
        this.source = Objects.requireNonNull(source, "source");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.preview = Objects.requireNonNull(preview, "preview");
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    /** Device search and stream. A device that cannot be found or started is reported, and nothing starts. */
    public synchronized boolean start() {
        try {
            device = source.discover();
            source.start(null, new Sink());
            return true;
        } catch (DeviceException | RuntimeException e) {
            fail(e);
            return false;
        }
    }

    /** Fires the session's timeouts even when no frame arrives, so a stalled stream still ends. */
    public synchronized void startTicker(Duration every) {
        if (ticker == null) {
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pramaan-session-ticker");
                t.setDaemon(true);
                return t;
            });
            ticker.scheduleAtFixedRate(() -> {
                LivenessEngine e = engine;
                if (e != null) {
                    e.tick();
                }
            }, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Receives the engine time of each frame, in nanoseconds: for diagnostic mode only (core reads no clock). */
    public void setEngineTimer(java.util.function.LongConsumer timer) {
        this.engineTimer = Objects.requireNonNull(timer, "timer");
    }

    /** The engine, once the frame rate has been measured. */
    public Optional<LivenessEngine> engine() {
        return Optional.ofNullable(engine);
    }

    /** The signed capture the session accepted, once it has. */
    public Optional<SignedCapture> acceptedCapture() {
        LivenessEngine e = engine;
        return e == null ? Optional.empty() : e.acceptedCapture();
    }

    @Override
    public synchronized void close() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
        source.stop();
    }

    private synchronized void onFirstSecond(Frame frame) {
        if (engine != null || failed) {
            return;
        }
        meter.onFrame(frame.hostArrival());
        if (!meter.settled()) {
            return;
        }
        try {
            LivenessEngine e = factory.create(SessionCapabilities.of(meter.fps().getAsDouble(), device), device);
            e.start();
            engine = e;
        } catch (Exception ex) {
            failed = true;
            problems.engineUnavailable(ex);
        }
    }

    private void fail(Exception e) {
        failed = true;
        problems.deviceUnavailable(e);
    }

    private final class Sink implements FrameSink {
        @Override
        public void onFrame(Frame frame) {
            preview.onFrame(frame);
            LivenessEngine e = engine;
            if (e == null) {
                onFirstSecond(frame);
            } else {
                long t0 = System.nanoTime();
                e.onFrame(frame);
                engineTimer.accept(System.nanoTime() - t0);
            }
        }

        @Override
        public void onDeviceEvent(DeviceEvent event) {
            preview.onDeviceEvent(event);
            LivenessEngine e = engine;
            if (e != null) {
                e.onDeviceEvent(event);
            } else if (event.type() == DeviceEvent.Type.DISCONNECTED || event.type() == DeviceEvent.Type.ERROR) {
                synchronized (CaptureSession.this) {
                    if (!failed) {
                        fail(new DeviceException(DeviceException.Reason.DEVICE_ERROR, event.detail()));
                    }
                }
            }
        }
    }
}
