PATCH_FILE = "integration/registration-client/0001-pramaan-liveness-hooks.patch"

with open(PATCH_FILE, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Update GenericBiometricsController fields
old_fields = """@@ -226,6 +226,9 @@ public class GenericBiometricsController extends BaseController {
 
 \tprivate Service<List<BiometricsDto>> rCaptureTaskService;
 
+\t/** PRAMAAN: the transactionId and previousHash the liveness hook chose for this face scan, or null (the client's own). */
+\tprivate volatile io.mosip.registration.liveness.CaptureTicket livenessTicket;
+
 \tprivate Service<MdmBioDevice> deviceSearchTask;"""

new_fields = """@@ -226,6 +226,17 @@ public class GenericBiometricsController extends BaseController {
 
 \tprivate Service<List<BiometricsDto>> rCaptureTaskService;
 
+\t/** PRAMAAN: the transactionId and previousHash the liveness hook chose for this face scan, or null (the client's own). */
+\tprivate volatile io.mosip.registration.liveness.CaptureTicket livenessTicket;
+
+\t/** PRAMAAN (P5): challenge prompt overlay and labels. */
+\t@FXML
+\tprivate javafx.scene.layout.StackPane livenessOverlayPane;
+\t@FXML
+\tprivate javafx.scene.control.Label livenessPromptLabel;
+\t@FXML
+\tprivate javafx.scene.control.Label livenessGuidanceLabel;
+
 \tprivate Service<MdmBioDevice> deviceSearchTask;"""

assert old_fields in content, "old_fields not found"
content = content.replace(old_fields, new_fields, 1)

# 2. Update GenericBiometricsController beginFaceScan
old_scan = """+\t\t\t\t\tif (isFace(currentModality)) {
+\t\t\t\t\t\tio.mosip.registration.liveness.LivenessHooks.get().beginFaceScan(RegistrationConstants.FACE_FULLFACE);
+\t\t\t\t\t}
 \t\t\t\t\trCaptureTaskService();"""

new_scan = """+\t\t\t\t\tif (isFace(currentModality)) {
+\t\t\t\t\t\tif (livenessOverlayPane != null) {
+\t\t\t\t\t\t\tlivenessOverlayPane.setVisible(true);
+\t\t\t\t\t\t}
+\t\t\t\t\t\tio.mosip.registration.liveness.LivenessHooks.get().beginFaceScan(RegistrationConstants.FACE_FULLFACE);
+\t\t\t\t\t\tio.mosip.registration.liveness.LivenessHooks.get().setPromptListener((prompt, guidance) -> {
+\t\t\t\t\t\t\tjavafx.application.Platform.runLater(() -> {
+\t\t\t\t\t\t\t\tif (livenessPromptLabel != null) {
+\t\t\t\t\t\t\t\t\tlivenessPromptLabel.setText(prompt != null ? prompt : "");
+\t\t\t\t\t\t\t\t}
+\t\t\t\t\t\t\t\tif (livenessGuidanceLabel != null) {
+\t\t\t\t\t\t\t\t\tlivenessGuidanceLabel.setText(guidance != null ? guidance : "");
+\t\t\t\t\t\t\t\t}
+\t\t\t\t\t\t\t});
+\t\t\t\t\t\t});
+\t\t\t\t\t}
 \t\t\t\t\trCaptureTaskService();"""

assert old_scan in content, "old_scan not found"
content = content.replace(old_scan, new_scan, 1)

# 3. Update GenericBiometricsController hide overlay on finish
old_after = """+\t\t\t\t\tif (isFace(currentModality)) {
+\t\t\t\t\t\ttry {
+\t\t\t\t\t\t\tString evidence = io.mosip.registration.liveness.LivenessHooks.get().afterCapture(mdsCapturedBiometricsList);"""

new_after = """+\t\t\t\t\tif (isFace(currentModality)) {
+\t\t\t\t\t\tif (livenessOverlayPane != null) {
+\t\t\t\t\t\t\tlivenessOverlayPane.setVisible(false);
+\t\t\t\t\t\t}
+\t\t\t\t\t\ttry {
+\t\t\t\t\t\t\tString evidence = io.mosip.registration.liveness.LivenessHooks.get().afterCapture(mdsCapturedBiometricsList);"""

assert old_after in content, "old_after not found"
content = content.replace(old_after, new_after, 1)

# 4. Update LivenessHook.java with setPromptListener
old_hook = """+\tString afterCapture(List<BiometricsDto> captured) throws LivenessRefusedException;
+}
diff --git a/registration/registration-services/src/main/java/io/mosip/registration/liveness/LivenessHooks.java"""

new_hook = """+\tString afterCapture(List<BiometricsDto> captured) throws LivenessRefusedException;
+
+\t/**
+\t * Seam for live challenge prompts and guidance to be rendered on the client UI overlay (P5).
+\t */
+\tdefault void setPromptListener(PromptListener listener) {
+\t}
+}
diff --git a/registration/registration-services/src/main/java/io/mosip/registration/liveness/PromptListener.java b/registration/registration-services/src/main/java/io/mosip/registration/liveness/PromptListener.java
new file mode 100644
index 0000000..83b8791
--- /dev/null
+++ b/registration/registration-services/src/main/java/io/mosip/registration/liveness/PromptListener.java
@@ -0,0 +1,15 @@
+/*
+ * This Source Code Form is subject to the terms of the Mozilla Public
+ * License, v. 2.0. If a copy of the MPL was not distributed with this
+ * file, You can obtain one at https://mozilla.org/MPL/2.0/.
+ */
+
+package io.mosip.registration.liveness;
+
+/**
+ * Seam for live challenge prompts and guidance to be rendered on the client UI overlay (P5).
+ */
+@FunctionalInterface
+public interface PromptListener {
+	void onPrompt(String prompt, String guidance);
+}
diff --git a/registration/registration-services/src/main/java/io/mosip/registration/liveness/LivenessHooks.java"""

assert old_hook in content, "old_hook not found"
content = content.replace(old_hook, new_hook, 1)

# 5. Add FXML diff for GenericBiometricFXML.fxml
fxml_diff = """diff --git a/registration/registration-client/src/main/resources/fxml/GenericBiometricFXML.fxml b/registration/registration-client/src/main/resources/fxml/GenericBiometricFXML.fxml
index 548dae3..429188e 100644
--- a/registration/registration-client/src/main/resources/fxml/GenericBiometricFXML.fxml
+++ b/registration/registration-client/src/main/resources/fxml/GenericBiometricFXML.fxml
@@ -145,6 +145,14 @@
 						<ImageView fx:id="biometricImage" fitHeight="340.0" fitWidth="440.0" pickOnBounds="true" preserveRatio="true" />
+						<StackPane fx:id="livenessOverlayPane" visible="false" mouseTransparent="true" style="-fx-background-color: rgba(20, 32, 46, 0.65); -fx-background-radius: 8;">
+							<VBox alignment="CENTER" spacing="10">
+								<Label fx:id="livenessPromptLabel" text="" style="-fx-text-fill: #f5a623; -fx-font-size: 16px; -fx-font-weight: bold;" />
+								<Label fx:id="livenessGuidanceLabel" text="" style="-fx-text-fill: white; -fx-font-size: 14px;" />
+							</VBox>
+						</StackPane>
 					</children>
"""

content = content + fxml_diff

with open(PATCH_FILE, "w", encoding="utf-8", newline="\n") as f:
    f.write(content)

print("Updated patch successfully.")
