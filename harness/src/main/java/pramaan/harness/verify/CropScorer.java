/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.verify;

import pramaan.core.image.RgbImage;

/** The server's own opinion of a sample crop: P(live). */
public interface CropScorer {

    double pLive(RgbImage crop) throws Exception;
}
