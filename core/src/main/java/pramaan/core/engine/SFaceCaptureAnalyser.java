/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import java.util.List;
import java.util.Objects;
import pramaan.core.binding.IsoFaceRecord;
import pramaan.core.binding.SFaceEmbedder;
import pramaan.core.binding.VerifiedCapture;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.detect.FaceBox;
import pramaan.core.detect.YuNetDetector;
import pramaan.core.image.ImageDecoder;
import pramaan.core.image.RgbImage;
import pramaan.core.source.SignedCapture;

/**
 * Embeds the face in a signed SBI capture, the same way on every platform: verify the JWS, parse the ISO 19794-5
 * record, decode its image with the platform decoder, find exactly one face with YuNet, embed it with SFace.
 * Pixels are only ever read from what the device signed.
 */
public final class SFaceCaptureAnalyser implements CaptureAnalyser {

    private final JwsTrust trust;
    private final YuNetDetector detector;
    private final SFaceEmbedder sface;
    private final ImageDecoder images;

    public SFaceCaptureAnalyser(JwsTrust trust, YuNetDetector detector, SFaceEmbedder sface, ImageDecoder images) {
        this.trust = Objects.requireNonNull(trust, "trust");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.sface = Objects.requireNonNull(sface, "sface");
        this.images = Objects.requireNonNull(images, "images");
    }

    @Override
    public float[] embed(SignedCapture capture) throws Exception {
        byte[] iso = VerifiedCapture.verify(capture, trust).isoRecord();
        if (iso.length == 0) {
            return null;
        }
        RgbImage image = images.decode(IsoFaceRecord.parse(iso).image());
        List<FaceBox> faces = detector.detect(image);
        return faces.size() == 1 ? sface.embed(image, faces.get(0).fivePoints()) : null;
    }
}
