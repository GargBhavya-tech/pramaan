/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.mock;

import java.time.Duration;
import java.util.Objects;

/**
 * Faults the mock can inject (Build Map #17), one per row of Bible Part 22's device error table. All
 * are off by default. Each is deterministic, so a test that exercises it fails the same way every time.
 */
public final class MockFaults {

    public static final MockFaults NONE = builder().build();

    final int disconnectAfterFrames;
    final int corruptEvery;
    final int wrongFormatAtFrame;
    final int latencySpikeAtFrame;
    final Duration latencySpike;
    final boolean captureError;
    final boolean emptyStream;

    private MockFaults(Builder b) {
        this.disconnectAfterFrames = b.disconnectAfterFrames;
        this.corruptEvery = b.corruptEvery;
        this.wrongFormatAtFrame = b.wrongFormatAtFrame;
        this.latencySpikeAtFrame = b.latencySpikeAtFrame;
        this.latencySpike = b.latencySpike;
        this.captureError = b.captureError;
        this.emptyStream = b.emptyStream;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fault configuration. */
    public static final class Builder {

        private int disconnectAfterFrames = -1;
        private int corruptEvery = 0;
        private int wrongFormatAtFrame = -1;
        private int latencySpikeAtFrame = -1;
        private Duration latencySpike = Duration.ZERO;
        private boolean captureError;
        private boolean emptyStream;

        /** The device disappears after delivering this many frames. */
        public Builder disconnectAfterFrames(int frames) {
            this.disconnectAfterFrames = requireNonNegative(frames);
            return this;
        }

        /** Every n-th frame's bytes are garbage instead of an image. */
        public Builder corruptEvery(int n) {
            if (n < 1) {
                throw new IllegalArgumentException("n must be at least 1");
            }
            this.corruptEvery = n;
            return this;
        }

        /** At this frame index the device switches to an unexpected pixel format. */
        public Builder wrongFormatAtFrame(int index) {
            this.wrongFormatAtFrame = requireNonNegative(index);
            return this;
        }

        /** The frame at this index arrives late by the given extra delay. */
        public Builder latencySpike(int atFrame, Duration extra) {
            this.latencySpikeAtFrame = requireNonNegative(atFrame);
            this.latencySpike = Objects.requireNonNull(extra, "extra");
            return this;
        }

        /** rcapture reports a device error. */
        public Builder captureError() {
            this.captureError = true;
            return this;
        }

        /** The stream connects but never delivers a frame. */
        public Builder emptyStream() {
            this.emptyStream = true;
            return this;
        }

        public MockFaults build() {
            return new MockFaults(this);
        }

        private static int requireNonNegative(int v) {
            if (v < 0) {
                throw new IllegalArgumentException("must not be negative: " + v);
            }
            return v;
        }
    }
}
