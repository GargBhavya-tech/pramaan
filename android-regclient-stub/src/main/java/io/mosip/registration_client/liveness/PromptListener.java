/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration_client.liveness;

/**
 * Seam for live challenge prompts and guidance to be rendered on the client UI overlay (P5).
 */
@FunctionalInterface
public interface PromptListener {
	void onPrompt(String prompt, String guidance);
}
