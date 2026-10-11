/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import java.util.Optional;
import pramaan.core.auth.FaceMatcher;
import pramaan.core.binding.Embeddings;
import pramaan.core.engine.CaptureAnalyser;
import pramaan.core.source.SignedCapture;

/**
 * The scaffold 1:1 matcher: SFace cosine between the signed capture and the enrolled embedding, at the operating
 * point table theta_same (bound to the SFace model hash) — no new threshold. In the Registration Client this is
 * the deployment BioSDK matcher; {@link pramaan.core.auth.OperatorAuthentication} decides when it may run.
 */
final class EnrolledFaceMatcher implements FaceMatcher {

    private final OperatorGallery gallery;
    private final CaptureAnalyser captures;
    private final double thetaSame;

    EnrolledFaceMatcher(OperatorGallery gallery, CaptureAnalyser captures, double thetaSame) {
        this.gallery = gallery;
        this.captures = captures;
        this.thetaSame = thetaSame;
    }

    @Override
    public boolean matches(SignedCapture capture, String personId) throws Exception {
        Optional<float[]> enrolled = gallery.enrolled(personId);
        if (enrolled.isEmpty()) {
            return false;
        }
        float[] probe = captures.embed(capture);
        return probe != null && Embeddings.cosine(probe, enrolled.get()) >= thetaSame;
    }
}
