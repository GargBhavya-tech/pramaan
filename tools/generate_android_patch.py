import os

os.makedirs("integration/android-registration-client", exist_ok=True)
PATCH_FILE = "integration/android-registration-client/0001-pramaan-android-liveness-hooks.patch"

patch_content = """diff --git a/app/src/main/java/io/mosip/registration/client/view/biometrics/FaceCaptureActivity.java b/app/src/main/java/io/mosip/registration/client/view/biometrics/FaceCaptureActivity.java
index 1a8b9c0..3d4e5f6 100644
--- a/app/src/main/java/io/mosip/registration/client/view/biometrics/FaceCaptureActivity.java
+++ b/app/src/main/java/io/mosip/registration/client/view/biometrics/FaceCaptureActivity.java
@@ -112,6 +112,14 @@ public class FaceCaptureActivity extends AppCompatActivity {
 
 	private String currentModality = "FACE_FULLFACE";
 
+	/** PRAMAAN: the transactionId and previousHash the liveness hook chose for this face scan, or null. */
+	private volatile io.mosip.registration.client.liveness.CaptureTicket livenessTicket;
+
+	/** PRAMAAN: challenge prompt and guidance views on Android mobile client. */
+	private View livenessOverlayView;
+	private TextView livenessPromptText;
+	private TextView livenessGuidanceText;
+
 	@Override
 	protected void onCreate(Bundle savedInstanceState) {
 		super.onCreate(savedInstanceState);
@@ -145,6 +153,24 @@ public class FaceCaptureActivity extends AppCompatActivity {
 	private void startStreamAndCapture() {
 		try {
+			if ("FACE_FULLFACE".equals(currentModality)) {
+				if (livenessOverlayView != null) {
+					livenessOverlayView.setVisibility(View.VISIBLE);
+				}
+				io.mosip.registration.client.liveness.LivenessHooks.get().beginFaceScan(currentModality);
+				io.mosip.registration.client.liveness.LivenessHooks.get().setPromptListener((prompt, guidance) -> {
+					runOnUiThread(() -> {
+						if (livenessPromptText != null) {
+							livenessPromptText.setText(prompt != null ? prompt : "");
+						}
+						if (livenessGuidanceText != null) {
+							livenessGuidanceText.setText(guidance != null ? guidance : "");
+						}
+					});
+				});
+			}
 			streamer.startStream(streamUrl, previewImageView);
 			executeCaptureAsync();
 		} catch (Exception e) {
@@ -210,6 +236,15 @@ public class FaceCaptureActivity extends AppCompatActivity {
 	private List<BiometricsDto> executeCaptureTask() throws Exception {
+		livenessTicket = null;
+		if ("FACE_FULLFACE".equals(currentModality)) {
+			try {
+				livenessTicket = io.mosip.registration.client.liveness.LivenessHooks.get().awaitCaptureClearance();
+			} catch (io.mosip.registration.client.liveness.LivenessRefusedException | InterruptedException e) {
+				Log.e("FaceCaptureActivity", "Liveness did not clear the capture", e);
+				throw new Exception("Liveness verification did not complete");
+			}
+		}
 		MDMRequestDto requestDto = buildCaptureRequest();
 		return sbiProvider.captureModality(requestDto);
 	}
@@ -250,6 +285,22 @@ public class FaceCaptureActivity extends AppCompatActivity {
 	private void onCaptureCompleted(List<BiometricsDto> capturedList) {
+		if ("FACE_FULLFACE".equals(currentModality)) {
+			if (livenessOverlayView != null) {
+				livenessOverlayView.setVisibility(View.GONE);
+			}
+			try {
+				String evidence = io.mosip.registration.client.liveness.LivenessHooks.get().afterCapture(capturedList);
+				for (BiometricsDto face : capturedList) {
+					face.setLivenessEvidence(evidence);
+				}
+			} catch (io.mosip.registration.client.liveness.LivenessRefusedException refused) {
+				Log.e("FaceCaptureActivity", "The signed capture was refused by liveness hook", refused);
+				showToast("Biometric capture failed liveness verification");
+				return;
+			}
+		}
 		saveBiometricsAndFinish(capturedList);
 	}
@@ -310,6 +361,9 @@ public class FaceCaptureActivity extends AppCompatActivity {
 		MDMRequestDto dto = new MDMRequestDto(currentModality, timeout, requestedScore);
+		io.mosip.registration.client.liveness.CaptureTicket ticket = "FACE_FULLFACE".equals(currentModality) ? livenessTicket : null;
+		dto.setTransactionId(ticket == null ? null : ticket.transactionId());
+		dto.setPreviousHash(ticket == null ? null : ticket.previousHash());
 		return dto;
 	}
diff --git a/app/src/main/java/io/mosip/registration/client/view/biometrics/Streamer.java b/app/src/main/java/io/mosip/registration/client/view/biometrics/Streamer.java
index 2b3c4d5..4e5f6a7 100644
--- a/app/src/main/java/io/mosip/registration/client/view/biometrics/Streamer.java
+++ b/app/src/main/java/io/mosip/registration/client/view/biometrics/Streamer.java
@@ -85,6 +85,7 @@ public class Streamer {
 				while (isStreaming && (imageBytes = fetchNextFrame(streamUrl)) != null) {
+					io.mosip.registration.client.liveness.LivenessHooks.frame(imageBytes, System.currentTimeMillis());
 					Bitmap bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
 					mainHandler.post(() -> targetView.setImageBitmap(bmp));
 				}
diff --git a/app/src/main/java/io/mosip/registration/client/model/biometrics/BiometricsDto.java b/app/src/main/java/io/mosip/registration/client/model/biometrics/BiometricsDto.java
index 7a8b9c0..8b9c0d1 100644
--- a/app/src/main/java/io/mosip/registration/client/model/biometrics/BiometricsDto.java
+++ b/app/src/main/java/io/mosip/registration/client/model/biometrics/BiometricsDto.java
@@ -20,6 +20,10 @@ public class BiometricsDto {
 	private String payLoad;
 	private String signature;
 	private String specVersion;
+	/** Signed liveness evidence (a JWS) for a face capture, stored in the BIR others map; null when no hook ran. */
+	private String livenessEvidence;
+	/** The device's raw rCapture response element (JSON: data, hash, specVersion, ...), kept so a liveness hook can verify it. */
+	private String sbiResponse;
 	private boolean captured;
 	private String modalityName;
diff --git a/app/src/main/java/io/mosip/registration/client/sbi/dto/MDMRequestDto.java b/app/src/main/java/io/mosip/registration/client/sbi/dto/MDMRequestDto.java
index 9a0b1c2..0b1c2d3 100644
--- a/app/src/main/java/io/mosip/registration/client/sbi/dto/MDMRequestDto.java
+++ b/app/src/main/java/io/mosip/registration/client/sbi/dto/MDMRequestDto.java
@@ -15,6 +15,10 @@ public class MDMRequestDto {
 	private String modality;
 	private int timeout;
 	private int requestedScore;
+	/** Optional SBI transactionId to send instead of a random one (liveness binding); null keeps the client's own. */
+	private String transactionId;
+	/** Optional SBI previousHash for the bio element (liveness binding); null keeps the client's own (null). */
+	private String previousHash;
 
 	public MDMRequestDto(String modality, int timeout, int requestedScore) {
 		this.modality = modality;
diff --git a/app/src/main/java/io/mosip/registration/client/sbi/service/impl/MosipDeviceSpecification_SBI_1_0_ProviderImpl.java b/app/src/main/java/io/mosip/registration/client/sbi/service/impl/MosipDeviceSpecification_SBI_1_0_ProviderImpl.java
index 1c2d3e4..2d3e4f5 100644
--- a/app/src/main/java/io/mosip/registration/client/sbi/service/impl/MosipDeviceSpecification_SBI_1_0_ProviderImpl.java
+++ b/app/src/main/java/io/mosip/registration/client/sbi/service/impl/MosipDeviceSpecification_SBI_1_0_ProviderImpl.java
@@ -180,6 +180,7 @@ public class MosipDeviceSpecification_SBI_1_0_ProviderImpl {
 				bioDto.setPayLoad(decodedPayload);
 				bioDto.setSignature(signature);
 				bioDto.setSpecVersion(bioResponse.getSpecVersion());
+				bioDto.setSbiResponse(jsonMapper.writeValueAsString(bioResponse));
 				bioDto.setCaptured(true);
 				bioDto.setModalityName(mdmRequestDto.getModality());
 				resultList.add(bioDto);
@@ -215,8 +216,9 @@ public class MosipDeviceSpecification_SBI_1_0_ProviderImpl {
 			SbiRCaptureRequestDTO request = new SbiRCaptureRequestDTO();
 			request.setEnvironment(mdmRequestDto.getEnvironment());
 			request.setTimeout(String.valueOf(mdmRequestDto.getTimeout()));
-			request.setTransactionId(UUID.randomUUID().toString());
+			request.setTransactionId(mdmRequestDto.getTransactionId() != null ? mdmRequestDto.getTransactionId() : UUID.randomUUID().toString());
+			bioRequest.setPreviousHash(mdmRequestDto.getPreviousHash());
 			return request;
 		}
diff --git a/app/src/main/java/io/mosip/registration/client/util/BIRBuilder.java b/app/src/main/java/io/mosip/registration/client/util/BIRBuilder.java
index 3e4f5a6..4f5a6b7 100644
--- a/app/src/main/java/io/mosip/registration/client/util/BIRBuilder.java
+++ b/app/src/main/java/io/mosip/registration/client/util/BIRBuilder.java
@@ -45,6 +45,10 @@ public class BIRBuilder {
 		BIR.BIRBuilder birBuilder = new BIR.BIRBuilder()
 				.withBdb(bioDto.getPayLoad() == null ? new byte[0] : bioDto.getPayLoad().getBytes())
 				.withOthers("SPEC_VERSION", bioDto.getSpecVersion() == null ? "" : bioDto.getSpecVersion());
+		if (bioDto.getLivenessEvidence() != null) {
+			// PRAMAAN: the signed liveness evidence travels with the face record; the bdb is untouched
+			birBuilder.withOthers("PRAMAAN_LIVENESS", bioDto.getLivenessEvidence());
+		}
 		return birBuilder.build();
 	}
diff --git a/app/src/main/res/layout/activity_face_capture.xml b/app/src/main/res/layout/activity_face_capture.xml
index 5a6b7c8..6b7c8d9 100644
--- a/app/src/main/res/layout/activity_face_capture.xml
+++ b/app/src/main/res/layout/activity_face_capture.xml
@@ -42,6 +42,21 @@
 		android:layout_width="match_parent"
 		android:layout_height="match_parent"
 		android:scaleType="centerCrop" />
+
+	<!-- PRAMAAN (P5): challenge prompt overlay on Android mobile client -->
+	<FrameLayout
+		android:id="@+id/liveness_overlay_container"
+		android:layout_width="match_parent"
+		android:layout_height="match_parent"
+		android:visibility="gone"
+		android:background="#A014202E">
+		<LinearLayout
+			android:layout_width="wrap_content"
+			android:layout_height="wrap_content"
+			android:layout_gravity="center"
+			android:orientation="vertical"
+			android:gravity="center">
+			<TextView
+				android:id="@+id/liveness_prompt_text"
+				android:layout_width="wrap_content"
+				android:layout_height="wrap_content"
+				android:textColor="#F5A623"
+				android:textSize="20sp"
+				android:textStyle="bold" />
+			<TextView
+				android:id="@+id/liveness_guidance_text"
+				android:layout_width="wrap_content"
+				android:layout_height="wrap_content"
+				android:textColor="#FFFFFF"
+				android:textSize="16sp"
+				android:layout_marginTop="8dp" />
+		</LinearLayout>
+	</FrameLayout>
diff --git a/app/src/main/java/io/mosip/registration/client/liveness/CaptureTicket.java b/app/src/main/java/io/mosip/registration/client/liveness/CaptureTicket.java
new file mode 100644
index 0000000..5559cbe
--- /dev/null
+++ b/app/src/main/java/io/mosip/registration/client/liveness/CaptureTicket.java
@@ -0,0 +1,33 @@
+/*
+ * This Source Code Form is subject to the terms of the Mozilla Public
+ * License, v. 2.0. If a copy of the MPL was not distributed with this
+ * file, You can obtain one at https://mozilla.org/MPL/2.0/.
+ */
+
+package io.mosip.registration.client.liveness;
+
+/**
+ * What a liveness hook hands the Android Registration Client when it clears a capture: the two values the client must
+ * put in its rCapture request/intent so the device signs them inside the capture (SBI 1.0: {@code transactionId} and the bio
+ * element's {@code previousHash}). Either may be {@code null}, which keeps the client's own behaviour for that value.
+ */
+public final class CaptureTicket {
+
+	private final String transactionId;
+	private final String previousHash;
+
+	public CaptureTicket(String transactionId, String previousHash) {
+		this.transactionId = transactionId;
+		this.previousHash = previousHash;
+	}
+
+	/** 4 to 50 characters of letters, digits and hyphens (SBI), or null. */
+	public String transactionId() {
+		return transactionId;
+	}
+
+	/** The hash the capture chains to, or null. */
+	public String previousHash() {
+		return previousHash;
+	}
+}
diff --git a/app/src/main/java/io/mosip/registration/client/liveness/LivenessHook.java b/app/src/main/java/io/mosip/registration/client/liveness/LivenessHook.java
new file mode 100644
index 0000000..7a3be79
--- /dev/null
+++ b/app/src/main/java/io/mosip/registration/client/liveness/LivenessHook.java
@@ -0,0 +1,55 @@
+/*
+ * This Source Code Form is subject to the terms of the Mozilla Public
+ * License, v. 2.0. If a copy of the MPL was not distributed with this
+ * file, You can obtain one at https://mozilla.org/MPL/2.0/.
+ */
+
+package io.mosip.registration.client.liveness;
+
+import java.util.List;
+
+import io.mosip.registration.client.model.biometrics.BiometricsDto;
+
+/**
+ * The four seams a face liveness engine needs in the Android Registration Client (PRAMAAN, ADR 0003, patches P1 to P5).
+ *
+ * <p>The client calls these only for the FACE modality. With no implementation on the classpath the client behaves exactly
+ * as it did before: see {@link LivenessHooks#NONE}. An implementation is found with {@link java.util.ServiceLoader}.
+ */
+public interface LivenessHook {
+
+	/** A face scan is starting (before the stream is read): start a liveness session. */
+	void beginFaceScan(String modality);
+
+	/**
+	 * Every decoded preview frame (P2): the JPEG bytes and when they arrived. Called on the streamer thread, so it must
+	 * return quickly and must not throw; the preview never stops because of the hook.
+	 */
+	void onPreviewFrame(byte[] jpeg, long arrivedAtEpochMillis);
+
+	/**
+	 * Called on the capture thread (P1) before the client asks the device for the signed capture. Blocks until the engine
+	 * has reached its capture step, or throws to stop the scan. The client no longer requests the capture the moment the
+	 * stream opens. Returns the {@link CaptureTicket}: the SBI {@code transactionId} and {@code previousHash} the client
+	 * must send in the rCapture request (P3), so the device signs them inside the capture, or {@code null} to keep the
+	 * client's own.
+	 */
+	CaptureTicket awaitCaptureClearance() throws InterruptedException, LivenessRefusedException;
+
+	/**
+	 * Called with the device's signed capture before the client stores it (P4). Each {@code BiometricsDto} carries the
+	 * device's raw response element in {@code getSbiResponse()}: the client keeps only the decoded payload and the
+	 * signature, and drops the JWS header (with the certificate chain) that a verifier needs. Returns the signed liveness
+	 * evidence (a JWS) to put in the face BIR {@code others} map, or throws {@link LivenessRefusedException} if the
+	 * capture is not the face that passed liveness, in which case nothing is stored.
+	 */
+	String afterCapture(List<BiometricsDto> captured) throws LivenessRefusedException;
+
+	/**
+	 * Seam for live challenge prompts and guidance to be rendered on the Android UI overlay (P5).
+	 */
+	default void setPromptListener(PromptListener listener) {
+	}
+}
diff --git a/app/src/main/java/io/mosip/registration/client/liveness/PromptListener.java b/app/src/main/java/io/mosip/registration/client/liveness/PromptListener.java
new file mode 100644
index 0000000..83b8791
--- /dev/null
+++ b/app/src/main/java/io/mosip/registration/client/liveness/PromptListener.java
@@ -0,0 +1,15 @@
+/*
+ * This Source Code Form is subject to the terms of the Mozilla Public
+ * License, v. 2.0. If a copy of the MPL was not distributed with this
+ * file, You can obtain one at https://mozilla.org/MPL/2.0/.
+ */
+
+package io.mosip.registration.client.liveness;
+
+/**
+ * Seam for live challenge prompts and guidance to be rendered on the Android client UI overlay (P5).
+ */
+@FunctionalInterface
+public interface PromptListener {
+	void onPrompt(String prompt, String guidance);
+}
diff --git a/app/src/main/java/io/mosip/registration/client/liveness/LivenessHooks.java b/app/src/main/java/io/mosip/registration/client/liveness/LivenessHooks.java
new file mode 100644
index 0000000..472a942
--- /dev/null
+++ b/app/src/main/java/io/mosip/registration/client/liveness/LivenessHooks.java
@@ -0,0 +1,86 @@
+/*
+ * This Source Code Form is subject to the terms of the Mozilla Public
+ * License, v. 2.0. If a copy of the MPL was not distributed with this
+ * file, You can obtain one at https://mozilla.org/MPL/2.0/.
+ */
+
+package io.mosip.registration.client.liveness;
+
+import java.util.Iterator;
+import java.util.List;
+import java.util.ServiceLoader;
+
+import io.mosip.registration.client.model.biometrics.BiometricsDto;
+
+/**
+ * Finds the {@link LivenessHook} implementation, if any, once. With none, {@link #NONE} does nothing, so the client is
+ * unchanged. If an implementation is present but cannot be loaded, the face scan is refused rather than run without
+ * liveness: a mobile station that was meant to check liveness must not silently stop checking.
+ */
+public final class LivenessHooks {
+
+	/** Does nothing: no session, no frames kept, no wait, no transaction id of its own, no evidence. */
+	public static final LivenessHook NONE = new LivenessHook() {
+		@Override
+		public void beginFaceScan(String modality) {
+			// nothing
+		}
+
+		@Override
+		public void onPreviewFrame(byte[] jpeg, long arrivedAtEpochMillis) {
+			// nothing
+		}
+
+		@Override
+		public CaptureTicket awaitCaptureClearance() {
+			return null;
+		}
+
+		@Override
+		public String afterCapture(List<BiometricsDto> captured) {
+			return null;
+		}
+	};
+
+	private static volatile LivenessHook hook;
+	private static volatile LivenessRefusedException loadFailure;
+
+	private LivenessHooks() {
+	}
+
+	/** The implementation, or {@link #NONE}. Throws if one is installed but failed to load (fail closed). */
+	public static LivenessHook get() throws LivenessRefusedException {
+		if (loadFailure != null) {
+			throw loadFailure;
+		}
+		LivenessHook h = hook;
+		if (h == null) {
+			synchronized (LivenessHooks.class) {
+				h = hook;
+				if (h == null) {
+					try {
+						Iterator<LivenessHook> it = ServiceLoader.load(LivenessHook.class).iterator();
+						h = it.hasNext() ? it.next() : NONE;
+					} catch (Throwable t) {
+						loadFailure = new LivenessRefusedException("a liveness hook is installed on Android but did not load: " + t);
+						throw loadFailure;
+					}
+					hook = h;
+				}
+			}
+		}
+		return h;
+	}
+
+	/** For the preview thread: never throws, and never slows the preview when there is no hook. */
+	public static void frame(byte[] jpeg, long arrivedAtEpochMillis) {
+		try {
+			LivenessHook h = get();
+			if (h != NONE) {
+				h.onPreviewFrame(jpeg, arrivedAtEpochMillis);
+			}
+		} catch (Throwable t) {
+			// the preview must keep running; a hook that fails here fails the capture at the next seam
+		}
+	}
+}
diff --git a/app/src/main/java/io/mosip/registration/client/liveness/LivenessRefusedException.java b/app/src/main/java/io/mosip/registration/client/liveness/LivenessRefusedException.java
new file mode 100644
index 0000000..720d2e2
--- /dev/null
+++ b/app/src/main/java/io/mosip/registration/client/liveness/LivenessRefusedException.java
@@ -0,0 +1,21 @@
+/*
+ * This Source Code Form is subject to the terms of the Mozilla Public
+ * License, v. 2.0. If a copy of the MPL was not distributed with this
+ * file, You can obtain one at https://mozilla.org/MPL/2.0/.
+ */
+
+package io.mosip.registration.client.liveness;
+
+/**
+ * Thrown by a {@link LivenessHook} to stop a face capture: liveness could not be established, or the signed capture is
+ * not the face that was checked. The client shows its generic capture-failure message and stores nothing; the reason
+ * belongs in the hook's own audit log, not on the screen.
+ */
+public class LivenessRefusedException extends Exception {
+
+	private static final long serialVersionUID = 1L;
+
+	public LivenessRefusedException(String message) {
+		super(message);
+	}
+}
"""

with open(PATCH_FILE, "w", encoding="utf-8", newline="\n") as f:
    f.write(patch_content.strip() + "\n")

print(f"Generated {PATCH_FILE} successfully.")
