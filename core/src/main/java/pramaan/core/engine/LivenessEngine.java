/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import pramaan.core.binding.BindingResult;
import pramaan.core.binding.CaptureBinder;
import pramaan.core.challenge.AccessibilityAdjustments;
import pramaan.core.challenge.Challenge;
import pramaan.core.challenge.ChallengeGenerator;
import pramaan.core.challenge.ChallengePlan;
import pramaan.core.challenge.ChallengePrompt;
import pramaan.core.challenge.LatencyRule;
import pramaan.core.challenge.Onset;
import pramaan.core.challenge.StepStatus;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.evidence.SampleSelector;
import pramaan.core.pad.CueRegistry;
import pramaan.core.source.Capability;
import pramaan.core.fusion.Decision;
import pramaan.core.fusion.RiskFusion;
import pramaan.core.fusion.Veto;
import pramaan.core.fusion.Zone;
import pramaan.core.fusion.ZoneDecider;
import pramaan.core.gate.FaceGate;
import pramaan.core.gate.FaceTracker;
import pramaan.core.landmarks.FaceLandmarks;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.ChallengeKind;
import pramaan.core.policy.ChallengePolicy;
import pramaan.core.policy.OperatingPoint;
import pramaan.core.policy.OperatingPointTable;
import pramaan.core.policy.SessionTimeouts;
import pramaan.core.policy.WorkflowProfile;
import pramaan.core.quality.Guidance;
import pramaan.core.session.ChallengeFailure;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.LivenessSession;
import pramaan.core.session.SessionEventRouter;
import pramaan.core.session.SessionState;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.DeviceException;
import pramaan.core.source.DeviceLevel;
import pramaan.core.source.FaceFrameSource;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.InjectionGuard;
import pramaan.core.source.SessionCapabilities;
import pramaan.core.source.SignedCapture;

/**
 * The engine loop: one frame in, the session one step further (Bible Part 3; Build Map Phase 11). It owns no
 * decision rule of its own — zones come from {@link ZoneDecider}, transitions from {@link LivenessSession},
 * locks from {@link CaptureBinder} — it only feeds each the evidence the previous one asked for:
 *
 * <ol>
 *   <li>ACQUIRING: one debounced face that passes quality starts the passive window.</li>
 *   <li>PASSIVE: each single-face frame joins the attempt's hash chain; passive, moiré and SFace results are
 *       kept; quality-passing landmarks become the frontal reference and the eye baseline. When the window
 *       is complete, per-frame scores are aggregated (trimmed logit mean), fused and zoned.</li>
 *   <li>CHALLENGING: after each step's random onset delay the prompt is shown, and the step is verified from
 *       that frame on; the latency rule judges its onset. PAD keeps scoring, and once every step has passed,
 *       fusion re-runs on every frame of the attempt, plus the cues the steps unlocked — a turn's flatness,
 *       a smile's deformation (Parts 9, 10, 11.6).</li>
 *   <li>CAPTURING, BINDING: the capture is requested with this attempt's nonce, the face in it embedded, and
 *       the four locks decide. ACCEPTED has no other way in.</li>
 * </ol>
 *
 * <p>Fails closed: anything thrown while analysing or deciding fails the attempt as an engine error; nothing
 * falls through to capture. Challenge kinds without a verifier here (GAZE, FLASH) are never drawn, so a
 * resident is never given a step nobody can check. Calls are serialised; capture blocks the calling thread,
 * which is the point — no frame is needed while the device captures.
 */
public final class LivenessEngine implements FrameSink {

    /**
     * Challenge kinds that unlock a PAD cue (Bible Part 10): every plan contains one when the pool allows it,
     * because a challenge of blinks alone adds no evidence and re-fusion after it cannot move the score.
     */
    static final Set<ChallengeKind> UNLOCKING = java.util.Collections.unmodifiableSet(
            EnumSet.of(ChallengeKind.TURN, ChallengeKind.SMILE));

    /** Name of the passive model's cue in the operating-point table. */
    public static final String PASSIVE_CUE = "passive";

    /**
     * Every cue the table knows, with what it needs (Build Map #29). A cue whose capability or frame rate is
     * missing is recorded as abstaining for that reason; one this engine does not implement yet (flash, rPPG)
     * abstains NOT_APPLICABLE. Neither ever votes.
     */
    static final List<CueRegistry.Spec> CUES = List.of(
            new CueRegistry.Spec(PASSIVE_CUE, Set.of(), 1),
            new CueRegistry.Spec("moire", Set.of(), 1),
            new CueRegistry.Spec("colorTexture", Set.of(), 1),
            new CueRegistry.Spec("flatness", Set.of(), 3),
            new CueRegistry.Spec("deformation", Set.of(), 1),
            new CueRegistry.Spec("flash", Set.of(Capability.RESIDENT_DISPLAY), SessionCapabilities.FLASH_MIN_FPS),
            new CueRegistry.Spec("rppg", Set.of(Capability.UNCOMPRESSED_FRAMES), SessionCapabilities.RPPG_MIN_FPS));
    static final Set<String> IMPLEMENTED = Set.of(PASSIVE_CUE, "moire", "colorTexture", "flatness", "deformation");
    /** Sample-crop candidates kept per step: enough for {@link SampleSelector} to choose and top up from. */
    static final int SAMPLES_PER_STEP = SampleSelector.MIN;

    private final LivenessSession session;
    private final SessionEventRouter router;
    private final FaceFrameSource source;
    private final FrameAnalyser analyser;
    private final CaptureAnalyser captureAnalyser;
    private final CaptureBinder binder;
    private final SessionCapabilities caps;
    private final DeviceLevel level;
    private final AccessibilityAdjustments adjustments;
    private final SecureRandom random;
    private final EngineListener listener;
    private final List<SignedCapture> earlierCaptures;
    private final int requestedScore;
    private final WorkflowProfile profile;
    private final SessionTimeouts timeouts;
    private final OperatingPoint point;
    private final RiskFusion fusion;
    private final ZoneDecider decider;
    private final DiagnosticSink diagnostics;
    private final EngineBundle bundle;
    private final List<CueResult> notRunning;

    private final CueAccumulator cues = new CueAccumulator();
    private final Map<Integer, List<SampleSelector.Candidate>> samples = new java.util.TreeMap<>();
    private Decision lastDecision;
    private BindingResult lastBinding;
    private int samplesAttempt;
    private final Map<String, CueResult> unlocked = new LinkedHashMap<>();
    private final List<double[]> earBaseline = new ArrayList<>();
    private int attemptSeen;
    private FaceLandmarks frontal;
    private ChallengeGenerator generator;
    private ChallengePlan plan;
    private int stepIndex = -1;
    private long stepStartMs;
    private long promptShownMs;
    private StepVerifier verifier;
    private FaceGate.Status gate = FaceGate.Status.NO_FACE;
    private final boolean boundVirtualCameraDetected;
    private long trackId;
    private SignedCapture acceptedCapture;

    private SessionState notifiedState;
    private Guidance notifiedGuidance;
    private String notifiedMessage;

    private LivenessEngine(Builder b) {
        this.session = b.session;
        this.source = b.source;
        this.analyser = b.analyser;
        this.captureAnalyser = b.captureAnalyser;
        this.caps = b.caps;
        this.level = b.level;
        // The operator's accessibility choice lives on the session (Build Map #92), so the evidence records the
        // profiles the plan was actually drawn under; an explicit builder value is for tests and tools.
        this.adjustments = b.adjustments != null ? b.adjustments : AccessibilityAdjustments.of(session.accessibilityProfiles());
        this.random = b.random;
        this.listener = b.listener;
        this.earlierCaptures = Collections.unmodifiableList(new ArrayList<>(b.earlierCaptures));
        this.requestedScore = b.requestedScore;
        EngineBundle bundle = b.bundle;
        this.bundle = bundle;
        List<CueResult> skipped = new ArrayList<>(CueRegistry.plan(CUES, caps.device().capabilities(), caps.fps()).abstentions());
        for (String runnable : CueRegistry.plan(CUES, caps.device().capabilities(), caps.fps()).runnable()) {
            if (!IMPLEMENTED.contains(runnable)) {
                skipped.add(CueResult.abstain(runnable, CueResult.AbstainReason.NOT_APPLICABLE));
            }
        }
        this.notRunning = Collections.unmodifiableList(skipped);
        // Bible Part 5, Build Map #84: the device bound to this capture path, checked once against the known
        // virtual-camera products. A hard veto only when policy says so; otherwise it never affects the session.
        this.boundVirtualCameraDetected = InjectionGuard.denylistMatch(caps.device().device()).isPresent();
        this.profile = bundle.policy().profile(session.workflow());
        this.timeouts = bundle.policy().sessionTimeouts();
        OperatingPointTable table = bundle.table();
        this.point = table.operatingPoint(profile.operatingPoint(), b.deviceClass);
        this.fusion = new RiskFusion(table);
        this.decider = new ZoneDecider(random::nextDouble, table.disagreementLogitGap());
        this.binder = new CaptureBinder(b.deviceTrust, table.thetaSame(), timeouts.bindingWindow(), timeouts.captureMaxSkew());
        this.router = new SessionEventRouter(session, FaceTracker.gapFor(caps.fps()));
        if (b.diagnostics == null) {
            this.diagnostics = new DiagnosticSink() {
            };
        } else {
            if (b.grant == null || !b.grant.policySha256().equals(bundle.policySha256())) {
                throw new IllegalArgumentException("diagnostics need a grant under this bundle policy");
            }
            this.diagnostics = b.diagnostics;
            b.grant.attached(session.sessionId());
            diagnostics.capabilities(caps);
        }
    }

    public static Builder builder(EngineBundle bundle, LivenessSession session) {
        return new Builder(bundle, session);
    }

    // ------------------------------------------------------------------------------------------ host API

    /** Starts the session's first attempt. */
    public synchronized boolean start() {
        boolean started = session.start();
        publish();
        return started;
    }

    /** Fires due timeouts; call from a timer so a stalled stream still times out. */
    public synchronized void tick() {
        session.tick();
        publish();
    }

    @Override
    public synchronized void onDeviceEvent(DeviceEvent event) {
        router.onDeviceEvent(event);
        publish();
    }

    @Override
    public synchronized void onFrame(Frame frame) {
        try {
            step(frame);
        } catch (Exception e) {
            // A bug or a model failure mid-decision: fail the attempt closed, never skip liveness.
            router.onEngineError(e);
        }
        syncAttempt();
        publish();
    }

    public LivenessSession session() {
        return session;
    }

    /** The signed capture the session accepted; empty until ACCEPTED. */
    public synchronized Optional<SignedCapture> acceptedCapture() {
        return Optional.ofNullable(acceptedCapture);
    }

    /** The verified bundle this engine runs on. */
    public EngineBundle bundle() {
        return bundle;
    }

    /** The accessibility adjustments the plans are drawn under. */
    public AccessibilityAdjustments accessibility() {
        return adjustments;
    }

    /**
     * Whether the device bound to this capture path matched the virtual-camera denylist (Bible Part 5; Build Map
     * #84), regardless of whether policy actually vetoes on it — for the evidence record.
     */
    public boolean boundVirtualCameraDetected() {
        return boundVirtualCameraDetected;
    }

    /** The latest fusion decision of the current (or just ended) attempt, for evidence; never for a screen. */
    public synchronized Optional<Decision> decision() {
        return Optional.ofNullable(lastDecision);
    }

    /** The binder's verdict on the current (or just ended) attempt's capture. */
    public synchronized Optional<BindingResult> bindingResult() {
        return Optional.ofNullable(lastBinding);
    }

    /** Sample-crop candidates of the current attempt (Build Map #61): the best few per step, by quality. */
    public synchronized List<SampleSelector.Candidate> samples() {
        List<SampleSelector.Candidate> out = new ArrayList<>();
        samples.values().forEach(out::addAll);
        return Collections.unmodifiableList(out);
    }

    /** The current attempt's challenge plan, if it has one. */
    public synchronized Optional<ChallengePlan> challengePlan() {
        return Optional.ofNullable(plan);
    }

    /** The seed behind {@link #challengePlan()}, for the evidence package; refused while that plan can still be answered. */
    public synchronized byte[] revealChallengeSeed() {
        SessionState s = session.state();
        if (generator == null || s == SessionState.PASSIVE || s == SessionState.CHALLENGING) {
            throw new IllegalStateException("no seed to reveal in state " + s);
        }
        return generator.revealSeed();
    }

    // ------------------------------------------------------------------------------------------ the loop

    private void step(Frame frame) throws Exception {
        syncAttempt();
        SessionState s = session.state();
        if (s != SessionState.ACQUIRING && s != SessionState.PASSIVE && s != SessionState.CHALLENGING) {
            return;
        }
        FrameObservation obs = analyser.analyse(frame);
        if (!obs.decoded()) {
            router.onFrame(frame, null, 0);
            return;
        }
        if (obs.trackReset()) {
            router.onTrackLost();
            syncAttempt();
        }
        gate = obs.gate();
        trackId = obs.trackId();
        guidance(obs.guidance());
        if (obs.gate() == FaceGate.Status.ONE_FACE) {
            if (session.state() == SessionState.ACQUIRING && obs.qualityPasses()) {
                session.faceAcquired();
            }
            s = session.state();
            if (s == SessionState.PASSIVE || s == SessionState.CHALLENGING) {
                long before = session.evidenceFrames();
                router.onFrame(frame, frame.copyBytes(), obs.trackId());
                syncAttempt();
                if (session.state() == s && session.evidenceFrames() > before) {
                    analysed(s, frame.hostArrival().toEpochMilli(), obs);
                }
            }
        }
        if (session.state() == SessionState.PASSIVE && session.passiveWindowComplete()) {
            decidePassive();
        }
        if (session.state() == SessionState.CHALLENGING && plan != null && session.challengeStep() >= plan.steps().size()) {
            decideAfterChallenge();
        }
        if (session.state() == SessionState.CAPTURING) {
            captureAndBind();
        }
    }

    /** A frame that joined the attempt's hash chain: its scores and landmarks become evidence. */
    private void analysed(SessionState s, long t, FrameObservation obs) {
        diagnostics.frame(t, obs);
        float[] embedding = obs.embedding();
        if (embedding != null) {
            session.recordEmbedding(embedding);
        }
        if (!Double.isNaN(obs.passive())) {
            cues.addProbability(PASSIVE_CUE, obs.passive());
        }
        if (obs.moire() != null) {
            cues.add(obs.moire());
        }
        obs.cues().forEach(cues::add);
        if (obs.sampleCrop() != null) {
            keepSample(new SampleSelector.Candidate(s == SessionState.PASSIVE ? -1 : session.challengeStep(),
                    obs.sampleQuality(), obs.sampleCrop()));
        }
        FaceLandmarks lm = obs.landmarks();
        if (lm == null) {
            return;
        }
        if (s == SessionState.PASSIVE) {
            if (obs.qualityPasses()) {
                frontal = lm;
                earBaseline.add(ear(lm));
            }
        } else {
            challengeFrame(t, lm, obs.qualityPasses());
        }
    }

    private void challengeFrame(long t, FaceLandmarks lm, boolean quality) {
        int index = session.challengeStep();
        if (index >= plan.steps().size()) {
            return;
        }
        if (index != stepIndex) {
            startStep(index, t);
        }
        Challenge c = plan.steps().get(index);
        if (verifier == null) {
            if (frontal == null) {
                if (!quality) {
                    return;
                }
                frontal = lm;
            }
            if (!StepVerifier.atRest(frontal, lm, earBaseline)) {
                stepStartMs = t; // the onset delay counts from rest: no prompt lands mid-movement
                return;
            }
            // At rest, before the prompt, the resident has no reason to act: these frames extend the eye baseline.
            if (quality) {
                earBaseline.add(ear(lm));
            }
            if (t >= stepStartMs + c.onsetDelayMs() && StepVerifier.ready(c, earBaseline.size())) {
                // Shown after this frame was captured: anything visible in it happened before the prompt.
                promptShownMs = t + 1;
                listener.prompt(ChallengePrompt.of(c, adjustments), index, plan.steps().size());
                verifier = new StepVerifier(c, adjustments, frontal, earBaseline, t, lm);
            }
            return;
        }
        StepStatus status = verifier.observe(t, lm);
        if (status == StepStatus.WRONG_ACTION) {
            session.challengeStepFailed(ChallengeFailure.WRONG_ACTION);
            return;
        }
        if (status != StepStatus.PASS) {
            return;
        }
        Onset onset = verifier.onset().orElse(new Onset(t, t));
        ChallengePolicy cp = profile.challenge();
        Duration timeout = cp.stepTimeout().multipliedBy(plan.adjustments().timeoutMultiplier());
        LatencyRule.Result latency = LatencyRule.judge(promptShownMs, onset, cp.onsetMin(), timeout, caps.earlyOnsetRuleEnabled());
        if (latency.passed()) {
            CueResult u = verifier.unlockedCue();
            if (u != null && (!u.abstained() || !unlocked.containsKey(u.cue()))) {
                unlocked.put(u.cue(), u); // a vote replaces an abstention, never the reverse
            }
            verifier = null;
            if (session.challengeStepPassed()) {
                listener.stepCompleted(index, plan.steps().size());
            }
        } else if (latency.verdict() == LatencyRule.Verdict.TOO_EARLY) {
            session.challengeStepFailed(ChallengeFailure.TOO_EARLY);
            if (session.state() == SessionState.CHALLENGING && session.challengeStep() == index) {
                startStep(index, t); // the session re-prompts once: same step, a fresh onset delay
            }
        } else {
            session.challengeStepFailed(ChallengeFailure.NOT_VERIFIED);
        }
    }

    /** The best few crops of each step, by face quality: bounded memory, and enough for the selector to choose. */
    private void keepSample(SampleSelector.Candidate c) {
        List<SampleSelector.Candidate> list = samples.computeIfAbsent(c.step(), k -> new ArrayList<>());
        list.add(c);
        list.sort(java.util.Comparator.comparingDouble(SampleSelector.Candidate::quality).reversed());
        if (list.size() > SAMPLES_PER_STEP) {
            list.subList(SAMPLES_PER_STEP, list.size()).clear();
        }
    }

    /** The registry's abstentions (capability, frame rate, not built) for every cue that has not spoken. */
    private List<CueResult> withAbstentions(List<CueResult> results) {
        List<CueResult> out = new ArrayList<>(results);
        for (CueResult a : notRunning) {
            if (out.stream().noneMatch(r -> r.cue().equals(a.cue()))) {
                out.add(a);
            }
        }
        return out;
    }

    private void startStep(int index, long t) {
        stepIndex = index;
        stepStartMs = t;
        verifier = null;
    }

    private void decidePassive() {
        List<CueResult> results = withAbstentions(cues.results());
        results.forEach(session::recordCue);
        Set<Veto> vetoes = vetoes();
        Decision d = decider.decide(fusion.fuse(results, session.workflow(), level), point, profile, vetoes);
        lastDecision = d;
        session.auditDecision("passive", results, d);
        diagnostics.decision("passive", results, d);
        ChallengePlan p = null;
        if (d.zone() == Zone.CHALLENGE) {
            generator = ChallengeGenerator.fresh(random);
            ChallengePolicy cp = profile.challenge();
            p = generator.plan(verifiable(cp.pool()), cp.minSteps(), cp.maxSteps(), caps, adjustments, UNLOCKING);
        }
        if (session.passiveDecided(d, p) && session.state() == SessionState.CHALLENGING) {
            plan = p;
            stepIndex = -1;
        }
    }

    private void decideAfterChallenge() {
        // Part 9's worked example: the passive window's evidence, the PAD scores that kept running through the
        // challenge (Part 11.6: a moiré spike during a passed challenge still counts), and what the steps unlocked.
        List<CueResult> results = new ArrayList<>(cues.results());
        results.addAll(unlocked.values());
        results = withAbstentions(results);
        results.forEach(session::recordCue);
        Decision d = decider.decideAfterChallenge(fusion.fuse(results, session.workflow(), level), point, profile, vetoes());
        lastDecision = d;
        session.auditDecision("after-challenge", results, d);
        diagnostics.decision("after-challenge", results, d);
        session.challengesDecided(d);
    }

    private void captureAndBind() throws Exception {
        String previousHash = earlierCaptures.isEmpty() ? "" : earlierCaptures.get(earlierCaptures.size() - 1).chainHash();
        CaptureRequest request = session.captureRequest(previousHash, requestedScore, timeouts.capture());
        publish();
        SignedCapture capture;
        try {
            capture = source.capture(request);
        } catch (DeviceException e) {
            router.onCaptureFailed(e);
            return;
        }
        if (!session.captured()) {
            return; // timed out while the device captured
        }
        publish();
        float[] embedding = captureAnalyser.embed(capture);
        BindingResult result = binder.bind(session.bindingInput(capture, embedding, trackId, earlierCaptures));
        lastBinding = result;
        session.bindingVerified(result);
        if (session.state() == SessionState.ACCEPTED) {
            acceptedCapture = capture;
        }
    }

    /**
     * A second face at decision time is a veto (Bible Part 19); before that it only pauses scoring. A device bound
     * to this capture path that matches the virtual-camera denylist is a veto only when policy says so
     * ({@code vetoOnBoundVirtualCamera}) — Bible Part 5: log-only otherwise.
     */
    private Set<Veto> vetoes() {
        Set<Veto> v = EnumSet.noneOf(Veto.class);
        if (gate == FaceGate.Status.MULTIPLE_FACES) {
            v.add(Veto.MULTIPLE_FACES);
        }
        if (boundVirtualCameraDetected && bundle.policy().vetoOnBoundVirtualCamera()) {
            v.add(Veto.INJECTION);
        }
        return v;
    }

    private static List<ChallengeKind> verifiable(List<ChallengeKind> pool) {
        List<ChallengeKind> out = new ArrayList<>();
        for (ChallengeKind k : pool) {
            if (StepVerifier.VERIFIABLE.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private static double[] ear(FaceLandmarks lm) {
        return new double[] {lm.eyeAspectRatio(true), lm.eyeAspectRatio(false),
            lm.blendshape(pramaan.core.landmarks.Blendshapes.EYE_BLINK_LEFT),
            lm.blendshape(pramaan.core.landmarks.Blendshapes.EYE_BLINK_RIGHT)};
    }

    /**
     * Everything gathered for an attempt belongs to that attempt's evidence: when the session restarts the
     * evidence (a new attempt, a lost face, another track), it is dropped here too, never carried over.
     */
    private void syncAttempt() {
        SessionState s = session.state();
        boolean live = s == SessionState.PASSIVE || s == SessionState.DECIDED || s == SessionState.CHALLENGING
                || s == SessionState.CAPTURING || s == SessionState.BINDING || s == SessionState.ACCEPTED;
        if (session.attempt() != samplesAttempt) {
            // What an attempt concluded stays readable after it ends (evidence, the exception flow) until the next.
            samplesAttempt = session.attempt();
            lastDecision = null;
            lastBinding = null;
        }
        if (!live || session.attempt() != attemptSeen) {
            attemptSeen = session.attempt();
            samples.clear();
            cues.clear();
            unlocked.clear();
            earBaseline.clear();
            frontal = null;
            plan = null;
            stepIndex = -1;
            verifier = null;
        }
    }

    private void guidance(Guidance g) {
        if (g != notifiedGuidance) {
            notifiedGuidance = g;
            listener.guidance(g);
        }
    }

    private void publish() {
        SessionState s = session.state();
        if (s != notifiedState) {
            notifiedState = s;
            if (s == SessionState.ACQUIRING && session.attempt() > 1) {
                listener.attempt(session.attempt(), profile.retries().maxAttempts());
            }
            listener.stateChanged(s);
            if (s == SessionState.RETRY_WAIT) {
                // The wait follows the attempt count, never a score (Bible Part 12), so the screen may show it.
                listener.cooldown(session.retryWaitMillis());
            }
        }
        String m = session.userMessageId().orElse(null);
        if (!Objects.equals(m, notifiedMessage)) {
            notifiedMessage = m;
            if (m != null) {
                listener.message(m);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ construction

    public static final class Builder {
        private final EngineBundle bundle;
        private final LivenessSession session;
        private FaceFrameSource source;
        private JwsTrust deviceTrust;
        private FrameAnalyser analyser;
        private CaptureAnalyser captureAnalyser;
        private SessionCapabilities caps;
        private DeviceLevel level;
        private String deviceClass = OperatingPointTable.DEFAULT_DEVICE_CLASS;
        /** Null: the session's own accessibility profiles. */
        private AccessibilityAdjustments adjustments;
        private SecureRandom random;
        private EngineListener listener = EngineListener.NONE;
        private List<SignedCapture> earlierCaptures = List.of();
        private Integer requestedScore;
        private DiagnosticSink diagnostics;
        private DiagnosticGrant grant;

        private Builder(EngineBundle bundle, LivenessSession session) {
            this.bundle = Objects.requireNonNull(bundle, "bundle");
            this.session = Objects.requireNonNull(session, "session");
        }

        /** The device, and the trust its captures must verify against. */
        public Builder device(FaceFrameSource frameSource, JwsTrust trust, DeviceLevel deviceLevel) {
            this.source = frameSource;
            this.deviceTrust = trust;
            this.level = deviceLevel;
            return this;
        }

        public Builder analysers(FrameAnalyser frames, CaptureAnalyser captures) {
            this.analyser = frames;
            this.captureAnalyser = captures;
            return this;
        }

        /** Capabilities from the measured frame rate (Build Map #14). */
        public Builder capabilities(SessionCapabilities sessionCapabilities) {
            this.caps = sessionCapabilities;
            return this;
        }

        /** The operating-point device class; the table's {@code default} if not set. */
        public Builder deviceClass(String name) {
            this.deviceClass = name;
            return this;
        }

        public Builder accessibility(AccessibilityAdjustments a) {
            this.adjustments = a;
            return this;
        }

        public Builder random(SecureRandom r) {
            this.random = r;
            return this;
        }

        public Builder listener(EngineListener l) {
            this.listener = l;
            return this;
        }

        /** This registration's earlier signed captures, oldest first: the SBI hash chain continues from them. */
        public Builder earlierCaptures(List<SignedCapture> captures) {
            this.earlierCaptures = captures;
            return this;
        }

        /** The capture quality asked of the device (SBI {@code requestedScore}, 0-100). */
        public Builder requestedScore(int score) {
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("requestedScore must be 0-100, got " + score);
            }
            this.requestedScore = score;
            return this;
        }

        /**
         * Diagnostic mode for this session (Bible Part 19.3): refused without a grant under this bundle policy,
         * and the attachment is audited. Never pass the resident screen here; it is for an administrator.
         */
        public Builder diagnostics(DiagnosticSink sink, DiagnosticGrant diagnosticGrant) {
            this.diagnostics = Objects.requireNonNull(sink, "sink");
            this.grant = Objects.requireNonNull(diagnosticGrant, "grant");
            return this;
        }

        public LivenessEngine build() {
            Objects.requireNonNull(source, "device");
            Objects.requireNonNull(deviceTrust, "device trust");
            Objects.requireNonNull(level, "device level");
            Objects.requireNonNull(analyser, "frame analyser");
            Objects.requireNonNull(captureAnalyser, "capture analyser");
            Objects.requireNonNull(caps, "capabilities");
            Objects.requireNonNull(deviceClass, "deviceClass");
            Objects.requireNonNull(random, "random");
            Objects.requireNonNull(listener, "listener");
            Objects.requireNonNull(earlierCaptures, "earlierCaptures");
            Objects.requireNonNull(requestedScore, "requestedScore");
            return new LivenessEngine(this);
        }
    }
}
