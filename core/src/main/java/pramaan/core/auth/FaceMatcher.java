/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.auth;

import pramaan.core.source.SignedCapture;

/**
 * 1:1 face match against a person's enrolled face — in the Registration Client, the deployment's BioSDK
 * matcher (Build Map #76). {@link OperatorAuthentication} calls it only for a capture whose liveness passed and
 * whose binding held, so a failed attempt never learns whether its face would have matched.
 */
public interface FaceMatcher {

    /** Whether the face in this signed capture is {@code personId}'s enrolled face. */
    boolean matches(SignedCapture capture, String personId) throws Exception;
}
