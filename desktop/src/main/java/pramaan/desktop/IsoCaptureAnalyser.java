/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop;

import pramaan.core.binding.SFaceEmbedder;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.engine.CaptureAnalyser;
import pramaan.core.engine.SFaceCaptureAnalyser;
import pramaan.core.source.SignedCapture;

/**
 * Embeds the face in a signed SBI capture on desktop: {@link SFaceCaptureAnalyser} — the same steps Android
 * takes — with ImageIO decoding, which reads the JPEG 2000 that MockMDS and many devices put in the ISO record.
 */
public final class IsoCaptureAnalyser implements CaptureAnalyser {

    private final SFaceCaptureAnalyser analyser;

    public IsoCaptureAnalyser(JwsTrust trust, YuNetDetector detector, SFaceEmbedder sface) {
        this.analyser = new SFaceCaptureAnalyser(trust, detector, sface, IsoFaceDecoder::decodeImage);
    }

    @Override
    public float[] embed(SignedCapture capture) throws Exception {
        return analyser.embed(capture);
    }
}
