import os
import shutil
import pathlib

root = pathlib.Path(".").resolve()

# 1. Update android-regclient-stub liveness files
stub_live_dir = root / "android-regclient-stub" / "src" / "main" / "java" / "io" / "mosip" / "registration_client" / "liveness"
stub_live_dir.mkdir(parents=True, exist_ok=True)

# Remove old liveness dir
old_live_dir = root / "android-regclient-stub" / "src" / "main" / "java" / "io" / "mosip" / "registration" / "client" / "liveness"
if old_live_dir.exists():
    shutil.rmtree(old_live_dir)

# Read the files from patch or from upstream definitions
# Let's write the exact 5 files
capture_ticket = '''/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration_client.liveness;

/**
 * What a liveness hook hands the client when it clears a capture: the two values the client must put in its rCapture
 * request so the device signs them into the capture (SBI 1.0: {@code transactionId} and the bio element's
 * {@code previousHash}). Either may be {@code null}, which keeps the client's own behaviour for that value.
 */
public final class CaptureTicket {

	private final String transactionId;
	private final String previousHash;

	public CaptureTicket(String transactionId, String previousHash) {
		this.transactionId = transactionId;
		this.previousHash = previousHash;
	}

	/** 4 to 50 characters of letters, digits and hyphens (SBI), or null. */
	public String transactionId() {
		return transactionId;
	}

	/** The hash the capture chains to, or null. */
	public String previousHash() {
		return previousHash;
	}
}
'''

liveness_hook = '''/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration_client.liveness;

import java.util.List;

import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;

/**
 * The four seams a face liveness engine needs in the Android Registration Client (PRAMAAN, ADR 0003, patches P1 to P4).
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
	 * client's own.
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
'''

liveness_hooks = '''/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration_client.liveness;

import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;

/**
 * Access to the configured {@link LivenessHook}.
 */
public final class LivenessHooks {

	public static final LivenessHook NONE = new LivenessHook() {
		@Override
		public void beginFaceScan(String modality) {
		}

		@Override
		public void onPreviewFrame(byte[] jpeg, long arrivedAtEpochMillis) {
		}

		@Override
		public CaptureTicket awaitCaptureClearance() {
			return null;
		}

		@Override
		public String afterCapture(List<BiometricsDto> captured) {
			return null;
		}

		@Override
		public void setPromptListener(PromptListener listener) {
		}
	};

	private static volatile LivenessHook active;

	private LivenessHooks() {
	}

	public static LivenessHook get() {
		LivenessHook h = active;
		if (h == null) {
			synchronized (LivenessHooks.class) {
				h = active;
				if (h == null) {
					active = h = discover();
				}
			}
		}
		return h;
	}

	public static void set(LivenessHook hook) {
		active = hook == null ? NONE : hook;
	}

	private static LivenessHook discover() {
		ServiceLoader<LivenessHook> loader = ServiceLoader.load(LivenessHook.class);
		Iterator<LivenessHook> it = loader.iterator();
		if (it.hasNext()) {
			return it.next();
		}
		return NONE;
	}
}
'''

liveness_refused = '''/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration_client.liveness;

/**
 * Thrown when the liveness engine refuses a face capture.
 */
public class LivenessRefusedException extends Exception {

	public LivenessRefusedException(String message) {
		super(message);
	}

	public LivenessRefusedException(String message, Throwable cause) {
		super(message, cause);
	}
}
'''

prompt_listener = '''/*
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
'''

for fname, content in [
    ("CaptureTicket.java", capture_ticket),
    ("LivenessHook.java", liveness_hook),
    ("LivenessHooks.java", liveness_hooks),
    ("LivenessRefusedException.java", liveness_refused),
    ("PromptListener.java", prompt_listener)
]:
    (stub_live_dir / fname).write_text(content.replace('\r\n', '\n'), encoding='utf-8', newline='\n')

# 2. Update BiometricsDto in android-regclient-stub
dto_dir = root / "android-regclient-stub" / "src" / "main" / "java" / "io" / "mosip" / "registration" / "clientmanager" / "dto" / "registration"
dto_dir.mkdir(parents=True, exist_ok=True)

biometrics_dto = '''/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.mosip.registration.clientmanager.dto.registration;

public class BiometricsDto {

    private String payLoad;
    private String signature;
    private String specVersion;
    private float qualityScore;
    private boolean isForceCaptured;
    private int numOfRetries;
    private double sdkScore;
    /** Signed liveness evidence (a JWS) for a face capture; null when no hook ran. */
    private String livenessEvidence;
    /** The device's raw rCapture response element, kept so a liveness hook can verify it. */
    private String sbiResponse;
    private boolean captured;
    private String modalityName;

    public float getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(float qualityScore) {
        this.qualityScore = qualityScore;
    }

    public String getPayLoad() {
        return payLoad;
    }

    public void setPayLoad(String payLoad) {
        this.payLoad = payLoad;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getSpecVersion() {
        return specVersion;
    }

    public void setSpecVersion(String specVersion) {
        this.specVersion = specVersion;
    }

    public String getLivenessEvidence() {
        return livenessEvidence;
    }

    public void setLivenessEvidence(String livenessEvidence) {
        this.livenessEvidence = livenessEvidence;
    }

    public String getSbiResponse() {
        return sbiResponse;
    }

    public void setSbiResponse(String sbiResponse) {
        this.sbiResponse = sbiResponse;
    }

    public boolean isCaptured() {
        return captured;
    }

    public void setCaptured(boolean captured) {
        this.captured = captured;
    }

    public String getModalityName() {
        return modalityName;
    }

    public void setModalityName(String modalityName) {
        this.modalityName = modalityName;
    }
}
'''
(dto_dir / "BiometricsDto.java").write_text(biometrics_dto.replace('\r\n', '\n'), encoding='utf-8', newline='\n')

# Clean up old BiometricsDto location
old_dto_dir = root / "android-regclient-stub" / "src" / "main" / "java" / "io" / "mosip" / "registration" / "client"
if old_dto_dir.exists():
    shutil.rmtree(old_dto_dir)

# 3. Update StubMatchesAndroidPatchTest
stub_test = root / "android-regclient-stub" / "src" / "test" / "java" / "pramaan" / "android" / "regclient" / "stub" / "StubMatchesAndroidPatchTest.java"
stub_test_content = '''/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android.regclient.stub;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The stand-ins in this module are copies of the classes the patch adds to MOSIP's Android Registration Client.
 * If the patch changes and a stand-in does not, the adapter would compile against an interface the real client does not have:
 * this test fails first.
 */
class StubMatchesAndroidPatchTest {

    private static final Path PATCH = Path.of("..", "integration", "android-registration-client", "0001-pramaan-android-liveness-hooks.patch");
    private static final Path STUBS = Path.of("src", "main", "java", "io", "mosip", "registration_client", "liveness");

    private static Set<String> addedLines() throws IOException {
        Set<String> added = new HashSet<>();
        for (String line : Files.readAllLines(PATCH, StandardCharsets.UTF_8)) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                added.add(strip(line.substring(1)));
            }
        }
        return added;
    }

    private static String strip(String s) {
        return s.replace("\\r", "");
    }

    @Test
    void everyLineOfEveryStandInIsAnAddedLineOfTheAndroidPatch() throws IOException {
        Set<String> added = addedLines();
        assertFalse(added.isEmpty(), "the Android patch was not found or is empty: " + PATCH.toAbsolutePath());
        for (String name : new String[] {"LivenessHook.java", "LivenessRefusedException.java", "CaptureTicket.java", "PromptListener.java", "LivenessHooks.java"}) {
            for (String line : Files.readAllLines(STUBS.resolve(name), StandardCharsets.UTF_8)) {
                String s = strip(line);
                assertTrue(added.contains(s), name + " has a line the Android patch does not add: '" + s + "'");
            }
        }
    }
}
'''
stub_test.write_text(stub_test_content.replace('\r\n', '\n'), encoding='utf-8', newline='\n')

# 4. Update META-INF/services in android-regclient-adapter
services_dir = root / "android-regclient-adapter" / "src" / "main" / "resources" / "META-INF" / "services"
old_service_file = services_dir / "io.mosip.registration.client.liveness.LivenessHook"
if old_service_file.exists():
    old_service_file.unlink()
new_service_file = services_dir / "io.mosip.registration_client.liveness.LivenessHook"
new_service_file.write_text("pramaan.android.regclient.PramaanAndroidLivenessHook\n", encoding='utf-8', newline='\n')

# 5. Update PramaanAndroidLivenessHook.java
hook_file = root / "android-regclient-adapter" / "src" / "main" / "java" / "pramaan" / "android" / "regclient" / "PramaanAndroidLivenessHook.java"
hook_code = hook_file.read_text(encoding='utf-8')
hook_code = hook_code.replace("io.mosip.registration.client.liveness.", "io.mosip.registration_client.liveness.")
hook_code = hook_code.replace("io.mosip.registration.client.model.biometrics.BiometricsDto;", "io.mosip.registration.clientmanager.dto.registration.BiometricsDto;")
hook_file.write_text(hook_code, encoding='utf-8')

# 6. Update PramaanAndroidLivenessHookTest.java
test_file = root / "android-regclient-adapter" / "src" / "test" / "java" / "pramaan" / "android" / "regclient" / "PramaanAndroidLivenessHookTest.java"
test_code = test_file.read_text(encoding='utf-8')
test_code = test_code.replace("io.mosip.registration.client.liveness.", "io.mosip.registration_client.liveness.")
test_code = test_code.replace("io.mosip.registration.client.model.biometrics.BiometricsDto;", "io.mosip.registration.clientmanager.dto.registration.BiometricsDto;")
test_file.write_text(test_code, encoding='utf-8')

print("Android subprojects alignment completed successfully.")
