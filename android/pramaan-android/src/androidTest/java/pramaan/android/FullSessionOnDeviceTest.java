/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.engine.EngineListener;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.engine.StandardEngines;
import pramaan.core.Workflow;
import pramaan.core.quality.Guidance;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.ModeDDevice;
import pramaan.core.source.SignedCapture;
import pramaan.desktop.capture.CaptureScreenModel;
import pramaan.desktop.capture.CaptureSession;
import pramaan.desktop.capture.Messages;
import pramaan.desktop.capture.Text;

/**
 * A whole liveness session on a phone: the front camera through {@link CameraFrameSource}, the Mode D device that signs its own
 * capture with a key held in Android Keystore, the real engine (passive, fusion, challenge, capture, binding) and the same screen
 * model the desktop uses, rendered as text on {@link CameraTestActivity}. Whoever holds the phone does what the screen asks.
 *
 * <p>Nothing is saved; the log carries state changes, the prompts, the screen's text and the outcome, never an image. The test
 * passes if the session ran to a decision (accepted, or a refusal the engine explains); it fails only if the pipeline broke. Skipped
 * without a front camera or the models.
 */
@RunWith(AndroidJUnit4.class)
public class FullSessionOnDeviceTest {

    private static final String TAG = "PramaanSession";
    private static final long LIMIT_MS = 150_000;

    @Test
    public void aWholeSessionOnThePhone() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        Context ctx = inst.getTargetContext();
        AssetManager assets = inst.getContext().getAssets();
        Assume.assumeTrue("no front camera", ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT));
        String[] modelFiles = assets.list("pramaan/models");
        Assume.assumeTrue("models not in the test APK", modelFiles != null && modelFiles.length > 0);

        long screenDeadline = System.currentTimeMillis() + 120_000;
        CameraTestActivity screen;
        while ((screen = CameraTestActivity.current) == null) {
            Assume.assumeTrue("the test screen was not started within 120 s", System.currentTimeMillis() < screenDeadline);
            Thread.sleep(250);
        }
        long permissionDeadline = System.currentTimeMillis() + 60_000;
        while (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Assume.assumeTrue("the camera permission was not granted within 60 s", System.currentTimeMillis() < permissionDeadline);
            Thread.sleep(250);
        }

        List<String> trail = Collections.synchronizedList(new ArrayList<>());
        java.util.function.Consumer<String> note = line -> {
            trail.add(line);
            Log.i(TAG, line);
        };
        final SessionState[] stateNow = {SessionState.IDLE};
        long started = System.currentTimeMillis();
        Bundle report = new Bundle();
        report.putString("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + ", Android "
                + android.os.Build.VERSION.RELEASE);
        Messages messages = Messages.forLocale(Locale.ENGLISH);
        final CameraTestActivity ui = screen;
        ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        CaptureSession capture = null;
        SessionState last;
        try (EngineBundle bundle = AndroidEngines.loadFromAssets(assets, "pramaan")) {
            Clock clock = Clock.systemUTC();
            SecureRandom random = new SecureRandom();
            ModeDKey key = ModeDKey.loadOrCreate();
            JwsTrust trust = key.trust(clock);
            report.putString("signingKey", "Android Keystore RSA-2048, hardware-backed: " + key.hardwareBacked());
            CameraFrameSource camera = new CameraFrameSource(ctx, true, 640, 480, 3.0, clock);
            camera.setPreviewSurface(ui.scanView().awaitPreviewSurface(8000));
            ModeDDevice device = new ModeDDevice(camera, key.payloadSigner(), trust, clock, "pramaan-mode-d", "Staging", () -> 0);
            StandardEngines engines = new StandardEngines(bundle, trust, random, new BitmapFrameDecoder(), new BitmapImageDecoder(),
                    System::nanoTime);
            LivenessSession session = bundle.newSession(Workflow.RESIDENT, "resident-1", "operator-1", "pramaan-mode-d", "10001",
                    new SessionRegistry(), event -> note.accept("audit " + event.type() + " " + event.fields()), clock, random);

            CaptureScreenModel model = new CaptureScreenModel(s -> {
                StringBuilder text = new StringBuilder();
                for (Text t : s.texts()) {
                    text.append(messages.resolve(t)).append("\n\n");
                }
                ui.say(text.toString().trim());
                note.accept(at(started) + " screen " + s.stage() + ": " + s.texts());
                if (s.tone()) {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200);
                }
            });
            EngineListener listener = new EngineListener() {
                @Override
                public void stateChanged(SessionState state) {
                    stateNow[0] = state;
                    note.accept(at(started) + " state " + state);
                    model.stateChanged(state);
                }

                @Override
                public void guidance(Guidance g) {
                    ui.scanView().setFaceOk(g == Guidance.NONE);
                    model.guidance(g);
                }

                @Override
                public void message(String messageId) {
                    note.accept(at(started) + " message " + messageId);
                    model.message(messageId);
                }

                @Override
                public void prompt(ChallengePrompt prompt, int step, int steps) {
                    note.accept(at(started) + " PROMPT " + prompt + " (" + step + "/" + steps + ")");
                    model.prompt(prompt, step, steps);
                }

                @Override
                public void stepCompleted(int step, int steps) {
                    note.accept(at(started) + " step done " + step + "/" + steps);
                    model.stepCompleted(step, steps);
                }

                @Override
                public void attempt(int attempt, int maxAttempts) {
                    note.accept(at(started) + " attempt " + attempt + "/" + maxAttempts);
                    model.attempt(attempt, maxAttempts);
                }

                @Override
                public void cooldown(long waitMillis) {
                    note.accept(at(started) + " cooldown " + waitMillis + " ms");
                    model.cooldown(waitMillis);
                }
            };
            FrameSink noPreview = new FrameSink() {
                @Override
                public void onFrame(Frame frame) {
                }

                @Override
                public void onDeviceEvent(pramaan.core.source.DeviceEvent event) {
                    note.accept(at(started) + " device " + event.type() + " " + event.detail());
                }
            };
            capture = new CaptureSession(device, (caps, dev) -> engines.create(session, device, listener, 40, caps, dev, null, null),
                    noPreview, new CaptureSession.Problems() {
                        @Override
                        public void deviceUnavailable(Exception cause) {
                            note.accept("DEVICE UNAVAILABLE " + cause);
                            model.deviceUnavailable();
                        }

                        @Override
                        public void engineUnavailable(Exception cause) {
                            note.accept("ENGINE UNAVAILABLE " + cause);
                            model.engineUnavailable();
                        }
                    });
            ui.say("Hold the phone upright at face height, face filling the screen. Starting...");
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 400);
            boolean streaming = capture.start();
            assertTrue("the camera and device started: " + trail, streaming);
            capture.startTicker(Duration.ofMillis(200));

            while (System.currentTimeMillis() - started < LIMIT_MS) {
                last = stateNow[0];
                if (last == SessionState.ACCEPTED || last == SessionState.LOCKED_OUT || last == SessionState.UNAVAILABLE) {
                    break;
                }
                Thread.sleep(200);
            }
            last = stateNow[0];
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 600);
            report.putString("finalState", last.toString());
            report.putString("elapsed", (System.currentTimeMillis() - started) / 1000 + " s");
            LivenessEngine engine = capture.engine().orElse(null);
            report.putString("engineBuilt", Boolean.toString(engine != null));
            SignedCapture accepted = capture.acceptedCapture().orElse(null);
            report.putString("signedCaptureAccepted", accepted == null ? "none" : "yes, transaction " + accepted.verifiedTransactionId()
                    + ", chain " + accepted.chainHash().substring(0, 12) + "...");
            ui.say("Finished: " + last + (accepted != null ? " (the capture was signed on this phone)" : ""));
            assertTrue("the session produced a trail", !trail.isEmpty() && engine != null);
        } finally {
            if (capture != null) {
                capture.close();
            }
            tone.release();
        }
        report.putString("trail", String.join(" | ", trail));
        java.io.File[] media = ctx.getExternalMediaDirs();
        if (media.length > 0 && media[0] != null) {
            // the whole trail, untruncated, for adb pull (the instrumentation status keeps only the first kilobytes)
            java.nio.file.Files.write(new java.io.File(media[0], "session-trail.txt").toPath(),
                    String.join("\n", trail).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        for (String key : report.keySet()) {
            Log.i(TAG, key + " = " + report.get(key));
        }
        inst.sendStatus(0, report);
    }

    private static String at(long started) {
        return String.format("[%.1fs]", (System.currentTimeMillis() - started) / 1000.0);
    }
}
