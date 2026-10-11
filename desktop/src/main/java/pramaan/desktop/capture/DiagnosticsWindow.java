/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import pramaan.core.engine.DiagnosticSink;
import pramaan.core.engine.FrameObservation;
import pramaan.core.fusion.Decision;
import pramaan.core.pad.CueResult;
import pramaan.core.source.SessionCapabilities;

/**
 * The diagnostic view (Build Map #78; Bible Part 19.3): per-cue scores, decisions, capabilities and engine time
 * per frame, in its own administrator window, never on the screen the resident sees. It exists only while an
 * audited {@link pramaan.core.engine.DiagnosticGrant} is held.
 */
public final class DiagnosticsWindow implements DiagnosticSink {

    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final TextArea log = new TextArea();
    private final Stage stage = new Stage();
    private long frames;
    private long timedFrames;
    private long engineNanos;

    /** Must be created on the FX thread. */
    public DiagnosticsWindow(String adminId) {
        log.setEditable(false);
        log.setStyle("-fx-font-family: monospace;");
        BorderPane root = new BorderPane(log);
        root.setTop(new Label("  Diagnostic mode \u2014 administrator " + adminId + " (audited). Not for residents."));
        stage.setTitle("PRAMAAN diagnostics");
        stage.setScene(new Scene(root, 640, 420));
    }

    public void show() {
        stage.show();
    }

    @Override
    public void capabilities(SessionCapabilities caps) {
        line(String.format(Locale.ROOT, "capabilities: %.1f fps, blink %s, precise latency %s, flash %s, gaze %s",
                caps.fps(), caps.blinkResolvable(), caps.preciseLatency(), caps.flashAllowed(), caps.gazeAllowed()));
    }

    @Override
    public void frame(long arrivalMillis, FrameObservation o) {
        frames++;
        if (!Double.isNaN(o.passive()) || o.moire() != null) {
            line(String.format(Locale.ROOT, "frame %d: track %d, quality %s, passive %s, moire %s", frames, o.trackId(),
                    o.qualityPasses() ? "pass" : "fail",
                    Double.isNaN(o.passive()) ? "-" : String.format(Locale.ROOT, "%.3f", o.passive()),
                    o.moire() == null ? "-" : cue(o.moire())));
        }
    }

    @Override
    public void decision(String phase, List<CueResult> cues, Decision d) {
        StringBuilder sb = new StringBuilder("decision (" + phase + "): " + d.zone() + "/" + d.tier() + " " + d.reasons());
        if (d.fusion() != null) {
            sb.append(String.format(Locale.ROOT, ", P(live) %.4f, log-odds %.3f, contributing %d",
                    d.fusion().pLive(), d.fusion().logOdds(), d.fusion().contributing()));
        }
        sb.append(", operating point ").append(d.operatingPoint());
        line(sb.toString());
        for (CueResult c : cues) {
            line("    " + c.cue() + ": " + cue(c));
        }
    }

    /** Engine time for one frame, measured by the capture session outside core (core reads no clock). */
    public void engineTime(long nanos) {
        engineNanos += nanos;
        timedFrames++;
        if (timedFrames % 30 == 0) {
            line(String.format(Locale.ROOT, "engine: %.1f ms per frame on average", engineNanos / 1e6 / timedFrames));
        }
    }

    private static String cue(CueResult c) {
        return c.abstained() ? "abstain (" + c.abstainReason() + ")" : String.format(Locale.ROOT, "%.3f", c.pLive());
    }

    private void line(String s) {
        String stamped = LocalTime.now().format(T) + "  " + s + System.lineSeparator();
        Platform.runLater(() -> log.appendText(stamped));
    }
}
