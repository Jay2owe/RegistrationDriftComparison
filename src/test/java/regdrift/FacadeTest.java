/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link RegDrift#run} as it stands: every mode answers, none of them throws,
 * and none of them needs an ImageJ window to be open.
 *
 * <p>No mode measures anything yet. Each returns a real result carrying a typed
 * {@code not_implemented} reason that names the build stage the branch arrives
 * in, which is what lets stage 04 build the dialog and click through it before
 * an estimator exists. As each stage lands, the assertion for its mode changes
 * from "says which stage" to "measured something", one mode at a time.
 *
 * <p>Nothing here opens a window, and nothing here is running inside a Fiji. The
 * whole test class is the demonstration for that half of the promise; the other
 * half - that it could not open one even by mistake - is
 * {@link ApiIsolationTest}.
 */
public class FacadeTest {

    @Test
    public void everyModeAnswersWithTheStageItArrivesIn() {
        assertNotImplemented(Mode.DIAGNOSE, "09");
        assertNotImplemented(Mode.DIAGNOSE_AND_RECOMMEND, "10");
        assertNotImplemented(Mode.APPLY, "13");
        assertNotImplemented(Mode.COMPARE, "13");
        assertNotImplemented(Mode.SCORE, "12");
    }

    /** Five modes, five branches. A mode with no branch must not slip through. */
    @Test
    public void everyModeInTheEnumHasABranch() {
        for (Mode mode : Mode.values()) {
            RegDriftResult result = RegDrift.run(requestFor(mode));
            assertNotNull("mode " + mode + " returned nothing at all", result);
            assertNotNull("mode " + mode + " returned an empty result rather than a reason",
                    result.failure());
        }
        assertEquals("five modes are wired here and in the dialog", 5, Mode.values().length);
    }

    /** The default request needs nothing but a recording. */
    @Test
    public void aRecordingOnItsOwnIsEnoughToCallThis() {
        RegDriftResult result = RegDrift.run(stack(1, 1, 6, "movie.tif"));
        assertNotNull(result);
        assertEquals(Mode.DIAGNOSE_AND_RECOMMEND, result.parameters().mode());
        assertEquals(Failure.Kind.NOT_IMPLEMENTED, result.failure().kind());
    }

    /**
     * Gate 6: no ImageJ window is open, and none is needed.
     *
     * <p>{@code WindowManager} is asked here so the test states the condition it
     * is running under rather than assuming it. The recording is never shown, so
     * nothing this plugin does can be leaning on a current image.
     */
    @Test
    public void nothingHereNeedsAWindowToBeOpen() {
        assertNull("this test runs with no ImageJ window open, by design",
                WindowManager.getCurrentImage());
        assertEquals(0, WindowManager.getImageCount());

        RegDriftResult result = RegDrift.run(stack(1, 1, 6, "movie.tif"));

        assertNotNull(result);
        assertNull("running the facade must not open a window",
                WindowManager.getCurrentImage());
        assertEquals(0, WindowManager.getImageCount());
        assertNull("nor may it start ImageJ itself", IJ.getInstance());
    }

    /** A run that produced nothing says why, and says it in a readable sentence. */
    @Test
    public void aRunThatCannotFinishGivesATypedReasonRatherThanNull() {
        RegDriftResult result = RegDrift.run(stack(1, 1, 6, "movie.tif"));

        assertFalse(result.isSuccess());
        assertNotNull(result.failure());
        assertEquals(Failure.Kind.NOT_IMPLEMENTED, result.failure().kind());
        assertTrue("the reason must be a sentence, not a code",
                result.failure().message().endsWith("."));
        assertNull("no mode has produced a registered stack yet", result.registered());
        assertNull("nothing has been measured, so nothing is recorded about it",
                result.provenance());
        assertEquals("", result.verdictReason());
        assertTrue(result.ranked().isEmpty());
    }

    // ------------------------------------------------------ refusing an input

    /** One frame carries no movement, and that is a reason, not a crash. */
    @Test
    public void aSingleFrameIsRefusedWithAReasonThatNamesTheRecording() {
        RegDriftResult result = RegDrift.run(stack(1, 1, 1, "still.tif"));

        assertEquals(Failure.Kind.NO_TIME_AXIS, result.failure().kind());
        assertTrue(result.failure().message(),
                result.failure().message().contains("still.tif"));
    }

    /** A plain stack is read as a time series, which is what the contract says. */
    @Test
    public void aPlainStackIsReadAsATimeSeries() {
        ImagePlus plain = stack(1, 9, 1, "plain.tif");
        assertEquals(9, RegDrift.frameCount(plain));
        assertEquals(Failure.Kind.NOT_IMPLEMENTED, RegDrift.run(plain).failure().kind());
        assertEquals(0, RegDrift.frameCount(null));
    }

    @Test
    public void scoringWithNoSecondStackNamesTheSettingThatIsMissing() {
        RegDriftResult result = RegDrift.run(RegDriftParameters
                .builder(stack(1, 1, 6, "movie.tif")).mode(Mode.SCORE).build());

        assertEquals(Failure.Kind.INVALID_PARAMETERS, result.failure().kind());
        assertTrue(result.failure().message(),
                result.failure().message().contains(RegDriftMacroOptions.COMPARE_WITH));
    }

    @Test
    public void scoringASecondStackOfAnotherShapeSaysBothShapes() {
        RegDriftResult result = RegDrift.run(RegDriftParameters
                .builder(stack(1, 1, 6, "movie.tif"))
                .mode(Mode.SCORE)
                .compareWith(stack(1, 1, 4, "registered.tif"))
                .build());

        assertEquals(Failure.Kind.SECOND_STACK_MISMATCH, result.failure().kind());
        assertTrue(result.failure().message(), result.failure().message().contains("6 frames"));
        assertTrue(result.failure().message(), result.failure().message().contains("4 frames"));
    }

    /** No settings at all is a mistake in the calling code, and says so. */
    @Test
    public void callingWithNoSettingsIsRefusedOutright() {
        try {
            RegDrift.run((RegDriftParameters) null);
            fail("running with no settings should be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("RegDriftParameters.builder"));
        }
    }

    /** The version travels with the result rather than being spelled twice. */
    @Test
    public void theVersionIsSpelledInOnePlace() {
        assertFalse(RegDrift.VERSION.trim().isEmpty());
        assertTrue("the not-implemented reason carries the build it came from",
                RegDrift.run(stack(1, 1, 6, "movie.tif")).failure().message()
                        .contains(RegDrift.VERSION));
    }

    // -------------------------------------------------------------- fixtures

    private static void assertNotImplemented(Mode mode, String stage) {
        RegDriftResult result = RegDrift.run(requestFor(mode));

        assertNotNull("mode " + mode.macroValue() + " returned nothing", result);
        assertNotNull("mode " + mode.macroValue() + " returned no reason", result.failure());
        assertEquals(Failure.Kind.NOT_IMPLEMENTED, result.failure().kind());
        assertTrue("mode " + mode.macroValue() + " should name build stage " + stage
                        + ", and said: " + result.failure().message(),
                result.failure().message().startsWith(
                        RegDrift.NOT_IMPLEMENTED_PREFIX + stage + "."));
        assertTrue("the reason should name the mode a user asked for",
                result.failure().message().contains(mode.macroValue()));
        assertEquals(mode, result.parameters().mode());
    }

    /** A request in the given mode, with whatever that mode needs to be valid. */
    private static RegDriftParameters requestFor(Mode mode) {
        RegDriftParameters.Builder builder = RegDriftParameters
                .builder(stack(1, 1, 6, "movie.tif")).mode(mode);
        if (mode == Mode.SCORE) {
            builder.compareWith(stack(1, 1, 6, "registered.tif"));
        }
        return builder.build();
    }

    /** A stack with no window attached, sized in channels, slices and frames. */
    private static ImagePlus stack(int channels, int slices, int frames, String title) {
        ImageStack planes = new ImageStack(8, 8);
        for (int i = 0; i < channels * slices * frames; i++) {
            planes.addSlice("plane " + (i + 1), new ByteProcessor(8, 8));
        }
        ImagePlus image = new ImagePlus(title, planes);
        image.setDimensions(channels, slices, frames);
        return image;
    }
}
