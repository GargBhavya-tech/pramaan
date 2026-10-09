/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.session;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.binding.BindingInput;
import pramaan.core.binding.BindingResult;
import pramaan.core.binding.LockOutcome;
import pramaan.core.challenge.ChallengePlan;
import pramaan.core.fusion.Decision;
import pramaan.core.fusion.Zone;
import pramaan.core.pad.CueDiagnostics;
import pramaan.core.pad.CueResult;
import pramaan.core.policy.AccessibilityProfile;
import pramaan.core.policy.LivenessPolicy;
import pramaan.core.policy.LockoutAction;
import pramaan.core.policy.SessionTimeouts;
import pramaan.core.policy.WorkflowProfile;
import pramaan.core.source.CaptureRequest;
import pramaan.core.source.SignedCapture;

/**
 * The one liveness lifecycle (Bible Part 12; Build Map #48-#50):
 * IDLE -> ACQUIRING -> PASSIVE -> {DECIDED | CHALLENGING} -> CAPTURING -> BINDING -> {ACCEPTED | FAILED},
 * FAILED -> {RETRY_WAIT -> ACQUIRING | LOCKED_OUT -> EXCEPTION_FLOW}.
 *
 * <p>It makes no decisions of its own: zones come from {@link pramaan.core.fusion.ZoneDecider}, and this
 * class only routes them, so no {@code if (score > x)} can creep into a UI. Time comes only from the
 * session's injected clock; call {@link #tick()} periodically (every frame is fine) to fire timeouts.
 * Events that arrive in the wrong state are ignored, audited, and reported as {@code false} — a late
 * "step passed" after a timeout must not resurrect a failed attempt.
 */
public final class LivenessSession {

    private final SessionContext context;
    private final Clock clock;
    private final WorkflowProfile profile;
    private final SessionTimeouts timeouts;
    private final LivenessPolicy policy;
    private final String subjectId;
    private final String operatorId;
    private final String deviceId;
    private final String centreId;
    private final SessionRegistry registry;
    private final Consumer<AuditEvent> audit;
    private final SecureRandom random;
    /** What a SUPERVISOR session's verdict approves: a packet id, or the resident session of an exception. */
    private final String approves;

    private final List<Transition> history = new ArrayList<>();
    private SessionState state = SessionState.IDLE;
    private Instant enteredAt;
    private Instant retryAt;
    private int attempt;
    private SessionNonce nonce;
    private ChallengePlan plan;
    private int stepIndex;
    private int totalSteps;
    private Instant stepDeadline;
    private boolean awaitingRefusion;
    private boolean reprompted;
    private FailureReason lastFailure;
    private Instant acceptedAt;
    private AttemptEvidence evidence;
    private long framesAtChallengeStart;
    private Instant capturingSince;
    private Instant captureRequestedAt;
    private final List<String> bindingFailures = new ArrayList<>();
    private String bindingFinalHash;
    private Instant lastObserved;
    private Set<AccessibilityProfile> accessibility = EnumSet.noneOf(AccessibilityProfile.class);
    private float[] operatorEmbedding;
    private Consumer<AttemptRecord> attemptObserver = r -> { };

    /** Package-private: sessions come from {@link EngineBundle#newSession}, so none exists without a verified bundle. */
    LivenessSession(SessionContext context, String subjectId, String operatorId, String deviceId, String centreId,
            SessionRegistry registry, Consumer<AuditEvent> audit, SecureRandom random) {
        this(context, subjectId, operatorId, deviceId, centreId, registry, audit, random, null);
    }

    /** @param approves what a supervisor verdict from this session may approve, fixed for the session's life */
    LivenessSession(SessionContext context, String subjectId, String operatorId, String deviceId, String centreId,
            SessionRegistry registry, Consumer<AuditEvent> audit, SecureRandom random, String approves) {
        this.approves = approves;
        this.context = Objects.requireNonNull(context, "context");
        this.clock = context.clock();
        this.policy = context.policy();
        this.profile = context.profile();
        this.timeouts = policy.sessionTimeouts();
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.centreId = Objects.requireNonNull(centreId, "centreId");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.random = Objects.requireNonNull(random, "random");
        this.enteredAt = clock.instant();
        this.lastObserved = enteredAt;
        this.evidence = new AttemptEvidence(context.nonce());
    }

    // ------------------------------------------------------------------------------------------ queries

    public String sessionId() {
        return context.sessionId();
    }

    public Workflow workflow() {
        return context.workflow();
    }

    /** Whose liveness this session checks: the resident, or the operator or supervisor themselves. */
    public String subjectId() {
        return subjectId;
    }

    /** For a supervisor session: the one thing its verdict may approve. */
    public Optional<String> approves() {
        return Optional.ofNullable(approves);
    }

    public Instant startedAt() {
        return context.startedAt();
    }

    public SessionState state() {
        tick();
        return state;
    }

    public List<Transition> history() {
        return Collections.unmodifiableList(history);
    }

    /** The nonce for this attempt's capture; a fresh one every attempt, so a failed attempt's capture cannot be replayed. */
    public SessionNonce nonce() {
        return nonce;
    }

    public int attempt() {
        return attempt;
    }

    public Optional<FailureReason> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    /** The current challenge step (0-based); the last one is the flash, when the plan has one. */
    public int challengeStep() {
        return stepIndex;
    }

    /** Hex head of this attempt's frame hash chain (Part 13, lock 2); restarts with the evidence. */
    public String evidenceHead() {
        return evidence.head();
    }

    public long evidenceFrames() {
        return evidence.frames();
    }

    /** The track all of this attempt's evidence belongs to; 0 before the first analysed frame. */
    public long evidenceTrackId() {
        return evidence.trackId();
    }

    public List<CueResult> evidenceCues() {
        return evidence.cues();
    }

    /** What the UI should show now, from Bible Part 22 (a message id, never text). */
    public Optional<String> userMessageId() {
        tick();
        if (state == SessionState.LOCKED_OUT || state == SessionState.EXCEPTION_FLOW) {
            return LivenessError.MAX_RETRIES.messageId();
        }
        if (state == SessionState.ACQUIRING && lastFailure == null) {
            return LivenessError.NO_FACE.messageId();
        }
        return lastFailure == null ? Optional.empty() : LivenessError.of(lastFailure).messageId();
    }

    /** True once the passive window has run and a decision is due. */
    public boolean passiveWindowComplete() {
        tick();
        return state == SessionState.PASSIVE && !now().isBefore(enteredAt.plus(timeouts.passiveWindow()));
    }

    /**
     * Present only for a SUPERVISOR session that reached ACCEPTED and names what it approves: the one way to
     * obtain a verdict. A supervisor login proves nothing about a packet (Build Map #77).
     */
    public Optional<SupervisorVerdict> supervisorVerdict() {
        if (context.workflow() == Workflow.SUPERVISOR && state == SessionState.ACCEPTED && approves != null) {
            return Optional.of(new SupervisorVerdict(context.sessionId(), subjectId, acceptedAt, approves));
        }
        return Optional.empty();
    }

    /** The accessibility profiles the operator selected for this session (Build Map #92); empty by default. */
    public Set<AccessibilityProfile> accessibilityProfiles() {
        return Collections.unmodifiableSet(accessibility);
    }

    /**
     * Records the operator's accessibility choice (Bible Part 16.1; Build Map #92) before the session starts. Only
     * profiles the policy lists are accepted. The choice is audited with the operator's id, because over-use of
     * profiles that relax checks is itself a fraud signal (Part 18).
     */
    public boolean useAccessibilityProfiles(Set<AccessibilityProfile> profiles) {
        tick();
        if (state != SessionState.IDLE || attempt != 0) {
            return ignored("useAccessibilityProfiles");
        }
        for (AccessibilityProfile p : profiles) {
            if (!policy.accessibilityProfiles().contains(p)) {
                throw new IllegalArgumentException("accessibility profile " + p + " is not enabled by the policy");
            }
        }
        accessibility = profiles.isEmpty() ? EnumSet.noneOf(AccessibilityProfile.class) : EnumSet.copyOf(profiles);
        emit(AuditEvent.of("ACCESSIBILITY_PROFILES").put("sessionId", context.sessionId()).put("operatorId", operatorId)
                .put("centreId", centreId).put("profiles", profileList()));
        return true;
    }

    /**
     * The logged-in operator's face (an SFace embedding), for a resident session: a capture that matches it is
     * refused at binding (Bible Part 15.3, 18: an operator must never enrol their own face as someone else).
     */
    public boolean guardAgainstOperatorFace(float[] embedding) {
        tick();
        if (context.workflow() != Workflow.RESIDENT) {
            throw new IllegalStateException("the operator-face check guards resident captures only");
        }
        if (state != SessionState.IDLE || attempt != 0) {
            return ignored("guardAgainstOperatorFace");
        }
        operatorEmbedding = Objects.requireNonNull(embedding, "embedding").clone();
        emit(AuditEvent.of("OPERATOR_FACE_GUARD").put("sessionId", context.sessionId()).put("operatorId", operatorId));
        return true;
    }

    /** Receives every attempt as it ends, pass or fail, with that attempt's own nonce and chain head (Bible Part 42). */
    public void onAttemptEnded(Consumer<AttemptRecord> observer) {
        this.attemptObserver = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Writes a fusion decision and every cue behind it to the audit log (Build Map #34), so any decision can be
     * explained afterwards. Numbers and ids only; the resident's screen never sees this.
     */
    public void auditDecision(String phase, List<CueResult> cues, Decision decision) {
        CueDiagnostics diagnostics = new CueDiagnostics(context.sessionId());
        cues.forEach(diagnostics::record);
        diagnostics.toAuditEvents().forEach(audit);
        AuditEvent d = decision.toAuditEvent(context.sessionId());
        AuditEvent.Builder b = AuditEvent.of(d.type()).put("phase", phase);
        d.fields().forEach((k, v) -> {
            if (v instanceof String) {
                b.put(k, (String) v);
            } else if (v instanceof Long || v instanceof Integer) {
                b.put(k, ((Number) v).longValue());
            } else if (v instanceof Double) {
                b.put(k, (Double) v);
            } else if (v instanceof Boolean) {
                b.put(k, (Boolean) v);
            }
        });
        emit(b);
    }

    /** How long until the next attempt may start: 0 unless waiting (Bible Part 12 backoff). */
    public long retryWaitMillis() {
        tick();
        return state == SessionState.RETRY_WAIT && retryAt != null ? Math.max(0, Duration.between(now(), retryAt).toMillis()) : 0;
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    /** IDLE (or RATE_LIMITED, later) -> ACQUIRING, unless the subject is locked or over the hourly limits. */
    public boolean start() {
        tick();
        if (state != SessionState.IDLE && state != SessionState.RATE_LIMITED) {
            return ignored("start");
        }
        Instant locked = registry.lockedUntil(subjectId, now());
        if (locked != null) {
            go(SessionState.LOCKED_OUT, "subject already locked out");
            if (locked.equals(SessionRegistry.INDEFINITE) && profile.onLockout() == LockoutAction.EXCEPTION_FLOW) {
                go(SessionState.EXCEPTION_FLOW, "locked resident: exception flow only");
            }
            return false;
        }
        return beginAttempt();
    }

    /** One stable, quality-passing face: ACQUIRING -> PASSIVE. */
    public boolean faceAcquired() {
        tick();
        if (state != SessionState.ACQUIRING) {
            return ignored("faceAcquired");
        }
        go(SessionState.PASSIVE, "face acquired");
        return true;
    }

    /**
     * The tracked face was lost for longer than the gap limit: back to ACQUIRING, and everything gathered so
     * far in this attempt is discarded (Bible Part 12; the hash-chain restart is #52). Ignored during capture
     * and binding, which the device owns.
     */
    public boolean faceLost() {
        tick();
        if (state != SessionState.PASSIVE && state != SessionState.CHALLENGING && state != SessionState.CAPTURING) {
            return ignored("faceLost");
        }
        clearAttemptEvidence();
        evidence = new AttemptEvidence(nonce);
        go(SessionState.ACQUIRING, "face lost; evidence from before the gap discarded");
        return true;
    }

    /**
     * The first decision, once {@link #passiveWindowComplete()}. Accept goes straight to capture; reject fails
     * the attempt; challenge runs the plan. A plan with nothing to ask (accessibility left no action and no
     * flash) sends a resident to the supervised exception flow without costing an attempt — never to a reject.
     */
    public boolean passiveDecided(Decision decision, ChallengePlan challengePlan) {
        if (!passiveWindowComplete() || evidence.frames() == 0) {
            return ignored("passiveDecided");
        }
        go(SessionState.DECIDED, describe(decision));
        if (!decision.vetoes().isEmpty()) {
            vetoes = decision.vetoes();
            fail(FailureReason.REJECT_VETO);
            return true;
        }
        switch (decision.zone()) {
            case ACCEPT_CANDIDATE:
                go(SessionState.CAPTURING, "accept candidate");
                return true;
            case REJECT_PAD:
                fail(FailureReason.REJECT_PAD);
                return true;
            case CHALLENGE:
                Objects.requireNonNull(challengePlan, "a challenge decision needs a plan");
                if (challengePlan.needsFallback()) {
                    if (context.workflow() == Workflow.RESIDENT) {
                        attemptEnded(AttemptRecord.EXCEPTION_FLOW, null);
                        go(SessionState.EXCEPTION_FLOW, "no action path for this resident; attempt not consumed");
                    } else {
                        fail(FailureReason.NO_CHALLENGE_AVAILABLE);
                    }
                    return true;
                }
                plan = challengePlan;
                totalSteps = plan.steps().size() + (plan.flash().isPresent() ? 1 : 0);
                stepIndex = 0;
                awaitingRefusion = false;
                reprompted = false;
                framesAtChallengeStart = evidence.frames();
                go(SessionState.CHALLENGING, totalSteps + " step(s), " + (plan.lowEntropy() ? "LOW ENTROPY" : "entropy ok"));
                stepDeadline = deadlineForStep(0);
                return true;
            default:
                throw new IllegalStateException("unhandled zone " + decision.zone());
        }
    }

    public boolean challengeStepPassed() {
        tick();
        if (state != SessionState.CHALLENGING || awaitingRefusion) {
            return ignored("challengeStepPassed");
        }
        stepIndex++;
        reprompted = false;
        if (stepIndex == totalSteps) {
            awaitingRefusion = true;
            stepDeadline = now().plus(timeouts.decisionGrace());
            emit(AuditEvent.of("CHALLENGE_STEPS_PASSED").put("sessionId", context.sessionId()).put("steps", totalSteps));
        } else {
            stepDeadline = deadlineForStep(stepIndex);
        }
        return true;
    }

    /** A step failed. An early onset the first time re-prompts the same step instead (Bible Part 11). */
    public boolean challengeStepFailed(ChallengeFailure failure) {
        tick();
        if (state != SessionState.CHALLENGING || awaitingRefusion) {
            return ignored("challengeStepFailed");
        }
        if (failure == ChallengeFailure.TOO_EARLY && !reprompted) {
            reprompted = true;
            stepDeadline = deadlineForStep(stepIndex);
            emit(AuditEvent.of("CHALLENGE_REPROMPT").put("sessionId", context.sessionId()).put("step", stepIndex));
            return true;
        }
        emit(AuditEvent.of("CHALLENGE_STEP_FAILED").put("sessionId", context.sessionId()).put("step", stepIndex)
                .put("failure", failure.name()));
        fail(FailureReason.CHALLENGE_FAILED);
        return true;
    }

    /**
     * Fusion re-run over the challenge frames (Part 11.6), made with
     * {@link pramaan.core.fusion.ZoneDecider#decideAfterChallenge}: accept captures, reject fails, and a
     * third "challenge" is inconclusive. An AUDIT decision here is a wiring error — it would loop forever for
     * a profile that audits every session — and is refused.
     */
    public boolean challengesDecided(Decision decision) {
        tick();
        if (state != SessionState.CHALLENGING || !awaitingRefusion) {
            return ignored("challengesDecided");
        }
        if (decision.reasons().contains(Decision.Reason.AUDIT)) {
            throw new IllegalArgumentException("re-fusion after a challenge must use ZoneDecider.decideAfterChallenge");
        }
        if (evidence.frames() == framesAtChallengeStart) {
            return ignored("challengesDecided without challenge frames");
        }
        emit(AuditEvent.of("CHALLENGE_REFUSED").put("sessionId", context.sessionId()).put("zone", decision.zone().name()));
        if (decision.zone() == Zone.ACCEPT_CANDIDATE) {
            go(SessionState.CAPTURING, "challenges passed and re-fusion accepts");
        } else {
            fail(decision.zone() == Zone.REJECT_PAD ? FailureReason.REJECT_PAD : FailureReason.INCONCLUSIVE);
        }
        return true;
    }

    /**
     * One analysed frame (the decoded bytes the engine scored). It joins this attempt's hash chain. A frame
     * from a different track than the evidence so far means a different face: the evidence restarts, as
     * if the loss had been reported (Build Map #52).
     */
    public boolean frameAnalysed(byte[] decoded, long epochMillis, long trackId) {
        tick();
        if (state != SessionState.PASSIVE && state != SessionState.CHALLENGING && state != SessionState.CAPTURING) {
            return ignored("frameAnalysed");
        }
        if (!evidence.accepts(trackId)) {
            emit(AuditEvent.of("EVIDENCE_TRACK_SWITCH").put("sessionId", context.sessionId())
                    .put("from", evidence.trackId()).put("to", trackId));
            faceLost();
            return false;
        }
        evidence.addFrame(decoded, epochMillis, trackId);
        return true;
    }

    /** One cue result from this attempt's frames, kept with the evidence it came from. */
    public boolean recordCue(CueResult result) {
        tick();
        if (state != SessionState.PASSIVE && state != SessionState.CHALLENGING) {
            return ignored("recordCue");
        }
        evidence.addCue(result);
        return true;
    }

    /** SFace embedding of a verified frame of this attempt; the identity lock compares the capture to their median. */
    public boolean recordEmbedding(float[] embedding) {
        tick();
        if (state != SessionState.PASSIVE && state != SessionState.CHALLENGING) {
            return ignored("recordEmbedding");
        }
        evidence.addEmbedding(embedding);
        return true;
    }

    /**
     * What the capture binder needs, taken from this attempt's own evidence (Bible Part 13). Only in BINDING.
     *
     * @param captureEmbedding SFace embedding of the face in the signed capture, or null if none was found
     * @param currentTrackId the tracker's track when the capture arrived
     * @param earlierCaptures this registration session's earlier signed captures, oldest first
     */
    public BindingInput bindingInput(SignedCapture capture, float[] captureEmbedding, long currentTrackId,
            List<SignedCapture> earlierCaptures) {
        tick();
        if (state != SessionState.BINDING) {
            throw new IllegalStateException("binding input only exists in BINDING, not " + state);
        }
        Instant lastFrame = evidence.lastFrameMillis() == Long.MIN_VALUE ? null : Instant.ofEpochMilli(evidence.lastFrameMillis());
        return new BindingInput(nonce.value(), evidence.head(), evidence.trackId(), currentTrackId, lastFrame,
                captureRequestedAt != null ? captureRequestedAt : capturingSince, evidence.embeddings(), captureEmbedding,
                capture, earlierCaptures, operatorEmbedding);
    }

    /**
     * The rcapture request for this attempt, with its nonce as {@code transactionId}. The session records when
     * it was made: the time lock measures from the last verified frame to <em>this</em> moment, so a caller
     * that waits before asking for the capture cannot hide the wait (Build Map #58). Once per attempt, in CAPTURING.
     */
    public CaptureRequest captureRequest(String previousHash, int requestedScore, Duration timeout) {
        tick();
        if (state != SessionState.CAPTURING) {
            throw new IllegalStateException("no capture request in state " + state);
        }
        if (captureRequestedAt != null) {
            throw new IllegalStateException("this attempt's capture was already requested");
        }
        captureRequestedAt = now();
        return new CaptureRequest(nonce.value(), previousHash, requestedScore, timeout);
    }

    /** "lock:CODE" for each lock the last binding verdict failed on; empty after a pass or before any. */
    public List<String> bindingFailures() {
        return Collections.unmodifiableList(new ArrayList<>(bindingFailures));
    }

    /** H_final of the accepted attempt (our frame chain bound to MOSIP's capture); null until ACCEPTED. */
    public String bindingFinalHash() {
        return bindingFinalHash;
    }

    /** The signed capture arrived: CAPTURING -> BINDING. */
    public boolean captured() {
        tick();
        if (state != SessionState.CAPTURING) {
            return ignored("captured");
        }
        go(SessionState.BINDING, "capture received");
        return true;
    }

    /**
     * The capture binder's verdict (Bible Part 13; Build Map #57): BINDING -> ACCEPTED only if every lock
     * passed and the result was computed for this attempt's nonce and evidence head. ACCEPT has no other way in.
     */
    public boolean bindingVerified(BindingResult result) {
        tick();
        if (state != SessionState.BINDING) {
            return ignored("bindingVerified");
        }
        bindingFailures.clear();
        String notOurs = !result.nonce().equals(nonce.value()) ? "RESULT_FOR_ANOTHER_ATTEMPT"
                : !result.evidenceHead().equals(evidence.head()) ? "EVIDENCE_CHAIN_MISMATCH" : null;
        boolean ours = notOurs == null;
        for (LockOutcome o : result.outcomes()) {
            if (!o.passed()) {
                bindingFailures.add(o.lock() + ":" + o.code());
                AuditEvent.Builder b = AuditEvent.of("BINDING_LOCK_FAILED").put("sessionId", context.sessionId())
                        .put("lock", o.lock()).put("code", o.code()).put("severity", "HIGH");
                if (!Double.isNaN(o.value())) {
                    b.put("value", o.value());
                }
                emit(b);
            }
        }
        if (!ours) {
            // A result computed on another nonce, or on a frame chain that is not this attempt's (a dropped,
            // added or reordered frame changes the head): the locks may all pass, and it is still refused.
            bindingFailures.add("attempt:" + notOurs);
            emit(AuditEvent.of("BINDING_LOCK_FAILED").put("sessionId", context.sessionId()).put("lock", "attempt")
                    .put("code", notOurs).put("severity", "HIGH"));
        }
        boolean bound = ours && result.passed();
        if (bound) {
            bindingFinalHash = result.finalHash();
            emit(AuditEvent.of("BINDING_OK").put("sessionId", context.sessionId()).put("finalHash", result.finalHash())
                    .put("locks", result.summary()));
            acceptedAt = now();
            registry.resetAttempts(subjectId);
            go(SessionState.ACCEPTED, "bound and accepted");
            attemptEnded(AttemptRecord.ACCEPTED, null);
        } else {
            fail(FailureReason.REJECT_BINDING);
        }
        return true;
    }

    /** A device or engine fault in any active state. It fails the attempt without consuming it. */
    public boolean infrastructureFailure(FailureReason reason) {
        if (reason.consumesAttempt()) {
            throw new IllegalArgumentException(reason + " is a liveness outcome, not an infrastructure failure");
        }
        tick();
        if (!state.active()) {
            return ignored("infrastructureFailure");
        }
        fail(reason);
        return true;
    }

    /** Ends an operator or supervisor lockout early; residents leave lockout through the exception flow. */
    public boolean adminUnlock(String adminId) {
        tick();
        if (state != SessionState.LOCKED_OUT) {
            return ignored("adminUnlock");
        }
        registry.unlock(subjectId);
        emit(AuditEvent.of("ADMIN_UNLOCK").put("sessionId", context.sessionId()).put("subjectId", subjectId)
                .put("adminId", adminId));
        go(SessionState.IDLE, "unlocked by administrator");
        return true;
    }

    /** Fires every timeout that is due. Idempotent; safe to call on every frame. */
    public void tick() {
        Instant t = now();
        if (t.isBefore(lastObserved)) {
            if (state != SessionState.UNAVAILABLE) {
                lastFailure = FailureReason.CLOCK_ANOMALY;
                go(SessionState.FAILED, "clock ran backwards by " + Duration.between(t, lastObserved).toMillis() + " ms");
                auditError(FailureReason.CLOCK_ANOMALY);
                if (attempt > 0) {
                    attemptEnded(FailureReason.CLOCK_ANOMALY.name(), FailureReason.CLOCK_ANOMALY);
                }
                go(SessionState.UNAVAILABLE, "failed closed: timing cannot be trusted");
            }
            return;
        }
        lastObserved = t;
        switch (state) {
            case ACQUIRING:
                if (!t.isBefore(enteredAt.plus(timeouts.acquire()))) {
                    fail(FailureReason.NO_USABLE_FACE);
                }
                break;
            case PASSIVE:
                if (!t.isBefore(enteredAt.plus(timeouts.passiveWindow()).plus(timeouts.decisionGrace()))) {
                    fail(FailureReason.ENGINE_TIMEOUT);
                }
                break;
            case CHALLENGING:
                if (!t.isBefore(stepDeadline)) {
                    fail(awaitingRefusion ? FailureReason.ENGINE_TIMEOUT : FailureReason.CHALLENGE_TIMEOUT);
                }
                break;
            case CAPTURING:
                if (!t.isBefore(enteredAt.plus(timeouts.capture()))) {
                    fail(FailureReason.DEVICE_TIMEOUT);
                }
                break;
            case BINDING:
                if (!t.isBefore(enteredAt.plus(timeouts.binding()))) {
                    fail(FailureReason.ENGINE_TIMEOUT);
                }
                break;
            case RETRY_WAIT:
                if (!t.isBefore(retryAt)) {
                    beginAttempt();
                }
                break;
            case LOCKED_OUT:
                if (registry.lockedUntil(subjectId, t) == null) {
                    registry.unlock(subjectId);
                    go(SessionState.IDLE, "lockout expired");
                }
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------------------------------ exception flow (#50)

    /**
     * Approves an exception for a locked-out resident (Bible Part 12): a fixed reason, a verdict from a
     * supervisor's own passing strict liveness session (not the operator, fresh, single-use), an exception
     * capture that does not itself look like an attack, and room under the rolling-24-hour limits.
     *
     * @param exceptionCapture passive decision on the exception photograph; a reject there is a photo of a
     *     photo, the Aadhaar pattern, and blocks the path for good
     */
    public ExceptionOutcome requestException(ExceptionReason reason, SupervisorVerdict verdict, Decision exceptionCapture) {
        tick();
        if (state != SessionState.EXCEPTION_FLOW) {
            throw new IllegalStateException("no exception flow in state " + state);
        }
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(exceptionCapture, "exceptionCapture");
        ExceptionOutcome outcome = judgeException(verdict, exceptionCapture);
        AuditEvent.Builder e = AuditEvent.of("EXCEPTION_REQUEST").put("sessionId", context.sessionId())
                .put("subjectId", subjectId).put("operatorId", operatorId).put("centreId", centreId)
                .put("reason", reason.name()).put("outcome", outcome.name());
        if (verdict != null) {
            e.put("supervisorId", verdict.supervisorId()).put("supervisorSessionId", verdict.supervisorSessionId());
        }
        emit(e);
        switch (outcome) {
            case APPROVED:
                registry.unlock(subjectId);
                go(SessionState.EXCEPTION_APPROVED, "exception " + reason + " approved; packet flagged EXCEPTION");
                break;
            case BLOCKED_CAPTURE_ATTACK:
            case BLOCKED_DAILY_LIMIT:
                go(SessionState.EXCEPTION_BLOCKED, outcome.name());
                break;
            default:
                break;
        }
        return outcome;
    }

    private ExceptionOutcome judgeException(SupervisorVerdict verdict, Decision capture) {
        if (verdict == null) {
            return ExceptionOutcome.REJECTED_NO_SUPERVISOR;
        }
        if (verdict.supervisorId().equals(operatorId)) {
            return ExceptionOutcome.REJECTED_SAME_PERSON;
        }
        if (!context.sessionId().equals(verdict.approves())) {
            return ExceptionOutcome.REJECTED_WRONG_PURPOSE;
        }
        if (Duration.between(verdict.acceptedAt(), now()).compareTo(policy.supervisorVerdictMaxAge()) > 0) {
            return ExceptionOutcome.REJECTED_STALE_VERDICT;
        }
        if (capture.zone() == Zone.REJECT_PAD || !capture.vetoes().isEmpty()) {
            emit(AuditEvent.of("EXCEPTION_CAPTURE_ATTACK").put("sessionId", context.sessionId())
                    .put("operatorId", operatorId).put("centreId", centreId).put("severity", "HIGH"));
            return ExceptionOutcome.BLOCKED_CAPTURE_ATTACK;
        }
        if (!registry.spendVerdict(verdict.supervisorSessionId())) {
            return ExceptionOutcome.REJECTED_VERDICT_REUSED;
        }
        if (!registry.tryReserveException(operatorId, centreId, policy.exceptionMaxPerOperatorPerDay(),
                policy.exceptionMaxPerCentrePerDay(), now())) {
            return ExceptionOutcome.BLOCKED_DAILY_LIMIT;
        }
        return ExceptionOutcome.APPROVED;
    }

    // ------------------------------------------------------------------------------------------ internals

    private boolean beginAttempt() {
        if (!registry.tryStartAttempt(subjectId, operatorId, deviceId, policy.rateLimits(), now())) {
            go(SessionState.RATE_LIMITED, "hourly attempt limit reached");
            return false;
        }
        attempt++;
        nonce = attempt == 1 ? context.nonce() : SessionNonce.generate(random);
        clearAttemptEvidence();
        evidence = new AttemptEvidence(nonce);
        go(SessionState.ACQUIRING, "attempt " + attempt);
        return true;
    }

    private void clearAttemptEvidence() {
        plan = null;
        stepIndex = 0;
        totalSteps = 0;
        awaitingRefusion = false;
        reprompted = false;
    }

    private Instant deadlineForStep(int i) {
        Duration timeout = profile.challenge().stepTimeout().multipliedBy(plan.adjustments().timeoutMultiplier());
        if (i < plan.steps().size()) {
            return now().plusMillis(plan.steps().get(i).onsetDelayMs()).plus(timeout);
        }
        long flashMs = 0;
        for (int d : plan.flash().orElseThrow().durationsMs()) {
            flashMs += d;
        }
        return now().plusMillis(flashMs).plus(timeout);
    }

    private void fail(FailureReason reason) {
        lastFailure = reason;
        go(SessionState.FAILED, reason.name());
        auditError(reason);
        attemptEnded(reason.name(), reason);
        if (!reason.consumesAttempt()) {
            go(SessionState.IDLE, "infrastructure: attempt not consumed");
            return;
        }
        int consumed = registry.recordConsumed(subjectId);
        int max = profile.retries().maxAttempts();
        if (consumed >= max) {
            lockout(consumed);
            return;
        }
        Duration wait = profile.retries().backoffBase().multipliedBy(1L << (consumed - 1));
        retryAt = now().plus(wait);
        go(SessionState.RETRY_WAIT, "attempt " + consumed + " of " + max + " used; wait " + wait.toMillis() + " ms");
    }

    /** The vetoes behind a REJECT_VETO being failed right now; cleared once audited, so none leaks into a later attempt. */
    private java.util.Set<pramaan.core.fusion.Veto> vetoes = Collections.emptySet();

    private void auditError(FailureReason reason) {
        boolean vetoed = reason == FailureReason.REJECT_VETO && !vetoes.isEmpty();
        // A veto's audit row names what was vetoed: a second face at decision time is not an injection.
        LivenessError e = vetoed ? LivenessError.ofVetoes(vetoes) : LivenessError.of(reason);
        AuditEvent.Builder b = AuditEvent.of("LIVENESS_ERROR").put("sessionId", context.sessionId())
                .put("error", e.name()).put("reason", reason.name()).put("severity", e.severity().name())
                .put("consumesAttempt", e.consumesAttempt()).put("analysedCount", evidence.frames());
        if (vetoed) {
            b.put("vetoes", vetoes.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        }
        vetoes = Collections.emptySet();
        emit(b);
    }

    /**
     * Hands the observer this attempt as it ends, while its nonce and chain head are still the attempt's own, and
     * audits it with the ids fraud analytics count by (Build Map #88): operator, centre, device, outcome, profiles.
     */
    private void attemptEnded(String outcome, FailureReason failure) {
        emit(AuditEvent.of("ATTEMPT_ENDED").put("sessionId", context.sessionId()).put("workflow", context.workflow().name())
                .put("subjectId", subjectId).put("operatorId", operatorId).put("centreId", centreId).put("deviceId", deviceId)
                .put("attempt", attempt).put("outcome", outcome)
                .put("consumesAttempt", failure != null && failure.consumesAttempt()).put("accessibility", profileList()));
        AttemptRecord r = new AttemptRecord(context.sessionId(), context.workflow(), attempt,
                nonce == null ? "" : nonce.value(), outcome, failure, evidence.head(),
                AttemptRecord.ACCEPTED.equals(outcome) ? bindingFinalHash : null, subjectId, operatorId, centreId, deviceId,
                accessibility);
        attemptObserver.accept(r);
    }

    private String profileList() {
        return accessibility.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private void lockout(int consumed) {
        switch (profile.onLockout()) {
            case EXCEPTION_FLOW:
                registry.lock(subjectId, SessionRegistry.INDEFINITE);
                go(SessionState.LOCKED_OUT, consumed + " attempts used");
                go(SessionState.EXCEPTION_FLOW, "supervisor-approved exception is the only way on");
                break;
            case LOCK_15_MIN_AND_ALERT:
                registry.lock(subjectId, now().plus(Duration.ofMinutes(15)));
                go(SessionState.LOCKED_OUT, consumed + " attempts used; locked 15 minutes");
                alert();
                break;
            case LOCK_AND_ALERT:
                registry.lock(subjectId, SessionRegistry.INDEFINITE);
                go(SessionState.LOCKED_OUT, consumed + " attempts used; locked until an administrator unlocks");
                alert();
                break;
            default:
                throw new IllegalStateException("unhandled lockout action " + profile.onLockout());
        }
    }

    private void alert() {
        emit(AuditEvent.of("LOCKOUT_ALERT").put("sessionId", context.sessionId()).put("workflow", context.workflow().name())
                .put("subjectId", subjectId).put("operatorId", operatorId).put("deviceId", deviceId).put("severity", "HIGH"));
    }

    private void go(SessionState to, String why) {
        if (to == SessionState.CAPTURING) {
            capturingSince = now();
            captureRequestedAt = null;
        }
        Transition t = new Transition(state, to, now(), why);
        history.add(t);
        emit(AuditEvent.of("SESSION_TRANSITION").put("sessionId", context.sessionId()).put("from", state.name())
                .put("to", to.name()).put("why", why).put("attempt", attempt));
        state = to;
        enteredAt = now();
    }

    private boolean ignored(String event) {
        emit(AuditEvent.of("SESSION_EVENT_IGNORED").put("sessionId", context.sessionId()).put("event", event)
                .put("state", state.name()));
        return false;
    }

    private void emit(AuditEvent.Builder b) {
        audit.accept(b.build());
    }

    private static String describe(Decision d) {
        return d.zone() + "/" + d.tier() + " " + d.reasons().stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private Instant now() {
        return clock.instant();
    }
}
