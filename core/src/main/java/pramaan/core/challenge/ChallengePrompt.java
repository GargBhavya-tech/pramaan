/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.challenge;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import pramaan.core.policy.ChallengeKind;

/**
 * What the UI and audio layers render for a challenge (Build Map #47): a message id with parameters, an
 * animation hint and an audio hint. Core holds no user-facing text; ids are translated by the client, in
 * the resident's language, with an icon for residents who cannot read (Bible Part 16.1).
 */
public final class ChallengePrompt {

    private static final Map<ChallengeKind, String[]> REGISTRY = new EnumMap<>(ChallengeKind.class);

    static {
        REGISTRY.put(ChallengeKind.TURN, new String[] {"pramaan.challenge.turn", "anim:head-turn", "speak:pramaan.challenge.turn"});
        REGISTRY.put(ChallengeKind.BLINK, new String[] {"pramaan.challenge.blink", "anim:blink", "speak:pramaan.challenge.blink"});
        REGISTRY.put(ChallengeKind.EYES_CLOSED, new String[] {"pramaan.challenge.eyes-closed", "anim:eyes-closed",
            "speak:pramaan.challenge.eyes-closed;tone:open-eyes"});
        REGISTRY.put(ChallengeKind.SMILE, new String[] {"pramaan.challenge.smile", "anim:smile", "speak:pramaan.challenge.smile"});
        REGISTRY.put(ChallengeKind.GAZE, new String[] {"pramaan.challenge.gaze", "anim:gaze-dot", "speak:pramaan.challenge.gaze"});
        REGISTRY.put(ChallengeKind.FLASH, new String[] {"pramaan.challenge.flash", "anim:look-at-screen", "speak:pramaan.challenge.flash"});
        for (ChallengeKind k : ChallengeKind.values()) {
            String[] entry = REGISTRY.get(k);
            if (entry == null || entry.length != 3 || entry[0].isEmpty() || entry[1].isEmpty() || entry[2].isEmpty()) {
                throw new IllegalStateException("challenge kind " + k + " has no complete prompt (id, animation, audio)");
            }
        }
    }

    private final ChallengeKind kind;
    private final String messageId;
    private final Map<String, String> params;
    private final String animationHint;
    private final String audioHint;

    private ChallengePrompt(ChallengeKind kind, String messageId, Map<String, String> params, String animationHint,
            String audioHint) {
        this.kind = kind;
        this.messageId = messageId;
        this.params = Collections.unmodifiableMap(params);
        this.animationHint = animationHint;
        this.audioHint = audioHint;
    }

    public static ChallengePrompt of(Challenge c, AccessibilityAdjustments adjustments) {
        String[] e = REGISTRY.get(c.kind());
        Map<String, String> params = new TreeMap<>();
        String animation = e[1];
        String audio = e[2];
        switch (c.kind()) {
            case TURN:
                String side = c.direction().name().toLowerCase(java.util.Locale.ROOT);
                params.put("direction", c.direction().name());
                params.put("angleDeg", Integer.toString(c.targetDeg()));
                animation = e[1] + "-" + side;
                if (adjustments.audioGuidedTurn()) {
                    audio = e[2] + ";tone-guide:turn-" + side;
                }
                break;
            case BLINK:
                params.put("count", Integer.toString(c.count()));
                break;
            case EYES_CLOSED:
                params.put("holdMs", Integer.toString(c.holdMs()));
                break;
            case GAZE:
                params.put("cell", Integer.toString(c.gazeCell()));
                break;
            default:
                break;
        }
        return new ChallengePrompt(c.kind(), e[0], params, animation, audio);
    }

    public static ChallengePrompt flash(FlashSequence sequence) {
        String[] e = REGISTRY.get(ChallengeKind.FLASH);
        Map<String, String> params = new TreeMap<>();
        params.put("colours", Integer.toString(sequence.colours().size()));
        return new ChallengePrompt(ChallengeKind.FLASH, e[0], params, e[1], e[2]);
    }

    /** What is being asked; for hosts that act on it (the harness plays the matching recorded action). */
    public ChallengeKind kind() {
        return kind;
    }

    public String messageId() {
        return messageId;
    }

    public Map<String, String> params() {
        return params;
    }

    public String animationHint() {
        return animationHint;
    }

    public String audioHint() {
        return audioHint;
    }
}
