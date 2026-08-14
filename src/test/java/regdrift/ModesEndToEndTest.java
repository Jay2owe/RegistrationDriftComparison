/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineFixtures;
import regdrift.autofix.EngineId;
import regdrift.harness.EngineDescriptor;
import regdrift.harness.EngineRunner;
import regdrift.internal.Transform;
import regdrift.score.ControlWarp;
import regdrift.ui.ResultsPanel;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * All five modes, end to end, over one recording built for the purpose.
 *
 * <h2>Why the engines are invented and the recording is not</h2>
 *
 * <p>The recording is real work: textured pixels drifting by a stated fraction
 * of a pixel per frame, which is what makes the fingerprint, the control and the
 * arbiter do their actual arithmetic. The <em>engines</em> are invented, through
 * the seam {@link RegDrift#bench}, and they have to be: what StackReg does to a
 * stack is a fact about somebody else's software on somebody else's machine, and
 * a test that depended on it would pass or fail according to whose laptop it ran
 * on. What is asserted here is everything this plugin decides - what is
 * dispatched, what is scored against what, how the results are ordered, and what
 * the screen says about them.
 *
 * <p>Each invented engine removes a stated share of the drift that is really
 * there, which is the same construction stage 12 measured its two-fold
 * separation claim with. Two of them remove all of it and are therefore
 * <b>indistinguishable by construction</b>: they exist to drive the rule that
 * says two arms inside the threshold share a rank rather than being put in an
 * order the measurement cannot justify.
 */
public class ModesEndToEndTest {

    /** Wide enough for the arbiter's margin to leave a real region behind. */
    private static final int SIDE = 96;

    private static final int FRAMES = 12;

    /**
     * Deliberately not whole numbers: a whole-pixel drift takes the block-copy
     * path, resamples nothing, and leaves the control an identity warp - which is
     * refused by name, and rightly (defect D11).
     */
    private static final double STEP_X = 0.73;
    private static final double STEP_Y = -0.41;

    /** The engines this invented computer has. TurboReg is here because StackReg needs it. */
    private static final Set<EngineId> HERE = EnumSet.of(EngineId.TURBOREG, EngineId.STACKREG,
            EngineId.MULTISTACKREG, EngineId.IMAGE_STABILIZER);

    /** What each invented engine takes out of the drift that is really there. */
    private static final Map<EngineId, Double> SHARE = shares();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private RegDrift.Bench realBench;
    private Arms arms;

    @Before
    public void putAnInventedComputerInFront() {
        realBench = RegDrift.bench;
        arms = new Arms();
        RegDrift.bench = bench(arms);
    }

    @After
    public void putTheRealComputerBack() {
        RegDrift.bench = realBench;
    }

    // ----------------------------------------------------------------- modes

    /**
     * Diagnosing measures and says whether the movement can be registered, and
     * draws nothing by itself.
     */
    @Test
    public void diagnoseMeasuresTheRecordingAndDrawsACurveFromIt() {
        RegDriftResult result = RegDrift.run(request(Mode.DIAGNOSE));

        assertNull(message(result), result.failure());
        assertNotNull(result.verdict());
        assertNotNull(result.diagnosis());
        assertTrue("diagnosing makes no recommendation", result.ranked().isEmpty());
        assertTrue("and drives nothing", result.arms().isEmpty());
        assertFalse("but it does hand back the curve of what it measured",
                result.traces().isEmpty());
        assertTrue("which is drawn without being asked for", traceOf(result,
                Trace.Kind.MOTION).shownByDefault());
        assertFalse("and the intensity trend, which is not", traceOf(result,
                Trace.Kind.INTENSITY).shownByDefault());
    }

    /**
     * Recommending ranks the engines that are here; comparing considers all ten.
     *
     * <p>The difference is deliberate and is the third presentation rule showing
     * up in the shape of the answer. {@code engines=installed} is the default, and
     * for a ranking it means what it says. For a comparison it would mean quietly
     * leaving out every engine somebody has not installed - and a comparison
     * listing four engines on a computer with four installed reads as a
     * comparison of the field, which it is not.
     */
    @Test
    public void recommendingRanksWhatIsHereAndComparingConsidersAllOfThem() {
        RegDriftResult ranking = RegDrift.run(request(Mode.DIAGNOSE_AND_RECOMMEND));

        assertNull(message(ranking), ranking.failure());
        assertEquals("the ranking is over the engines this computer has",
                HERE.size(), ranking.ranked().size());
        assertEquals(1, ranking.ranked().get(0).rank());
        assertTrue("nothing was driven to produce a ranking - house rule 9",
                ranking.arms().isEmpty());
        assertEquals("and no engine was dispatched", 0, arms.dispatched.get());

        RegDriftResult comparison = RegDrift.run(request(Mode.COMPARE));
        assertNull(message(comparison), comparison.failure());
        assertEquals("a comparison considers every engine this plugin knows about",
                EngineId.values().length, comparison.arms().size());
    }

    /**
     * Applying runs one engine, hands back the registered recording, and reports
     * what it did to the recording rather than what it did to the raw one.
     */
    @Test
    public void applyRunsOneEngineAndReportsWhatItDidAgainstTheControl() {
        RegDriftResult result = RegDrift.run(request(Mode.APPLY));

        assertNull(message(result), result.failure());
        assertEquals("apply drives one engine and no more", 1, arms.dispatched.get());
        assertEquals("and produces one row", 1, result.arms().size());
        ArmOutcome applied = result.arms().get(0);
        assertTrue("which is ranked, because it produced a figure", applied.isRanked());
        assertTrue("and the figure says the recording got stiller: " + applied,
                applied.sdVsControlPercent() < -regdrift.score.Arbiter.CANNOT_SEPARATE_PERCENT);
        assertNotNull("a registered recording comes back", result.registered());
        assertEquals(FRAMES, result.registered().getStackSize());
        assertNotNull("and the before-and-after panel is drawn from it", result.qcPanel());
        assertNotNull("with one row per frame of the arm that ran", result.frames());
        assertEquals(FRAMES, result.frames().size());
    }

    /**
     * Comparing runs every engine that is here, one after another, and ranks
     * them - sharing a rank where it cannot tell two apart.
     *
     * <p>The first presentation rule of this stage. Two of the invented engines
     * remove exactly the same drift, so no measurement could separate them; both
     * carry rank 1 and the later one carries the sentence saying which rank it
     * could not be separated from. A ranking that put one of them second would be
     * reporting a difference that is not there.
     */
    @Test
    public void twoArmsThatCannotBeSeparatedShareARankAndSayWhy() {
        RegDriftResult result = RegDrift.run(request(Mode.COMPARE));

        assertNull(message(result), result.failure());
        assertEquals("every engine the catalogue knows about gets a row",
                EngineId.values().length, result.arms().size());
        assertEquals("three of them are here and drivable", 3, arms.dispatched.get());

        List<ArmOutcome> ranked = new ArrayList<ArmOutcome>();
        for (ArmOutcome arm : result.arms()) {
            if (arm.isRanked()) ranked.add(arm);
        }
        assertEquals("three arms produced a figure", 3, ranked.size());
        assertEquals("the first is rank 1", 1, ranked.get(0).rank());
        assertEquals("and so is the second, because nothing separates them: "
                        + ranked.get(0) + " / " + ranked.get(1), 1, ranked.get(1).rank());
        assertFalse("the arm that leads its group claims nothing about a tie",
                ranked.get(0).sharesItsRank());
        assertTrue("the one beside it says which rank it could not be separated from",
                ranked.get(1).sharesItsRank());
        assertEquals(1, ranked.get(1).cannotSeparateFromRank());
        assertTrue(ranked.get(1).rankNote(),
                ranked.get(1).rankNote().contains("cannot separate from rank 1"));
        assertTrue("and the reason names the threshold it came from: "
                        + ranked.get(1).rankNote(), ranked.get(1).rankNote().contains("3.0%"));

        assertTrue("the third arm is far enough away to hold a rank of its own: "
                        + ranked.get(2), ranked.get(2).rank() > 1);
        assertFalse(ranked.get(2).sharesItsRank());
        assertTrue("and no rank is produced where nothing was measured",
                Math.abs(ranked.get(0).sdVsControlPercent() - ranked.get(2).sdVsControlPercent())
                        >= regdrift.score.Arbiter.CANNOT_SEPARATE_PERCENT);

        assertTrue("the shared rank reaches the saved table too, as a token a script can read",
                RegDriftTables.cellText(result.comparison(), "status",
                        rowOf(result, ranked.get(1).engineName()))
                        .contains("cannot_separate_from_rank=1"));
    }

    /**
     * Every arm is scored against one control, so the figures can be put beside
     * each other.
     *
     * <p>Two engines that did exactly the same thing to the recording come out at
     * exactly the same figure. Scored against their own transforms they would
     * too, so what this really pins down is the arithmetic below it: the control
     * came from the recording's own movement rather than from any arm, so the
     * arm that fails or is missing cannot move anybody else's number. The
     * provenance says which, in words, because the two are different claims about
     * the same column.
     */
    @Test
    public void everyArmIsScoredAgainstOneControlTakenFromTheRecordingItself() {
        RegDriftResult result = RegDrift.run(request(Mode.COMPARE));

        assertNull(message(result), result.failure());
        double first = Double.NaN;
        for (ArmOutcome arm : result.arms()) {
            if (!arm.hasFigure()) continue;
            if (Double.isNaN(first)) {
                first = arm.sdVsControlPercent();
                continue;
            }
            if (SHARE.get(arm.engine()).doubleValue() != 1.0) continue;
            assertEquals("two engines that did the same thing must read the same figure",
                    first, arm.sdVsControlPercent(), 1e-9);
        }
        String record = result.provenance().calibrationSet();
        assertTrue("the record says the control came from the recording rather than from an arm: "
                        + record,
                record.contains("the recording's own movement, measured before any engine ran"));
        assertTrue("and that every arm shared it: " + record,
                record.contains("shared by every arm of this comparison"));
    }

    /** Scoring rates a registration somebody else produced, on its own terms. */
    @Test
    public void scoreRatesARegistrationThisPluginDidNotProduce() {
        Drifting fixture = fixture();
        RegDriftResult result = RegDrift.run(RegDriftParameters
                .builder(fixture.rawStack("movie.tif"))
                .mode(Mode.SCORE)
                .compareWith(fixture.registeredStack("registered.tif", 1.0))
                .build());

        assertNull(message(result), result.failure());
        assertEquals("nothing is driven to score something already registered",
                0, arms.dispatched.get());
        assertNotNull(result.comparison());
        assertEquals(1, result.comparison().size());
        assertTrue("the figure says the registration held the field still",
                RegDriftTables.cellText(result.comparison(), "status", 0).startsWith("improved"));
    }

    // --------------------------------------------------------- the three rules

    /**
     * An engine that is not on this computer is a row with what to do about it.
     *
     * <p>The third presentation rule. Six of the ten engines are absent from the
     * invented computer and every one of them is in the table, with the sentence
     * the Engines section would show. A comparison that dropped them would look
     * like a complete answer over a set somebody never chose.
     */
    @Test
    public void anEngineThatIsNotHereIsARowWithItsInstallAction() {
        RegDriftResult result = RegDrift.run(request(Mode.COMPARE));

        int absent = 0;
        for (ArmOutcome arm : result.arms()) {
            if (HERE.contains(arm.engine())) continue;
            absent++;
            assertEquals("an engine that is not here says so: " + arm,
                    regdrift.harness.ArmStatus.NOT_INSTALLED, arm.status());
            assertFalse("with a sentence rather than an empty cell: " + arm,
                    arm.detail().isEmpty());
            assertTrue("naming what installing it would take: " + arm.detail(),
                    arm.detail().contains("not on this computer"));
            assertFalse("and never a rank, because nothing about it was measured: " + arm,
                    arm.isRanked());
        }
        assertEquals("six of the ten are absent from this invented computer",
                EngineId.values().length - HERE.size(), absent);
        assertEquals("and every one of them is in the table",
                EngineId.values().length, result.comparison().size());
    }

    /**
     * An engine that is here but that this build drives no arm for is a row too,
     * carrying its own reason rather than a missing-install one.
     */
    @Test
    public void anEngineWithNoArmSaysWhyRatherThanReadingAsAbsent() {
        RegDriftResult result = RegDrift.run(request(Mode.COMPARE));

        ArmOutcome turboReg = armFor(result, EngineId.TURBOREG);
        assertEquals(regdrift.harness.ArmStatus.COULD_NOT_DRIVE, turboReg.status());
        assertEquals(EngineDescriptor.forEngine(EngineId.TURBOREG).notDrivableReason(),
                turboReg.detail());
        assertFalse(turboReg.isRanked());
    }

    /**
     * The calibration flag sits beside the engine's name, and the sentence saying
     * what the calibration set is sits at the top of the same screen.
     *
     * <p>The second presentation rule, checked where a person would meet it. The
     * fixture is 96 pixels square, which is too small to be measured at the
     * factor the calibration's own structure figures were taken at, so every row
     * is outside the calibrated range and says so on its own line.
     */
    @Test
    public void theCalibrationFlagIsBesideTheNameAndTheCalibrationSetIsOnTheSameScreen() {
        RegDriftResult result = RegDrift.run(request(Mode.DIAGNOSE_AND_RECOMMEND));

        List<String> lines = ResultsPanel.lines(result);
        int flagged = 0;
        for (String line : lines) {
            if (!line.contains(ResultsPanel.OUTSIDE_FLAG)) continue;
            flagged++;
            int flag = line.indexOf(ResultsPanel.OUTSIDE_FLAG);
            String beforeTheFlag = line.substring(0, flag);
            assertTrue("the flag belongs after the engine's name on the same line: " + line,
                    beforeTheFlag.contains(result.ranked().get(0).engine())
                            || beforeTheFlag.trim().endsWith(nameOn(line, flag)));
        }
        assertEquals("every ranked row is flagged on this fixture", result.ranked().size(),
                flagged);

        boolean saidWhatItWasMeasuredOn = false;
        for (String line : lines) {
            saidWhatItWasMeasuredOn |= line.startsWith("Calibration:")
                    && line.contains("three IncuCyte phase-contrast");
        }
        assertTrue("the calibration set belongs on this screen, not in a footnote: " + lines,
                saidWhatItWasMeasuredOn);

        boolean saidWhatIsMeasuredForMostEngines = false;
        for (String line : lines) {
            saidWhatIsMeasuredForMostEngines |= line.contains(ResultsPanel.NO_MEASURED_ROW);
        }
        assertTrue("the engines nobody timed say so rather than showing an empty cell: " + lines,
                saidWhatIsMeasuredForMostEngines);
    }

    /** The results view says the same thing about a shared rank that the row does. */
    @Test
    public void theResultsViewShowsASharedRankAndTheReasonForIt() {
        RegDriftResult result = RegDrift.run(request(Mode.COMPARE));
        List<String> lines = ResultsPanel.lines(result);

        int rankOne = 0;
        boolean saidWhy = false;
        for (String line : lines) {
            if (line.startsWith("  rank 1  ")) rankOne++;
            saidWhy |= line.contains("cannot separate from rank 1");
        }
        assertTrue("rank 1 appears twice, which is the correct output here: " + lines,
                rankOne >= 2);
        assertTrue("and the reason is beside it: " + lines, saidWhy);
        assertTrue("the head-to-head is on the screen", lines.contains(
                ResultsPanel.HEAD_TO_HEAD_HEADING));
        assertTrue("and so is what this plugin actually measured for most engines",
                ResultsPanel.clipboardText(result).contains(
                        "never run on the calibration set"));
    }

    // ------------------------------------------------------- before dispatch

    /**
     * The estimate arrives before anything is driven, and stopping at it runs
     * nothing.
     */
    @Test
    public void stoppingAtTheEstimateDrivesNothingAtAll() {
        final List<DispatchEstimate> asked = new ArrayList<DispatchEstimate>();
        RegDriftResult result = RegDrift.run(RegDriftParameters
                .builder(fixture().rawStack("movie.tif"))
                .mode(Mode.COMPARE)
                .dispatch(new Dispatch() {
                    @Override
                    public boolean proceed(DispatchEstimate estimate) {
                        asked.add(estimate);
                        return false;
                    }
                })
                .build());

        assertEquals("the question is asked once, before anything runs", 1, asked.size());
        assertEquals("and nothing was dispatched", 0, arms.dispatched.get());
        assertFalse(result.isSuccess());
        assertEquals(Failure.Kind.CANCELED, result.failure().kind());
        assertNull("nothing was registered", result.registered());
        assertNull("and no comparison table was produced", result.comparison());

        DispatchEstimate estimate = asked.get(0);
        assertEquals("the estimate covers the arms that would have run", 3, estimate.armCount());
        assertTrue("in processor seconds, and more than none of them",
                estimate.totalSeconds() > 0);
        assertTrue("it says the per-engine figure is a stand-in rather than a measurement of the"
                        + " engines in front of you: " + estimate.text(),
                estimate.text().contains("a stand-in rather than a measurement of the engines"));
        assertTrue("that processor time is not clock time: " + estimate.text(),
                estimate.text().contains("Processor time is not the time on the clock"));
        assertTrue("and that stopping here runs nothing: " + estimate.text(),
                estimate.text().contains("Stopping here runs nothing at all"));
    }

    /** Nobody is asked when there is nothing to drive. */
    @Test
    public void noEstimateIsShownWhenThereIsNothingToDrive() {
        RegDrift.bench = bench(arms, EnumSet.noneOf(EngineId.class));
        final AtomicInteger asked = new AtomicInteger();
        RegDriftResult result = RegDrift.run(RegDriftParameters
                .builder(fixture().rawStack("movie.tif"))
                .mode(Mode.COMPARE)
                .dispatch(new Dispatch() {
                    @Override
                    public boolean proceed(DispatchEstimate estimate) {
                        asked.incrementAndGet();
                        return true;
                    }
                })
                .build());

        assertEquals("there is nothing to warn about", 0, asked.get());
        assertTrue(message(result), result.isSuccess());
        assertEquals("and every engine is still a row", EngineId.values().length,
                result.arms().size());
    }

    /** A run stopped before it starts drives nothing and says so. */
    @Test
    public void stoppingBeforeTheFirstArmDrivesNothing() {
        Cancellation.Flag stop = Cancellation.flag();
        stop.cancel();
        RegDriftResult result = RegDrift.run(RegDriftParameters
                .builder(fixture().rawStack("movie.tif"))
                .mode(Mode.COMPARE)
                .cancellation(stop)
                .build());

        assertEquals(0, arms.dispatched.get());
        assertFalse(result.isSuccess());
        assertEquals(Failure.Kind.CANCELED, result.failure().kind());
    }

    /** Stopping part-way through stops before the next engine, not inside one. */
    @Test
    public void stoppingPartWayStopsBeforeTheNextEngine() {
        final Cancellation.Flag stop = Cancellation.flag();
        arms.stopAfterFirstArm = stop;
        RegDriftResult result = RegDrift.run(RegDriftParameters
                .builder(fixture().rawStack("movie.tif"))
                .mode(Mode.COMPARE)
                .cancellation(stop)
                .build());

        assertEquals("the arm that was already running was allowed to finish",
                1, arms.dispatched.get());
        assertFalse(result.isSuccess());
        assertEquals(Failure.Kind.CANCELED, result.failure().kind());
    }

    // ----------------------------------------------------------- no windows

    /**
     * Gate item 5: no window, no plot, no shown table on any mode, and the result
     * object filled in all the same.
     */
    @Test
    public void hidingTheDisplayShowsNothingOnAnyModeAndStillFillsTheResult() {
        assertEquals("this test runs with no ImageJ window open, by design",
                0, WindowManager.getImageCount());
        for (Mode mode : Mode.values()) {
            RegDriftResult result = RegDrift.run(request(mode, true));

            assertNull("mode " + mode.macroValue() + ": " + message(result), result.failure());
            assertEquals("mode " + mode.macroValue() + " opened a window", 0,
                    WindowManager.getImageCount());
            assertNull("nor did it start ImageJ itself", ij.IJ.getInstance());
            assertNotNull("mode " + mode.macroValue() + " measured nothing",
                    result.provenance());
            if (mode == Mode.SCORE) continue;
            assertNotNull(result.diagnosis());
            assertNotNull(result.verdict());
        }
    }

    /** The registered recording and the panel drawn from it reach the saved tree. */
    @Test
    public void autoSaveWritesTheRegisteredStackAndTheBeforeAndAfterPanel() throws Exception {
        File root = folder.newFolder("results");
        RegDriftResult result = RegDrift.run(RegDriftParameters
                .builder(fixture().rawStack("movie.tif"))
                .mode(Mode.APPLY)
                .saveRoot(root.getAbsolutePath())
                .build());
        assertNull(message(result), result.failure());

        RegDriftAutoSave.Report report = RegDriftAutoSave.save(result);
        assertTrue(report.failure() == null ? "" : report.failure().message(), report.isSuccess());

        File tree = new File(root, RegDriftAutoSave.TREE_FOLDER);
        File registered = new File(tree, RegDriftAutoSave.REGISTERED_FOLDER);
        File qc = new File(tree, RegDriftAutoSave.QC_FOLDER);
        assertEquals("one registered recording, named after the engine that produced it",
                1, registered.list().length);
        assertTrue(registered.list()[0], registered.list()[0].endsWith(".tif"));
        assertTrue("named after the engine rather than after the run: " + registered.list()[0],
                registered.list()[0].contains(result.arms().get(0).engineName()
                        .replace(' ', '_')));
        assertEquals("and one before-and-after panel", 1, qc.list().length);
        assertEquals("movie_kymograph.tif", qc.list()[0]);
        assertTrue("the comparison table is written too",
                new File(tree, "comparison/movie_comparison.csv").isFile());
        assertTrue("and one row per frame of the arm that ran",
                new File(tree, "frames/movie_frames.csv").isFile());
    }

    // ------------------------------------------------------------- fixtures

    private RegDriftParameters request(Mode mode) {
        return request(mode, false);
    }

    private RegDriftParameters request(Mode mode, boolean hide) {
        Drifting fixture = fixture();
        RegDriftParameters.Builder builder = RegDriftParameters
                .builder(fixture.rawStack("movie.tif"))
                .mode(mode)
                .hideDisplay(hide);
        if (mode == Mode.SCORE) {
            builder.compareWith(fixture.registeredStack("registered.tif", 1.0));
        }
        return builder.build();
    }

    private static String message(RegDriftResult result) {
        return result.failure() == null ? "" : result.failure().message();
    }

    private static Trace traceOf(RegDriftResult result, Trace.Kind kind) {
        for (Trace trace : result.traces()) {
            if (trace.kind() == kind) return trace;
        }
        throw new AssertionError("no " + kind + " curve came back: " + result.traces());
    }

    private static ArmOutcome armFor(RegDriftResult result, EngineId engine) {
        for (ArmOutcome arm : result.arms()) {
            if (arm.engine() == engine) return arm;
        }
        throw new AssertionError(engine + " has no row at all, which is the omission this stage"
                + " exists to prevent");
    }

    private static int rowOf(RegDriftResult result, String engineName) {
        for (int row = 0; row < result.comparison().size(); row++) {
            if (engineName.equals(RegDriftTables.cellText(result.comparison(), "engine", row))) {
                return row;
            }
        }
        throw new AssertionError(engineName + " has no row in the comparison table");
    }

    /** The name that sits just before a flag on one line of the results view. */
    private static String nameOn(String line, int flagAt) {
        return line.substring(0, flagAt).trim();
    }

    private static Map<EngineId, Double> shares() {
        Map<EngineId, Double> shares = new EnumMap<EngineId, Double>(EngineId.class);
        // Two engines that take out all of the drift, and are therefore identical by
        // construction: no measurement could separate them, and the ranking has to say so.
        shares.put(EngineId.STACKREG, Double.valueOf(1.0));
        shares.put(EngineId.MULTISTACKREG, Double.valueOf(1.0));
        // And one that takes out half, which stage 12 measured as separating comfortably.
        shares.put(EngineId.IMAGE_STABILIZER, Double.valueOf(0.5));
        return shares;
    }

    private RegDrift.Bench bench(Arms driver) {
        return bench(driver, HERE);
    }

    private RegDrift.Bench bench(final Arms driver, final Set<EngineId> here) {
        final AutofixService catalogue = EngineFixtures.serviceWherePresent(
                here.toArray(new EngineId[0]));
        final EngineRunner runner = new EngineRunner(new EngineRunner.Presence() {
            @Override
            public boolean installed(EngineId engine) {
                return here.contains(engine);
            }
        }, driver);
        return new RegDrift.Bench() {
            @Override
            public AutofixService catalogue() {
                return catalogue;
            }

            @Override
            public EngineRunner runner() {
                return runner;
            }
        };
    }

    /**
     * The invented engines: each takes out a stated share of the drift that is
     * really in the recording.
     */
    private static final class Arms implements EngineRunner.Driver {

        private final AtomicInteger dispatched = new AtomicInteger();
        private Cancellation.Flag stopAfterFirstArm;

        @Override
        public void drive(ImagePlus working, EngineDescriptor engine, String options) {
            dispatched.incrementAndGet();
            double share = SHARE.containsKey(engine.id())
                    ? SHARE.get(engine.id()).doubleValue() : 1.0;
            ImageStack stack = working.getStack();
            int width = working.getWidth();
            int height = working.getHeight();
            for (int slice = 1; slice <= stack.getSize(); slice++) {
                int t = slice - 1;
                Transform undo = Transform.translation(-share * STEP_X * t, -share * STEP_Y * t);
                float[] pixels = (float[]) stack.getProcessor(slice).convertToFloat().getPixels();
                stack.setPixels(ControlWarp.warp(pixels, width, height, undo,
                        ControlWarp.Interpolation.BILINEAR, 0f), slice);
            }
            if (stopAfterFirstArm != null) stopAfterFirstArm.cancel();
        }
    }

    private static Drifting fixture() {
        return new Drifting(SIDE, FRAMES, STEP_X, STEP_Y);
    }

    /**
     * A textured recording that drifts by a stated fraction of a pixel per frame,
     * cut out of a larger field so that nothing has to be filled in.
     */
    private static final class Drifting {

        private final int side;
        private final float[][] raw;

        Drifting(int side, int frames, double stepX, double stepY) {
            this.side = side;
            int field = side + 64;
            float[] world = texture(field, field, 4242L);
            this.raw = new float[frames][];
            for (int t = 0; t < frames; t++) {
                raw[t] = sampled(world, field, field, 32 + stepX * t, 32 + stepY * t, side);
            }
        }

        ImagePlus rawStack(String title) {
            return stack(title, raw);
        }

        /** The recording after an engine that took out this share of the drift. */
        ImagePlus registeredStack(String title, double share) {
            float[][] out = new float[raw.length][];
            for (int t = 0; t < raw.length; t++) {
                Transform undo = Transform.translation(-share * STEP_X * t, -share * STEP_Y * t);
                out[t] = ControlWarp.warp(raw[t], side, side, undo,
                        ControlWarp.Interpolation.BILINEAR, 0f);
            }
            return stack(title, out);
        }

        private ImagePlus stack(String title, float[][] planes) {
            ImageStack images = new ImageStack(side, side);
            for (int t = 0; t < planes.length; t++) {
                images.addSlice("t" + (t + 1),
                        new FloatProcessor(side, side, planes[t].clone(), null));
            }
            return new ImagePlus(title, images);
        }
    }

    /** Fine-grained texture with structure at every scale, from a fixed seed. */
    private static float[] texture(int w, int h, long seed) {
        java.util.Random random = new java.util.Random(seed);
        float[] plane = new float[w * h];
        for (int i = 0; i < plane.length; i++) {
            plane[i] = 400f + 120f * (float) random.nextGaussian();
        }
        for (int pass = 0; pass < 3; pass++) {
            float[] smoothed = new float[plane.length];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double sum = 0;
                    int counted = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                            sum += plane[ny * w + nx];
                            counted++;
                        }
                    }
                    smoothed[y * w + x] = (float) (sum / counted);
                }
            }
            plane = smoothed;
        }
        return plane;
    }

    /** A window of the field, sampled bilinearly at a fractional offset. */
    private static float[] sampled(float[] field, int fieldWidth, int fieldHeight,
                                   double ox, double oy, int side) {
        float[] out = new float[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                double fx = ox + x;
                double fy = oy + y;
                int x0 = (int) Math.floor(fx);
                int y0 = (int) Math.floor(fy);
                double ax = fx - x0;
                double ay = fy - y0;
                out[y * side + x] = (float) (
                        at(field, fieldWidth, fieldHeight, x0, y0) * (1 - ax) * (1 - ay)
                                + at(field, fieldWidth, fieldHeight, x0 + 1, y0) * ax * (1 - ay)
                                + at(field, fieldWidth, fieldHeight, x0, y0 + 1) * (1 - ax) * ay
                                + at(field, fieldWidth, fieldHeight, x0 + 1, y0 + 1) * ax * ay);
            }
        }
        return out;
    }

    private static double at(float[] field, int w, int h, int x, int y) {
        int cx = Math.max(0, Math.min(w - 1, x));
        int cy = Math.max(0, Math.min(h - 1, y));
        return field[cy * w + cx];
    }
}
