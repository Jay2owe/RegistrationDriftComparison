/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The estimator that was written rather than borrowed, measured against displacements that are known
 * by construction.
 *
 * <p><b>The sign convention is the first thing asserted and the most important.</b> A sign flip here
 * produces a motion trace with the right magnitudes, the right shape and the drift running the wrong
 * way - a fingerprint that is exactly wrong and completely plausible. {@link Synth#frame} builds a
 * frame whose content has moved by a stated amount, so the expected answer is not itself an estimate.
 *
 * <p>The fixture is an analytic function of position rather than a resampled image, so a sub-pixel
 * result can be attributed to the estimator instead of to whichever interpolator built the fixture.
 */
public class PyramidSsdTest {

    private static final int W = 128;
    private static final int H = 128;
    /** Comfortably past every shift tested, and small enough to keep the pyramid two levels deep. */
    private static final double BOUND = 12;
    /** What the estimate has to be worth. Exit gate: a tenth of a pixel. */
    private static final double TOLERANCE = 0.10;

    private static final PyramidSsd SSD = new PyramidSsd();

    private static Estimator.Displacement measure(double dx, double dy) {
        return measure(dx, dy, BOUND);
    }

    private static Estimator.Displacement measure(double dx, double dy, double bound) {
        return SSD.shift(Synth.frame(W, H, 0, 0), Synth.frame(W, H, dx, dy), W, H, bound,
                Frames.Bin.none());
    }

    // ------------------------------------------------------------- recovering

    @Test
    public void theNameIsThePublishedOne() {
        assertEquals("pyramid sum of squared differences", SSD.name());
    }

    /**
     * Content that moved right and up is reported as moving right and up.
     *
     * <p>Whole pixels, so nothing here depends on the sub-pixel stage and a failure means the sign or
     * the pyramid is wrong rather than the refinement.
     */
    @Test
    public void recoversAWholePixelShiftWithTheProjectSignConvention() {
        Estimator.Displacement d = measure(5, -4);
        assertEquals("dx", 5.0, d.dx(), TOLERANCE);
        assertEquals("dy", -4.0, d.dy(), TOLERANCE);
        assertEquals(Estimator.Status.OK, d.status());
    }

    /** And in the other three quadrants, because a sign error can be on one axis only. */
    @Test
    public void recoversShiftsInEveryDirection() {
        double[][] truths = {{3, 2}, {-3, 2}, {-3, -2}, {3, -2}, {0, 6}, {-6, 0}};
        for (int i = 0; i < truths.length; i++) {
            Estimator.Displacement d = measure(truths[i][0], truths[i][1]);
            assertEquals("dx of " + truths[i][0] + "," + truths[i][1],
                    truths[i][0], d.dx(), TOLERANCE);
            assertEquals("dy of " + truths[i][0] + "," + truths[i][1],
                    truths[i][1], d.dy(), TOLERANCE);
        }
    }

    /** Fractional truths, which is what the sub-pixel stage exists for. */
    @Test
    public void recoversFractionalShifts() {
        double[][] truths = {{2.5, 1.25}, {-0.75, 3.4}, {0.5, -0.5}, {4.2, -2.8}};
        for (int i = 0; i < truths.length; i++) {
            Estimator.Displacement d = measure(truths[i][0], truths[i][1]);
            assertEquals("dx of " + truths[i][0] + "," + truths[i][1],
                    truths[i][0], d.dx(), TOLERANCE);
            assertEquals("dy of " + truths[i][0] + "," + truths[i][1],
                    truths[i][1], d.dy(), TOLERANCE);
        }
    }

    @Test
    public void zeroShiftIsZero() {
        Estimator.Displacement d = measure(0, 0);
        assertEquals(0.0, d.magnitude(), TOLERANCE);
    }

    /** Swapping the frames negates the answer, or the two estimators cannot be compared at all. */
    @Test
    public void isAntisymmetricUnderSwappingTheFrames() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, 3.5, -2.25);
        Estimator.Displacement ab = SSD.shift(a, b, W, H, BOUND, Frames.Bin.none());
        Estimator.Displacement ba = SSD.shift(b, a, W, H, BOUND, Frames.Bin.none());
        assertEquals("dx", -ab.dx(), ba.dx(), TOLERANCE);
        assertEquals("dy", -ab.dy(), ba.dy(), TOLERANCE);
    }

    /**
     * An exact translation of real-looking texture, recovered without a formula in sight.
     *
     * <p>Two crops of one broadband field: nothing is interpolated, and the content is not generated
     * by an expression the search could be in sympathy with. This is the strongest form of the
     * recovery test.
     */
    @Test
    public void recoversAnExactCropOffsetOfBroadbandTexture() {
        int fw = W + 64;
        float[] field = Synth.texture(fw, H + 64, 4242L, 2);
        int dx = 5;
        int dy = -4;
        Estimator.Displacement d = SSD.shift(
                Synth.crop(field, fw, 32, 32, W, H),
                Synth.crop(field, fw, 32 - dx, 32 - dy, W, H), W, H, BOUND, Frames.Bin.none());
        assertEquals("dx", dx, d.dx(), TOLERANCE);
        assertEquals("dy", dy, d.dy(), TOLERANCE);
    }

    // ------------------------------------------------------------- the bound

    /**
     * A displacement past the bound is reported as sitting on it, with a status.
     *
     * <p>Not truncated in silence: a clipped answer is indistinguishable from a genuine one a couple
     * of pixels short, and it would enter the motion descriptors as though it had been measured.
     *
     * <p>A true 5 px displacement against a 3 px bound. The bound is close enough that the surface
     * inside it still slopes the right way - see
     * {@link #aBoundFarBelowTheTruthLandsWhereverTheLocalSurfaceLeads} for what happens when it does
     * not, which is the limit of what a status can tell anybody.
     */
    @Test
    public void aShiftBeyondTheBoundIsReportedAsSittingOnIt() {
        Estimator.Displacement d = measure(4, -3, 3.0);
        assertEquals(d.toString(), Estimator.Status.AT_SHIFT_BOUND, d.status());
        assertEquals("on the bound", 3.0, d.magnitude(), 1e-6);
        assertTrue("still pointing the way the pixels said: " + d, d.dx() > 0 && d.dy() < 0);
        assertTrue("and it is not a silent zero", d.magnitude() > 0);
    }

    /** The bound is an argument. The same pair with a larger one gets the whole displacement. */
    @Test
    public void theSamePairWithARoomierBoundGetsTheWholeDisplacement() {
        Estimator.Displacement clamped = measure(4, -3, 3.0);
        Estimator.Displacement free = measure(4, -3, BOUND);
        assertEquals(Estimator.Status.AT_SHIFT_BOUND, clamped.status());
        assertEquals(Estimator.Status.OK, free.status());
        assertEquals("dx", 4.0, free.dx(), TOLERANCE);
        assertEquals("dy", -3.0, free.dy(), TOLERANCE);
    }

    /**
     * The limit of the bound status, pinned rather than hidden.
     *
     * <p>A bound of 3 px against a true 10 px displacement does not come back on the bound. It comes
     * back somewhere else entirely - measured here as about 3 px in the wrong direction - and the
     * status says {@code ok}, because as far as the search can tell it settled in a minimum with room
     * to spare. Content with structure repeating every ten pixels or so, which is what a field of
     * cells is, has minima all over a disc that size.
     *
     * <p>No status can catch that; the search cannot see what it never looked at. Two things guard
     * against it instead. The bound comes from the recording as a whole rather than from a guess, and
     * the second estimator lands somewhere different, which is what the agreement column is for.
     */
    @Test
    public void aBoundFarBelowTheTruthLandsWhereverTheLocalSurfaceLeads() {
        Estimator.Displacement d = measure(8, -6, 3.0);
        assertTrue("inside the bound it was given: " + d, d.magnitude() <= 3.0 + 1e-9);
        assertTrue("and nowhere near the truth, which is the point: " + d,
                Math.hypot(d.dx() - 8, d.dy() + 6) > 3.0);
    }

    @Test
    public void aBoundOfZeroOrLessIsRefusedRatherThanTreatedAsUnbounded() {
        try {
            measure(1, 1, 0);
            fail("a search bound of zero was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("bound"));
        }
    }

    // ------------------------------------------------------- the pyramid itself

    /**
     * The level count answers to the sweep budget first and the frame size second.
     *
     * <p>A bound of 6 px needs no pyramid at all; a bound of 48 needs enough levels to bring the
     * exhaustive sweep down to something affordable; and a small frame caps both, because a coarsest
     * level of a few pixels has nothing left in it to match.
     */
    @Test
    public void theLevelCountFollowsTheBoundAndTheFrameSize() {
        assertEquals("a small bound needs no pyramid", 1, PyramidSsd.levelsFor(512, 512, 6));
        assertEquals("nor does a smaller one", 1, PyramidSsd.levelsFor(512, 512, 1));
        assertEquals("12 px halves once", 2, PyramidSsd.levelsFor(512, 512, 12));
        assertEquals("48 px halves three times", 4, PyramidSsd.levelsFor(512, 512, 48));
        assertEquals("never past the ceiling", PyramidSsd.MAX_LEVELS,
                PyramidSsd.levelsFor(4096, 4096, 4000));
        assertEquals("a small frame caps it", 1, PyramidSsd.levelsFor(40, 40, 4000));
        int coarsest = PyramidSsd.widthAt(512, PyramidSsd.levelsFor(512, 512, 1000) - 1);
        assertTrue("the coarsest level stays measurable, was " + coarsest,
                coarsest >= PyramidSsd.MIN_COARSE_SIZE);
    }

    /** Each level is half the one below it, and holds the mean of what it replaced. */
    @Test
    public void eachLevelIsTheMeanOfTheOneBelowIt() {
        float[] plane = Synth.frame(64, 64, 0, 0);
        float[][] pyramid = PyramidSsd.pyramid(plane, 64, 64, 3);
        assertEquals(3, pyramid.length);
        assertEquals(64 * 64, pyramid[0].length);
        assertEquals(32 * 32, pyramid[1].length);
        assertEquals(16 * 16, pyramid[2].length);
        assertEquals("a 2x2 block mean", 0.25f * (plane[0] + plane[1] + plane[64] + plane[65]),
                pyramid[1][0], 1e-3f);
        assertEquals("the mean survives the reduction", mean(pyramid[0]), mean(pyramid[2]), 1e-2);
    }

    /** An odd axis keeps its last row rather than dropping it. */
    @Test
    public void anOddSizedFrameKeepsItsEdge() {
        float[] plane = Synth.frame(33, 17, 0, 0);
        float[][] pyramid = PyramidSsd.pyramid(plane, 33, 17, 3);
        assertEquals(17, PyramidSsd.widthAt(33, 1));
        assertEquals(9, PyramidSsd.heightAt(17, 1));
        assertEquals(17 * 9, pyramid[1].length);
        assertEquals(9 * 5, pyramid[2].length);
    }

    /**
     * Building each frame's pyramid once and searching the pair gives the same answer, to the last
     * bit, as building them per pair.
     *
     * <p>That equality is what lets a run measuring thirty-five pairs over thirty-six frames reduce
     * each frame once. If the cached path could differ, the diagnosis would depend on how many pairs
     * were asked for.
     */
    @Test
    public void theCachedPyramidPathIsBitIdenticalToTheDirectOne() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, 3.25, -1.5);
        Estimator.Displacement direct = SSD.shift(a, b, W, H, BOUND, Frames.Bin.none());
        int levels = PyramidSsd.levelsFor(W, H, BOUND);
        Estimator.Displacement cached = PyramidSsd.shiftOf(
                PyramidSsd.pyramid(a, W, H, levels), PyramidSsd.pyramid(b, W, H, levels),
                W, H, BOUND, Frames.Bin.none());
        assertEquals(Double.doubleToRawLongBits(direct.dx()),
                Double.doubleToRawLongBits(cached.dx()));
        assertEquals(Double.doubleToRawLongBits(direct.dy()),
                Double.doubleToRawLongBits(cached.dy()));
    }

    /** A frame's pyramid survives being searched against several partners. */
    @Test
    public void aCachedPyramidIsNotConsumedByTheFirstPairThatUsesIt() {
        int levels = PyramidSsd.levelsFor(W, H, BOUND);
        float[][] a = PyramidSsd.pyramid(Synth.frame(W, H, 0, 0), W, H, levels);
        float[][] first = PyramidSsd.pyramid(Synth.frame(W, H, 2, 0), W, H, levels);
        float[][] second = PyramidSsd.pyramid(Synth.frame(W, H, 0, -3), W, H, levels);
        assertEquals(2.0, PyramidSsd.shiftOf(a, first, W, H, BOUND, Frames.Bin.none()).dx(),
                TOLERANCE);
        assertEquals(-3.0, PyramidSsd.shiftOf(a, second, W, H, BOUND, Frames.Bin.none()).dy(),
                TOLERANCE);
    }

    // ----------------------------------------------------------- awkward input

    /** Unmeasured pixels are skipped, not read as zero, which would be a hole moving with the frame. */
    @Test
    public void unmeasuredPixelsAreSkippedRatherThanRead() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, 3, -2);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < W; x++) {
                a[y * W + x] = Float.NaN;
                b[y * W + x] = Float.NaN;
            }
        }
        Estimator.Displacement d = SSD.shift(a, b, W, H, BOUND, Frames.Bin.none());
        assertEquals("dx", 3.0, d.dx(), TOLERANCE);
        assertEquals("dy", -2.0, d.dy(), TOLERANCE);
    }

    /** The scale rides on the answer, and a missing one is refused rather than assumed. */
    @Test
    public void theScaleIsRequiredAndIsCarriedOut() {
        Frames.Bin four = Frames.Bin.factor(4);
        assertEquals(four, SSD.shift(Synth.frame(W, H, 0, 0), Synth.frame(W, H, 2, 2),
                W, H, BOUND, four).measuredAt());
        try {
            SSD.shift(Synth.frame(W, H, 0, 0), Synth.frame(W, H, 2, 2), W, H, BOUND, null);
            fail("a displacement was handed out with no scale on it");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("scale"));
        }
    }

    private static double mean(float[] plane) {
        double total = 0;
        int counted = 0;
        for (int i = 0; i < plane.length; i++) {
            if (Float.isNaN(plane[i])) continue;
            total += plane[i];
            counted++;
        }
        return counted == 0 ? Double.NaN : total / counted;
    }
}
