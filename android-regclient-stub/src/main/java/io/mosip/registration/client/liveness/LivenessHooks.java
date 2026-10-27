/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration.client.liveness;

import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import io.mosip.registration.client.model.biometrics.BiometricsDto;

/**
 * Finds the {@link LivenessHook} implementation, if any, once. With none, {@link #NONE} does nothing, so the client is
 * unchanged. If an implementation is present but cannot be loaded, the face scan is refused rather than run without
 * liveness: a mobile station that was meant to check liveness must not silently stop checking.
 */
public final class LivenessHooks {

	/** Does nothing: no session, no frames kept, no wait, no transaction id of its own, no evidence. */
	public static final LivenessHook NONE = new LivenessHook() {
		@Override
		public void beginFaceScan(String modality) {
			// nothing
		}

		@Override
		public void onPreviewFrame(byte[] jpeg, long arrivedAtEpochMillis) {
			// nothing
		}

		@Override
		public CaptureTicket awaitCaptureClearance() {
			return null;
		}

		@Override
		public String afterCapture(List<BiometricsDto> captured) {
			return null;
		}
	};

	private static volatile LivenessHook hook;
	private static volatile LivenessRefusedException loadFailure;

	private LivenessHooks() {
	}

	/** The implementation, or {@link #NONE}. Throws if one is installed but failed to load (fail closed). */
	public static LivenessHook get() throws LivenessRefusedException {
		if (loadFailure != null) {
			throw loadFailure;
		}
		LivenessHook h = hook;
		if (h == null) {
			synchronized (LivenessHooks.class) {
				h = hook;
				if (h == null) {
					try {
						Iterator<LivenessHook> it = ServiceLoader.load(LivenessHook.class).iterator();
						h = it.hasNext() ? it.next() : NONE;
					} catch (Throwable t) {
						loadFailure = new LivenessRefusedException("a liveness hook is installed on Android but did not load: " + t);
						throw loadFailure;
					}
					hook = h;
				}
			}
		}
		return h;
	}

	/** For the preview thread: never throws, and never slows the preview when there is no hook. */
	public static void frame(byte[] jpeg, long arrivedAtEpochMillis) {
		try {
			LivenessHook h = get();
			if (h != NONE) {
				h.onPreviewFrame(jpeg, arrivedAtEpochMillis);
			}
		} catch (Throwable t) {
			// the preview must keep running; a hook that fails here fails the capture at the next seam
		}
	}
}
