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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T1, carried across from the research repository.
 *
 * <p>Localisability has to separate the two things frame correlation cannot tell apart: structure
 * that can be localised, and a smooth blob that correlates just as well and cannot. The two-band
 * separation and the NaN handling are the parts the survey rests on, and they carry unchanged.
 *
 * <p>What is added in the lift is the scale. Every result here states the effective pixel size it
 * was measured at, because the threshold is calibrated at one scale and reads an order of magnitude
 * differently at another - defect D12, still open, owned by stage 09.
 */
public class LocalisabilityTest {

    private static final int W = 96;
    private static final int H = 96;

    private static final Frames.Bin NATIVE = Frames.Bin.none();

    // ------------------------------------------------ the measurement itself

    /**
     * <b>The measurement the survey rests on.</b> Both frames correlate near 1 with their successors,
     * so plain frame correlation cannot rank them - and ranking by it once chose a saturated
     * fluorescence channel on which two estimators disagreed by 15.7 px.
     */
    @Test
    public void structureIsLocalisableAndASmoothBlobIsNot() {
        float[] textured = Synth.frame(W, H, 0, 0);
        float[] moved = Synth.frame(W, H, 0.25, 0.25);
        float[] smooth = Synth.blob(W, H);

        double corrTextured = Localisability.correlation(textured, moved);
        double corrSmooth = Localisability.correlation(smooth, smooth);
        assertTrue("both must correlate highly, or the test is not about the right thing: "
                        + corrTextured + " and " + corrSmooth,
                corrTextured > 0.9 && corrSmooth > 0.9);

        double sharp = Localisability.ofPair(textured, moved, W, H, NATIVE).value();
        double dull = Localisability.ofPair(smooth, smooth, W, H, NATIVE).value();
        assertTrue("textured " + sharp + " must exceed smooth " + dull, sharp > 10 * dull);
        assertTrue("a smooth blob is poorly localisable: " + dull,
                Localisability.ofPair(smooth, smooth, W, H, NATIVE).poor());
    }

    /**
     * The threshold has to sit in the empty gap the survey measured - 0.031 was the highest of the
     * eleven recordings on which two estimators disagreed by 1.6 to 18.6 px, and 0.086 the lowest of
     * the thirteen on which they agreed to better than 0.7 px. Nothing was measured between them.
     */
    @Test
    public void theWarningThresholdSitsInTheGapTheSurveyMeasured() {
        assertTrue("must not condemn a recording the survey found usable",
                Localisability.WARN_BELOW < 0.086);
        assertTrue("must condemn every recording the survey found unusable",
                Localisability.WARN_BELOW > 0.031);
        assertTrue(Localisability.at(0.031, NATIVE).poor());
        assertFalse(Localisability.at(0.086, NATIVE).poor());
    }

    /** A constant frame has no correlation at all, and NaN must not be reported as poor. */
    @Test
    public void aFlatFrameIsUndefinedRatherThanBad() {
        Localisability.Result r = Localisability.ofPair(
                Synth.flat(W, H, 1500f), Synth.flat(W, H, 1500f), W, H, NATIVE);
        assertTrue("expected NaN, got " + r.value(), Double.isNaN(r.value()));
        assertFalse("NaN must not trip the warning", r.poor());
        assertFalse("and must say it measured nothing", r.defined());
        assertNotNull("even an undefined result carries its scale", r.measuredAt());
    }

    /** Whole-recording localisability is the mean over consecutive pairs, and is defined at n >= 2. */
    @Test
    public void aWholeRecordingAveragesItsPairs() {
        float[][] planes = new float[4][];
        for (int t = 0; t < planes.length; t++) planes[t] = Synth.frame(W, H, 0.3 * t, 0.2 * t);
        Localisability.Result whole = Localisability.of(Synth.source(W, H, planes));

        double sum = 0;
        for (int t = 1; t < planes.length; t++) {
            sum += Localisability.ofPair(planes[t - 1], planes[t], W, H, NATIVE).value();
        }
        assertEquals(sum / (planes.length - 1), whole.value(), 1e-9);

        assertFalse("a single frame has no pair",
                Localisability.of(Synth.source(W, H, planes[0])).defined());
    }

    /** A masked margin must not make two frames look correlated because their masks agree. */
    @Test
    public void naNPixelsAreSkippedInPairs() {
        float[] a = Synth.frame(W, H, 0, 0);
        float[] b = Synth.frame(W, H, 0.25, 0.25);
        float[] maskedA = a.clone();
        float[] maskedB = b.clone();
        for (int x = 0; x < W; x++) {
            maskedA[x] = Float.NaN;
            maskedB[x] = Float.NaN;
        }
        double full = Localisability.correlation(a, b);
        double masked = Localisability.correlation(maskedA, maskedB);
        assertEquals("one masked row must barely move the correlation", full, masked, 0.05);
        assertTrue("and must stay a real number", masked > 0.5);
    }

    // ---------------------------------------------- the scale, added in the lift

    /** Defect D12: no result exists without the scale it was measured at. */
    @Test
    public void everyResultCarriesTheScaleItWasMeasuredAt() {
        Frames.Bin two = Frames.Bin.factor(2);
        assertSame(two, Localisability.ofPair(Synth.frame(W, H, 0, 0),
                Synth.frame(W, H, 1, 0), W, H, two).measuredAt());
        assertSame(two, Localisability.at(0.12, two).measuredAt());
        assertSame(two, Localisability.of(Synth.source(W, H, two,
                Synth.frame(W, H, 0, 0), Synth.frame(W, H, 1, 0))).measuredAt());
    }

    /** A missing scale is refused rather than defaulted. A default is how a scale goes stale. */
    @Test
    public void aResultWithoutAScaleCannotBeMade() {
        try {
            Localisability.at(0.12, null);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("D12"));
        }
        try {
            Localisability.ofPair(Synth.frame(W, H, 0, 0), Synth.frame(W, H, 1, 0), W, H, null);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("scale"));
        }
    }

    /**
     * Labelling a measurement with a scale it was not taken at is exactly how D12 happened, so it is
     * refused rather than believed.
     */
    @Test
    public void aScaleThatDisagreesWithTheFramesIsRefused() {
        FrameSource binned = Synth.source(W, H, Frames.Bin.factor(2),
                Synth.frame(W, H, 0, 0), Synth.frame(W, H, 1, 0));
        try {
            Localisability.of(binned, Frames.Bin.none());
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("not comparable across two scales"));
        }
        assertTrue("the scale the frames really are at is accepted",
                Localisability.of(binned, Frames.Bin.factor(2)).defined());
    }

    /**
     * The same recording reads differently at two scales, which is the whole of D12 in one
     * assertion. Neither number is wrong; comparing them with one threshold is.
     */
    @Test
    public void theSameContentReadsDifferentlyAtTwoScales() {
        float[][] planes = new float[4][];
        for (int t = 0; t < planes.length; t++) planes[t] = Synth.frame(W, H, 0.3 * t, 0.2 * t);
        Localisability.Result atNative = Localisability.of(Synth.source(W, H, NATIVE, planes));

        Frames.Bin two = Frames.Bin.factor(2);
        float[][] binned = new float[planes.length][];
        for (int t = 0; t < planes.length; t++) binned[t] = two.apply(planes[t], W, H);
        Localisability.Result atTwo =
                Localisability.of(Synth.source(W / 2, H / 2, two, binned));

        assertTrue("both must be measurable", atNative.defined() && atTwo.defined());
        assertTrue("one pixel after binning is a larger relative displacement, so the fall in"
                        + " correlation is larger: native " + atNative.value() + " vs binned "
                        + atTwo.value(),
                atTwo.value() > atNative.value());
        assertEquals(1, atNative.measuredAt().factor());
        assertEquals(2, atTwo.measuredAt().factor());
    }

    @Test
    public void resultsCompareOnValueAndScaleTogether() {
        assertEquals(Localisability.at(0.2, Frames.Bin.factor(2)),
                Localisability.at(0.2, Frames.Bin.factor(2)));
        assertEquals(Localisability.at(0.2, Frames.Bin.factor(2)).hashCode(),
                Localisability.at(0.2, Frames.Bin.factor(2)).hashCode());
        assertFalse("the same number at two scales is not the same measurement",
                Localisability.at(0.2, Frames.Bin.factor(2))
                        .equals(Localisability.at(0.2, Frames.Bin.none())));
        assertTrue(Localisability.at(0.2, Frames.Bin.factor(2)).toString()
                .contains("2 x 2 pixel mean"));
    }
}
