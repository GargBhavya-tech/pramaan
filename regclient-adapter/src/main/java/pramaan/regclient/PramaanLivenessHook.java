/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.regclient;

import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.liveness.CaptureTicket;
import io.mosip.registration.liveness.LivenessHook;
import io.mosip.registration.liveness.LivenessRefusedException;
import io.mosip.registration.liveness.PromptListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import pramaan.core.Workflow;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.engine.EngineListener;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.quality.Guidance;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionState;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.PixelFormat;
import pramaan.desktop.capture.CaptureSession;
import pramaan.sbi.SbiConfig;
import pramaan.sbi.SbiFrameSource;

/**
 * PRAMAAN as the Registration Client's {@link LivenessHook}. The client keeps its own stream and its own rCapture call; this
 * class runs the engine on the frames the client decodes, tells the client when to capture and with which transaction id and
 * hash chain, then binds the signed capture the client got and returns the signed evidence for the face BIR, or refuses.
 *
 * <ol>
 *   <li>{@link #beginFaceScan}: a liveness session starts and searches for the device (to learn what it is);</li>
 *   <li>{@link #onPreviewFrame}: each decoded frame is queued for the engine on a thread of its own, so the client's preview
 *       never waits for the engine;</li>
 *   <li>{@link #awaitCaptureClearance}: blocks until the engine, having passed liveness, asks for the capture, and returns its
 *       nonce and frame hash chain as the {@link CaptureTicket} the client puts in its rCapture request;</li>
 *   <li>{@link #afterCapture}: hands the engine the device's raw response, waits for the four binding locks, and returns the
 *       evidence JWS, or throws {@link LivenessRefusedException} and the client stores nothing.</li>
 * </ol>
 *
 * It fails closed: no settings, no device, a timeout, a failed lock or an engine error all end in a refusal, never in a
 * capture without liveness. Nothing here has run inside the real client (integration/registration-client/README.md).
 */
public final class PramaanLivenessHook implements LivenessHook {

    private static volatile AdapterSettings configured;

    private volatile Scan current;
    private volatile PromptListener promptListener;

    /** The host calls this once at start-up, before the first face scan. */
    public static void configure(AdapterSettings settings) {
        configured = Objects.requireNonNull(settings, "settings");
    }

    /** Removes the settings: every later scan is refused until {@link #configure} is called again. */
    public static void reset() {
        configured = null;
    }

    /** For {@link java.util.ServiceLoader}. Settings come from {@link #configure}. */
    public PramaanLivenessHook() {
    }

    @Override
    public void setPromptListener(PromptListener listener) {
        this.promptListener = listener;
    }

    @Override
    public void beginFaceScan(String modality) {
        Scan old;
        synchronized (this) {
            old = current;
            AdapterSettings s = configured;
            current = s == null ? Scan.refused("PRAMAAN is installed but not configured") : new Scan(s, promptListener);
        }
        if (old != null) {
            old.close();
        }
    }

    @Override
    public void onPreviewFrame(byte[] jpeg, long arrivedAtEpochMillis) {
        Scan scan = current;
        if (scan != null) {
            scan.offer(jpeg, arrivedAtEpochMillis);
        }
    }

    @Override
    public CaptureTicket awaitCaptureClearance() throws InterruptedException, LivenessRefusedException {
        Scan scan = current;
        if (scan == null) {
            throw new LivenessRefusedException("no face scan was started");
        }
        try {
            return scan.awaitClearance();
        } catch (InterruptedException | LivenessRefusedException e) {
            close(scan);
            throw e;
        }
    }

    @Override
    public String afterCapture(List<BiometricsDto> captured) throws LivenessRefusedException {
        Scan scan = current;
        if (scan == null) {
            throw new LivenessRefusedException("no face scan was started");
        }
        try {
            return scan.finish(captured);
        } finally {
            close(scan);
        }
    }

    private synchronized void close(Scan scan) {
        if (current == scan) {
            current = null;
        }
        scan.close();
    }

    private static LivenessRefusedException refuse(String why) {
        return new LivenessRefusedException(why);
    }

    /** One face scan: a session, an engine, a frame pump, and what the client has said so far. */
    private static final class Scan implements AutoCloseable {

        private final AdapterSettings s;
        private final AdapterSettings.Subject subject;
        private final HookFrameSource source;
        private final CaptureSession capture;
        private final LivenessSession session;
        private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(8);
        private final AtomicLong index = new AtomicLong();
        private final Thread pump;
        private final PromptListener promptListener;
        private volatile String failure;

        /** A scan that can only refuse, because the hook was not set up: fail closed. */
        static Scan refused(String why) {
            return new Scan(why);
        }

        private Scan(String why) {
            this.s = null;
            this.subject = null;
            this.source = null;
            this.capture = null;
            this.session = null;
            this.pump = null;
            this.promptListener = null;
            this.failure = why;
        }

        Scan(AdapterSettings settings, PromptListener promptListener) {
            this.s = settings;
            this.promptListener = promptListener;
            LivenessSession built = null;
            HookFrameSource src = null;
            CaptureSession cs = null;
            AdapterSettings.Subject who = null;
            Thread pumpThread = null;
            try {
                who = settings.subject.get();
                built = settings.bundle.newSession(who.workflow(), who.subjectId(),
                        who.workflow() == Workflow.RESIDENT ? who.operatorId() : who.subjectId(), "sbi-face", who.centreId(),
                        settings.registry, settings.audit, settings.clock, settings.random);
                src = new HookFrameSource(new SbiFrameSource(SbiConfig.builder(settings.deviceTrust, settings.clock)
                        .host(settings.sbiHost).ports(settings.firstPort, settings.lastPort).build()));
                cs = new CaptureSession(src, settings.engineFactory.create(built, src, new Tracking(), settings.requestedScore),
                        new FrameSink() {
                            @Override
                            public void onFrame(Frame frame) {
                                // the client already shows the preview
                            }

                            @Override
                            public void onDeviceEvent(DeviceEvent event) {
                                // device events matter to the engine, which CaptureSession forwards
                            }
                        }, new CaptureSession.Problems() {
                            @Override
                            public void deviceUnavailable(Exception cause) {
                                fail("the face device is unavailable: " + cause.getMessage());
                            }

                            @Override
                            public void engineUnavailable(Exception cause) {
                                fail("the liveness engine is unavailable: " + cause);
                            }
                        });
                final CaptureSession starting = cs;
                Thread starter = new Thread(() -> {
                    if (starting.start()) {
                        starting.startTicker(Duration.ofMillis(250));
                    }
                }, "pramaan-hook-device-search");
                starter.setDaemon(true);
                starter.start();
                final HookFrameSource pumping = src;
                pumpThread = new Thread(() -> {
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            pumping.deliver(queue.take());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "pramaan-hook-frames");
                pumpThread.setDaemon(true);
                pumpThread.start();
            } catch (RuntimeException e) {
                failure = "the liveness session could not start: " + e;
            }
            this.subject = who;
            this.session = built;
            this.source = src;
            this.capture = cs;
            this.pump = pumpThread;
        }

        private void fail(String why) {
            if (failure == null) {
                failure = why;
            }
        }

        private static boolean endsTheScan(SessionState state) {
            switch (state) {
                case LOCKED_OUT:
                case EXCEPTION_FLOW:
                case EXCEPTION_BLOCKED:
                case UNAVAILABLE:
                case RATE_LIMITED:
                    return true;
                default:
                    return false;
            }
        }

        /** The engine's events: passed to the host's listener, and watched for the states that end the scan. */
        private final class Tracking implements EngineListener {
            @Override
            public void stateChanged(SessionState state) {
                if (endsTheScan(state)) {
                    fail("liveness ended: " + state);
                }
                s.listener.stateChanged(state);
            }

            @Override
            public void guidance(Guidance guidance) {
                s.listener.guidance(guidance);
                PromptListener pl = promptListener;
                if (pl != null && guidance != null && guidance != Guidance.NONE) {
                    pl.onPrompt("", guidance.name());
                }
            }

            @Override
            public void message(String messageId) {
                s.listener.message(messageId);
            }

            @Override
            public void prompt(ChallengePrompt prompt, int step, int steps) {
                s.listener.prompt(prompt, step, steps);
                PromptListener pl = promptListener;
                if (pl != null && prompt != null) {
                    pl.onPrompt(prompt.messageId(), prompt.animationHint());
                }
            }

            @Override
            public void stepCompleted(int step, int steps) {
                s.listener.stepCompleted(step, steps);
            }

            @Override
            public void attempt(int attempt, int maxAttempts) {
                s.listener.attempt(attempt, maxAttempts);
            }

            @Override
            public void cooldown(long waitMillis) {
                s.listener.cooldown(waitMillis);
            }
        }

        void offer(byte[] jpeg, long arrivedAtEpochMillis) {
            if (source == null || jpeg == null) {
                return;
            }
            Frame frame = new Frame(jpeg, PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP,
                    Instant.ofEpochMilli(arrivedAtEpochMillis), index.getAndIncrement(), source.metadata());
            if (!queue.offer(frame)) {
                queue.poll(); // the oldest frame is the least useful one
                queue.offer(frame);
            }
        }

        CaptureTicket awaitClearance() throws InterruptedException, LivenessRefusedException {
            long deadline = System.nanoTime() + (s == null ? 0 : s.clearanceTimeout.toNanos());
            while (true) {
                CaptureRequest request = source == null ? null : source.pendingRequest();
                if (request != null) {
                    return new CaptureTicket(request.transactionId(), request.previousHash());
                }
                String why = failure;
                if (why != null) {
                    throw refuse(why);
                }
                if (System.nanoTime() > deadline) {
                    throw refuse("liveness did not reach its capture step in time");
                }
                Thread.sleep(25);
            }
        }

        String finish(List<BiometricsDto> captured) throws LivenessRefusedException {
            if (failure != null) {
                throw refuse(failure);
            }
            if (captured == null || captured.isEmpty() || captured.get(0).getSbiResponse() == null) {
                throw refuse("the device's signed response was not kept by the client");
            }
            if (source.pendingRequest() == null) {
                throw refuse("the liveness session did not ask for this capture");
            }
            source.respond(("{\"biometrics\":[" + captured.get(0).getSbiResponse() + "]}").getBytes(StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + s.verdictTimeout.toNanos();
            while (true) {
                SessionState state = session.state();
                if (state == SessionState.ACCEPTED) {
                    break;
                }
                if (state != SessionState.CAPTURING && state != SessionState.BINDING) {
                    throw refuse("the capture was not accepted: " + state);
                }
                if (failure != null) {
                    throw refuse(failure);
                }
                if (System.nanoTime() > deadline) {
                    throw refuse("the capture was not decided in time");
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw refuse("interrupted while the capture was being bound");
                }
            }
            LivenessEngine engine = capture.engine().orElseThrow(() -> refuse("the liveness engine is gone"));
            try {
                return s.packager.pack(engine, subject.operatorId(), subject.centreId(), s.clock.instant()).evidenceJws();
            } catch (GeneralSecurityException | IOException | RuntimeException e) {
                throw refuse("the evidence could not be packed: " + e);
            }
        }

        @Override
        public void close() {
            if (pump != null) {
                pump.interrupt();
            }
            if (capture != null) {
                capture.close();
            }
        }
    }
}
