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
import regdrift.Cancellation;
import regdrift.internal.PairScheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Where the windows fall, which pairs that makes, and the one search bound they
 * are all measured with.
 *
 * <p>The claim the sampler exists for is that its cost does not grow with the
 * recording, so that claim is asserted rather than assumed: the same 35 frame
 * pairs come out of a 48-frame recording and a 500-frame one.
 */
public class WindowSamplerTest {

    private static final double EPS = 1e-9;

    // ------------------------------------------------------------ placement

    /** The measured default, on the length every library recording has. */
    @Test
    public void theDefaultLandsWhereTheMeasurementPutIt() {
        WindowSampler.Plan plan = WindowSampler.measured().plan(48);

        assertArray(new int[]{0, 18, 36}, plan.windowStarts());
        assertEquals(3, plan.windows());
        assertEquals(12, plan.framesPerWindow());
        assertEquals(2, plan.bridgeCount());
        assertEquals(35, plan.measuredPairs());
        assertEquals(3 * 11 + 2, plan.measuredPairs());
        assertFalse("three windows of twelve do not cover a 48-frame recording",
                plan.everyConsecutivePair());
        assertFalse(plan.reduced());
    }

    /** No window may start before the one before it has finished. */
    @Test
    public void theWindowsDoNotOverlap() {
        for (int frames = 36; frames <= 600; frames += 7) {
            WindowSampler.Plan plan = WindowSampler.measured().plan(frames);
            int[] starts = plan.windowStarts();
            for (int w = 1; w < plan.windows(); w++) {
                assertTrue("windows " + (w - 1) + " and " + w + " overlap at " + frames
                                + " frames: " + starts[w - 1] + " and " + starts[w],
                        starts[w] >= starts[w - 1] + plan.framesPerWindow());
            }
            assertTrue("the last window runs past the end at " + frames + " frames",
                    plan.windowEnd(plan.windows() - 1) <= frames - 1);
        }
    }

    /**
     * The whole point of the sampler: the cost is the same on a short recording
     * and a long one.
     *
     * <p>Asserted rather than assumed. Everything the sampler was measured on is
     * 48 frames or 108, so the 500-frame case is an extrapolation - which is
     * exactly why the arithmetic behind it is pinned here.
     */
    @Test
    public void theCostDoesNotGrowWithTheRecording() {
        WindowSampler sampler = WindowSampler.measured();
        assertEquals(35, sampler.plan(48).measuredPairs());
        assertEquals(35, sampler.plan(500).measuredPairs());
        assertEquals(35, sampler.plan(5000).measuredPairs());

        assertEquals(70, sampler.plan(48).estimateCount());
        assertEquals(70, sampler.plan(5000).estimateCount());

        // The comparison the constant-cost claim is against.
        assertEquals(47, WindowSampler.everyConsecutivePair().plan(48).measuredPairs());
        assertEquals(499, WindowSampler.everyConsecutivePair().plan(500).measuredPairs());
    }

    /** {@code W*(K-1) + (W-1)} pairs, for every shape that fits. */
    @Test
    public void theCostFormulaHolds() {
        int[][] shapes = {{2, 8}, {3, 8}, {2, 12}, {3, 12}, {4, 8}, {4, 12}, {1, 12}, {5, 20}};
        for (int[] shape : shapes) {
            int w = shape[0];
            int k = shape[1];
            for (int frames : new int[]{w * k, w * k + 3, 500, 5000}) {
                WindowSampler.Plan plan = WindowSampler.of(w, k).plan(frames);
                assertEquals("W" + w + " K" + k + " on " + frames + " frames",
                        w * (k - 1) + (w - 1), plan.measuredPairs());
                assertEquals(w * (k - 1), plan.windowPairCount());
                assertEquals(w - 1, plan.bridgeCount());
            }
        }
    }

    /** Bridge {@code i} joins the last frame of window {@code i} to the first of window {@code i+1}. */
    @Test
    public void eachBridgeSpansOneGap() {
        WindowSampler.Plan plan = WindowSampler.measured().plan(48);
        int[] starts = plan.windowStarts();
        assertEquals(2, plan.bridgeCount());
        for (int i = 0; i < plan.bridgeCount(); i++) {
            assertArray(new int[]{starts[i] + 11, starts[i + 1]}, plan.bridge(i));
        }
        assertArray(new int[]{11, 18}, plan.bridge(0));
        assertArray(new int[]{29, 36}, plan.bridge(1));
    }

    /** Window pairs first, in frame order, then the bridges. Everything downstream indexes on it. */
    @Test
    public void thePairsComeInTheOrderTheApiPromises() {
        WindowSampler.Plan plan = WindowSampler.measured().plan(48);
        int[][] pairs = plan.pairs();
        assertEquals(35, pairs.length);
        int[] starts = plan.windowStarts();
        for (int w = 0; w < 3; w++) {
            assertEquals(w * 11, plan.firstPairOfWindow(w));
            for (int j = 1; j < 12; j++) {
                assertArray(new int[]{starts[w] + j - 1, starts[w] + j}, pairs[w * 11 + j - 1]);
            }
        }
        assertEquals(33, plan.windowPairCount());
        assertEquals(33, plan.bridgePairIndex(0));
        assertEquals(34, plan.bridgePairIndex(1));
        assertArray(plan.bridge(0), pairs[33]);
        assertArray(plan.bridge(1), pairs[34]);
    }

    /** {@code pairs()} hands out a copy, so a caller cannot rewrite the plan. */
    @Test
    public void thePlanIsNotHandedOutForEditing() {
        WindowSampler.Plan plan = WindowSampler.measured().plan(48);
        plan.pairs()[0][0] = 999;
        plan.windowStarts()[0] = 999;
        plan.bridge(0)[0] = 999;
        assertArray(new int[]{0, 1}, plan.pairs()[0]);
        assertArray(new int[]{0, 18, 36}, plan.windowStarts());
        assertArray(new int[]{11, 18}, plan.bridge(0));
    }

    // --------------------------------------------------- the edges and falls

    /** {@code windows=0} measures everything and divides by nothing. */
    @Test
    public void zeroWindowsMeansEveryConsecutivePairAndNoBridges() {
        WindowSampler sampler = WindowSampler.of(0, 12);
        assertEquals(0, sampler.windows());
        for (int frames : new int[]{2, 3, 48, 501}) {
            WindowSampler.Plan plan = sampler.plan(frames);
            assertTrue(plan.everyConsecutivePair());
            assertEquals(0, plan.bridgeCount());
            assertEquals(frames - 1, plan.measuredPairs());
            assertEquals(1, plan.windows());
            assertArray(new int[]{0}, plan.windowStarts());
            int[][] pairs = plan.pairs();
            for (int t = 1; t < frames; t++) assertArray(new int[]{t - 1, t}, pairs[t - 1]);
        }
    }

    /** One window has no gap to bridge, and the placement must not divide by {@code W-1}. */
    @Test
    public void oneWindowIsPlacedAtTheStartAndBridgesNothing() {
        WindowSampler.Plan plan = WindowSampler.of(1, 12).plan(500);
        assertArray(new int[]{0}, plan.windowStarts());
        assertEquals(0, plan.bridgeCount());
        assertEquals(11, plan.measuredPairs());
        assertFalse(plan.everyConsecutivePair());
    }

    /**
     * A recording shorter than {@code W*K} sheds windows rather than shortening
     * them or letting them overlap, and says in the provenance what it actually
     * did.
     */
    @Test
    public void aShortRecordingLosesWindowsRatherThanOverlappingThem() {
        WindowSampler sampler = WindowSampler.measured();

        WindowSampler.Plan two = sampler.plan(35);
        assertEquals(2, two.windows());
        assertEquals(12, two.framesPerWindow());
        assertArray(new int[]{0, 23}, two.windowStarts());
        assertEquals(2 * 11 + 1, two.measuredPairs());
        assertTrue(two.reduced());
        assertTrue(two.provenance(), two.provenance().contains("fewer windows"));

        // Down to one window, the exact measurement is cheaper than the sample would have been.
        WindowSampler.Plan one = sampler.plan(20);
        assertEquals(1, one.windows());
        assertEquals(20, one.framesPerWindow());
        assertEquals(19, one.measuredPairs());
        assertTrue(one.everyConsecutivePair());
        assertTrue(one.reduced());

        // Shorter than a single window.
        WindowSampler.Plan tiny = sampler.plan(5);
        assertEquals(1, tiny.windows());
        assertEquals(5, tiny.framesPerWindow());
        assertEquals(4, tiny.measuredPairs());
        assertTrue(tiny.reduced());

        // Every fall-back stays within the recording and never costs more than the exact answer.
        for (int frames = 2; frames <= 40; frames++) {
            WindowSampler.Plan plan = sampler.plan(frames);
            assertTrue(frames + " frames: " + plan.provenance(),
                    plan.measuredPairs() <= Math.max(frames - 1, 35));
            assertTrue(plan.windowEnd(plan.windows() - 1) <= frames - 1);
        }
    }

    /** An explicit request is not grown just because the recording is long. */
    @Test
    public void anExplicitlySmallSamplerStaysSmall() {
        WindowSampler.Plan plan = WindowSampler.of(1, 12).plan(500);
        assertEquals(12, plan.framesPerWindow());
        assertFalse(plan.reduced());
    }

    /** A single frame has no transition, and is said so rather than measured as nothing. */
    @Test
    public void aRecordingWithNoTransitionIsRefusedInWords() {
        try {
            WindowSampler.measured().plan(1);
            fail("a one-frame recording has no transition to measure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("two frames"));
        }
        try {
            WindowSampler.of(3, 1);
            fail("a one-frame window holds no transition");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("2 frames"));
        }
        try {
            WindowSampler.of(-1, 12);
            fail("a negative window count is not a sampler");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    // ---------------------------------------------------------- the bound

    /**
     * <b>The bound is one number for the whole recording.</b>
     *
     * <p>The fixture is two windows of the same recording: one where the field
     * creeps a pixel a frame, one where it lurches twenty. The quiet window is
     * measured with the bound the busy one earned.
     *
     * <p>The counterfactual is asserted beside it, because it is the reason the
     * rule exists: a bound derived from the quiet window alone is 13.5 px, and at
     * that bound every step of the busy window comes back sitting on the bound
     * rather than measured. The descriptors would then be describing the search
     * box.
     */
    @Test
    public void oneBoundIsDerivedForTheWholeRecordingAndReachesEveryWindow() {
        FrameSource frames = quietThenBusy();
        WindowSampler.Plan plan = WindowSampler.of(2, 6).plan(12);
        assertArray(new int[]{0, 6}, plan.windowStarts());

        WindowSampler.Bound global = WindowSampler.bound(frames, plan, 1,
                PairScheduler.Progress.NONE, Cancellation.never());

        assertEquals(11, global.measuredPairs());
        assertEquals(0, global.refusedPairs());
        assertEquals("the busy window sets the largest step", 20.0,
                global.largestWindowStepPx(), 0.5);
        assertEquals("nothing moved across the gap", 0.0, global.largestBridgeStepPx(), 0.5);
        assertEquals(WindowSampler.boundFor(global.largestStepPx()), global.px(), EPS);
        assertEquals(1.5 * 20.0 + 12.0, global.px(), 1.0);
        assertFalse(global.atFloor());

        // The quiet window on its own would have earned a much smaller bound.
        FrameSource quiet = firstSix(frames);
        WindowSampler.Bound perWindow = WindowSampler.bound(quiet,
                WindowSampler.everyConsecutivePair().plan(6), 1,
                PairScheduler.Progress.NONE, Cancellation.never());
        assertEquals(1.5 * 1.0 + 12.0, perWindow.px(), 1.0);
        assertTrue("a per-window bound is smaller than the global one here",
                perWindow.px() < global.px());

        // With the global bound every pair in both windows is measured, quiet and busy alike.
        Estimators.Result measured = Estimators.measure(frames, plan.pairs(), global.px(), 1,
                PairScheduler.Progress.NONE, Cancellation.never());
        for (Estimators.PairEstimate pair : measured.pairs()) {
            assertEquals(pair.toString(), Estimator.Status.OK,
                    pair.byPhaseCorrelation().status());
        }

        // With the quiet window's own bound the busy window is clamped instead of measured.
        Estimators.Result clamped = Estimators.measure(frames, plan.pairs(), perWindow.px(), 1,
                PairScheduler.Progress.NONE, Cancellation.never());
        int atBound = 0;
        for (int i = plan.firstPairOfWindow(1); i < plan.windowPairCount(); i++) {
            if (clamped.pairs().get(i).byPhaseCorrelation().status()
                    == Estimator.Status.AT_SHIFT_BOUND) {
                atBound++;
            }
        }
        assertEquals("every busy step is clamped by a bound the quiet window set", 5, atBound);
    }

    /** A bridge that moves further than any step still raises the one bound. */
    @Test
    public void aMovementInsideAGapRaisesTheBoundForEveryWindow() {
        FrameSource frames = jumpInTheGap();
        WindowSampler.Plan plan = WindowSampler.of(2, 6).plan(14);
        WindowSampler.Bound bound = WindowSampler.bound(frames, plan, 1,
                PairScheduler.Progress.NONE, Cancellation.never());

        assertEquals("nothing inside a window moved far", 1.0, bound.largestWindowStepPx(), 0.5);
        assertEquals("the gap did", 30.0, bound.largestBridgeStepPx(), 0.5);
        assertEquals(bound.largestBridgeStepPx(), bound.largestStepPx(), EPS);
        assertEquals(1.5 * 30.0 + 12.0, bound.px(), 1.0);
        assertTrue(bound.provenance(), bound.provenance().contains("whole recording"));
    }

    /** A recording nothing can be read from gets the floor, and says so. */
    @Test
    public void anUnreadableRecordingGetsTheFloorAndSaysWhy() {
        float[][] planes = new float[8][];
        for (int t = 0; t < planes.length; t++) planes[t] = Synth.flat(64, 64, 700f);
        FrameSource frames = Synth.source(64, 64, planes);

        WindowSampler.Plan plan = WindowSampler.of(2, 4).plan(8);
        WindowSampler.Bound bound = WindowSampler.bound(frames, plan, 1,
                PairScheduler.Progress.NONE, Cancellation.never());

        assertEquals(0, bound.measuredPairs());
        assertEquals(plan.measuredPairs(), bound.refusedPairs());
        assertEquals(WindowSampler.MIN_SHIFT_PX, bound.px(), EPS);
        assertTrue(bound.atFloor());
        assertTrue(bound.provenance(), bound.provenance().contains("could be read"));
    }

    /** The floor and the ceiling, in the arithmetic itself. */
    @Test
    public void theBoundIsFlooredAndCapped() {
        assertEquals(WindowSampler.MIN_SHIFT_PX, WindowSampler.boundFor(0), EPS);
        assertEquals(WindowSampler.MIN_SHIFT_PX, WindowSampler.boundFor(Double.NaN), EPS);
        assertEquals(WindowSampler.MAX_SHIFT_PX, WindowSampler.boundFor(10000), EPS);
        assertEquals(1.5 * 40 + 12, WindowSampler.boundFor(40), EPS);
    }

    /** Serial, two-worker and full-width runs land on the same bits. */
    @Test
    public void theBoundIsTheSameHoweverManyWorkersRunIt() {
        FrameSource frames = quietThenBusy();
        WindowSampler.Plan plan = WindowSampler.of(2, 6).plan(12);
        long serial = Double.doubleToRawLongBits(WindowSampler.bound(frames, plan, 1,
                PairScheduler.Progress.NONE, Cancellation.never()).px());
        long two = Double.doubleToRawLongBits(WindowSampler.bound(frames, plan, 2,
                PairScheduler.Progress.NONE, Cancellation.never()).px());
        long wide = Double.doubleToRawLongBits(WindowSampler.bound(frames, plan, 0,
                PairScheduler.Progress.NONE, Cancellation.never()).px());
        assertEquals(serial, two);
        assertEquals(serial, wide);
    }

    /** Every pair the plan names is measured, and progress counts them all. */
    @Test
    public void passOneMeasuresExactlyThePairsTheSamplerWill() {
        FrameSource frames = quietThenBusy();
        WindowSampler.Plan plan = WindowSampler.of(2, 6).plan(12);
        final int[] seen = new int[2];
        WindowSampler.bound(frames, plan, 1, new PairScheduler.Progress() {
            @Override
            public void update(int done, int total) {
                seen[0] = done;
                seen[1] = total;
            }
        }, Cancellation.never());
        assertEquals(plan.measuredPairs(), seen[0]);
        assertEquals(plan.measuredPairs(), seen[1]);
    }

    /** The bound carries the scale its pixels were measured at. */
    @Test
    public void theBoundCarriesTheScaleItWasMeasuredAt() {
        FrameSource frames = quietThenBusy();
        WindowSampler.Plan plan = WindowSampler.of(2, 6).plan(12);
        WindowSampler.Bound bound = WindowSampler.bound(frames, plan, 1,
                PairScheduler.Progress.NONE, Cancellation.never());
        assertEquals(Frames.Bin.none(), bound.measuredAt());
        assertTrue(bound.provenance(), bound.provenance().contains("native resolution"));
        assertEquals(plan, bound.plan());
    }

    // ---------------------------------------------------------- the fixtures

    private static final int W = 128;
    private static final int H = 128;
    private static final int FIELD = 320;
    private static final int ORIGIN = 96;

    /**
     * Twelve frames: the first six creep a pixel a frame, the last six lurch
     * twenty pixels each, and nothing moves across the gap between them.
     */
    private static FrameSource quietThenBusy() {
        int[] x = new int[12];
        for (int t = 0; t < 6; t++) x[t] = t;
        for (int t = 6; t < 12; t++) x[t] = 5 + ((t - 6) % 2 == 0 ? 0 : 20);
        return sourceFor(x, new int[12]);
    }

    /** Fourteen frames, two windows of six, with a thirty-pixel jump inside the gap. */
    private static FrameSource jumpInTheGap() {
        int[] x = new int[14];
        for (int t = 0; t < 6; t++) x[t] = t;
        for (int t = 6; t < 14; t++) x[t] = 33 + (t - 6);
        return sourceFor(x, new int[14]);
    }

    private static FrameSource sourceFor(int[] cumulativeX, int[] cumulativeY) {
        float[] field = Synth.texture(FIELD, FIELD, 20260813L, 2);
        float[][] planes = new float[cumulativeX.length][];
        for (int t = 0; t < planes.length; t++) {
            planes[t] = Synth.crop(field, FIELD,
                    ORIGIN - cumulativeX[t], ORIGIN - cumulativeY[t], W, H);
        }
        return Synth.source(W, H, planes);
    }

    /** The first six frames of a source, as a source of its own. */
    private static FrameSource firstSix(final FrameSource all) {
        float[][] planes = new float[6][];
        for (int t = 0; t < 6; t++) planes[t] = all.plane(t);
        return Synth.source(all.width(), all.height(), all.bin(), planes);
    }

    private static void assertArray(int[] expected, int[] actual) {
        assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
    }
}
