/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What a burst of signed frames showed (SBI Liveness Profile, addition 4): the face embedding of each frame, or null
 * for a frame with no single face in it, or the reason the burst could not be taken. A device that advertises the
 * burst and then fails to deliver it has not been compared with anything, and the binder fails closed on that (the
 * same reasoning as hiding the signed stream: a downgrade must not look like a quirk).
 */
public final class BurstEvidence {

    private final List<float[]> embeddings;
    private final String failure;

    private BurstEvidence(List<float[]> embeddings, String failure) {
        this.embeddings = Collections.unmodifiableList(embeddings);
        this.failure = failure;
    }

    /** @param embeddings one per frame, null where the frame held no single face */
    public static BurstEvidence of(List<float[]> embeddings) {
        Objects.requireNonNull(embeddings, "embeddings");
        List<float[]> copy = new ArrayList<>();
        for (float[] e : embeddings) {
            copy.add(e == null ? null : e.clone());
        }
        return new BurstEvidence(copy, null);
    }

    /** The burst was offered and could not be taken or verified. */
    public static BurstEvidence failed(String why) {
        return new BurstEvidence(List.of(), Objects.requireNonNull(why, "why"));
    }

    public List<float[]> embeddings() {
        return embeddings;
    }

    /** Why the burst could not be taken, or null if it was. */
    public String failure() {
        return failure;
    }
}
