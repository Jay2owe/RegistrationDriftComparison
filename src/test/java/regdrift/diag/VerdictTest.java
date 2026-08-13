/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import ij.ImagePlus;
import org.junit.Test;
import regdrift.Cancellation;
import regdrift.internal.PairScheduler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The four verdicts, the wording each carries, and the two rules that decide what the verdict is
 * allowed to route on.
 *
 * <h2>What this stage decided, and why the tests look like this</h2>
 *
 * <p>Defect D12 asked whether {@link Localisability#WARN_BELOW} survives being measured at a defined
 * scale. It does not. The survey's binning factor was recovered - it ran at bin 4 - and the twelve
 * library recordings were re-measured at bins 1, 2, 4 and 8; at every one of them the threshold
 * either warns on recordings that register well or fails to warn on recordings that do not, and no
 * cut anywhere separates the two. The measurement is {@code docs/D12_MEASUREMENT.md}.
 *
 * <p>So <b>localisability is reported with its scale and is not thresholded</b>, and the tests below
 * assert that as a property rather than trusting it: content measuring well under the shipped
 * threshold still comes back {@code registrable}, and the compiled verdict never calls the method
 * that applies the threshold.
 *
 * <p><b>What that costs, stated plainly.</b> A smooth featureless blob measures 0.0012 and comes back
 * {@code registrable}, because the two estimators agree about it. That is the price of dropping a
 * threshold that warned on the four best-registering recordings in the library, and it is asserted
 * here so nobody discovers it by surprise. The signal that does order the library - estimator
 * agreement - is what the verdict routes on instead.
 */
public class VerdictTest {

    private static final int SIDE = 96;
    private static final Frames.Bin NATIVE = Frames.Bin.none();

    // ------------------------------------------------------------ the four verdicts

    /**
     * {@code estimators_disagree} is the verdict that says "I cannot tell", and it is the one
     * {@code 10_unresolved_methods_disagree} has to produce.
     *
     * <p>The fixture is a sum of a few sinusoids stepped seven pixels a frame. Phase correlation
     * normalises every frequency to equal weight and cannot localise narrow-band content - a
     * documented property of the method, recorded on {@link Synth} - while the pyramid search
     * recovers the step exactly. Both return a measurement; the measurements are different; and that
     * difference is precisely what the verdict exists to report.
     */
    @Test
    public void twoMethodsThatDescribeDifferentMovementProduceEstimatorsDisagree() {
        Fingerprint measured = fingerprintOf(narrowBandSteppedFar());
        assertTrue("the fixture has to make them part company by more than the limit: "
                + measured.agreementPx(), measured.agreementPx() > Verdict.AGREEMENT_LIMIT_PX);

        Verdict verdict = Verdict.of(measured);
        assertEquals(regdrift.Verdict.ESTIMATORS_DISAGREE, verdict.kind());
        assertEquals("estimators_disagree", verdict.tableValue());
        assertTrue(verdict.text(), verdict.text().contains("cannot tell"));
        assertTrue("the number and the limit both have to be in the sentence: " + verdict.text(),
                verdict.text().contains("px per transition")
                        && verdict.text().contains("limit"));
        assertFalse("and it is not a clear result", verdict.clear());
    }

    /**
     * When neither method can read a single pair, the verdict warns - and warns is all it does.
     * Defect D7.
     */
    @Test
    public void aRecordingNeitherMethodCanReadWarnsAndDoesNotRefuse() {
        Fingerprint measured = fingerprintOf(flat());
        assertEquals("both estimators declined every pair", 0, measured.comparedPairs());
        assertTrue("the measurement still happened", measured.measuredPairs() > 0);

        Verdict verdict = Verdict.of(measured);
        assertEquals(regdrift.Verdict.WARN_LOW_STRUCTURE, verdict.kind());
        assertEquals("warn_low_structure", verdict.tableValue());
        assertTrue(verdict.text(),
                verdict.text().contains("no method is likely to localize this channel well"));
        assertTrue("it says outright that it is not a refusal: " + verdict.text(),
                verdict.text().contains("warning and not a refusal"));
    }

    /**
     * Defect D7 and defect D12 in one fixture: noise-free synthetic content measuring
     * <b>below</b> the survey's threshold, whose two estimators agree to a fifth of a pixel, comes
     * back {@code registrable}.
     *
     * <p>Under the shipped threshold this recording would have been told that no method is likely to
     * localize it, while a pyramid search recovers its quarter-pixel steps to three decimal places.
     * That is the failure D12 describes, reproduced in a test rather than in a library.
     */
    @Test
    public void contentBelowTheSurveysThresholdStillComesBackRegistrable() {
        Fingerprint measured = fingerprintOf(narrowBandSteppedFinely());
        double localisability = measured.localisability().value();
        assertTrue("the fixture has to sit below the shipped threshold, and measured "
                        + localisability,
                localisability < Localisability.WARN_BELOW);
        assertTrue("the shipped threshold would have warned about it",
                measured.localisability().poor());

        Verdict verdict = Verdict.of(measured);
        assertEquals("a low measurement is not a warning any more - see defect D12",
                regdrift.Verdict.REGISTRABLE, verdict.kind());
        assertTrue(verdict.clear());
        assertTrue("and the number is still reported, with its scale: " + verdict.text(),
                verdict.text().contains("Localisability measured")
                        && verdict.text().contains("at bin " + measured.measuredAt().factor()));
        assertTrue("with the reason it is not thresholded: " + verdict.text(),
                verdict.text().contains("is not thresholded in this version"));
    }

    /**
     * A smooth featureless blob measures 0.0012 - two hundredths of the shipped threshold - and is
     * called registrable, because the two independent methods agree about it.
     *
     * <p>Asserted rather than glossed over. It is what dropping the threshold costs, it is the
     * strongest argument for closing D12 with a wider calibration in v0.2.0, and a test that
     * pretended otherwise would be the same kind of quiet claim this plugin exists to replace.
     */
    @Test
    public void aSmoothBlobMeasuresFarBelowTheThresholdAndIsStillCalledRegistrable() {
        Fingerprint measured = fingerprintOf(smoothBlob());
        assertTrue("a smooth blob has nothing to localize: " + measured.localisability().value(),
                measured.localisability().value() < 0.01);
        assertEquals(regdrift.Verdict.REGISTRABLE, Verdict.of(measured).kind());
    }

    /**
     * {@code not_registrable} is for a recording there is structurally nothing to measure in, and a
     * one-pixel-wide stack is one. It is never reached by a number being small.
     */
    @Test
    public void notRegistrableIsStructuralAndIsReachedByStructureAlone() {
        float[][] planes = new float[8][];
        for (int t = 0; t < planes.length; t++) planes[t] = Synth.frame(1, 8, 0, t);
        Fingerprint measured = fingerprintOf(Synth.stack("one pixel wide", 1, 8, planes));

        assertFalse(measured.measurablePlane());
        Verdict verdict = Verdict.of(measured);
        assertEquals(regdrift.Verdict.NOT_REGISTRABLE, verdict.kind());
        assertEquals("not_registrable", verdict.tableValue());
        assertTrue(verdict.text(), verdict.text().contains("too small to place a measurement"
                + " window in"));
        assertNotNull("even a structural refusal states the scale", verdict.measuredAt());
    }

    /**
     * The rule behind the one above, asserted over every fixture this file has: no measurement,
     * however poor, produces {@code not_registrable}.
     */
    @Test
    public void noMeasurementHoweverPoorEverProducesNotRegistrable() {
        ImagePlus[] everything = {
                flat(), smoothBlob(), narrowBandSteppedFar(), narrowBandSteppedFinely(),
                independentNoise(),
        };
        for (int i = 0; i < everything.length; i++) {
            Verdict verdict = Verdict.of(fingerprintOf(everything[i]));
            assertFalse("fixture " + i + " reached not_registrable through a measurement, and"
                            + " not_registrable is for structural impossibility. See defect D7",
                    verdict.kind() == regdrift.Verdict.NOT_REGISTRABLE);
        }
    }

    /** And it cannot be reached by asking for it without naming what is structurally absent. */
    @Test
    public void notRegistrableRefusesToBeIssuedWithoutAStructuralReason() {
        for (String empty : new String[]{null, "", "   "}) {
            try {
                Verdict.notRegistrable(empty, NATIVE);
                fail("not_registrable was issued with no reason: '" + empty + "'");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().contains("structurally nothing to measure"));
            }
        }
        try {
            Verdict.notRegistrable("no time axis", null);
            fail("a verdict was issued with no measurement scale");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("scale"));
        }
    }

    // --------------------------------------------- what the verdict is allowed to read

    /**
     * Defect D12, made structural. The verdict never applies the localisability threshold, and the
     * compiled class is what says so - a convention would be forgotten by the stage that widens the
     * calibration.
     */
    @Test
    public void theVerdictNeverAppliesTheLocalisabilityThreshold() throws Exception {
        assertNotNull("the method being banned has to exist, or this test bans nothing",
                Localisability.Result.class.getMethod("poor"));
        for (String className : Bytecode.classAndNested("regdrift.diag.Verdict")) {
            Bytecode.Pool pool = Bytecode.poolOf(className);
            assertFalse(className + " calls Localisability.Result.poor(), which applies"
                            + " WARN_BELOW. Localisability is reported with its scale and is not"
                            + " thresholded - see defect D12 and docs/D12_MEASUREMENT.md.",
                    pool.members.contains("regdrift/diag/Localisability$Result#poor"));
            assertFalse(className + " names WARN_BELOW - see defect D12",
                    pool.mentions("WARN_BELOW"));
        }
        assertTrue("the scan must be able to see a call that is really there",
                Bytecode.poolOf("regdrift.diag.Verdict").members
                        .contains("regdrift/diag/Localisability$Result#value"));
    }

    /**
     * Nothing a user reads tells them what a localisability number means, because this stage
     * measured that nobody knows.
     *
     * <p>The channel ranking used to add "it is still below the warning threshold, so expect any
     * method to struggle on this recording" to its reason, which reaches the dialog and the
     * auto-saved notes. On {@code 04_drift} - which removed 50.6% of the temporal standard
     * deviation when it was registered, the most of any recording in the library - that sentence
     * would have been printed. It is gone, and the ranking is still a ranking.
     */
    @Test
    public void nothingTellsAUserWhatALocalisabilityNumberMeans() throws IOException {
        for (String className : Bytecode.classAndNested("regdrift.diag.ChannelRanker")) {
            Bytecode.Pool pool = Bytecode.poolOf(className);
            assertFalse(className + " still tells a user their recording is below a threshold"
                            + " nothing has been able to calibrate - see defect D12",
                    pool.mentions("warning threshold") || pool.mentions("struggle"));
        }
    }

    /**
     * Frame correlation is reported in the table and routed on nowhere. It fires on
     * {@code 02_jitter} (0.163) and {@code 03_jitter_drift} (0.198), two of the recordings that
     * register best, while {@code 08}, {@code 09} and {@code 10} sit at 0.88-0.89: it ranks the
     * library almost inversely.
     */
    @Test
    public void theVerdictNeverRoutesOnFrameCorrelation() throws IOException {
        for (String className : Bytecode.classAndNested("regdrift.diag.Verdict")) {
            Bytecode.Pool pool = Bytecode.poolOf(className);
            assertFalse(className + " reads frame correlation, which ranks the library almost"
                            + " inversely to the outcome",
                    pool.members.contains("regdrift/diag/Fingerprint#frameCorrelation"));
        }
        File source = new File(projectRoot(), "src/main/java/regdrift/diag/Verdict.java");
        assertTrue(source.getAbsolutePath(), source.isFile());
        assertFalse("the column name must not appear in the verdict's source at all",
                read(source).contains("frame_" + "correlation"));
    }

    /**
     * The threshold the verdict does route on, pinned to the band it was measured from, so an edit
     * that moves it out of that band fails here rather than in a validation run three stages later.
     */
    @Test
    public void theAgreementLimitSitsInTheEmptyBandTheLibraryMeasured() {
        // Both scales, every value converted to the image's own pixels.
        double largestAgreeing = 2.303;        // 12_long_baseline_9d, measured at bin 1
        double smallestDisagreeing = 10.996;   // 09_knock_extreme, measured at bin 4 (2.749 x 4)
        assertTrue("AGREEMENT_LIMIT_PX = " + Verdict.AGREEMENT_LIMIT_PX + " is at or below the"
                        + " largest agreement measured on a library recording whose two estimators"
                        + " tracked each other (" + largestAgreeing + " px)",
                Verdict.AGREEMENT_LIMIT_PX > largestAgreeing);
        assertTrue("AGREEMENT_LIMIT_PX = " + Verdict.AGREEMENT_LIMIT_PX + " is at or above the"
                        + " smallest agreement measured on a library recording whose two estimators"
                        + " parted company (" + smallestDisagreeing + " px)",
                Verdict.AGREEMENT_LIMIT_PX < smallestDisagreeing);
    }

    /**
     * Defect D12 in the place it happened a second time: every displacement leaves a fingerprint in
     * the image's own pixels, not in the ones the estimators worked in.
     *
     * <p>{@link MotionDescriptors}'s knock floor and severity bands are native-pixel numbers - the
     * survey they came from binned its frames to estimate and multiplied the displacements back
     * before describing them. Feeding them bin-4 pixels would move every severity two bands and
     * change every knock decision, and nothing would have complained.
     */
    @Test
    public void everyDisplacementIsReportedInTheImagesOwnPixels() {
        ImagePlus movie = binnedFixture();
        Frames.Bin bin = Fingerprint.binFor(movie.getWidth(), movie.getHeight());
        assertEquals("the fixture has to be large enough to be binned, or this proves nothing",
                Fingerprint.MEASUREMENT_BIN, bin.factor());

        Fingerprint measured = fingerprintOf(movie);
        assertEquals("the pixels were sampled at the survey's scale",
                bin, measured.measuredAt());
        assertEquals("and every displacement comes back out of it",
                Frames.Bin.none(), measured.displacementsAt());
        assertEquals(Frames.Bin.none(), measured.motion().measuredAt());
        assertEquals("localisability keeps the scale it belongs to",
                bin, measured.localisability().measuredAt());

        // The step the fixture actually takes is 4 native pixels a frame. At bin 4 the estimators
        // see one; what comes out has to be the four.
        assertEquals("the step in the image's own pixels", 4.0,
                measured.motion().stepMaxPx() + measured.motion().driftRatePx(), 1.0);
        assertTrue("and the estimators' own view is still there, in their own pixels: "
                        + measured.estimates().measuredAt(),
                measured.estimates().measuredAt().equals(bin));
    }

    // --------------------------------------------------------------- the wording

    @Test
    public void everyVerdictCarriesFinishedTextItsScaleAndItsCalibration() {
        List<Verdict> all = Arrays.asList(
                Verdict.of(fingerprintOf(narrowBandSteppedFar())),
                Verdict.of(fingerprintOf(flat())),
                Verdict.of(fingerprintOf(narrowBandSteppedFinely())),
                Verdict.notRegistrable("This recording holds one frame, so it carries no"
                        + " movement to measure.", NATIVE));
        for (Verdict verdict : all) {
            assertNotNull(verdict.kind());
            assertNotNull(verdict.measuredAt());
            assertFalse("every verdict carries a finished sentence", verdict.text().isEmpty());
            assertTrue("finished means a sentence, not a token: " + verdict.text(),
                    verdict.text().endsWith(".") && verdict.text().length() > 40);
            assertEquals(verdict.kind().tableValue(), verdict.tableValue());
        }
        for (int i = 0; i < 3; i++) {
            assertTrue("a measured verdict says what it was calibrated on: " + all.get(i).text(),
                    all.get(i).text().contains("IncuCyte phase-contrast"));
        }
    }

    /** House rules 5 and 6, over the strings this stage added. */
    @Test
    public void noVerdictUsesAWordTheHouseRulesForbid() {
        String[] forbidden = {"accu" + "racy", "b" + "est", "opti" + "mal", "on" + "ly"};
        String[] texts = {
                Verdict.of(fingerprintOf(narrowBandSteppedFar())).text(),
                Verdict.of(fingerprintOf(flat())).text(),
                Verdict.of(fingerprintOf(narrowBandSteppedFinely())).text(),
                Verdict.of(fingerprintOf(oneByEight())).text(),
                Verdict.CALIBRATION_NOTE,
                Verdict.LOCALISABILITY_NOTE,
        };
        for (String text : texts) {
            for (String word : forbidden) {
                assertFalse("a verdict uses a word the house rules forbid, '" + word + "': " + text,
                        Pattern.compile("\\b" + word + "\\b")
                                .matcher(text.toLowerCase(java.util.Locale.ROOT)).find());
            }
        }
    }

    /** The same measurement always gives the same verdict, wording included. */
    @Test
    public void twoVerdictsOverTheSameMeasurementAreEqual() {
        Fingerprint measured = fingerprintOf(narrowBandSteppedFinely());
        assertEquals(Verdict.of(measured), Verdict.of(measured));
        assertEquals(Verdict.of(measured).hashCode(), Verdict.of(measured).hashCode());
        assertFalse(Verdict.of(measured).equals(Verdict.of(fingerprintOf(flat()))));
    }

    @Test
    public void aVerdictNeedsAMeasurementToBeMadeFrom() {
        try {
            Verdict.of(null);
            fail("a verdict was made from nothing");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("measurement"));
        }
    }

    // ---------------------------------------------------------------- machinery

    private static Fingerprint fingerprintOf(ImagePlus movie) {
        Frames.Bin bin = Fingerprint.binFor(movie.getWidth(), movie.getHeight());
        Frames frames = Frames.of(movie, 1, Frames.PROJECT_Z, bin);
        try {
            WindowSampler.Plan plan = WindowSampler.measured().plan(frames.count());
            return Fingerprint.measure(frames, plan, 1,
                    PairScheduler.Progress.NONE, Cancellation.never());
        } finally {
            frames.release();
        }
    }

    /** Narrow-band content stepped far enough that phase correlation cannot follow it. */
    private static ImagePlus narrowBandSteppedFar() {
        float[][] planes = new float[14][];
        for (int t = 0; t < planes.length; t++) {
            planes[t] = Synth.frame(SIDE, SIDE, 7.0 * t, 3.0 * t);
        }
        return Synth.stack("narrow band, 7 px steps", SIDE, SIDE, planes);
    }

    /** The same content stepped a quarter of a pixel, which both methods follow. */
    private static ImagePlus narrowBandSteppedFinely() {
        float[][] planes = new float[14][];
        for (int t = 0; t < planes.length; t++) {
            planes[t] = Synth.frame(SIDE, SIDE, 0.25 * t, 0.125 * t);
        }
        return Synth.stack("narrow band, quarter-pixel steps", SIDE, SIDE, planes);
    }

    private static ImagePlus flat() {
        float[][] planes = new float[14][];
        for (int t = 0; t < planes.length; t++) planes[t] = Synth.flat(SIDE, SIDE, 500f);
        return Synth.stack("nothing at all", SIDE, SIDE, planes);
    }

    private static ImagePlus smoothBlob() {
        float[][] planes = new float[14][];
        for (int t = 0; t < planes.length; t++) planes[t] = Synth.blob(SIDE, SIDE);
        return Synth.stack("a smooth blob", SIDE, SIDE, planes);
    }

    private static ImagePlus independentNoise() {
        float[][] planes = new float[14][];
        for (int t = 0; t < planes.length; t++) planes[t] = Synth.texture(SIDE, SIDE, 100 + t, 1);
        return Synth.stack("independent noise", SIDE, SIDE, planes);
    }

    /**
     * Large enough that {@link Fingerprint#binFor} keeps the survey's factor, so the conversion out
     * of the measured scale is exercised rather than being the identity. Drifts four native pixels
     * a frame, which is exactly one measured pixel at bin 4.
     */
    private static ImagePlus binnedFixture() {
        int side = 256;
        int field = 352;
        float[] bg = Synth.texture(field, field, 5150L, 3);
        float[][] planes = new float[14][];
        for (int t = 0; t < planes.length; t++) {
            planes[t] = Synth.crop(bg, field, 8 + 4 * t, 8, side, side);
        }
        return Synth.stack("drifts four pixels a frame", side, side, planes);
    }

    private static ImagePlus oneByEight() {
        float[][] planes = new float[8][];
        for (int t = 0; t < planes.length; t++) planes[t] = Synth.frame(1, 8, 0, t);
        return Synth.stack("one pixel wide", 1, 8, planes);
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File projectRoot() {
        File here = Bytecode.buildOutput();          // target/classes
        return here.getParentFile().getParentFile();
    }
}
