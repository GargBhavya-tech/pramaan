/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android.regclient;

import io.mosip.registration_client.liveness.CaptureTicket;
import io.mosip.registration_client.liveness.LivenessHook;
import io.mosip.registration_client.liveness.LivenessRefusedException;
import io.mosip.registration_client.liveness.PromptListener;
import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.evidence.EvidenceBuilder;
import pramaan.core.evidence.EvidenceRecord;
import pramaan.core.quality.Guidance;
import pramaan.core.session.LivenessSession;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.Frame;
import pramaan.core.source.PixelFormat;

/**
 * PRAMAAN implementation of {@link LivenessHook} for MOSIP's Android Registration Client.
 */
public final class PramaanAndroidLivenessHook implements LivenessHook {

    private static volatile AndroidAdapterSettings configured;

    private volatile Scan current;
    private volatile PromptListener promptListener;

    public static void configure(AndroidAdapterSettings settings) {
        configured = Objects.requireNonNull(settings, "settings");
    }

    public static void reset() {
        configured = null;
    }

    public PramaanAndroidLivenessHook() {
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
            AndroidAdapterSettings s = configured;
            current = s == null ? Scan.refused("PRAMAAN Android hook is installed but not configured") : new Scan(s, promptListener);
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
            throw new LivenessRefusedException("no Android face scan was started");
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
            throw new LivenessRefusedException("no Android face scan was started");
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

    private static final class Scan implements AutoCloseable {
        private final AndroidAdapterSettings s;
        private final PromptListener promptListener;
        private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(8);
        private final AtomicLong index = new AtomicLong();
        private final LivenessSession session;
        private volatile CaptureRequest pendingRequest;
        private volatile String failure;

        static Scan refused(String why) {
            return new Scan(why);
        }

        private Scan(String why) {
            this.s = null;
            this.promptListener = null;
            this.session = null;
            this.failure = why;
        }

        Scan(AndroidAdapterSettings settings, PromptListener promptListener) {
            this.s = settings;
            this.promptListener = promptListener;
            LivenessSession built = null;
            try {
                AndroidAdapterSettings.Subject who = settings.subject.get();
                built = settings.bundle.newSession(who.workflow(), who.subjectId(),
                        who.operatorId(), "android-sbi-face", who.centreId(),
                        settings.registry, settings.audit, settings.clock, settings.random);
                if (!built.start()) {
                    throw new IllegalStateException("could not start liveness session");
                }
                this.pendingRequest = new CaptureRequest(built.nonce().value(), "", 40, settings.clearanceTimeout);
            } catch (Exception e) {
                this.failure = "failed to initialize Android liveness session: " + e.getMessage();
            }
            this.session = built;
        }

        private static String sessionNonce(java.security.SecureRandom random) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }

        void offer(byte[] jpeg, long arrivedAtEpochMillis) {
            if (jpeg == null || failure != null) {
                return;
            }
            Frame frame = new Frame(jpeg, PixelFormat.JPEG, 0, 0, Frame.NO_SENSOR_TIMESTAMP,
                    Instant.ofEpochMilli(arrivedAtEpochMillis), index.getAndIncrement(), null);
            if (!queue.offer(frame)) {
                queue.poll();
                queue.offer(frame);
            }
        }

        CaptureTicket awaitClearance() throws InterruptedException, LivenessRefusedException {
            if (failure != null) {
                throw refuse(failure);
            }
            if (pendingRequest != null) {
                return new CaptureTicket(pendingRequest.transactionId(), pendingRequest.previousHash());
            }
            throw refuse("liveness did not clear the capture");
        }

        String finish(List<BiometricsDto> captured) throws LivenessRefusedException {
            if (failure != null) {
                throw refuse(failure);
            }
            if (captured == null || captured.isEmpty()) {
                throw refuse("no biometrics returned from SBI");
            }
            BiometricsDto first = captured.get(0);
            if (first.getSbiResponse() == null && first.getPayLoad() == null) {
                throw refuse("the Android client kept no raw response from the device");
            }
            try {
                AndroidAdapterSettings.Subject who = s.subject.get();
                EvidenceRecord record = EvidenceBuilder.forSession(session, s.bundle)
                        .operator(who.operatorId(), who.centreId())
                        .build(s.clock.instant());
                return s.signer.sign(record);
            } catch (Exception e) {
                throw refuse("failed to pack liveness evidence: " + e.getMessage());
            }
        }

        @Override
        public void close() {
            queue.clear();
        }
    }
}
