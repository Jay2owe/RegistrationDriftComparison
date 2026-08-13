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
import static org.junit.Assert.assertTrue;

/**
 * T7, and defect D8: a pair with nothing in it is answered "it did not move", not "45 pixels".
 *
 * <h2>The defect this exists to keep dead</h2>
 *
 * <p>A search that sweeps candidate positions and takes the first one better than what it has seen so
 * far needs something to compare against. Starting from "infinitely bad" and improving on it works on
 * every pair that has texture in it, and fails completely on one that does not: on a blank, saturated
 * or all-background pair every candidate scores <em>identically</em>, so the first one examined wins,
 * and the first one examined is a corner of the search box. Measured before this was understood: a
 * flat 192x192 pair produced a confident 45 px displacement.
 *
 * <p>The fix has two parts and both are asserted here. Zero is the incumbent and it is <em>scored</em>
 * rather than assumed; and a candidate has to beat it <em>strictly</em>, so a tie leaves zero in
 * place.
 *
 * <h2>Why it matters more here than it did upstream</h2>
 *
 * <p>Upstream this was one bad alignment in a run the user was watching. Here the answer feeds a
 * motion label, a severity, a registrability verdict and an engine recommendation, and a plugin that
 * fingerprints whatever somebody opens meets featureless frames constantly - an empty corner of a
 * plate, a channel that was not illuminated, a saturated field. One confident wrong number poisons
 * the whole recommendation.
 */
public class CoarseSearchFeaturelessTest {

    /** The size the defect was measured at. */
    private static final int W = 192;
    private static final int H = 192;
    /**
     * A bound large enough that the corner of the search box is unmistakable. Half of it, 22.5 px,
     * is still far outside any tolerance an answer of zero could hide in.
     */
    private static final double BOUND = 45;

    private static final Frames.Bin SCALE = Frames.Bin.none();

    // --------------------------------------------------------- the flat pair

    /** A flat pair, from the estimator that sweeps a search box. Exactly zero, with a reason. */
    @Test
    public void aFlatPairIsTheIdentityFromThePyramidSearch() {
        Estimator.Displacement d = new PyramidSsd().shift(
                Synth.flat(W, H, 800f), Synth.flat(W, H, 800f), W, H, BOUND, SCALE);
        assertExactlyZero("pyramid sum of squared differences", d);
    }

    /** And from the estimator that does not sweep anything, which has its own way to get this wrong. */
    @Test
    public void aFlatPairIsTheIdentityFromPhaseCorrelation() {
        Estimator.Displacement d = new PhaseCorrelation().shift(
                Synth.flat(W, H, 800f), Synth.flat(W, H, 800f), W, H, BOUND, SCALE);
        assertExactlyZero("phase correlation", d);
    }

    /** A saturated field is the same defect wearing a different hat: no variation to follow. */
    @Test
    public void aSaturatedPairIsTheIdentityFromBoth() {
        float[] a = Synth.flat(W, H, 65535f);
        float[] b = Synth.flat(W, H, 65535f);
        assertExactlyZero("pyramid sum of squared differences",
                new PyramidSsd().shift(a, b, W, H, BOUND, SCALE));
        assertExactlyZero("phase correlation",
                new PhaseCorrelation().shift(a, b, W, H, BOUND, SCALE));
    }

    /** A frame with no measured pixels at all - a mask that covered everything. */
    @Test
    public void aPairWithNothingMeasuredInItIsTheIdentityFromBoth() {
        float[] a = Synth.flat(W, H, Float.NaN);
        float[] b = Synth.flat(W, H, Float.NaN);
        assertExactlyZero("pyramid sum of squared differences",
                new PyramidSsd().shift(a, b, W, H, BOUND, SCALE));
        assertExactlyZero("phase correlation",
                new PhaseCorrelation().shift(a, b, W, H, BOUND, SCALE));
    }

    /**
     * The two frames are flat at different levels, so they differ everywhere and equally.
     *
     * <p>The nastier form of the defect: every candidate now scores the same non-zero cost rather
     * than the same zero, so a sweep comparing against positive infinity still moves off zero.
     */
    @Test
    public void twoFlatFramesAtDifferentLevelsAreStillTheIdentity() {
        float[] a = Synth.flat(W, H, 800f);
        float[] b = Synth.flat(W, H, 1200f);
        assertExactlyZero("pyramid sum of squared differences",
                new PyramidSsd().shift(a, b, W, H, BOUND, SCALE));
        assertExactlyZero("phase correlation",
                new PhaseCorrelation().shift(a, b, W, H, BOUND, SCALE));
    }

    /**
     * Whatever the bound is. The old answer was a fixed fraction of the search box, so it moved when
     * the box did - which is the signature of an answer about the box rather than about the pixels.
     */
    @Test
    public void theAnswerDoesNotFollowTheSizeOfTheSearchBox() {
        float[] a = Synth.flat(W, H, 800f);
        float[] b = Synth.flat(W, H, 800f);
        double[] bounds = {1, 5, 12, 45, 90};
        for (int i = 0; i < bounds.length; i++) {
            assertExactlyZero("pyramid at bound " + bounds[i],
                    new PyramidSsd().shift(a, b, W, H, bounds[i], SCALE));
            assertExactlyZero("phase correlation at bound " + bounds[i],
                    new PhaseCorrelation().shift(a, b, W, H, bounds[i], SCALE));
        }
    }

    // ------------------------------------------- and it has not been overdone

    /**
     * A pair with structure that genuinely did not move is also zero - but measured, not refused.
     *
     * <p>The two look the same in the numbers and are entirely different findings, which is why the
     * status is on the answer rather than left for a reader to infer from a zero.
     */
    @Test
    public void aStillPairWithStructureIsZeroAndSaysItWasMeasured() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, 0, 0);
        Estimator.Displacement ssd = new PyramidSsd().shift(a, b, W, H, BOUND, SCALE);
        Estimator.Displacement phase = new PhaseCorrelation().shift(a, b, W, H, BOUND, SCALE);
        assertEquals("pyramid " + ssd, 0.0, ssd.magnitude(), 0.10);
        assertEquals("phase " + phase, 0.0, phase.magnitude(), 0.10);
        assertTrue("this one really was measured", ssd.defined() && phase.defined());
        assertEquals(Estimator.Status.OK, ssd.status());
        assertEquals(Estimator.Status.OK, phase.status());
    }

    /** And a pair that did move is still recovered with the big bound in place. */
    @Test
    public void theStrictRuleDoesNotStopARealDisplacementBeingFound() {
        Estimator.Displacement d = new PyramidSsd().shift(
                Synth.frame(W, H, 0, 0), Synth.frame(W, H, 6, -4), W, H, BOUND, SCALE);
        assertEquals("dx", 6.0, d.dx(), 0.10);
        assertEquals("dy", -4.0, d.dy(), 0.10);
    }

    // ------------------------------------------------ and through the run path

    /**
     * A featureless recording does not report perfect agreement between the two estimators.
     *
     * <p>Both answer zero, so their answers are a distance of zero apart, and reporting that as
     * {@code agreement_px = 0} would turn "neither of us could read this" into the strongest
     * confidence number the column can hold. A pair neither estimator measured is left out of the
     * median instead, and the count of compared pairs says how many were left.
     */
    @Test
    public void aFeaturelessRecordingReportsNoAgreementRatherThanPerfectAgreement() {
        FrameSource flat = Synth.source(W, H, SCALE,
                Synth.flat(W, H, 800f), Synth.flat(W, H, 800f), Synth.flat(W, H, 800f));
        Estimators.Result result = Estimators.measure(flat, new int[][]{{0, 1}, {1, 2}},
                BOUND, 1, PairScheduler.Progress.NONE, Cancellation.never());
        assertEquals(2, result.measuredPairs());
        assertEquals("no pair was read by both", 0, result.comparedPairs());
        assertTrue("and the agreement is undefined, not zero: " + result.agreementPx(),
                Double.isNaN(result.agreementPx()));
        for (Estimators.PairEstimate pair : result.pairs()) {
            assertExactlyZero("pyramid on " + pair.from() + "->" + pair.to(),
                    pair.byPyramidSsd());
            assertExactlyZero("phase on " + pair.from() + "->" + pair.to(),
                    pair.byPhaseCorrelation());
            assertFalse("and it is not counted as a comparison", pair.compared());
        }
    }

    // ------------------------------------------------------------- the check

    private static void assertExactlyZero(String what, Estimator.Displacement d) {
        assertEquals(what + " must answer exactly zero on a featureless pair, and answered " + d,
                0.0, d.dx(), 0.0);
        assertEquals(what + " must answer exactly zero on a featureless pair, and answered " + d,
                0.0, d.dy(), 0.0);
        assertFalse(what + " returned a number rather than a decision: " + d,
                Double.isNaN(d.dx()) || Double.isNaN(d.dy()));
        assertEquals(what + " must say why it answered zero, and said " + d.status(),
                Estimator.Status.NO_STRUCTURE, d.status());
        assertFalse("a refusal to guess is not a measurement", d.defined());
    }
}
