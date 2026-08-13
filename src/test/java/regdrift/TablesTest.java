/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.measure.ResultsTable;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The four tables: every column, spelled the way the contract spells it, in the
 * order the contract puts it.
 *
 * <p>The expected names below are written out again by hand rather than read
 * from {@link RegDriftTables}. That is the point. A test that asks the class
 * under test what its columns are agrees with it by construction and would
 * notice nothing; this one disagrees the moment a name changes, which is what
 * anybody parsing last month's CSV needs it to do.
 */
public class TablesTest {

    private static final List<String> DIAGNOSIS = Arrays.asList(
            "channel", "localisability", "measured_at_bin", "frame_correlation", "drift_rate_px",
            "bridge_max_px", "bridge_span", "wander", "step_rms_px", "step_max_px",
            "knock_present", "log2_trend", "bright_fraction", "agreement_px", "motion_label",
            "motion_dominant", "severity", "verdict");

    private static final List<String> RECOMMENDATION = Arrays.asList(
            "engine", "rank", "reason", "expected_error_px", "expected_seconds", "calibration",
            "installed", "install_size_mb", "install_action", "menu_path", "macro_line");

    private static final List<String> COMPARISON = Arrays.asList(
            "engine", "settings", "cpu_seconds", "residual_before", "residual_after",
            "residual_removed", "sd_vs_control", "path_px", "net_px", "frames_flagged", "status");

    private static final List<String> FRAMES = Arrays.asList(
            "t", "cum_dx", "cum_dy", "step_dx", "step_dy", "residual_before", "residual_after",
            "valid_fraction", "status");

    // ------------------------------------------------------------ the columns

    @Test
    public void everyDiagnosisColumnExistsInTheContractsOrder() {
        assertColumns(DIAGNOSIS, RegDriftTables.diagnosis());
        assertEquals(DIAGNOSIS, RegDriftTables.DIAGNOSIS_COLUMNS);
    }

    @Test
    public void everyRecommendationColumnExistsInTheContractsOrder() {
        assertColumns(RECOMMENDATION, RegDriftTables.recommendation());
        assertEquals(RECOMMENDATION, RegDriftTables.RECOMMENDATION_COLUMNS);
    }

    @Test
    public void everyComparisonColumnExistsInTheContractsOrder() {
        assertColumns(COMPARISON, RegDriftTables.comparison());
        assertEquals(COMPARISON, RegDriftTables.COMPARISON_COLUMNS);
    }

    @Test
    public void everyFramesColumnExistsInTheContractsOrder() {
        assertColumns(FRAMES, RegDriftTables.frames());
        assertEquals(FRAMES, RegDriftTables.FRAMES_COLUMNS);
    }

    /**
     * The columns exist before any row does, so a column first written in a
     * later stage keeps the position it is given here.
     */
    @Test
    public void theColumnsExistBeforeAnyRowIsWritten() {
        ResultsTable diagnosis = RegDriftTables.diagnosis();
        assertEquals(0, diagnosis.size());
        assertEquals(DIAGNOSIS.size(), diagnosis.getHeadings().length);
    }

    /**
     * {@code localisability} and {@code measured_at_bin} are neighbors on
     * purpose: the first has no meaning without the second - defect D12.
     */
    @Test
    public void theMeasurementScaleSitsBesideTheNumberItQualifies() {
        assertEquals(DIAGNOSIS.indexOf("localisability") + 1,
                DIAGNOSIS.indexOf("measured_at_bin"));
    }

    /**
     * Never a column called accuracy. The residual columns are mismatch, which
     * is a thing this plugin can measure; accuracy would be a claim about a true
     * registration nobody knows.
     */
    @Test
    public void noColumnClaimsToMeasureAgainstAKnownAnswer() {
        for (List<String> columns : Arrays.asList(DIAGNOSIS, RECOMMENDATION, COMPARISON, FRAMES)) {
            for (String column : columns) {
                assertFalse("column '" + column + "' claims a known answer",
                        column.contains("accuracy"));
                assertFalse("column '" + column + "' is not lower case, or is not US English",
                        !column.equals(column.toLowerCase(java.util.Locale.ROOT))
                                || column.contains("colour") || column.contains("centre"));
            }
        }
        assertTrue("residual, not accuracy, is what the comparison reports",
                COMPARISON.contains("residual_after") && !COMPARISON.contains("accuracy"));
    }

    // --------------------------------------------------------------- the rows

    @Test
    public void aTypedDiagnosisRowLandsInTheColumnsItNames() {
        ResultsTable table = RegDriftTables.diagnosis();
        RegDriftTables.diagnosisRow()
                .channel(2)
                .localisability(0.081)
                .measuredAtBin(4)
                .frameCorrelation(0.97)
                .driftRatePx(0.42)
                .bridgeMaxPx(11.5)
                .bridgeSpan("112-241")
                .wander(0.7)
                .stepRmsPx(1.25)
                .stepMaxPx(9.0)
                .knockPresent(true)
                .log2Trend(-0.4)
                .brightFraction(0.02)
                .agreementPx(0.15)
                .motionLabel("DRIFT+JITTER")
                .motionDominant("DRIFT")
                .severity("moderate")
                .verdict(Verdict.REGISTRABLE)
                .appendTo(table);

        assertEquals(1, table.size());
        assertEquals(2.0, table.getValue("channel", 0), 0.0);
        assertEquals(0.081, table.getValue("localisability", 0), 1e-9);
        assertEquals(4.0, table.getValue("measured_at_bin", 0), 0.0);
        assertEquals("112-241", table.getStringValue("bridge_span", 0));
        assertEquals("DRIFT+JITTER", table.getStringValue("motion_label", 0));
        assertEquals(Verdict.REGISTRABLE.tableValue(), table.getStringValue("verdict", 0));
        assertColumns(DIAGNOSIS, table);
    }

    @Test
    public void aTypedFramesRowLandsInTheColumnsItNames() {
        ResultsTable table = RegDriftTables.frames();
        RegDriftTables.framesRow()
                .t(7)
                .cumDx(1.5)
                .cumDy(-2.5)
                .stepDx(0.25)
                .stepDy(-0.5)
                .residualBefore(4.0)
                .residualAfter(1.0)
                .validFraction(0.98)
                .status(FrameStatus.AT_SHIFT_BOUND)
                .appendTo(table);

        assertEquals(7.0, table.getValue("t", 0), 0.0);
        assertEquals(-2.5, table.getValue("cum_dy", 0), 1e-9);
        assertEquals("at_shift_bound", table.getStringValue("status", 0));
        assertColumns(FRAMES, table);
    }

    @Test
    public void aTypedComparisonRowLandsInTheColumnsItNames() {
        ResultsTable table = RegDriftTables.comparison();
        RegDriftTables.comparisonRow()
                .engine("StackReg")
                .settings("transformation=Translation")
                .cpuSeconds(12.25)
                .residualBefore(6.0)
                .residualAfter(1.5)
                .residualRemoved(4.5)
                .sdVsControl(0.72)
                .pathPx(240.0)
                .netPx(18.0)
                .framesFlagged(2)
                .status("ok")
                .appendTo(table);

        assertEquals("StackReg", table.getStringValue("engine", 0));
        assertEquals(12.25, table.getValue("cpu_seconds", 0), 1e-9);
        assertEquals(2.0, table.getValue("frames_flagged", 0), 0.0);
        assertColumns(COMPARISON, table);
    }

    /** One ranked engine goes into the table through one call, so the two agree. */
    @Test
    public void aRankedEngineFillsEveryRecommendationColumn() {
        ResultsTable table = RegDriftTables.recommendation();
        RegDriftTables.append(table, Recommendation.builder("Correct 3D drift", 1)
                .reason("measured on slow drift with little jitter")
                .expectedErrorPx(0.6)
                .expectedSeconds(31.0)
                .calibration(Recommendation.Calibration.IN_RANGE)
                .presence(Recommendation.Presence.VERSION_NOT_DRIVEN)
                .foundVersion("1.2")
                .installSizeMb(2.5)
                .installAction("update through the Fiji updater")
                .menuPath("Plugins > Registration > Correct 3D drift")
                .macroLine("run(\"Correct 3D drift\", \"channel=1\");")
                .build());

        assertEquals("Correct 3D drift", table.getStringValue("engine", 0));
        assertEquals(1.0, table.getValue("rank", 0), 0.0);
        assertEquals("in_range", table.getStringValue("calibration", 0));
        assertEquals("wrong_version: 1.2", table.getStringValue("installed", 0));
        assertEquals(2.5, table.getValue("install_size_mb", 0), 1e-9);
        assertColumns(RECOMMENDATION, table);
    }

    /**
     * A cell nobody set reads as unmeasured rather than as zero. A recording
     * whose drift was never estimated must not report a drift rate of nought.
     */
    @Test
    public void aColumnNobodyFilledIsUnmeasuredRatherThanZero() {
        ResultsTable table = RegDriftTables.diagnosis();
        RegDriftTables.diagnosisRow().channel(1).appendTo(table);

        assertTrue("drift_rate_px was never measured and must not read as 0",
                Double.isNaN(table.getValue("drift_rate_px", 0)));
        assertTrue("localisability was never measured and must not read as 0",
                Double.isNaN(table.getValue("localisability", 0)));
        assertEquals("", table.getStringValue("motion_label", 0));
    }

    /** A row cannot be appended to a table of another shape. */
    @Test
    public void aRowRefusesATableOfAnotherShape() {
        try {
            RegDriftTables.diagnosisRow().channel(1).appendTo(RegDriftTables.frames());
            fail("a diagnosis row should not append to a frames table");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
            assertTrue(expected.getMessage(), expected.getMessage().contains("motion_label"));
        }
    }

    /** A plain table has none of the columns, so it is refused too. */
    @Test
    public void aRowRefusesATableItDidNotBuild() {
        try {
            RegDriftTables.framesRow().t(1).appendTo(new ResultsTable());
            fail("a frames row should not append to a table with no columns");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /** Reading a cell that was never written gives empty text, never a throw. */
    @Test
    public void readingAnAbsentCellGivesEmptyText() {
        ResultsTable table = RegDriftTables.diagnosis();
        assertEquals("", RegDriftTables.cellText(table, "motion_label", 0));
        RegDriftTables.diagnosisRow().channel(1).localisability(0.08).appendTo(table);
        assertEquals("", RegDriftTables.cellText(table, "drift_rate_px", 0));
        assertEquals("", RegDriftTables.cellText(table, "no_such_column", 0));
        assertEquals("", RegDriftTables.cellText(null, "channel", 0));
        assertEquals("", RegDriftTables.cellText(table, "channel", 4));
    }

    /** A count reads back as a count, not as a measurement to one decimal place. */
    @Test
    public void aWholeNumberReadsBackWithoutADecimalTail() {
        ResultsTable table = RegDriftTables.diagnosis();
        RegDriftTables.diagnosisRow().channel(2).knockPresent(true).localisability(0.081)
                .motionLabel("JITTER").appendTo(table);

        assertEquals("2", RegDriftTables.cellText(table, "channel", 0));
        assertEquals("1", RegDriftTables.cellText(table, "knock_present", 0));
        assertEquals("0.081", RegDriftTables.cellText(table, "localisability", 0));
        assertEquals("JITTER", RegDriftTables.cellText(table, "motion_label", 0));
    }

    /**
     * The knock column holds presence, and says so in its name.
     *
     * <p>Stage 03 fixed the column set from a contract that called this column
     * {@code knocks} and documented it as a count. Stage 08 measured that the
     * count is a statistic of whichever frame pairs happened to be sampled rather
     * than of the recording - one library recording read one, two, four and seven
     * knocks across six samplings of itself - and renamed the column instead of
     * writing 1 and 0 under a heading that still says "count". Defect D13.
     */
    @Test
    public void theKnockColumnReportsPresenceAndIsNamedForIt() {
        assertTrue(RegDriftTables.DIAGNOSIS_COLUMNS.contains("knock_present"));
        assertFalse("a column headed knocks reads as a count, which is the claim D13 withdrew",
                RegDriftTables.DIAGNOSIS_COLUMNS.contains("knocks"));

        ResultsTable table = RegDriftTables.diagnosis();
        RegDriftTables.diagnosisRow().channel(1).knockPresent(false).appendTo(table);
        assertEquals("0", RegDriftTables.cellText(table, "knock_present", 0));
    }

    /** Every frame status is a word the frames table can hold and read back. */
    @Test
    public void everyFrameStatusRoundTrips() {
        for (FrameStatus status : FrameStatus.values()) {
            assertEquals(status, FrameStatus.parse(status.tableValue()));
        }
        assertTrue(FrameStatus.validValues().contains("refused_low_overlap"));
    }

    private static void assertColumns(List<String> expected, ResultsTable table) {
        assertEquals(expected, Arrays.asList(table.getHeadings()));
    }
}
