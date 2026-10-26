/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.CueResult;
import pramaan.core.pad.MoireCue;

/**
 * The moire threshold belongs to the camera and to the signed operating-point table, not to a launch flag: a camera the table
 * names gets its own calibration, every other camera gets the table's default, and a calibration that does not say where its
 * numbers came from, or whose numbers cannot be right, makes the whole table refuse to load.
 */
class MoireCalibrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private ObjectNode table;
    private Map<String, String> models;

    @BeforeEach
    void loadShippedTable() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("..", "config", "operating-points.json"));
        table = (ObjectNode) JSON.readTree(bytes);
        models = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = table.get("models").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            models.put(e.getKey(), e.getValue().textValue());
        }
    }

    private OperatingPointTable load() throws Exception {
        return OperatingPointTable.load(JSON.writeValueAsString(table).getBytes(StandardCharsets.UTF_8), "test", models);
    }

    private ObjectNode entry(double threshold, double slope, String basis) {
        return entry(threshold, slope, "vote", basis);
    }

    private ObjectNode entry(double threshold, double slope, String mode, String basis) {
        ObjectNode n = JSON.createObjectNode();
        n.put("mode", mode);
        n.put("peakinessThreshold", threshold);
        n.put("slope", slope);
        n.put("basis", basis);
        return n;
    }

    private ObjectNode section(ObjectNode defaultEntry, String camera, ObjectNode cameraEntry) {
        ObjectNode s = JSON.createObjectNode();
        s.set("default", defaultEntry);
        if (camera != null) {
            ObjectNode cameras = JSON.createObjectNode();
            cameras.set(camera, cameraEntry);
            s.set("cameras", cameras);
        }
        return s;
    }

    @Test
    void aTableWithNoSectionRunsEveryCameraOnTheProvisionalDefault() throws Exception {
        table.remove("moireCalibration");
        OperatingPointTable t = load();
        MoireCalibration c = t.moireCalibration("Any", "Camera");
        assertEquals(MoireCalibration.PROVISIONAL_THRESHOLD, c.peakinessThreshold());
        assertEquals(MoireCalibration.PROVISIONAL_SLOPE, c.slope());
        assertFalse(t.hasMoireCalibrationFor("Any", "Camera"));
    }

    @Test
    void aNamedCameraGetsItsOwnCalibrationAndEveryOtherCameraGetsTheDefault() throws Exception {
        table.set("moireCalibration", section(entry(10, 0.6, "default for the test"), "Acme Phone X",
                entry(24, 0.5, "measured on 40 live and 40 replay frames")));
        OperatingPointTable t = load();
        assertEquals(24, t.moireCalibration("Acme", "Phone X").peakinessThreshold());
        assertEquals(0.5, t.moireCalibration("Acme", "Phone X").slope());
        assertTrue(t.hasMoireCalibrationFor("Acme", "Phone X"));
        assertEquals(10, t.moireCalibration("Other", "Camera").peakinessThreshold());
        assertFalse(t.hasMoireCalibrationFor("Other", "Camera"));
        assertEquals("measured on 40 live and 40 replay frames", t.moireCalibration("  Acme ", " Phone X ").basis());
    }

    @Test
    void aCalibrationWithoutABasisOrWithImpossibleNumbersMakesTheTableRefuseToLoad() {
        table.set("moireCalibration", section(entry(10, 0.6, "  "), null, null));
        assertThrows(InvalidPolicyException.class, this::load);
        table.set("moireCalibration", section(entry(0, 0.6, "zero threshold"), null, null));
        assertThrows(InvalidPolicyException.class, this::load);
        table.set("moireCalibration", section(entry(10, 9, "slope too steep"), null, null));
        assertThrows(InvalidPolicyException.class, this::load);
        table.set("moireCalibration", section(entry(10000, 0.6, "threshold beyond any spectrum"), null, null));
        assertThrows(InvalidPolicyException.class, this::load);
    }

    @Test
    void anUnknownFieldInACalibrationIsRefusedNotIgnored() {
        ObjectNode e = entry(10, 0.6, "default for the test");
        e.put("threshhold", 99);
        table.set("moireCalibration", section(e, null, null));
        assertThrows(InvalidPolicyException.class, this::load);
    }

    @Test
    void theSameSpectrumIsReadAsAScreenOrAsALiveFaceDependingOnTheCamerasThreshold() {
        Random r = new Random(7);
        byte[] bytes = new byte[160 * 200 * 3];
        r.nextBytes(bytes);
        RgbImage crop = RgbImage.of(160, 200, bytes);
        CueResult strict = new MoireCue(new MoireCalibration(4, 0.6, MoireCalibration.Mode.VOTE, "low threshold")).evaluate(crop);
        CueResult lenient = new MoireCue(new MoireCalibration(40, 0.6, MoireCalibration.Mode.VOTE, "high threshold")).evaluate(crop);
        assertFalse(strict.abstained() || lenient.abstained());
        assertEquals(strict.rawValue(), lenient.rawValue(), 1e-9, "the same peakiness is measured");
        assertTrue(lenient.pLive() > strict.pLive(),
                "a camera whose live faces are peaky must not have them read as screens: " + lenient.pLive() + " vs "
                        + strict.pLive());
    }

    @Test
    void aCameraCalibratedToAbstainNeverVotesAndTheShippedTableAbstainsByDefault() throws Exception {
        byte[] bytes = new byte[160 * 200 * 3];
        new Random(3).nextBytes(bytes);
        RgbImage crop = RgbImage.of(160, 200, bytes);
        CueResult r = new MoireCue(new MoireCalibration(10, 0.6, MoireCalibration.Mode.ABSTAIN, "no measurement separates")).evaluate(crop);
        assertTrue(r.abstained());
        assertEquals(CueResult.AbstainReason.UNTRAINED, r.abstainReason());
        // the table in the repository: a camera it does not name abstains, because none has yet been shown to separate live from screen
        MoireCalibration shipped = load().moireCalibration("Any", "Camera");
        assertFalse(shipped.votes(), "the shipped default: " + shipped);
        assertTrue(shipped.basis().contains("Measured"), "the basis records the measurement: " + shipped.basis());
    }

    @Test
    void aModeThatIsNeitherVoteNorAbstainIsRefused() {
        table.set("moireCalibration", section(entry(10, 0.6, "sometimes", "typo in the mode"), null, null));
        assertThrows(InvalidPolicyException.class, this::load);
    }
}
