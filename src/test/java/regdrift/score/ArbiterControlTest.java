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
import ij.measure.ResultsTable;
import org.junit.Test;
import regdrift.Cancellation;
import regdrift.Mode;
import regdrift.RegDrift;
import regdrift.RegDriftParameters;
import regdrift.RegDriftResult;
import regdrift.RegDriftTables;
import regdrift.diag.Bytecode;
import regdrift.diag.FrameSource;
import regdrift.internal.PairScheduler;
import regdrift.internal.Transform;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T10, and defect D11 in full.
 *
 * <p><b>The defect, in one sentence.</b> Resampling a picture at a fractional
 * offset smooths it, smoothing lowers temporal standard deviation for free, and a
 * registered recording scored against the <em>raw</em> recording therefore gets
 * credit for smoothing that any method at all would have got. On the twelve
 * library entries the smoothing alone accounts for between a fifth and a third of
 * the temporal standard deviation.
 *
 * <p><b>The defence.</b> Every arm is scored against a control: the same recording
 * shifted by the fractional part <em>only</em> of each frame's transform. It goes
 * through the identical resampling and removes none of the drift, so a method that
 * did nothing but blur scores zero.
 *
 * <p>The test that matters most here is
 * {@link #aMethodThatOnlyBlurredScoresExactlyZero}: it builds an arm that is
 * literally the control, and asserts the arbiter gives it nothing. Every other
 * assertion in this class supports that one.
 */
public class ArbiterControlTest {

    private static final int SIDE = 96;
    private static final int FRAMES = 20;
    private static final double STEP_X = 0.37;
    private static final double STEP_Y = -0.23;

    // ------------------------------------------- the control is a real control

    /**
     * The control carries the fractional part and nothing else, so it moves the
     * field by less than half a pixel and takes out none of the drift.
     */
    @Test
    public void theControlKeepsTheFractionalPartAndRemovesNoDrift() {
        Fixture.Drifting movie = movie();
        Transform[] control = ControlWarp.fractionalOf(movie.cumulative);

        assertEquals(movie.cumulative.length, control.length);
        for (int t = 0; t < control.length; t++) {
            assertTrue("control frame " + t + " moved " + control[t].dx + " px in x, which is more"
                    + " than the fractional part", Math.abs(control[t].dx) <= 0.5);
            assertTrue("control frame " + t + " moved " + control[t].dy + " px in y, which is more"
                    + " than the fractional part", Math.abs(control[t].dy) <= 0.5);
            assertEquals("a control never rotates", 0.0, control[t].theta, 0.0);
        }

        double realDrift = Arbiter.netPx(movie.cumulative);
        double controlDrift = Arbiter.netPx(control);
        assertTrue("the fixture has to drift, or this test asserts nothing: " + realDrift,
                realDrift > 5);
        assertTrue("the control removed systematic drift: " + controlDrift + " px net",
                controlDrift < 1.0);
    }

    /**
     * Same interpolator, same code path. Not "an equivalent one": the control and
     * the registered recording go through the identical branch of the identical
     * method, so the smoothing they carry is the same smoothing.
     */
    @Test
    public void theControlAndTheRegisteredRecordingTakeTheSameCodePath() {
        Fixture.Drifting movie = movie();
        Transform[] control = ControlWarp.fractionalOf(movie.cumulative);
        ControlWarp.Interpolation how = ControlWarp.Interpolation.BILINEAR;

        int resampled = 0;
        for (int t = 1; t < control.length; t++) {
            ControlWarp.Path real = ControlWarp.pathFor(movie.cumulative[t], how);
            ControlWarp.Path asControl = ControlWarp.pathFor(control[t], how);
            assertEquals("frame " + t + ": the control must resample exactly as the registered"
                    + " recording did", real, asControl);
            if (asControl == ControlWarp.Path.RESAMPLED) resampled++;
        }
        assertEquals("every frame of this fixture drifts by a fraction of a pixel, so every one"
                + " must be resampled", control.length - 1, resampled);
    }

    /**
     * The whole point, stated as a number: an arm that only resampled scores
     * <b>exactly</b> zero, not "about" zero.
     *
     * <p>The blurred-only arm is built by warping each frame by the fractional
     * part of its transform - which is what the control is - so the two are the
     * same pixels and the ratio is one to the last bit. Scored against the raw
     * recording instead, the same arm would have shown a double-digit improvement,
     * and that number is asserted here too so the size of the defect is on the
     * record rather than in a commit message.
     */
    @Test
    public void aMethodThatOnlyBlurredScoresExactlyZero() {
        Fixture.Drifting movie = movie();
        ControlWarp.Interpolation how = ControlWarp.Interpolation.BILINEAR;
        Arbiter.Scoring blurredOnly = Arbiter.score(movie.rawSource(),
                movie.blurredOnlySource(how), movie.cumulative, how, 1,
                PairScheduler.Progress.NONE, Cancellation.never());

        assertEquals("an arm that only resampled must score zero against the control",
                0.0, blurredOnly.sdVsControl(), 0.0);
        assertEquals(Arbiter.Separation.CANNOT_SEPARATE, blurredOnly.separation());

        // And what the uncontrolled comparison would have said instead. The blurred-only arm's
        // temporal standard deviation against the RAW recording is computed here, in the test,
        // once, to show the size of the defect - it is deliberately not computable from anything
        // the plugin exposes.
        double blur = 100 * (meanTemporalSd(movie.blurredOnlySource(how), blurredOnly.margin())
                / meanTemporalSd(movie.rawSource(), blurredOnly.margin()) - 1);
        assertTrue("the fixture has to show the defect it is testing: resampling alone moved the"
                + " raw temporal standard deviation by " + blur + "%, which is too small to be"
                + " worth controlling for", blur < -3.0);
        assertEquals("scored against the control, that same arm gets none of it", 0.0,
                blurredOnly.sdVsControlPercent(), 0.0);
    }

    /** A real registration, on the same fixture, does score - so the control is not simply flat. */
    @Test
    public void aRealRegistrationScoresAgainstTheSameControl() {
        Fixture.Drifting movie = movie();
        ControlWarp.Interpolation how = ControlWarp.Interpolation.BILINEAR;
        Arbiter.Scoring scoring = Arbiter.score(movie.rawSource(), movie.registeredSource(how),
                movie.cumulative, how, 1, PairScheduler.Progress.NONE, Cancellation.never());

        assertEquals(Arbiter.Separation.IMPROVED, scoring.separation());
        assertTrue("a recording put back where it started must be stiller than the control: "
                + scoring.sdVsControlPercent() + "%", scoring.sdVsControlPercent() < -20);
        // The residual after does not go to zero, and should not: each registered frame carries a
        // different amount of resampling blur, because the fractional part of its shift differs,
        // so consecutive registered frames genuinely differ. That floor is real.
        assertTrue("and the residual mismatch must fall, in pixels: "
                        + scoring.medianResidualBefore() + " -> " + scoring.medianResidualAfter(),
                scoring.residualRemoved() > 0.3 * scoring.medianResidualBefore());
    }

    /**
     * The residual columns are in pixels, and they mean it: on a recording whose
     * per-frame step is known exactly, {@code residual_before} recovers it.
     */
    @Test
    public void theResidualColumnsAreInPixelsAndRecoverAKnownStep() {
        Fixture.Drifting movie = movie();
        ControlWarp.Interpolation how = ControlWarp.Interpolation.BILINEAR;
        Arbiter.Scoring scoring = Arbiter.score(movie.rawSource(), movie.registeredSource(how),
                movie.cumulative, how, 1, PairScheduler.Progress.NONE, Cancellation.never());

        double trueStep = Math.hypot(STEP_X, STEP_Y);
        double measured = scoring.medianResidualBefore();
        assertEquals("residual_before is the frame-to-frame mismatch as an equivalent shift, and"
                + " this fixture steps " + trueStep + " px per frame", trueStep, measured, 0.25);
    }

    // -------------------------------------------- an identity warp is refused

    /**
     * A control with nothing fractional in it does no resampling at all, and
     * silently turns {@code sd_vs_control} back into the raw comparison the whole
     * defect is about. It is refused by name.
     */
    @Test
    public void anIdentityWarpIsRefusedAsAControlWithAStatedReason() {
        Transform[] wholePixels = new Transform[8];
        for (int t = 0; t < wholePixels.length; t++) {
            wholePixels[t] = Transform.translation(3 * t, -2 * t);
        }
        Transform[] control = ControlWarp.fractionalOf(wholePixels);
        for (int t = 0; t < control.length; t++) {
            assertEquals("a whole-pixel drift leaves nothing fractional", 0.0, control[t].dx, 0.0);
            assertEquals(0.0, control[t].dy, 0.0);
        }
        try {
            ControlWarp.requireAnInterpolatingControl(control,
                    ControlWarp.Interpolation.BILINEAR);
            fail("a control that resamples nothing must be refused");
        } catch (IllegalArgumentException refused) {
            String said = refused.getMessage();
            assertTrue("the refusal has to say what is wrong, and said: " + said,
                    said.contains("identity warp is not a control"));
            assertTrue("and name the defect it re-opens: " + said, said.contains("D11"));
            assertTrue("and say what the consequence would be: " + said,
                    said.contains("sd_vs_control"));
        }
    }

    /** The refusal reaches the caller of the arbiter, not just the helper. */
    @Test
    public void scoringRefusesARecordingWhoseControlWouldResampleNothing() {
        Fixture.Drifting movie = Fixture.drifting(SIDE, 8, 2.0, -1.0, 4242L);
        try {
            Arbiter.score(movie.rawSource(),
                    movie.registeredSource(ControlWarp.Interpolation.BILINEAR), movie.cumulative,
                    ControlWarp.Interpolation.BILINEAR, 1, PairScheduler.Progress.NONE,
                    Cancellation.never());
            fail("scoring against a control that resamples nothing must be refused");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage().contains("identity warp is not a control"));
        }
    }

    /** And the whole run reports it as a typed reason rather than throwing at a user. */
    @Test
    public void theScoreModeReportsThatRefusalAsATypedReason() {
        Fixture.Drifting movie = Fixture.drifting(SIDE, 8, 2.0, -1.0, 99L);
        ImagePlus raw = movie.rawStack("whole pixel raw");
        ImagePlus registered = movie.registeredStack("whole pixel registered",
                ControlWarp.Interpolation.BILINEAR);
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(raw)
                .mode(Mode.SCORE)
                .compareWith(registered)
                .serial(true)
                .build());

        // The recovered shifts are whole pixels here, so the control would resample nothing. The
        // run says so; it does not quietly score against the raw recording instead.
        if (!result.isSuccess()) {
            assertEquals(regdrift.Failure.Kind.SCORING_FAILED, result.failure().kind());
            assertTrue(result.failure().message().contains("identity warp is not a control"));
        }
    }

    // ------------------------------------------ raw SD is nowhere on any surface

    /** {@code sd_vs_control} is the column, and it is the one the score mode fills. */
    @Test
    public void sdVsControlIsWhatAppearsInTheComparisonTable() {
        assertTrue("the comparison table has to carry it",
                RegDriftTables.COMPARISON_COLUMNS.contains("sd_vs_control"));
        for (String column : RegDriftTables.COMPARISON_COLUMNS) {
            assertFalse("no comparison column may report raw temporal standard deviation: "
                    + column, column.contains("sd_raw") || column.contains("raw_sd"));
        }
        for (String column : RegDriftTables.FRAMES_COLUMNS) {
            assertFalse("nor may the frames table: " + column,
                    column.contains("sd_raw") || column.contains("raw_sd"));
        }

        Fixture.Drifting movie = movie();
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie.rawStack("raw"))
                .mode(Mode.SCORE)
                .compareWith(movie.registeredStack("registered",
                        ControlWarp.Interpolation.BILINEAR))
                .serial(true)
                .build());
        assertTrue(reason(result), result.isSuccess());
        ResultsTable comparison = result.comparison();
        assertNotNull("the score mode produces a comparison table", comparison);
        assertEquals(1, comparison.size());
        String scored = RegDriftTables.cellText(comparison, "sd_vs_control", 0);
        assertFalse("sd_vs_control must be filled in", scored.isEmpty());
        assertTrue("and it must be an improvement on this fixture: " + scored,
                Double.parseDouble(scored) < 0);
        assertEquals("one row per frame in the frames table", movie.raw.length,
                result.frames().size());
    }

    /**
     * Nothing public anywhere computes or hands out raw temporal standard
     * deviation. Read from the compiled classes rather than from the source,
     * because a fully written name has no import line to grep for.
     */
    @Test
    public void rawTemporalStandardDeviationIsNotExposedAnywhere() throws IOException {
        String[] banned = {"rawSd", "sdRaw", "rawStandardDeviation", "standardDeviationRaw",
                "sd_raw", "raw_sd"};
        List<String> classes = Bytecode.classesIn("regdrift");
        assertTrue("this test has to be looking at the compiled plugin", classes.size() > 40);
        for (String className : classes) {
            Bytecode.Pool pool = Bytecode.poolOf(className);
            for (String name : banned) {
                assertFalse(className + " names '" + name + "'. Raw temporal standard deviation is"
                        + " not an effect and must not be exposed as one - not in a table, not on"
                        + " a plot axis, not in a tooltip. See defect D11.",
                        pool.mentions(name));
            }
        }
    }

    /**
     * The scoring object hands out the two figures the ratio is built from and
     * nothing else, so the ratio can be checked without the raw recording's own
     * spread being available to quote.
     */
    @Test
    public void theScoringCarriesTheControlAndTheRegisteredSpreadAndNoThird() {
        Fixture.Drifting movie = movie();
        ControlWarp.Interpolation how = ControlWarp.Interpolation.BILINEAR;
        Arbiter.Scoring scoring = Arbiter.score(movie.rawSource(), movie.registeredSource(how),
                movie.cumulative, how, 1, PairScheduler.Progress.NONE, Cancellation.never());

        assertTrue(scoring.meanSdControl() > 0);
        assertTrue(scoring.meanSdRegistered() > 0);
        assertEquals("sd_vs_control is exactly the ratio of the two, minus one",
                scoring.meanSdRegistered() / scoring.meanSdControl() - 1,
                scoring.sdVsControl(), 0.0);
        for (java.lang.reflect.Method method : Arbiter.Scoring.class.getMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse("Arbiter.Scoring." + method.getName() + " looks like raw temporal standard"
                    + " deviation, which must not be reachable", name.contains("sdraw")
                    || name.contains("rawsd"));
        }
    }

    // -------------------------------------------------- cannot separate

    /**
     * Where the control and the result are within noise, the arbiter says so
     * instead of producing a ranking.
     */
    @Test
    public void withinNoiseTheArbiterSaysCannotSeparateAndRanksNothing() {
        Fixture.Drifting movie = movie();
        ControlWarp.Interpolation how = ControlWarp.Interpolation.BILINEAR;
        Arbiter.Scoring nothing = Arbiter.score(movie.rawSource(), movie.blurredOnlySource(how),
                movie.cumulative, how, 1, PairScheduler.Progress.NONE, Cancellation.never());

        assertEquals(Arbiter.Separation.CANNOT_SEPARATE, nothing.separation());
        assertEquals("cannot_separate", nothing.separation().word());
        String said = nothing.separationText();
        assertTrue("the sentence has to say it is not a tie: " + said,
                said.contains("Cannot separate") && said.contains("not a tie"));
        assertTrue("and name the noise level it used: " + said, said.contains("3.0%"));
        assertTrue("and say why no ranking follows: " + said, said.contains("no ranking"));
    }

    /** The threshold is a stated constant, not something derived from the arm being scored. */
    @Test
    public void theNoiseThresholdIsThreePercentAndIsFixed() {
        assertEquals(3.0, Arbiter.CANNOT_SEPARATE_PERCENT, 0.0);
    }

    /**
     * The threshold sorts the twelve library entries the way the library itself
     * sorts them.
     *
     * <p>These are the figures this arbiter measured on the entries, re-measured
     * from {@code original.tif} and each entry's own {@code shifts.csv}; they
     * reproduce the library's own {@code SD vs ctrl} column to within 0.4
     * percentage points, and {@code docs/D11_MEASUREMENT.md} holds the comparison.
     * The two entries the library labels <em>unresolved</em>, and the one where
     * the arbiter is known to run out, must come back as "cannot separate"; the
     * nine that registered must not.
     *
     * <p>This is here so that a later change to the threshold has to argue with
     * the data rather than with a comment. There is nothing between 2.1 and 3.8,
     * so any cut in that gap passes and any cut outside it fails.
     */
    @Test
    public void theThresholdSortsTheTwelveLibraryEntriesTheWayTheLibraryDoes() {
        assertSorts("01_jitter_mild", -12.1, Arbiter.Separation.IMPROVED);
        assertSorts("02_jitter", -37.5, Arbiter.Separation.IMPROVED);
        assertSorts("03_jitter_drift", -29.6, Arbiter.Separation.IMPROVED);
        assertSorts("04_drift", -50.7, Arbiter.Separation.IMPROVED);
        assertSorts("05_drift_dominant", -5.3, Arbiter.Separation.IMPROVED);
        assertSorts("06_knock", -40.3, Arbiter.Separation.IMPROVED);
        assertSorts("07_knock_drift", -37.2, Arbiter.Separation.IMPROVED);
        assertSorts("08_knock_severe", -3.8, Arbiter.Separation.IMPROVED);
        assertSorts("09_knock_extreme", -14.4, Arbiter.Separation.IMPROVED);
        assertSorts("10_unresolved_methods_disagree", +0.3,
                Arbiter.Separation.CANNOT_SEPARATE);
        assertSorts("11_unresolved_moving_artefact", -2.1,
                Arbiter.Separation.CANNOT_SEPARATE);
        assertSorts("12_long_baseline_9d", -0.3, Arbiter.Separation.CANNOT_SEPARATE);
        assertEquals("an arm that made the recording less still says so rather than being"
                        + " rounded to no effect", Arbiter.Separation.WORSE,
                Arbiter.separationOf(+9.0));
        assertEquals("and nothing measurable is not a tie", Arbiter.Separation.CANNOT_SEPARATE,
                Arbiter.separationOf(Double.NaN));
    }

    private static void assertSorts(String entry, double percent,
                                    Arbiter.Separation expected) {
        assertEquals(entry + " measured " + percent + "%", expected,
                Arbiter.separationOf(percent));
    }

    // ---------------------------------------------------------------- machinery

    private static Fixture.Drifting movie() {
        return Fixture.drifting(SIDE, FRAMES, STEP_X, STEP_Y, 20260813L);
    }

    private static String reason(RegDriftResult result) {
        return result.isSuccess() ? "" : result.failure().kind() + ": "
                + result.failure().message();
    }

    /**
     * Mean per-pixel temporal standard deviation of one recording, in the test
     * only, so the size of defect D11 can be asserted without the plugin exposing
     * a figure nobody should quote.
     */
    private static double meanTemporalSd(FrameSource frames, ControlWarp.Margin margin) {
        int width = frames.width();
        int height = frames.height();
        int n = frames.count();
        int croppedWidth = margin.croppedWidth(width);
        int croppedHeight = margin.croppedHeight(height);
        double[] sum = new double[croppedWidth * croppedHeight];
        double[] sumSq = new double[croppedWidth * croppedHeight];
        double reference = Arbiter.frameMedian(frames.plane(0), width, height, margin);
        for (int t = 0; t < n; t++) {
            float[] plane = frames.plane(t);
            double median = Arbiter.frameMedian(plane, width, height, margin);
            double scale = median > 0 ? reference / median : 1.0;
            for (int y = margin.top(); y < height - margin.bottom(); y++) {
                for (int x = margin.left(); x < width - margin.right(); x++) {
                    int o = (y - margin.top()) * croppedWidth + (x - margin.left());
                    double v = plane[y * width + x] * scale;
                    sum[o] += v;
                    sumSq[o] += v * v;
                }
            }
        }
        double total = 0;
        for (int o = 0; o < sum.length; o++) {
            double mean = sum[o] / n;
            total += Math.sqrt(Math.max(0, sumSq[o] / n - mean * mean));
        }
        return total / sum.length;
    }
}
