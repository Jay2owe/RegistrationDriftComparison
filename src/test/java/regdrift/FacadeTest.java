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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link RegDrift#run} as it stands: every mode answers, none of them throws,
 * and none of them needs an ImageJ window to be open.
 *
 * <p><b>Three modes now do their work; two do not yet.</b> Each of those two
 * returns a real result carrying a typed {@code not_implemented} reason
 * that names the build stage the branch arrives in, which is what let stage 04
 * build the dialog and click through it before an estimator existed. As each
 * stage lands, the assertion for its mode changes from "says which stage" to
 * "measured something", one mode at a time; stage 09 moved {@link Mode#DIAGNOSE}
 * across and stage 10 moved {@link Mode#DIAGNOSE_AND_RECOMMEND}. What those two
 * measure is {@code DiagnoseModeTest}'s and {@code RecommenderTest}'s business,
 * not this file's - here they only have to answer.
 *
 * <p>{@link Mode#DIAGNOSE_AND_RECOMMEND} is the default mode, so the four tests
 * below that call {@link RegDrift#run} with nothing but a recording used to be
 * the not-implemented tests as well. They are not any more: a default request
 * now comes back with a ranking, and the tests that need a mode with a reason to
 * give name one.
 *
 * <p>Nothing here opens a window, and nothing here is running inside a Fiji. The
 * whole test class is the demonstration for that half of the promise; the other
 * half - that it could not open one even by mistake - is
 * {@link ApiIsolationTest}.
 */
public class FacadeTest {

    /**
     * Every mode is built, so no mode names a build stage any more.
     *
     * <p>Stage 13 took the last two - {@link Mode#APPLY} and {@link Mode#COMPARE}
     * - which is why this test no longer has a list of unbuilt modes in it. What
     * replaced the list is stronger: whatever any mode comes back with, it is
     * either a measurement or a reason about <em>this recording</em>, and never
     * "the branch has not been written". A mode reintroduced as unbuilt would
     * fail here rather than being noticed on somebody's screen.
     */
    @Test
    public void noModeNamesABuildStageAnyMore() {
        for (Mode mode : Mode.values()) {
            RegDriftResult result = RegDrift.run(requestFor(mode));
            assertNotNull("mode " + mode.macroValue() + " returned nothing at all", result);
            if (result.failure() == null) continue;
            assertNotEquals("mode " + mode.macroValue() + " has been built, so it must not be"
                            + " naming a build stage any more", Failure.Kind.NOT_IMPLEMENTED,
                    result.failure().kind());
            assertFalse("mode " + mode.macroValue() + " must not open its reason with the"
                            + " not-implemented wording either: " + result.failure().message(),
                    result.failure().message().startsWith("not_implemented"));
            assertTrue("mode " + mode.macroValue() + " must give a reason a person can read: "
                            + result.failure().message(),
                    result.failure().message().endsWith("."));
        }
    }

    /**
     * Applying answers about this computer rather than about this build.
     *
     * <p>Nothing is installed in a test JVM, so the mode that has to run one
     * engine comes back saying so and naming what to do about it. That is the
     * answer it gives on a bare Fiji too, which is the case that matters - and it
     * is reached after the recording has been measured and the ranking worked
     * out, so the sentence can name the engine the measurements support.
     */
    @Test
    public void applyAnswersAboutThisComputer() {
        RegDriftResult result = RegDrift.run(requestFor(Mode.APPLY));

        assertNotNull("apply returned no reason", result.failure());
        assertEquals(result.failure().message(),
                Failure.Kind.ENGINE_UNAVAILABLE, result.failure().kind());
        assertTrue("the reason must name where an engine comes from: "
                        + result.failure().message(),
                result.failure().message().contains("Engines section"));
        assertTrue("and must say that nothing was fetched to produce it - house rule 9: "
                        + result.failure().message(),
                result.failure().message().contains("Nothing was fetched"));
        assertNull("no engine ran, so there is no registered stack", result.registered());
    }

    /**
     * A comparison with no engine to compare is a table of rows, not a refusal.
     *
     * <p>The third presentation rule of stage 13, run without a person: every
     * engine the comparison considered gets a row saying what would have to
     * change for it to run, and none of them is left out. A comparison that came
     * back with a sentence instead would leave somebody guessing which engines it
     * had in mind.
     */
    @Test
    public void comparingWithNothingInstalledStillGivesARowPerEngine() {
        RegDriftResult result = RegDrift.run(requestFor(Mode.COMPARE));

        assertNull(result.failure() == null ? "" : result.failure().message(), result.failure());
        assertNotNull("a comparison that ran nothing still measured the recording",
                result.verdict());
        assertEquals("every engine the catalogue knows about gets a row, present or not",
                result.ranked().size(), result.arms().size());
        assertFalse(result.arms().isEmpty());
        assertNotNull("and the rows reach the table too", result.comparison());
        assertEquals(result.arms().size(), result.comparison().size());
        for (ArmOutcome arm : result.arms()) {
            assertFalse("an engine that was never dispatched carries no rank: " + arm,
                    arm.isRanked());
            assertFalse("and says why, rather than leaving an empty cell: " + arm,
                    arm.detail().isEmpty());
        }
        assertNull("nothing ran, so there is no registered stack", result.registered());
    }

    /**
     * Stage 12 moved {@link Mode#SCORE} across: it rates a registration rather
     * than naming the stage it was waiting for.
     *
     * <p>The recording here is eight pixels square and uniformly black, which is
     * the honest hard case: there is nothing in it to measure a shift from, so the
     * shifts recovered are all zero, and a control built from them would resample
     * nothing at all. That is exactly the state defect D11 warns about - a control
     * that is an identity warp leaves {@code sd_vs_control} measuring the raw
     * recording and flatters every method - so the run <b>refuses</b> and says
     * which. It does not quietly score against the raw recording, and it does not
     * come back saying the mode is unbuilt.
     *
     * <p>What the mode produces on a recording that does carry structure is
     * {@code regdrift.score.ArbiterControlTest}'s business.
     */
    @Test
    public void scoreModeRatesRatherThanNamingAStage() {
        RegDriftResult result = RegDrift.run(requestFor(Mode.SCORE));

        assertNotNull(result.failure());
        assertEquals("a recording with nothing in it cannot be scored, and the reason is typed",
                Failure.Kind.SCORING_FAILED, result.failure().kind());
        assertFalse("and it is not the not-implemented reason any more",
                result.failure().message().startsWith("not_implemented"));
        assertTrue("the refusal names the defect it is protecting: "
                        + result.failure().message(),
                result.failure().message().contains("identity warp is not a control"));
        assertTrue("and says what it would otherwise have measured: "
                        + result.failure().message(),
                result.failure().message().contains("sd_vs_control"));
        assertEquals(Mode.SCORE, result.parameters().mode());
    }

    /** The one mode that measures, answering with a measurement rather than a reason. */
    @Test
    public void diagnoseModeMeasuresRatherThanNamingAStage() {
        RegDriftResult result = RegDrift.run(requestFor(Mode.DIAGNOSE));

        assertNull("diagnose was filled in by stage 09", result.failure());
        assertNotNull("so it answers with a verdict", result.verdict());
        assertNotNull(result.diagnosis());
        assertNotNull("and with the record of what it did", result.provenance());
        assertTrue("which states the scale it measured at, always - see defect D12",
                result.provenance().measuredAtBin() >= 1);
    }

    /**
     * The mode that ranks, answering with a ranking rather than a reason.
     *
     * <p>Stage 10 filled this branch in, so the assertion that used to read
     * "names build stage 10" reads "produced a ranking" instead. What is in the
     * ranking is {@code RecommenderTest}'s business; here it has to exist, be
     * numbered from rank 1, carry the recording's own table, and say which
     * recordings the figures in it were measured on - defect D10.
     */
    @Test
    public void diagnoseAndRecommendRanksRatherThanNamingAStage() {
        RegDriftResult result = RegDrift.run(requestFor(Mode.DIAGNOSE_AND_RECOMMEND));

        assertNull(result.failure() == null ? "" : result.failure().message(), result.failure());
        assertNotNull("it measures first, so it answers with a verdict too", result.verdict());
        assertNotNull(result.diagnosis());
        assertFalse("and with a ranking, which is what this mode adds",
                result.ranked().isEmpty());
        assertNotNull("carried as a table as well as as objects", result.recommendation());
        assertEquals("the ranking starts at rank 1, never at rank 0",
                1, result.ranked().get(0).rank());
        assertNotNull(result.provenance());
        assertTrue("which states the recordings the table was measured on - defect D10",
                result.provenance().calibrationSet().contains("three IncuCyte phase-contrast"));
        assertTrue("and nothing was installed to produce it - house rule 9",
                result.ranked().get(0).presence() != null);
    }

    /**
     * Five modes, five branches. A mode with no branch must not slip through -
     * whether it answers with a measurement or with the stage it arrives in.
     */
    @Test
    public void everyModeInTheEnumHasABranch() {
        for (Mode mode : Mode.values()) {
            RegDriftResult result = RegDrift.run(requestFor(mode));
            assertNotNull("mode " + mode + " returned nothing at all", result);
            assertTrue("mode " + mode + " returned an empty result rather than either a"
                            + " measurement or a reason",
                    result.failure() != null || result.verdict() != null);
        }
        assertEquals("five modes are wired here and in the dialog", 5, Mode.values().length);
    }

    /**
     * The default request needs nothing but a recording, and now answers with a
     * ranking rather than with the stage it was waiting for.
     */
    @Test
    public void aRecordingOnItsOwnIsEnoughToCallThis() {
        RegDriftResult result = RegDrift.run(stack(1, 1, 6, "movie.tif"));
        assertNotNull(result);
        assertEquals(Mode.DIAGNOSE_AND_RECOMMEND, result.parameters().mode());
        assertNull(result.failure() == null ? "" : result.failure().message(), result.failure());
        assertTrue("the default request measures and then ranks", result.isSuccess());
        assertFalse("so a recording on its own is enough to get a ranking",
                result.ranked().isEmpty());
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

    /**
     * A run that produced nothing says why, and says it in a readable sentence.
     *
     * <p>Asked of {@link Mode#APPLY} rather than of the default mode, because the
     * default mode produces something now. The rule this checks is about the
     * shape of a run that could not finish, not about which mode is unbuilt this
     * week, so it moves to whichever mode still has a reason to give.
     */
    @Test
    public void aRunThatCannotFinishGivesATypedReasonRatherThanNull() {
        RegDriftResult result = RegDrift.run(requestFor(Mode.APPLY));

        assertFalse(result.isSuccess());
        assertNotNull(result.failure());
        assertEquals(Failure.Kind.ENGINE_UNAVAILABLE, result.failure().kind());
        assertTrue("the reason must be a sentence, not a code",
                result.failure().message().endsWith("."));
        assertNull("a mode that could not run an engine produced no registered stack",
                result.registered());
        assertNull("a run that gave up carries no record, whatever it managed on the way",
                result.provenance());
        assertEquals("", result.verdictReason());
        assertTrue("nor a half-filled ranking", result.ranked().isEmpty());
        assertTrue("nor half-filled arms", result.arms().isEmpty());
        assertTrue("nor curves nobody can read a verdict beside", result.traces().isEmpty());
        assertNull(result.comparison());
        assertNull(result.qcPanel());
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

    /**
     * A plain stack is read as a time series, which is what the contract says.
     *
     * <p>Nine slices, one declared frame, no channels: an unlabelled TIFF from a
     * microscope. It is counted as nine time points and it runs, rather than
     * being refused as a single frame with no movement in it.
     */
    @Test
    public void aPlainStackIsReadAsATimeSeries() {
        ImagePlus plain = stack(1, 9, 1, "plain.tif");
        assertEquals(9, RegDrift.frameCount(plain));

        RegDriftResult result = RegDrift.run(plain);

        assertNull("nine slices are nine time points, not a still frame",
                result.failure() == null ? null : result.failure().kind());
        assertTrue(result.isSuccess());
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

    /**
     * The version travels with the result, and is written down in one file.
     *
     * <p>The reason a mode gave used to carry it too, which is how it was checked
     * before stage 13 built the last two modes and left no mode with a build
     * stage to name. What replaced that is the stronger check: the literal
     * appears in exactly one source file, so a release that bumps it cannot leave
     * a second copy behind saying something else.
     */
    @Test
    public void theVersionIsSpelledInOnePlace() throws java.io.IOException {
        assertFalse(RegDrift.VERSION.trim().isEmpty());
        assertEquals("a run that finished records it rather than spelling it again",
                RegDrift.VERSION,
                RegDrift.run(stack(1, 1, 6, "movie.tif")).provenance().pluginVersion());
        assertEquals("a comparison records it too", RegDrift.VERSION,
                RegDrift.run(requestFor(Mode.COMPARE)).provenance().pluginVersion());

        java.util.List<String> carrying = new java.util.ArrayList<String>();
        for (java.io.File source : javaFilesUnder(new java.io.File(projectRoot(),
                "src/main/java/regdrift"))) {
            String text = new String(java.nio.file.Files.readAllBytes(source.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (text.contains("\"" + RegDrift.VERSION + "\"")) carrying.add(source.getName());
        }
        assertEquals("the version literal belongs in RegDrift.java and nowhere else, and was"
                        + " found in " + carrying,
                java.util.Collections.singletonList("RegDrift.java"), carrying);
    }

    /** Every {@code .java} file under a folder of the repository. */
    private static java.util.List<java.io.File> javaFilesUnder(java.io.File folder) {
        java.util.List<java.io.File> found = new java.util.ArrayList<java.io.File>();
        java.io.File[] files = folder.listFiles();
        assertNotNull("this test reads source from " + folder.getAbsolutePath()
                + ", and it is not there", files);
        for (java.io.File file : files) {
            if (file.isDirectory()) {
                found.addAll(javaFilesUnder(file));
            } else if (file.getName().endsWith(".java")) {
                found.add(file);
            }
        }
        return found;
    }

    /** The repository root, found from where the compiled classes sit. */
    private static java.io.File projectRoot() {
        try {
            java.io.File output = new java.io.File(FacadeTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return output.getParentFile().getParentFile();
        } catch (java.net.URISyntaxException unreadable) {
            throw new AssertionError("could not find the source tree: " + unreadable);
        }
    }

    // -------------------------------------------------------------- fixtures

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
