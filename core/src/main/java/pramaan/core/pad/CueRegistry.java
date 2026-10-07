/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.pad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import pramaan.core.source.Capability;

/**
 * Decides which cues can run in this session (Build Map #29): a cue whose capability is missing or whose
 * frame-rate floor is not met does not run and is recorded as abstaining, with the reason.
 */
public final class CueRegistry {

    /** A cue's static requirements. */
    public static final class Spec {
        final String name;
        final Set<Capability> requires;
        final double minFps;

        public Spec(String name, Set<Capability> requires, double minFps) {
            this.name = name;
            this.requires = requires;
            this.minFps = minFps;
        }
    }

    /** The outcome: cues that may run, and abstentions for those that may not. */
    public static final class Plan {
        private final List<String> runnable;
        private final List<CueResult> abstentions;

        Plan(List<String> runnable, List<CueResult> abstentions) {
            this.runnable = Collections.unmodifiableList(runnable);
            this.abstentions = Collections.unmodifiableList(abstentions);
        }

        public List<String> runnable() {
            return runnable;
        }

        public List<CueResult> abstentions() {
            return abstentions;
        }
    }

    private CueRegistry() {
    }

    public static Plan plan(List<Spec> cues, Set<Capability> available, double measuredFps) {
        List<String> run = new ArrayList<>();
        List<CueResult> skip = new ArrayList<>();
        for (Spec c : cues) {
            if (!available.containsAll(c.requires)) {
                skip.add(CueResult.abstain(c.name, CueResult.AbstainReason.CAPABILITY_MISSING));
            } else if (measuredFps < c.minFps) {
                skip.add(CueResult.abstain(c.name, CueResult.AbstainReason.FRAME_RATE_TOO_LOW));
            } else {
                run.add(c.name);
            }
        }
        return new Plan(run, skip);
    }
}
