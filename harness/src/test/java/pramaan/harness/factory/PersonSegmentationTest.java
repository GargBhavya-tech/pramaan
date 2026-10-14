/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pramaan.core.image.RgbImage;
import pramaan.core.pad.PersonMask;
import pramaan.core.pad.PersonSegmenter;

/**
 * Route A against known truth (Bible Part 43, 45), on rooms the threshold was never tuned on. SYNTHETIC: it shows how
 * the no-model method behaves under controlled colour and exposure changes, not on real rooms. The bounds are set a
 * little inside the measured values, so a regression shows and noise does not.
 */
class PersonSegmentationTest {

    private static final PersonSegmenter SUBTRACTION = SegmentationBench.subtraction();
    private static final long[] SEEDS = {101, 202, 303};

    private static SegmentationBench.Scenario scenario(long seed, String name) {
        return SegmentationBench.scenarios(seed).stream().filter(s -> s.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aContrastingPersonIsCaughtAndTheRoomIsLeftAlone() {
        for (long seed : SEEDS) {
            SegmentationBench.Score s = SegmentationBench.run(scenario(seed, "contrasting shirt, centred"), "subtraction",
                    SUBTRACTION);
            assertTrue(s.recall > 0.97, "recall " + s.recall + " seed " + seed);
            assertTrue(s.roomKept > 0.90, "room kept " + s.roomKept + " seed " + seed);
        }
    }

    @Test
    void itKeepsMoreOfTheRoomThanTheRectangleAndCatchesAtLeastAsMuchOfThePerson() {
        for (long seed : SEEDS) {
            SegmentationBench.Scenario sc = scenario(seed, "person off to the left");
            SegmentationBench.Score a = SegmentationBench.run(sc, "subtraction", SUBTRACTION);
            SegmentationBench.Score rect = SegmentationBench.run(sc, "rectangle", null);
            assertTrue(a.roomKept > rect.roomKept, "kept " + a.roomKept + " vs " + rect.roomKept);
            assertTrue(a.recall >= rect.recall - 0.01, "recall " + a.recall + " vs " + rect.recall);
        }
    }

    @Test
    void aPersonWalkingBehindIsFoundWhichTheRectangleNeverCould() {
        for (long seed : SEEDS) {
            SegmentationBench.Scenario sc = scenario(seed, "someone walking behind");
            SegmentationBench.Score a = SegmentationBench.run(sc, "subtraction", SUBTRACTION);
            SegmentationBench.Score rect = SegmentationBench.run(sc, "rectangle", null);
            assertTrue(a.recall > 0.95, "recall " + a.recall);
            assertTrue(rect.recall < 0.75, "the rectangle misses the walker: " + rect.recall);
        }
    }

    @Test
    void aChangeOfExposureIsNotMistakenForAnAudience() {
        for (long seed : SEEDS) {
            for (String name : new String[] {"15% brighter than at set-up", "25% darker than at set-up"}) {
                SegmentationBench.Score s = SegmentationBench.run(scenario(seed, name), "subtraction", SUBTRACTION);
                assertTrue(s.recall > 0.97, name + " recall " + s.recall);
                assertTrue(s.roomKept > 0.85, name + " room kept " + s.roomKept);
            }
        }
    }

    @Test
    void aRearrangedRoomCostsBackgroundButNeverAcceptsAnyone() {
        for (long seed : SEEDS) {
            SegmentationBench.Score s = SegmentationBench.run(scenario(seed, "a poster moved since set-up"), "subtraction",
                    SUBTRACTION);
            assertTrue(s.recall > 0.97, "recall " + s.recall);
            assertTrue(s.roomKept > 0.80, "a moved poster is read as a person, nothing more: " + s.roomKept);
        }
    }

    /** The limit of the method, stated as a test so the documentation cannot drift from the behaviour. */
    @Test
    void clothesTheColourOfTheWallAreOnlyPartlyFoundAndTheTorsoColumnIsWhatSavesIt() {
        for (long seed : SEEDS) {
            SegmentationBench.Score s = SegmentationBench.run(scenario(seed, "shirt the colour of the wall"), "subtraction",
                    SUBTRACTION);
            assertTrue(s.recall < 0.97, "wall-coloured clothes are not fully found: " + s.recall);
            assertTrue(s.recall > 0.75, "but the face and the column under it always are: " + s.recall);
            assertTrue(s.roomKept > 0.90, "and the room is not given up for it: " + s.roomKept);
        }
    }

    @Test
    void theFaceIsAlwaysMaskedEvenWithNoStoredRoom() {
        SegmentationBench.Scenario sc = scenario(101, "contrasting shirt, centred");
        RgbImage frame = sc.live.image();
        PersonMask m = SUBTRACTION.segment(frame, sc.faceBox, null);
        assertTrue(m.personAtFrame((sc.faceBox[0] + sc.faceBox[2]) / 2, (sc.faceBox[1] + sc.faceBox[3]) / 2, frame.width(),
                frame.height()));
        assertTrue(m.personFraction() < 0.5, "with no room to compare against, only the prior is masked");
    }

    @Test
    void scenariosAreDeterministic() {
        List<SegmentationBench.Scenario> a = SegmentationBench.scenarios(101);
        List<SegmentationBench.Scenario> b = SegmentationBench.scenarios(101);
        assertEquals(a.get(0).live.truth(), b.get(0).live.truth());
    }
}
