/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.advise;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import org.junit.Test;
import regdrift.Mode;
import regdrift.RegDrift;
import regdrift.RegDriftParameters;
import regdrift.RegDriftResult;
import regdrift.RegDriftTables;
import regdrift.Recommendation;
import regdrift.autofix.EngineId;
import regdrift.autofix.EngineRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T8. The lookup: that every figure it prints was recomputed from the bundled
 * files rather than written down, that the condition mapping does what its
 * javadoc says on each branch, and that the calibration flag fires where it
 * should and changes nothing else when it does.
 *
 * <h2>Nothing here is asserted against a hand-written number</h2>
 *
 * <p>Every expected error and every expected CPU second below is recomputed, in
 * this test, by a second parser reading the CSV off disk. That is deliberate:
 * an expectation typed into a test is a copy of the table that can drift from
 * the table, and the whole claim of this stage is that the recommendation and
 * the evidence are the same thing. The two exceptions are marked where they
 * appear, and both are assertions <em>about</em> the files rather than about
 * their contents.
 */
public class RecommenderTest {

    /** A recording that maps to CLEAN: stable intensity, nothing bright. */
    private static final double STABLE = 0.01;

    /** No bright structure at all, which is what ten of the twelve library entries read. */
    private static final double NOTHING_BRIGHT = 0.0;

    /** A localisability inside the upper of the two regimes the survey found. */
    private static final double IN_THE_UPPER_REGIME = 0.1300;

    /** A localisability inside the lower regime. */
    private static final double IN_THE_LOWER_REGIME = 0.0170;

    private static final int FRAMES = 48;

    // ------------------------------------------------- every figure traces back

    /**
     * Gate 2. For every constructed condition, the figure the ranking prints is
     * the figure a second parser computes from the CSV on disk - row by row,
     * seed by seed.
     */
    @Test
    public void everyExpectedFigureIsRecomputedFromTheBundledCsv() throws IOException {
        for (CalibrationTable.Condition condition : CalibrationTable.Condition.values()) {
            List<String[]> arm = armRowsOf(condition);
            assertEquals("the benchmark ran three seeds under " + condition, 3, arm.size());

            double[] errors = new double[arm.size()];
            double[] costs = new double[arm.size()];
            for (int i = 0; i < arm.size(); i++) {
                errors[i] = Double.parseDouble(arm.get(i)[8]);
                costs[i] = Double.parseDouble(arm.get(i)[7]);
            }
            Arrays.sort(errors);
            Arrays.sort(costs);
            double expectedError = errors[1];
            double expectedCost = costs[1];

            CalibrationTable.Arm measured =
                    CalibrationTable.bundled().armFor(EngineId.STACKREG, condition);
            assertNotNull("StackReg has a measured arm under " + condition, measured);
            Recommender.Request request = requestFor(condition);
            assertEquals(condition + ": median across the seeds",
                    expectedError, measured.medianErrPx(), 1e-12);
            assertEquals(condition + ": lowest seed", errors[0], measured.lowestErrPx(), 1e-12);
            assertEquals(condition + ": highest seed",
                    errors[errors.length - 1], measured.highestErrPx(), 1e-12);
            assertEquals(condition + ": CPU ms per pair",
                    expectedCost, measured.cpuMsPerPair(), 1e-12);
            if (request == null) {
                // CHANGE_MOVED: no branch of the mapping reaches it, so its figures are checked
                // against the file above and there is no ranking to check them through.
                continue;
            }

            Recommender.Result ranked = Recommender.rank(request,
                    Recommender.everyEngine(), absentEverywhere());
            assertEquals("the mapping has to reach " + condition,
                    condition, ranked.condition());
            Recommendation stackReg = rowFor(ranked, EngineId.STACKREG);
            assertEquals(condition + ": expected_error_px",
                    expectedError, stackReg.expectedErrorPx(), 1e-12);
            assertEquals(condition + ": expected_seconds is cpu_ms_per_pair x pairs / 1000",
                    expectedCost * (FRAMES - 1) / 1000.0, stackReg.expectedSeconds(), 1e-12);
            assertTrue(condition + ": the reason names the file it came from: "
                            + stackReg.reason(),
                    stackReg.reason().contains(CalibrationTable.THIRD_PARTY_FILE));
        }
    }

    /**
     * The CPU figure scales with the recording, and with nothing else. Twice the
     * frames, twice the seconds - because it is a per-pair cost multiplied by the
     * pair count, which is the whole of what {@code expected_seconds} is.
     */
    @Test
    public void expectedSecondsScalesWithTheFrameCountAndNothingElse() {
        Recommendation shortRun = rowFor(Recommender.rank(
                        request(IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, 101),
                        Recommender.everyEngine(), absentEverywhere()),
                EngineId.STACKREG);
        Recommendation longRun = rowFor(Recommender.rank(
                        request(IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, 201),
                        Recommender.everyEngine(), absentEverywhere()),
                EngineId.STACKREG);
        assertEquals(2 * shortRun.expectedSeconds(), longRun.expectedSeconds(), 1e-12);
        assertEquals("and the measured error does not move with the length",
                shortRun.expectedErrorPx(), longRun.expectedErrorPx(), 1e-12);
    }

    // ------------------------------------------------------- the ranking itself

    /**
     * Engines the benchmark measured come first, ordered by what it measured;
     * engines it never ran come after them and say so.
     */
    @Test
    public void measuredEnginesRankAheadOfEnginesTheBenchmarkNeverRan() {
        Recommender.Result ranked = Recommender.rank(
                request(IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, FRAMES),
                Recommender.everyEngine(), absentEverywhere());

        List<Recommendation> rows = ranked.ranked();
        assertEquals(EngineId.values().length, rows.size());
        boolean seenUnmeasured = false;
        for (int i = 0; i < rows.size(); i++) {
            Recommendation row = rows.get(i);
            assertEquals("rank is a position, starting at 1", i + 1, row.rank());
            boolean measured = !Double.isNaN(row.expectedErrorPx());
            if (!measured) seenUnmeasured = true;
            assertFalse("a measured engine appeared after an unmeasured one: " + rows,
                    measured && seenUnmeasured);
            if (!measured) {
                assertTrue("an unmeasured row has to say so: " + row.reason(),
                        row.reason().contains("no measured row"));
                assertTrue("expected_seconds is unmeasured too",
                        Double.isNaN(row.expectedSeconds()));
            }
        }
        assertEquals("the two engines the benchmark drove come first",
                2, countMeasured(rows));
    }

    /**
     * {@code rank = 1} is a position in a list and the wording never says
     * otherwise - house rule 6, and exit gate 8.
     */
    @Test
    public void nothingInTheAdvicePackageClaimsAnEngineIsTheBestOne() throws IOException {
        String[] forbidden = {"b" + "est", "opti" + "mal", "accu" + "racy"};
        for (File source : javaFilesUnder("src/main/java/regdrift/advise")) {
            String text = read(source).toLowerCase(Locale.ROOT);
            for (String word : forbidden) {
                assertFalse(source.getName() + " uses '" + word + "'. The ranking says which"
                                + " engine scored highest on this movement by a named measurement,"
                                + " and shows the number; it does not say which one is right.",
                        Pattern.compile("\\b" + word).matcher(text).find());
            }
        }
    }

    /**
     * Exit gate 7. No figure anywhere in the advice package can come from a wall
     * clock - defect D6, and the 237,000 ms per pair that was a laptop standby.
     */
    @Test
    public void nothingInTheAdvicePackageCanReadAWallClock() throws IOException {
        for (File source : javaFilesUnder("src/main/java/regdrift/advise")) {
            String text = read(source);
            assertFalse(source.getName() + " reads a wall clock; every timing this plugin shows or"
                            + " ranks on is CPU time - defect D6",
                    text.contains("currentTimeMillis") || text.contains("nanoTime")
                            || text.contains("System.currentTime"));
        }
    }

    /** Exit gate 9. The engine with no update site is nowhere in the output. */
    @Test
    public void logRatioRegistrationIsNotInTheCatalogueOrTheRecommendation() {
        for (String name : EngineRegistry.displayNames()) {
            assertFalse("the catalogue names an engine that cannot be installed: " + name,
                    name.toLowerCase(Locale.ROOT).contains("log-ratio"));
        }
        Recommender.Result ranked = Recommender.rank(
                request(IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, FRAMES),
                Recommender.everyEngine(), absentEverywhere());
        for (Recommendation row : ranked.ranked()) {
            String all = (row.engine() + " " + row.reason() + " " + row.menuPath() + " "
                    + row.macroLine() + " " + row.installAction()).toLowerCase(Locale.ROOT);
            assertFalse("a recommendation row names an engine that cannot be installed: " + row,
                    all.contains("log-ratio"));
        }
    }

    // ------------------------------------------------------ the condition mapping

    /**
     * The judgement, branch by branch, with the evidence behind each in the
     * javadoc of {@link Recommender#conditionOf}.
     */
    @Test
    public void eachBranchOfTheConditionMappingReachesTheConditionItSays() {
        assertEquals(CalibrationTable.Condition.CLEAN,
                conditionOf(0.0, 0.0));
        assertEquals("the largest trend measured on a real recording is still clean",
                CalibrationTable.Condition.CLEAN, conditionOf(-0.3763, 0.0));
        assertEquals("and the largest bright share measured on a real recording is too",
                CalibrationTable.Condition.CLEAN, conditionOf(0.0, 0.0048));

        assertEquals("half the benchmark's own 4x ramp is a fade",
                CalibrationTable.Condition.GAIN_FADE, conditionOf(-1.0, 0.0));
        assertEquals("and so is the whole of it",
                CalibrationTable.Condition.GAIN_FADE, conditionOf(-2.0, 0.0));
        assertEquals("a fade upward is a fade",
                CalibrationTable.Condition.GAIN_FADE, conditionOf(2.0, 0.0));

        assertEquals("half the benchmark's own block coverage is a bright artefact",
                CalibrationTable.Condition.CHANGE_BLOCKS, conditionOf(0.0, 0.05));
        assertEquals("and so is the whole of it",
                CalibrationTable.Condition.CHANGE_BLOCKS, conditionOf(0.0, 0.10));
    }

    /**
     * The two gaps, and the rule that a recording in one is flagged rather than
     * rounded to the nearer side.
     *
     * <p>This is the survey's two-regime problem in a second place: the
     * benchmark constructed one value of each nuisance and real recordings sit
     * far below it, so between the two there is nothing at all. Picking the
     * nearer edge would be inventing a measurement.
     */
    @Test
    public void aRecordingBetweenTheMeasuredValuesIsFlaggedRatherThanRounded() {
        assertNull("halfway up the fade axis is a gap nobody measured",
                conditionOf(0.6, 0.0));
        assertNull("and halfway up the bright axis is another",
                conditionOf(0.0, 0.02));
        assertTrue("the reason has to name the gap: "
                        + Recommender.conditionOf(0.6, 0.0).reason(),
                Recommender.conditionOf(0.6, 0.0).reason().contains("Nothing was measured in"
                        + " between"));
    }

    /** The benchmark never combined two nuisances, so nothing describes a recording with both. */
    @Test
    public void aRecordingWithBothNuisancesMapsToNoCondition() {
        assertNull(conditionOf(-2.0, 0.10));
        assertTrue(Recommender.conditionOf(-2.0, 0.10).reason(),
                Recommender.conditionOf(-2.0, 0.10).reason()
                        .contains("one nuisance at a time"));
    }

    /**
     * The fourth condition the benchmark constructed has no branch, because the
     * fingerprint has no measurement that would identify it. Stated as a test so
     * that a later contributor who folds it into {@code CLEAN} - which is
     * tempting, since the two read 0.30 px and 0.33 px for the one engine
     * measured - has to argue with the build first.
     */
    @Test
    public void noBranchEverReachesTheConditionTheFingerprintCannotIdentify() {
        double[] trends = {-2.5, -2.0, -1.0, -0.6, -0.38, 0.0, 0.38, 0.6, 1.0, 2.0, 2.5};
        double[] shares = {0.0, 0.001, 0.005, 0.02, 0.05, 0.10, 0.30};
        for (double trend : trends) {
            for (double share : shares) {
                assertFalse("(" + trend + ", " + share + ") reached CHANGE_MOVED",
                        CalibrationTable.Condition.CHANGE_MOVED == conditionOf(trend, share));
            }
        }
        assertNotNull("and its rows are still in the bundled file, unread",
                CalibrationTable.bundled().armFor(EngineId.STACKREG,
                        CalibrationTable.Condition.CHANGE_MOVED));
    }

    /** A measurement that failed maps to nothing rather than to the nearest thing. */
    @Test
    public void anUnmeasurableRecordingMapsToNoConditionAndSaysWhich() {
        assertNull(conditionOf(Double.NaN, 0.0));
        assertTrue(Recommender.conditionOf(Double.NaN, 0.0).reason(),
                Recommender.conditionOf(Double.NaN, 0.0).reason().contains("intensity trend"));
        assertNull(conditionOf(0.0, Double.NaN));
        assertTrue(Recommender.conditionOf(0.0, Double.NaN).reason(),
                Recommender.conditionOf(0.0, Double.NaN).reason().contains("bright share"));
    }

    // ---------------------------------------------------- the calibration flag

    /** A recording resembling the calibration set is in range, and says why. */
    @Test
    public void aRecordingLikeTheCalibrationSetIsInRange() {
        Recommender.Result ranked = Recommender.rank(
                request(IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, FRAMES),
                Recommender.everyEngine(), absentEverywhere());
        assertEquals(Recommendation.Calibration.IN_RANGE, ranked.calibration());
        for (Recommendation row : ranked.ranked()) {
            assertEquals("in_range", row.calibration().tableValue());
        }
        assertEquals(Recommendation.Calibration.IN_RANGE, Recommender.rank(
                request(IN_THE_LOWER_REGIME, STABLE, NOTHING_BRIGHT, FRAMES),
                Recommender.everyEngine(), absentEverywhere()).calibration());
    }

    /**
     * Exit gate 3, four ways: outside the span, between the two regimes, at the
     * wrong measurement scale, and with the two estimators in disagreement. Each
     * one flags, and the sentence names which.
     */
    @Test
    public void aFingerprintOutsideTheMeasuredRangeIsFlaggedAndSaysWhy() {
        Recommender.Result farAbove = Recommender.rank(
                request(0.9, STABLE, NOTHING_BRIGHT, FRAMES),
                Recommender.everyEngine(), absentEverywhere());
        assertEquals(Recommendation.Calibration.OUTSIDE_CALIBRATED_RANGE, farAbove.calibration());
        assertTrue(farAbove.calibrationReason(),
                farAbove.calibrationReason().contains("outside the"));

        Recommender.Result betweenRegimes = Recommender.rank(
                request(0.05, STABLE, NOTHING_BRIGHT, FRAMES),
                Recommender.everyEngine(), absentEverywhere());
        assertEquals(Recommendation.Calibration.OUTSIDE_CALIBRATED_RANGE,
                betweenRegimes.calibration());
        assertTrue(betweenRegimes.calibrationReason(),
                betweenRegimes.calibrationReason().contains("gap between the two regimes"));
        assertTrue("and it refuses to pick the nearer one: " + betweenRegimes.calibrationReason(),
                betweenRegimes.calibrationReason().contains("nearer is not a measurement"));

        Recommender.Result wrongScale = Recommender.rank(
                Recommender.Request.of(1, IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, FRAMES,
                        regdrift.Verdict.REGISTRABLE),
                Recommender.everyEngine(), absentEverywhere());
        assertEquals(Recommendation.Calibration.OUTSIDE_CALIBRATED_RANGE, wrongScale.calibration());
        assertTrue(wrongScale.calibrationReason(),
                wrongScale.calibrationReason().contains("not comparable"));

        Recommender.Result cannotTell = Recommender.rank(
                Recommender.Request.of(4, IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, FRAMES,
                        regdrift.Verdict.ESTIMATORS_DISAGREE),
                Recommender.everyEngine(), absentEverywhere());
        assertEquals(Recommendation.Calibration.OUTSIDE_CALIBRATED_RANGE, cannotTell.calibration());
        assertTrue(cannotTell.calibrationReason(),
                cannotTell.calibrationReason().contains("different movement"));

        for (Recommendation row : cannotTell.ranked()) {
            assertEquals("and the flag reaches every row of the table",
                    "outside_calibrated_range", row.calibration().tableValue());
        }
    }

    /**
     * The flag changes the flag and nothing else.
     *
     * <p>This is the line between defect D10 and defect D12. Localisability is
     * read here to answer "is this recording like the ones the table was
     * measured on", which is a question about the table. It is <b>not</b> a
     * threshold on whether the recording registers: two recordings identical
     * apart from it get the same engines, in the same order, with the same
     * figures, and differ by one column and one sentence.
     */
    @Test
    public void theCalibrationFlagChangesNothingButTheFlag() {
        Recommender.Result inside = Recommender.rank(
                request(IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, FRAMES),
                Recommender.everyEngine(), absentEverywhere());
        Recommender.Result outside = Recommender.rank(
                request(0.05, STABLE, NOTHING_BRIGHT, FRAMES),
                Recommender.everyEngine(), absentEverywhere());

        assertEquals(inside.ranked().size(), outside.ranked().size());
        for (int i = 0; i < inside.ranked().size(); i++) {
            Recommendation a = inside.ranked().get(i);
            Recommendation b = outside.ranked().get(i);
            assertEquals("the same engine at the same rank", a.engine(), b.engine());
            assertEquals(a.rank(), b.rank());
            assertEquals(a.reason(), b.reason());
            assertEquals(a.expectedErrorPx(), b.expectedErrorPx(), 0.0);
            assertEquals(a.expectedSeconds(), b.expectedSeconds(), 0.0);
            assertEquals(a.menuPath(), b.menuPath());
            assertEquals(a.macroLine(), b.macroLine());
        }
        assertFalse("and only the flag differs",
                inside.calibration() == outside.calibration());
    }

    /** The calibration set travels with every ranking - defect D10, exit gate 4. */
    @Test
    public void everyRankingCarriesTheCalibrationSet() {
        Recommender.Result ranked = Recommender.rank(
                request(IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, FRAMES),
                Recommender.everyEngine(), absentEverywhere());
        String set = ranked.calibrationSet();
        assertTrue(set, set.contains("three IncuCyte phase-contrast seed frames"));
        assertTrue(set, set.contains("0.017"));
        assertTrue(set, set.contains("0.160"));
        for (String file : CalibrationTable.FILES) {
            assertTrue(set + " must name " + file, set.contains(file));
        }
    }

    // --------------------------------------------------------------- the recipe

    /**
     * Exit gate 6, at this level: on a computer with nothing installed, every
     * engine appears, marked absent, with something a person can do about it.
     */
    @Test
    public void onABareFijiEveryEngineAppearsWithAnInstallAction() {
        Recommender.Result ranked = Recommender.rank(
                request(IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, FRAMES),
                Recommender.everyEngine(), absentEverywhere());

        assertEquals(EngineId.values().length, ranked.ranked().size());
        for (Recommendation row : ranked.ranked()) {
            assertEquals(row.engine() + " reads as installed on a bare Fiji",
                    "no", row.installedColumn());
            assertFalse(row.engine() + " has nothing a person could do about it",
                    row.installAction().isEmpty());
            assertFalse(row.engine() + " has no menu path", row.menuPath().isEmpty());
            assertFalse(row.engine() + " has no macro line", row.macroLine().isEmpty());
        }
    }

    /** An engine already here needs no repair, and is not offered one. */
    @Test
    public void anEngineThatIsAlreadyHereIsOfferedNoRepair() {
        Recommender.Result ranked = Recommender.rank(
                request(IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, FRAMES),
                Recommender.everyEngine(), presentOnly(EngineId.STACKREG));
        Recommendation stackReg = rowFor(ranked, EngineId.STACKREG);
        assertEquals("yes", stackReg.installedColumn());
        assertEquals("", stackReg.installAction());
        assertEquals(0.0, stackReg.installSizeMb(), 0.0);
    }

    /**
     * The two engines whose menu entry this plugin has not read say so rather
     * than printing a plausible path.
     *
     * <p>One of them, and it is stated here so that a later contributor filling
     * it in knows it was left out on purpose rather than forgotten.
     */
    @Test
    public void anEngineWhoseMenuEntryWasNeverReadSaysSo() {
        assertEquals(Recipe.MENU_PATH_NOT_READ,
                Recipe.forEngine(EngineId.NANOJ_CORE).menuPath());
        int unread = 0;
        for (EngineId engine : EngineId.values()) {
            if (Recipe.MENU_PATH_NOT_READ.equals(Recipe.forEngine(engine).menuPath())) unread++;
        }
        assertEquals("exactly one engine's menu entry is unread", 1, unread);
    }

    /** The recipes for the engines the benchmark drove are the ones a person runs. */
    @Test
    public void theRecipeForTheMeasuredEngineIsTheOneAPersonWouldRun() {
        Recipe stackReg = Recipe.forEngine(EngineId.STACKREG);
        assertEquals("Plugins > StackReg", stackReg.menuPath());
        assertEquals("run(\"StackReg \", \"transformation=Translation\");", stackReg.macroLine());
        assertTrue("the trailing space is part of the command, not a typo",
                stackReg.macroLine().contains("StackReg \""));
    }

    // ------------------------------------------------------------- end to end

    /**
     * Exit gates 1, 3 and 6 through the published entry point, on a recording
     * nothing has ever seen.
     *
     * <p>Nothing is installed on this machine, so every row comes back absent
     * with an install action, which is the bare-Fiji case the gate is about.
     */
    @Test
    public void theFacadeProducesAPopulatedRecommendationTable() {
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie())
                .mode(Mode.DIAGNOSE_AND_RECOMMEND)
                .engines(regdrift.EngineSelection.all())
                .serial(true)
                .build());

        assertTrue(String.valueOf(result.failure()), result.isSuccess());
        ResultsTable table = result.recommendation();
        assertNotNull("diagnose and recommend has to produce a recommendation table", table);
        assertEquals(EngineId.values().length, table.size());
        assertEquals(RegDriftTables.RECOMMENDATION_COLUMNS,
                Arrays.asList(table.getHeadings()));

        for (int row = 0; row < table.size(); row++) {
            assertFalse("every row names an engine",
                    RegDriftTables.cellText(table, "engine", row).isEmpty());
            assertEquals("rank is a position starting at 1",
                    Integer.toString(row + 1), RegDriftTables.cellText(table, "rank", row));
            String calibration = RegDriftTables.cellText(table, "calibration", row);
            assertTrue("the calibration flag reaches the table: " + calibration,
                    "in_range".equals(calibration)
                            || "outside_calibrated_range".equals(calibration));
            assertEquals("nothing is installed on this machine",
                    "no", RegDriftTables.cellText(table, "installed", row));
            assertFalse("and every row says what could be done about that",
                    RegDriftTables.cellText(table, "install_action", row).isEmpty());
        }
        assertEquals("the ranking travels as values as well as cells",
                table.size(), result.ranked().size());
        assertTrue("the calibration set is in the saved record: "
                        + result.provenance().calibrationSet(),
                result.provenance().calibrationSet()
                        .contains("three IncuCyte phase-contrast seed frames"));
        assertNotNull("and the diagnosis is still produced alongside it", result.diagnosis());
    }

    /** A named engine this build does not know is a typed refusal, not an empty table. */
    @Test
    public void anUnknownEngineNameIsRefusedByName() {
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie())
                .mode(Mode.DIAGNOSE_AND_RECOMMEND)
                .engines(regdrift.EngineSelection.named(
                        Collections.singletonList("Elastix")))
                .serial(true)
                .build());
        assertFalse(result.isSuccess());
        assertTrue(result.failure().message(), result.failure().message().contains("Elastix"));
        assertTrue("and names the ones it does know: " + result.failure().message(),
                result.failure().message().contains("StackReg"));
    }

    // ---------------------------------------------------------------- fixtures

    private static CalibrationTable.Condition conditionOf(double trend, double bright) {
        return Recommender.conditionOf(trend, bright).condition();
    }

    private static Recommender.Request requestFor(CalibrationTable.Condition condition) {
        switch (condition) {
            case GAIN_FADE:
                return request(IN_THE_UPPER_REGIME, -2.0, NOTHING_BRIGHT, FRAMES);
            case CHANGE_BLOCKS:
                return request(IN_THE_UPPER_REGIME, STABLE, 0.10, FRAMES);
            case CHANGE_MOVED:
                // No branch reaches it - see the test above. The arm is read straight off the
                // table here so that its figures are still checked against the file.
                return null;
            default:
                return request(IN_THE_UPPER_REGIME, STABLE, NOTHING_BRIGHT, FRAMES);
        }
    }

    private static Recommender.Request request(double localisability, double trend,
                                               double bright, int frames) {
        return Recommender.Request.of(Recommender.CALIBRATION_BIN, localisability, trend, bright,
                frames, regdrift.Verdict.REGISTRABLE);
    }

    private static Recommender.Availability absentEverywhere() {
        return new Recommender.Availability() {
            @Override
            public Recommendation.Presence presenceOf(EngineId engine) {
                return Recommendation.Presence.ABSENT;
            }
        };
    }

    private static Recommender.Availability presentOnly(final EngineId here) {
        return new Recommender.Availability() {
            @Override
            public Recommendation.Presence presenceOf(EngineId engine) {
                return engine == here
                        ? Recommendation.Presence.PRESENT : Recommendation.Presence.ABSENT;
            }
        };
    }

    private static Recommendation rowFor(Recommender.Result ranked, EngineId engine) {
        String name = EngineRegistry.displayName(engine);
        for (Recommendation row : ranked.ranked()) {
            if (row.engine().equals(name)) return row;
        }
        throw new AssertionError("no row for " + name + " in " + ranked.ranked());
    }

    private static int countMeasured(List<Recommendation> rows) {
        int measured = 0;
        for (Recommendation row : rows) {
            if (!Double.isNaN(row.expectedErrorPx())) measured++;
        }
        return measured;
    }

    /**
     * The third-party arm's rows for one condition, read out of the CSV on disk
     * by a parser that shares nothing with the one under test.
     *
     * <p>Fields, in the file's own order: seed, condition, estimator,
     * reconciliation, frames, pairs, estimate_cpu_ms, cpu_ms_per_pair,
     * median_err_px, p90_err_px, max_err_px.
     */
    private static List<String[]> armRowsOf(CalibrationTable.Condition condition)
            throws IOException {
        File csv = new File(projectRoot(),
                "src/main/resources/calibration/" + CalibrationTable.THIRD_PARTY_FILE);
        assertTrue("the bundled file is not where this test expects it: " + csv, csv.isFile());
        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        assertEquals("the header names eleven columns", 11, lines.get(0).split(",", -1).length);
        List<String[]> found = new ArrayList<String[]>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            String[] fields = lines.get(i).split(",", -1);
            if (!condition.name().equals(fields[1])) continue;
            if (!CalibrationTable.THIRD_PARTY_ARM.equals(fields[2])) continue;
            if (!CalibrationTable.CHAIN.equals(fields[3])) continue;
            found.add(fields);
        }
        return found;
    }

    private static List<File> javaFilesUnder(String relativePath) {
        File folder = new File(projectRoot(), relativePath);
        assertTrue("this test reads source from " + folder.getAbsolutePath()
                + ", and it is not there", folder.isDirectory());
        List<File> found = new ArrayList<File>();
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".java")) found.add(file);
            }
        }
        assertFalse("no source files under " + folder.getAbsolutePath(), found.isEmpty());
        return found;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File projectRoot() {
        try {
            File output = new File(RecommenderTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return output.getParentFile().getParentFile();
        } catch (java.net.URISyntaxException unreadable) {
            throw new AssertionError("could not find the source tree: " + unreadable);
        }
    }

    // ------------------------------------------------------------ the recording

    private static final int SIDE = 256;
    private static final int FIELD = 320;
    private static final int MOVIE_FRAMES = 16;

    /** A textured recording that drifts a little and jitters a little. */
    private static ImagePlus movie() {
        float[] field = texture(FIELD, FIELD, 20260813L, 4);
        ImageStack images = new ImageStack(SIDE, SIDE);
        for (int t = 0; t < MOVIE_FRAMES; t++) {
            int ox = 16 + (int) Math.round(0.4 * t);
            int oy = 16 + (t % 3);
            images.addSlice("t" + (t + 1),
                    new FloatProcessor(SIDE, SIDE, crop(field, FIELD, ox, oy), null));
        }
        return new ImagePlus("recommend fixture", images);
    }

    private static float[] texture(int w, int h, long seed, int passes) {
        java.util.Random rng = new java.util.Random(seed);
        float[] f = new float[w * h];
        for (int i = 0; i < f.length; i++) f[i] = (float) (128 + 40 * rng.nextGaussian());
        for (int pass = 0; pass < passes; pass++) {
            float[] t = f.clone();
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    f[y * w + x] = (t[y * w + x] * 4
                            + t[y * w + x - 1] + t[y * w + x + 1]
                            + t[(y - 1) * w + x] + t[(y + 1) * w + x]) / 8f;
                }
            }
        }
        return f;
    }

    private static float[] crop(float[] field, int fieldWidth, int ox, int oy) {
        float[] out = new float[SIDE * SIDE];
        for (int y = 0; y < SIDE; y++) {
            System.arraycopy(field, (y + oy) * fieldWidth + ox, out, y * SIDE, SIDE);
        }
        return out;
    }
}
