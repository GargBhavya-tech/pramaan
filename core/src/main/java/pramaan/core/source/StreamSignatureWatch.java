/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compares every JPEG frame of a session with the enrolled encoder signature (Bible Part 5). Reports counts and the
 * signatures seen; whether a mismatch vetoes is the policy's business ({@code vetoOnStreamSignatureMismatch}), because a
 * firmware update legitimately changes the headers and the station must be re-enrolled, not locked.
 *
 * <p>With no enrolled profile it observes and judges nothing: {@link #enrolled()} is false and nothing is a mismatch.
 */
public final class StreamSignatureWatch {

    /** A veto needs this many frames from another encoder, so one corrupt frame cannot reject a resident. */
    public static final int VETO_FRAMES = 3;
    private static final int KEEP = 8;

    private final StreamSignatureProfile profile;
    private final Map<String, String> unexpected = new LinkedHashMap<>();
    private int frames;
    private int unreadable;
    private int mismatching;

    /** @param profile what was enrolled, or null for a station that has not enrolled its camera */
    public StreamSignatureWatch(StreamSignatureProfile profile) {
        this.profile = profile;
    }

    public synchronized void observe(Frame frame) {
        if (frame.format() != PixelFormat.JPEG) {
            return;
        }
        frames++;
        JpegSignature s = JpegSignature.of(frame.copyBytes());
        if (s == null) {
            unreadable++;
            return;
        }
        if (profile != null && !profile.contains(s)) {
            mismatching++;
            if (unexpected.size() < KEEP) {
                unexpected.putIfAbsent(s.hex(), s.summary());
            }
        }
    }

    public synchronized void reset() {
        frames = 0;
        unreadable = 0;
        mismatching = 0;
        unexpected.clear();
    }

    public boolean enrolled() {
        return profile != null;
    }

    public synchronized int frames() {
        return frames;
    }

    /** JPEG frames whose headers could not be read at all. */
    public synchronized int unreadable() {
        return unreadable;
    }

    /** Frames whose signature is not one that was enrolled. */
    public synchronized int mismatching() {
        return mismatching;
    }

    /** The signatures that were not enrolled, hex to summary (at most 8). */
    public synchronized Map<String, String> unexpected() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(unexpected));
    }

    /** True once {@link #VETO_FRAMES} frames came from an encoder that was not enrolled. */
    public synchronized boolean foreignEncoder() {
        return profile != null && mismatching >= VETO_FRAMES;
    }
}
