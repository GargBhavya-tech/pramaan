/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import pramaan.core.policy.ChallengeKind;

/**
 * What this session can actually do, given the measured frame rate and the device's capabilities
 * (Bible Part 1.2 fps table; Build Map #14). Low fps degrades the challenge set instead of failing people.
 */
public final class SessionCapabilities {

    /** Below this, blinks (70-400 ms) and onset latencies cannot be resolved reliably. */
    public static final double BLINK_MIN_FPS = 10.0;
    public static final double PURSUIT_MIN_FPS = 15.0;
    public static final double SACCADE_MIN_FPS = 30.0;
    public static final double RPPG_MIN_FPS = 10.0;
    /** Flash needs about two frames per colour step; at 3 fps the steps are lengthened to 700-1000 ms. */
    public static final double FLASH_MIN_FPS = 3.0;
    private static final double FLASH_FAST_STEPS_MIN_FPS = 6.0;

    private final double fps;
    private final DeviceCapabilities device;

    private SessionCapabilities(double fps, DeviceCapabilities device) {
        this.fps = fps;
        this.device = device;
    }

    public static SessionCapabilities of(double measuredFps, DeviceCapabilities device) {
        if (!(measuredFps > 0)) {
            throw new IllegalArgumentException("measured fps must be positive, got " + measuredFps);
        }
        return new SessionCapabilities(measuredFps, Objects.requireNonNull(device, "device"));
    }

    public double fps() {
        return fps;
    }

    /** The discovered device and station capabilities this session runs with. */
    public DeviceCapabilities device() {
        return device;
    }

    public boolean blinkResolvable() {
        return fps >= BLINK_MIN_FPS;
    }

    /** Onset-latency checks (Bible Part 11.5) need about 100 ms resolution. */
    public boolean preciseLatency() {
        return fps >= BLINK_MIN_FPS;
    }

    /** The "onset under 150 ms is implausible" rule is inert when a frame is longer than 100 ms. */
    public boolean earlyOnsetRuleEnabled() {
        return preciseLatency();
    }

    public boolean flashAllowed() {
        return device.has(Capability.RESIDENT_DISPLAY) && fps >= FLASH_MIN_FPS;
    }

    /** Shortest flash colour step: 350 ms at 6 fps and above, 700 ms below (photosensitivity: at most 3 changes/s). */
    public Duration flashMinStep() {
        return fps >= FLASH_FAST_STEPS_MIN_FPS ? Duration.ofMillis(350) : Duration.ofMillis(700);
    }

    public Duration flashMaxStep() {
        return fps >= FLASH_FAST_STEPS_MIN_FPS ? Duration.ofMillis(500) : Duration.ofMillis(1000);
    }

    /** Number of colours in a flash sequence: fewer at low fps so the sequence stays a few seconds long. */
    public int flashSteps() {
        return fps >= FLASH_FAST_STEPS_MIN_FPS ? 8 : 5;
    }

    public boolean gazeAllowed() {
        return device.has(Capability.RESIDENT_DISPLAY);
    }

    public boolean pursuitAllowed() {
        return gazeAllowed() && fps >= PURSUIT_MIN_FPS;
    }

    public boolean saccadeAllowed() {
        return gazeAllowed() && fps >= SACCADE_MIN_FPS;
    }

    /** rPPG needs frames without lossy compression; SBI M-JPEG never qualifies (Bible Part 8.5). */
    public boolean rppgAllowed() {
        return device.has(Capability.UNCOMPRESSED_FRAMES) && fps >= RPPG_MIN_FPS;
    }

    /**
     * The policy's challenge pool, reduced to what this session can run: BLINK becomes EYES_CLOSED below
     * 10 fps, and FLASH and GAZE drop out without a resident-facing display. Order is preserved.
     */
    public List<ChallengeKind> effectivePool(List<ChallengeKind> policyPool) {
        List<ChallengeKind> out = new ArrayList<>();
        for (ChallengeKind kind : policyPool) {
            ChallengeKind usable = kind;
            if (kind == ChallengeKind.BLINK && !blinkResolvable()) {
                usable = ChallengeKind.EYES_CLOSED;
            } else if (kind == ChallengeKind.FLASH && !flashAllowed()) {
                usable = null;
            } else if (kind == ChallengeKind.GAZE && !gazeAllowed()) {
                usable = null;
            }
            if (usable != null && !out.contains(usable)) {
                out.add(usable);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
