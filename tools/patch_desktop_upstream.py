import sys
from pathlib import Path

fxml_path = Path("third_party/registration-client/registration/registration-client/src/main/resources/fxml/GenericBiometricFXML.fxml")
content = fxml_path.read_text(encoding="utf-8")

target = '<ImageView fx:id="biometricImage" fitHeight="${biometricPane.height-10}" fitWidth="${biometricPane.width-10}" pickOnBounds="true" preserveRatio="false" GridPane.halignment="CENTER" GridPane.valignment="TOP" />'
overlay = """<ImageView fx:id="biometricImage" fitHeight="${biometricPane.height-10}" fitWidth="${biometricPane.width-10}" pickOnBounds="true" preserveRatio="false" GridPane.halignment="CENTER" GridPane.valignment="TOP" />
                                                             <StackPane fx:id="livenessOverlayPane" visible="false" mouseTransparent="true" style="-fx-background-color: rgba(20, 32, 46, 0.65); -fx-background-radius: 8;" GridPane.halignment="CENTER" GridPane.valignment="CENTER">
                                                                <VBox alignment="CENTER" spacing="10">
                                                                   <Label fx:id="livenessPromptLabel" text="" style="-fx-text-fill: #f5a623; -fx-font-size: 16px; -fx-font-weight: bold;" />
                                                                   <Label fx:id="livenessGuidanceLabel" text="" style="-fx-text-fill: white; -fx-font-size: 14px;" />
                                                                </VBox>
                                                             </StackPane>"""

if target in content:
    content = content.replace(target, overlay, 1)
    fxml_path.write_text(content, encoding="utf-8")
    print("SUCCESS: GenericBiometricFXML.fxml updated")
else:
    print("ERROR: target not found in GenericBiometricFXML.fxml")
