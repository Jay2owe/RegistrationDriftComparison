/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.score;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import org.junit.Test;
import regdrift.internal.Transform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The two outputs that are read rather than ranked: the before-and-after panel,
 * and the motion-preservation flag.
 *
 * <p>Neither feeds a score. The panel is the figure somebody puts in a paper; the
 * flag is a sentence somebody reads before believing one. The assertions here are
 * about them staying that way - a panel that is legible as a still, and a flag
 * that never becomes a verdict.
 */
public class KymographAndMotionFlagTest {

    private static final int SIDE = 64;
    private static final int FRAMES = 24;

    // ------------------------------------------------------------- the panel

    @Test
    public void thePanelIsAnEightBitBeforeAndAfterImage() {
        Fixture.Drifting movie = Fixture.drifting(SIDE, FRAMES, 0.45, -0.3, 7L);
        ControlWarp.Margin margin = ControlWarp.validMargin(movie.cumulative, SIDE, SIDE,
                ControlWarp.Interpolation.BILINEAR);
        ImagePlus panel = Kymograph.beforeAndAfter(movie.rawSource(),
                movie.registeredSource(ControlWarp.Interpolation.BILINEAR), margin,
                "Kymograph before and after");

        assertNotNull(panel);
        assertEquals("8-bit, so it prints and pastes as a still", 8, panel.getBitDepth());
        assertEquals("one row per frame", FRAMES, panel.getHeight());
        assertEquals("two halves and the gap between them",
                2 * margin.croppedWidth(SIDE) + Kymograph.GAP, panel.getWidth());
        assertEquals("it is a still, not a stack", 1, panel.getStackSize());
        assertEquals("Kymograph before and after", panel.getTitle());
        assertFalse("building a panel must not put it on screen", panel.isVisible());
    }

    /**
     * Legible means the two halves carry structure and the join between them
     * cannot be mistaken for data.
     */
    @Test
    public void thePanelIsLegibleAsAStill() {
        Fixture.Drifting movie = Fixture.drifting(SIDE, FRAMES, 0.45, -0.3, 11L);
        ControlWarp.Margin margin = ControlWarp.validMargin(movie.cumulative, SIDE, SIDE,
                ControlWarp.Interpolation.BILINEAR);
        ImagePlus panel = Kymograph.beforeAndAfter(movie.rawSource(),
                movie.registeredSource(ControlWarp.Interpolation.BILINEAR), margin, "panel");
        ImageProcessor pixels = panel.getProcessor();
        int half = margin.croppedWidth(SIDE);

        for (int y = 0; y < panel.getHeight(); y++) {
            for (int x = 0; x < Kymograph.GAP; x++) {
                assertEquals("the separator has to be plainly not data", 255,
                        pixels.get(half + x, y));
            }
        }
        assertTrue("the raw half has to carry structure", spread(pixels, 0, half) > 20);
        assertTrue("and so does the registered half",
                spread(pixels, half + Kymograph.GAP, half) > 20);
    }

    // -------------------------------------------------------------- the flag

    /**
     * The case the flag exists to catch, at its measured figures:
     * {@code 11_unresolved_moving_artefact} walked 4547 px of path across a 768 px
     * frame - nearly six frame-widths - and its registration had followed a moving
     * object. Its dominance figure is 0.000, so path length is what has to catch
     * it.
     */
    @Test
    public void theMovingArtefactEntryRaisesTheFlagOnPathLengthAlone() {
        MotionPreservation flag = MotionPreservation.of(4547, 768, 768, 0.000);
        assertTrue("the entry the flag was built for must raise it, and dominance is no help"
                + " on phase contrast", flag.raised());
        assertEquals(4547.0 / 768, flag.pathFraction(), 1e-9);
    }

    /**
     * And the cases it must not catch, at their measured figures.
     * {@code 08_knock_severe} walked 872 px across a 768 px frame and its
     * registration is right; {@code 12_long_baseline_9d} walked 701 px across
     * 640 px. Neither is a moving artefact, and a flag that fired on both would be
     * a flag nobody reads.
     */
    @Test
    public void ordinaryLongPathsDoNotRaiseIt() {
        assertFalse("08_knock_severe", MotionPreservation.of(872, 768, 768, 0.000).raised());
        assertFalse("12_long_baseline_9d", MotionPreservation.of(701, 640, 640, 0.000).raised());
        assertFalse("09_knock_extreme", MotionPreservation.of(437, 768, 768, 0.001).raised());
        assertFalse("04_drift", MotionPreservation.of(101, 512, 512, 0.001).raised());
        assertFalse("01_jitter_mild", MotionPreservation.of(73, 512, 512, 0.002).raised());
    }

    /** One dominant structure and a path longer than the frame is the second route in. */
    @Test
    public void aDominantStructureRaisesItOverAShorterPath() {
        assertFalse("a dominant structure that barely moved is not a finding",
                MotionPreservation.of(60, 512, 512, 0.9).raised());
        assertTrue("but one that walked past the frame's own width is",
                MotionPreservation.of(800, 512, 512, 0.9).raised());
    }

    /** The caveat travels with the flag whether it is up or down, and says it is a flag. */
    @Test
    public void theCaveatTravelsWithTheFlagAndSaysItIsNotAVerdict() {
        MotionPreservation raised = MotionPreservation.of(4547, 512, 512, 0.42);
        MotionPreservation down = MotionPreservation.of(73, 512, 512, 0.11);
        for (MotionPreservation flag : new MotionPreservation[]{raised, down}) {
            String caveat = flag.caveat();
            assertTrue("the caveat has to give the measurement: " + caveat,
                    caveat.contains("Recovered path length"));
            assertTrue("and say what it might mean: " + caveat,
                    caveat.contains("follow the structure rather than the field"));
            assertTrue("and say it might mean nothing: " + caveat,
                    caveat.contains("really did travel"));
            assertTrue("and say, every time, that it is a flag: " + caveat,
                    caveat.contains("This is a flag, not a verdict"));
            assertTrue("and that nothing is reordered by it: " + caveat,
                    caveat.contains("reorders a ranking"));
        }
    }

    /** Dominance reads high on one bright object and low on an evenly textured field. */
    @Test
    public void dominanceSeparatesOneObjectFromAnEvenField() {
        float[] oneObject = new float[SIDE * SIDE];
        java.util.Arrays.fill(oneObject, 100f);
        for (int y = 20; y < 44; y++) {
            for (int x = 20; x < 44; x++) oneObject[y * SIDE + x] = 900f;
        }
        double dominated = MotionPreservation.dominance(oneObject, SIDE, SIDE);
        assertTrue("one bright square against a flat field: " + dominated, dominated > 0.9);

        float[] even = Fixture.texture(SIDE, SIDE, 3L, 1);
        double spread = MotionPreservation.dominance(even, SIDE, SIDE);
        assertTrue("an evenly textured field: " + spread, spread < 0.5);
    }

    /** A frame with nothing in it says so rather than dividing zero by zero. */
    @Test
    public void aFlatFrameHasNoDominanceToReport() {
        float[] flat = new float[SIDE * SIDE];
        java.util.Arrays.fill(flat, 42f);
        assertTrue(Double.isNaN(MotionPreservation.dominance(flat, SIDE, SIDE)));
    }

    /** A run that asked not to flag motion loss still measures the path, and never raises. */
    @Test
    public void aRunThatDidNotAskForTheFlagStillMeasuresThePath() {
        MotionPreservation quiet = MotionPreservation.notAsked(4547, 512, 512);
        assertFalse(quiet.raised());
        assertEquals(4547.0, quiet.pathPx(), 0.0);
        assertTrue(Double.isNaN(quiet.dominance()));
    }

    /** The path and the net displacement are what the comparison table's two columns hold. */
    @Test
    public void pathAndNetAreMeasuredFromTheTransforms() {
        Transform[] there = {
                Transform.translation(0, 0),
                Transform.translation(3, 4),
                Transform.translation(0, 0),
        };
        assertEquals("out five and back five", 10.0, Arbiter.pathPx(there), 1e-12);
        assertEquals("and nowhere, net", 0.0, Arbiter.netPx(there), 1e-12);
    }

    // ---------------------------------------------------------------- machinery

    /** How far apart the brightest and dimmest pixels of one half are. */
    private static int spread(ImageProcessor pixels, int fromColumn, int columns) {
        int low = 255;
        int high = 0;
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = fromColumn; x < fromColumn + columns; x++) {
                int v = pixels.get(x, y);
                if (v < low) low = v;
                if (v > high) high = v;
            }
        }
        return high - low;
    }
}
