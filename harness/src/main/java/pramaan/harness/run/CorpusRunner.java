/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.run;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.engine.DiagnosticGrant;
import pramaan.core.engine.DiagnosticSink;
import pramaan.core.engine.EngineListener;
import pramaan.core.engine.LivenessEngine;
import pramaan.core.fusion.Decision;
import pramaan.core.pad.CueResult;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.FailureReason;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionRegistry;
import pramaan.core.session.SessionState;
import pramaan.core.source.DeviceCapabilities;
import pramaan.core.source.SessionCapabilities;
import pramaan.desktop.capture.DesktopEngines;
import pramaan.harness.corpus.ClipLoader;
import pramaan.harness.corpus.CorpusManifest;
import pramaan.harness.eval.SessionRow;
import pramaan.harness.mock.MockFrameSource;

/**
 * One presentation through the production engine (Build Map #67): the clip plays through the mock device into
 * the engine the desktop builds — {@link DesktopEngines#factory}, the verified bundle's own models, the real
 * binder and signatures — on a simulated clock, so a 60 s session runs as fast as the models do. No
 * harness-only branch decides anything; the harness only plays the person in front of the camera.
 *
 * <p>Challenges: a bona fide person answers from their {@link ActionLibrary} recordings after a human reaction
 * delay. An attack clip does not answer: a print cannot turn. When a bona fide person has no recording for
 * the prompt, the row is a non-response flagged {@code unanswered} — the harness does not fail a real person
 * on its own account. What the engine decided comes from the audited diagnostic channel, never from a new tap.
 */
public final class CorpusRunner {

    /** Options for a run. */
    public static final class Options {
        Workflow workflow = Workflow.RESIDENT;
        Duration reaction = Duration.ofMillis(600);
        Duration maxSession = Duration.ofSeconds(90);
        double fpsOverride = Double.NaN;

        public Options workflow(Workflow w) {
            this.workflow = Objects.requireNonNull(w, "workflow");
            return this;
        }

        public Options reaction(Duration d) {
            this.reaction = Objects.requireNonNull(d, "reaction");
            return this;
        }

        /** Replay every clip at this rate instead of the manifest's, to see a 30 fps corpus at 3 fps. */
        public Options fps(double fps) {
            this.fpsOverride = fps;
            return this;
        }
    }

    private final EngineBundle bundle;
    private final DiagnosticGrant grant;
    private final ActionLibrary actions;
    private final Options options;
    private final Consumer<AuditEvent> audit;

    /** @param grant a diagnostic grant under {@code bundle}'s policy: the channel the scores are read from */
    public CorpusRunner(EngineBundle bundle, DiagnosticGrant grant, ActionLibrary actions, Options options,
            Consumer<AuditEvent> audit) {
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        this.grant = Objects.requireNonNull(grant, "grant");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.options = Objects.requireNonNull(options, "options");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Runs one attempt on this clip and reports it as one row. */
    public SessionRow run(CorpusManifest.Entry e, String id) throws Exception {
        List<byte[]> clip = ClipLoader.load(e.clip());
        double fps = Double.isNaN(options.fpsOverride) ? e.fps() : options.fpsOverride;
        SimClock clock = new SimClock(Instant.parse("2026-01-01T09:00:00Z"));
        MockFrameSource source = MockFrameSource.builder(clock).delayer(clock::advance).fps(fps).neutral(clip).build();
        DeviceCapabilities device = source.discover();
        LivenessSession session = bundle.newSession(options.workflow, e.person(), "harness", "mock-" + e.device(),
                "harness", new SessionRegistry(), audit, clock, new SecureRandom());
        boolean bonaFide = e.label() == CorpusManifest.Label.BONA_FIDE;
        Observed seen = new Observed();
        EngineListener person = new EngineListener() {
            @Override
            public void guidance(pramaan.core.quality.Guidance g) {
                seen.guidance = g.name();
            }

            @Override
            public void prompt(ChallengePrompt p, int step, int steps) {
                seen.challenged = true;
                if (!bonaFide) {
                    return; // a print, a screen or a mask does not react to what it cannot see
                }
                actions.answer(e.person(), p).ifPresentOrElse(
                        frames -> source.playAt(frames, clock.instant().plus(options.reaction)),
                        () -> seen.unanswered = true);
            }
        };
        LivenessEngine engine = new DesktopEngines(bundle, source.trust(), new SecureRandom())
                .factory(session, source, person, 40, seen, grant)
                .create(SessionCapabilities.of(fps, device), device);
        long t0 = System.nanoTime();
        source.start(null, engine);
        engine.start();
        long maxFrames = (long) Math.ceil(options.maxSession.toMillis() / 1000.0 * fps);
        long frames = 0;
        // One presentation is one attempt: stop at its outcome. A failure that does not consume the attempt (no
        // usable face) goes straight back to acquiring, so the state alone would never say the attempt ended.
        while (frames < maxFrames && !ended(session.state()) && session.lastFailure().isEmpty() && session.attempt() <= 1) {
            source.step();
            frames++;
        }
        long engineMs = (System.nanoTime() - t0) / 1_000_000;
        source.stop();
        return row(e, id, session, seen, frames, engineMs, fps);
    }

    private static boolean ended(SessionState s) {
        return s == SessionState.ACCEPTED || s == SessionState.FAILED || s == SessionState.RETRY_WAIT
                || s == SessionState.LOCKED_OUT || s == SessionState.EXCEPTION_FLOW || s == SessionState.UNAVAILABLE
                || s == SessionState.RATE_LIMITED;
    }

    private SessionRow row(CorpusManifest.Entry e, String id, LivenessSession session, Observed seen, long frames,
            long engineMs, double fps) {
        SessionState end = session.state();
        FailureReason failure = session.lastFailure().orElse(null);
        SessionRow.Outcome outcome;
        String failureName = failure == null ? (end == SessionState.ACCEPTED ? "" : end.name()) : failure.name();
        if (end == SessionState.ACCEPTED) {
            outcome = SessionRow.Outcome.ACCEPTED;
        } else if (seen.unanswered && e.label() == CorpusManifest.Label.BONA_FIDE) {
            outcome = SessionRow.Outcome.NO_RESPONSE; // the harness had no recording to answer with
        } else if (failure != null && failure.consumesAttempt()) {
            outcome = SessionRow.Outcome.REJECTED; // a liveness outcome: PAD, veto, challenge, binding
        } else {
            outcome = SessionRow.Outcome.NO_RESPONSE; // no usable face, device or engine fault, time-out
            if (failure == null && !ended(end)) {
                failureName = "HARNESS_TIME_LIMIT";
            }
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("person", e.person());
        attributes.put("lighting", e.lighting());
        attributes.put("eyewear", e.eyewear());
        attributes.put("device", e.device());
        attributes.put("fps", String.valueOf(fps));
        attributes.put("zone", seen.firstZone == null ? "" : seen.firstZone);
        attributes.put("challenged", String.valueOf(seen.challenged));
        attributes.put("unanswered", String.valueOf(seen.unanswered));
        attributes.put("failure", failureName);
        attributes.put("lastGuidance", seen.guidance);
        attributes.put("frames", String.valueOf(frames));
        attributes.put("engineMs", String.valueOf(engineMs));
        return new SessionRow(id, e.label() == CorpusManifest.Label.ATTACK ? SessionRow.Label.ATTACK : SessionRow.Label.BONA_FIDE,
                e.pais(), 0, attributes, options.workflow.name(), "L1", seen.firstCues, seen.firstPLive, outcome);
    }

    /** What the diagnostic channel and the prompts showed during one presentation. */
    private static final class Observed implements DiagnosticSink {
        Map<String, Double> firstCues = new LinkedHashMap<>();
        Double firstPLive;
        String firstZone;
        boolean challenged;
        boolean unanswered;
        String guidance = "";

        @Override
        public void decision(String phase, List<CueResult> cues, Decision d) {
            if (firstZone != null) {
                return; // the row reports the first decision; what followed is in zone, outcome and failure
            }
            firstZone = d.zone().name();
            firstPLive = d.fusion() == null ? null : d.fusion().pLive();
            for (CueResult c : cues) {
                firstCues.put(c.cue(), c.abstained() ? null : c.pLive());
            }
        }
    }

    /** Simulated time: it moves only when the mock device waits for its next frame. */
    static final class SimClock extends Clock {
        private Instant now;

        SimClock(Instant start) {
            this.now = start;
        }

        synchronized void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
