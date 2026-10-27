/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration.liveness;

import java.util.List;

import io.mosip.registration.dto.packetmanager.BiometricsDto;

/**
 * The four seams a face liveness engine needs in the Registration Client (PRAMAAN, ADR 0003, patches P1 to P4).
 *
 * <p>The client calls these only for the FACE modality. With no implementation on the classpath the client behaves exactly
 * as it did before: see {@link LivenessHooks#NONE}. An implementation is found with {@link java.util.ServiceLoader}.
 */
public interface LivenessHook {

	/** A face scan is starting (before the stream is read): start a liveness session. */
	void beginFaceScan(String modality);

	/**
	 * Every decoded preview frame (P2): the JPEG bytes and when they arrived. Called on the streamer thread, so it must
	 * return quickly and must not throw; the preview never stops because of the hook.
	 */
	void onPreviewFrame(byte[] jpeg, long arrivedAtEpochMillis);

	/**
	 * Called on the capture thread (P1) before the client asks the device for the signed capture. Blocks until the engine
	 * has reached its capture step, or throws to stop the scan. The client no longer requests the capture the moment the
	 * stream opens. Returns the {@link CaptureTicket}: the SBI {@code transactionId} and {@code previousHash} the client
	 * must send in the rCapture request (P3), so the device signs them inside the capture, or {@code null} to keep the
	 * client's own. (The engine's nonce and hash chain exist only once it reaches its capture step, so they are returned
	 * here and not from {@link #beginFaceScan}.)
	 */
	CaptureTicket awaitCaptureClearance() throws InterruptedException, LivenessRefusedException;

	/**
	 * Called with the device's signed capture before the client stores it (P4). Each {@code BiometricsDto} carries the
	 * device's raw response element in {@code getSbiResponse()}: the client keeps only the decoded payload and the
	 * signature, and drops the JWS header (with the certificate chain) that a verifier needs. Returns the signed liveness
	 * evidence (a JWS) to put in the face BIR {@code others} map, or throws {@link LivenessRefusedException} if the
	 * capture is not the face that passed liveness, in which case nothing is stored.
	 */
	String afterCapture(List<BiometricsDto> captured) throws LivenessRefusedException;

	/**
	 * Seam for live challenge prompts and guidance to be rendered on the client UI overlay (P5).
	 */
	default void setPromptListener(PromptListener listener) {
	}
}
