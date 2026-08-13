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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The four result tables, with their columns fixed here and nowhere else.
 *
 * <p>Every column of every table is declared now, including the ones nothing
 * fills yet. Two reasons, and the second is the one that bites. A
 * {@code ResultsTable} shows its columns in the order they were created, so a
 * column first written in a later stage would appear on the end - and a
 * diagnosis CSV whose column order changes between two versions of the plugin
 * is a quiet nuisance to everybody parsing it. And a table whose shape is
 * settled before the measurements exist means eight later stages fill named
 * columns rather than inventing them, which is why {@code TablesTest} holds its
 * own copy of the column list and fails the build if a name here changes.
 *
 * <p>Rows go in through a typed builder rather than through
 * {@code addValue(String, ...)}, so writing a word into {@code drift_rate_px}
 * is a compile error rather than a cell that reads as {@code NaN} three stages
 * later. A cell nobody set stays {@code NaN} in a number column and empty in a
 * text column: a partly filled row never shows a zero that looks like a
 * measurement.
 *
 * <p>Coordinator thread, like everything else that touches ImageJ state. A
 * worker returns numbers; the thread that owns the run writes the row.
 */
public final class RegDriftTables {

    // ---------------------------------------------------------------- schemas

    /** One row per channel: what the movement in this recording looks like. */
    private static final Column[] DIAGNOSIS = {
            number("channel"),
            number("localisability"),
            number("measured_at_bin"),
            number("frame_correlation"),
            number("drift_rate_px"),
            number("bridge_max_px"),
            text("bridge_span"),
            number("wander"),
            number("step_rms_px"),
            number("step_max_px"),
            number("knock_present"),
            number("log2_trend"),
            number("bright_fraction"),
            number("agreement_px"),
            text("motion_label"),
            text("motion_dominant"),
            text("severity"),
            text("verdict"),
    };

    /** One row per candidate engine: the ranking and the recipe. */
    private static final Column[] RECOMMENDATION = {
            text("engine"),
            number("rank"),
            text("reason"),
            number("expected_error_px"),
            number("expected_seconds"),
            text("calibration"),
            text("installed"),
            number("install_size_mb"),
            text("install_action"),
            text("menu_path"),
            text("macro_line"),
    };

    /** One row per arm that actually ran, with what it cost and what it left. */
    private static final Column[] COMPARISON = {
            text("engine"),
            text("settings"),
            number("cpu_seconds"),
            number("residual_before"),
            number("residual_after"),
            number("residual_removed"),
            number("sd_vs_control"),
            number("path_px"),
            number("net_px"),
            number("frames_flagged"),
            text("status"),
    };

    /** One row per frame of the scored arm. */
    private static final Column[] FRAMES = {
            number("t"),
            number("cum_dx"),
            number("cum_dy"),
            number("step_dx"),
            number("step_dy"),
            number("residual_before"),
            number("residual_after"),
            number("valid_fraction"),
            text("status"),
    };

    /** The diagnosis columns, in the order they are written. */
    public static final List<String> DIAGNOSIS_COLUMNS = namesOf(DIAGNOSIS);

    /** The recommendation columns, in the order they are written. */
    public static final List<String> RECOMMENDATION_COLUMNS = namesOf(RECOMMENDATION);

    /** The comparison columns, in the order they are written. */
    public static final List<String> COMPARISON_COLUMNS = namesOf(COMPARISON);

    /** The frames columns, in the order they are written. */
    public static final List<String> FRAMES_COLUMNS = namesOf(FRAMES);

    private RegDriftTables() {
    }

    // ----------------------------------------------------------- empty tables

    /** An empty diagnosis table with every column already declared. */
    public static ResultsTable diagnosis() {
        return build(DIAGNOSIS);
    }

    /** An empty recommendation table with every column already declared. */
    public static ResultsTable recommendation() {
        return build(RECOMMENDATION);
    }

    /** An empty comparison table with every column already declared. */
    public static ResultsTable comparison() {
        return build(COMPARISON);
    }

    /** An empty frames table with every column already declared. */
    public static ResultsTable frames() {
        return build(FRAMES);
    }

    // -------------------------------------------------------------- new rows

    /** A diagnosis row. Set what is known; the rest stays unmeasured. */
    public static DiagnosisRow diagnosisRow() {
        return new DiagnosisRow();
    }

    /** A recommendation row. */
    public static RecommendationRow recommendationRow() {
        return new RecommendationRow();
    }

    /** A comparison row. */
    public static ComparisonRow comparisonRow() {
        return new ComparisonRow();
    }

    /** A frames row. */
    public static FramesRow framesRow() {
        return new FramesRow();
    }

    /**
     * Writes one ranked engine into a recommendation table.
     *
     * <p>The one place the ranking and the table meet, so the ranking a caller
     * reads from {@link RegDriftResult#ranked()} and the table it is shown in
     * cannot disagree.
     */
    public static void append(ResultsTable table, Recommendation ranked) {
        if (ranked == null) {
            throw new IllegalArgumentException("A recommendation row needs a ranked engine.");
        }
        recommendationRow()
                .engine(ranked.engine())
                .rank(ranked.rank())
                .reason(ranked.reason())
                .expectedErrorPx(ranked.expectedErrorPx())
                .expectedSeconds(ranked.expectedSeconds())
                .calibration(ranked.calibration())
                .installed(ranked.installedColumn())
                .installSizeMb(ranked.installSizeMb())
                .installAction(ranked.installAction())
                .menuPath(ranked.menuPath())
                .macroLine(ranked.macroLine())
                .appendTo(table);
    }

    // ---------------------------------------------------------------- reading

    /**
     * One cell as text, whichever kind the column holds.
     *
     * <p>The number is read first. A column holding words reads back as
     * {@code NaN}, so anything that is not a number falls through to the words,
     * and a number column nothing filled - which this class leaves at
     * {@code NaN} on purpose - comes back empty rather than as the word
     * {@code NaN}. A whole number is written without a trailing {@code .0},
     * because {@code channel} and {@code t} are counts and a spreadsheet column
     * of {@code 2.0} reads as something that was measured to one decimal place.
     *
     * <p>A column that is absent, or a row out of range, reads as empty rather
     * than throwing: the callers are a save routine and a summary line, and
     * neither should abandon a file over a cell nothing filled.
     */
    public static String cellText(ResultsTable table, String column, int row) {
        if (table == null || column == null || row < 0 || row >= table.size()) return "";
        if (table.getColumnIndex(column) == ResultsTable.COLUMN_NOT_FOUND) return "";
        double number = Double.NaN;
        try {
            number = table.getValue(column, row);
        } catch (RuntimeException notANumberColumn) {
            // Fall through and read it as words.
        }
        if (!Double.isNaN(number)) {
            if (Double.isInfinite(number)) return Double.toString(number);
            if (number == Math.rint(number) && Math.abs(number) < 1e15) {
                return Long.toString((long) number);
            }
            return Double.toString(number);
        }
        try {
            String words = table.getStringValue(column, row);
            if (words == null || "NaN".equals(words)) return "";
            return words;
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    // ------------------------------------------------------------- row types

    /** One row per channel. */
    public static final class DiagnosisRow extends Row {

        private DiagnosisRow() {
            super(DIAGNOSIS);
        }

        /** The 1-based channel this row describes. */
        public DiagnosisRow channel(int channel) {
            return (DiagnosisRow) put("channel", channel);
        }

        /** Fall in frame-to-frame correlation under a one-pixel displacement. */
        public DiagnosisRow localisability(double localisability) {
            return (DiagnosisRow) put("localisability", localisability);
        }

        /**
         * The binning the measurement was made at; 1 is native resolution.
         *
         * <p>Written beside {@link #localisability(double)} every time, because
         * that number has no meaning without it - defect D12.
         */
        public DiagnosisRow measuredAtBin(int measuredAtBin) {
            return (DiagnosisRow) put("measured_at_bin", measuredAtBin);
        }

        /** Median frame-to-frame correlation. */
        public DiagnosisRow frameCorrelation(double frameCorrelation) {
            return (DiagnosisRow) put("frame_correlation", frameCorrelation);
        }

        /** Slope of the line fitted within each window, in pixels per frame. */
        public DiagnosisRow driftRatePx(double driftRatePx) {
            return (DiagnosisRow) put("drift_rate_px", driftRatePx);
        }

        /** Largest displacement across a gap between windows, in pixels. */
        public DiagnosisRow bridgeMaxPx(double bridgeMaxPx) {
            return (DiagnosisRow) put("bridge_max_px", bridgeMaxPx);
        }

        /** The frame range that largest bridge step spans, such as {@code 112-241}. */
        public DiagnosisRow bridgeSpan(String bridgeSpan) {
            return (DiagnosisRow) put("bridge_span", bridgeSpan);
        }

        /** Residual excursion about the fitted line, over its step size. */
        public DiagnosisRow wander(double wander) {
            return (DiagnosisRow) put("wander", wander);
        }

        /** Root-mean-square step size, in pixels. */
        public DiagnosisRow stepRmsPx(double stepRmsPx) {
            return (DiagnosisRow) put("step_rms_px", stepRmsPx);
        }

        /** Largest step, in pixels. */
        public DiagnosisRow stepMaxPx(double stepMaxPx) {
            return (DiagnosisRow) put("step_max_px", stepMaxPx);
        }

        /**
         * Whether any step was grossly inconsistent with its neighbors: 1 when at
         * least one was, 0 when none was.
         *
         * <p><b>Presence, and never a count.</b> The column was called
         * {@code knocks} and documented as a count until stage 08. A knock is a
         * step larger than three pixels or six times the typical step, whichever
         * is bigger, and the typical step is computed over whatever frame pairs
         * were sampled - so the threshold moves when the sample moves and the
         * count moves with it. One library recording read one, two, four and seven
         * knocks across six samplings of itself; presence was stable across all
         * six. The column was renamed rather than quietly filled with 1 and 0,
         * because a column headed {@code knocks} holding {@code 1} reads as
         * "exactly one knock", which is the claim being withdrawn. See defect D13.
         */
        public DiagnosisRow knockPresent(boolean knockPresent) {
            return (DiagnosisRow) put("knock_present", knockPresent ? 1 : 0);
        }

        /** Fitted global intensity change across the recording, in log2 units. */
        public DiagnosisRow log2Trend(double log2Trend) {
            return (DiagnosisRow) put("log2_trend", log2Trend);
        }

        /** Share of the frame occupied by structure brighter than the sample. */
        public DiagnosisRow brightFraction(double brightFraction) {
            return (DiagnosisRow) put("bright_fraction", brightFraction);
        }

        /** Median per-transition difference between the two estimators. */
        public DiagnosisRow agreementPx(double agreementPx) {
            return (DiagnosisRow) put("agreement_px", agreementPx);
        }

        /** The unordered component set, rendered in its canonical order. */
        public DiagnosisRow motionLabel(String motionLabel) {
            return (DiagnosisRow) put("motion_label", motionLabel);
        }

        /** Which component dominates, or {@code unclear}. */
        public DiagnosisRow motionDominant(String motionDominant) {
            return (DiagnosisRow) put("motion_dominant", motionDominant);
        }

        /** How large the movement is, in words. */
        public DiagnosisRow severity(String severity) {
            return (DiagnosisRow) put("severity", severity);
        }

        /** Whether the movement can be registered. */
        public DiagnosisRow verdict(Verdict verdict) {
            return (DiagnosisRow) put("verdict", verdict == null ? "" : verdict.tableValue());
        }
    }

    /** One row per candidate engine. */
    public static final class RecommendationRow extends Row {

        private RecommendationRow() {
            super(RECOMMENDATION);
        }

        /** The engine's own spelling of its name. */
        public RecommendationRow engine(String engine) {
            return (RecommendationRow) put("engine", engine);
        }

        /** Position in the ranking; 1 is the engine that scored highest. */
        public RecommendationRow rank(int rank) {
            return (RecommendationRow) put("rank", rank);
        }

        /** The measured row this came from, in one clause. */
        public RecommendationRow reason(String reason) {
            return (RecommendationRow) put("reason", reason);
        }

        /** Expected residual mismatch after registration, in pixels. */
        public RecommendationRow expectedErrorPx(double expectedErrorPx) {
            return (RecommendationRow) put("expected_error_px", expectedErrorPx);
        }

        /** Expected CPU seconds, scaled by frame count. Never wall-clock. */
        public RecommendationRow expectedSeconds(double expectedSeconds) {
            return (RecommendationRow) put("expected_seconds", expectedSeconds);
        }

        /** Whether the movement sits inside the range the table was measured over. */
        public RecommendationRow calibration(Recommendation.Calibration calibration) {
            return (RecommendationRow) put("calibration",
                    calibration == null ? "" : calibration.tableValue());
        }

        /** Whether this engine is present in the Fiji that is running. */
        public RecommendationRow installed(String installed) {
            return (RecommendationRow) put("installed", installed);
        }

        /** How large the repair for a missing engine would be, in megabytes. */
        public RecommendationRow installSizeMb(double installSizeMb) {
            return (RecommendationRow) put("install_size_mb", installSizeMb);
        }

        /** What the repair panel would do, or the reason it cannot. */
        public RecommendationRow installAction(String installAction) {
            return (RecommendationRow) put("install_action", installAction);
        }

        /** Where this engine sits in the Fiji menu. */
        public RecommendationRow menuPath(String menuPath) {
            return (RecommendationRow) put("menu_path", menuPath);
        }

        /** A macro line that runs this engine with these settings. */
        public RecommendationRow macroLine(String macroLine) {
            return (RecommendationRow) put("macro_line", macroLine);
        }
    }

    /** One row per arm that actually ran. */
    public static final class ComparisonRow extends Row {

        private ComparisonRow() {
            super(COMPARISON);
        }

        /** The engine's own spelling of its name. */
        public ComparisonRow engine(String engine) {
            return (ComparisonRow) put("engine", engine);
        }

        /** The settings this arm was driven with. */
        public ComparisonRow settings(String settings) {
            return (ComparisonRow) put("settings", settings);
        }

        /** What the arm cost, in CPU seconds. Never wall-clock - defect D6. */
        public ComparisonRow cpuSeconds(double cpuSeconds) {
            return (ComparisonRow) put("cpu_seconds", cpuSeconds);
        }

        /** Residual mismatch before registration, in pixels. */
        public ComparisonRow residualBefore(double residualBefore) {
            return (ComparisonRow) put("residual_before", residualBefore);
        }

        /** Residual mismatch after registration, in pixels. */
        public ComparisonRow residualAfter(double residualAfter) {
            return (ComparisonRow) put("residual_after", residualAfter);
        }

        /** How much of the residual mismatch the arm removed, in pixels. */
        public ComparisonRow residualRemoved(double residualRemoved) {
            return (ComparisonRow) put("residual_removed", residualRemoved);
        }

        /** Temporal standard deviation against an interpolation-matched control. */
        public ComparisonRow sdVsControl(double sdVsControl) {
            return (ComparisonRow) put("sd_vs_control", sdVsControl);
        }

        /** Total path length walked over the recording, in pixels. */
        public ComparisonRow pathPx(double pathPx) {
            return (ComparisonRow) put("path_px", pathPx);
        }

        /** Net displacement from first frame to last, in pixels. */
        public ComparisonRow netPx(double netPx) {
            return (ComparisonRow) put("net_px", netPx);
        }

        /** How many frames carried a status other than {@code ok}. */
        public ComparisonRow framesFlagged(int framesFlagged) {
            return (ComparisonRow) put("frames_flagged", framesFlagged);
        }

        /** How the arm ended. */
        public ComparisonRow status(String status) {
            return (ComparisonRow) put("status", status);
        }
    }

    /** One row per frame of the scored arm. */
    public static final class FramesRow extends Row {

        private FramesRow() {
            super(FRAMES);
        }

        /** The 1-based frame index. */
        public FramesRow t(int t) {
            return (FramesRow) put("t", t);
        }

        /** Cumulative displacement in x, from the first frame, in pixels. */
        public FramesRow cumDx(double cumDx) {
            return (FramesRow) put("cum_dx", cumDx);
        }

        /** Cumulative displacement in y, from the first frame, in pixels. */
        public FramesRow cumDy(double cumDy) {
            return (FramesRow) put("cum_dy", cumDy);
        }

        /** Displacement in x from the frame before, in pixels. */
        public FramesRow stepDx(double stepDx) {
            return (FramesRow) put("step_dx", stepDx);
        }

        /** Displacement in y from the frame before, in pixels. */
        public FramesRow stepDy(double stepDy) {
            return (FramesRow) put("step_dy", stepDy);
        }

        /** Residual mismatch at this frame before registration, in pixels. */
        public FramesRow residualBefore(double residualBefore) {
            return (FramesRow) put("residual_before", residualBefore);
        }

        /** Residual mismatch at this frame after registration, in pixels. */
        public FramesRow residualAfter(double residualAfter) {
            return (FramesRow) put("residual_after", residualAfter);
        }

        /** Share of the frame that still held data after the transform. */
        public FramesRow validFraction(double validFraction) {
            return (FramesRow) put("valid_fraction", validFraction);
        }

        /** What happened at this frame. */
        public FramesRow status(FrameStatus status) {
            return (FramesRow) put("status", status == null ? "" : status.tableValue());
        }
    }

    // ------------------------------------------------------------- machinery

    /**
     * A row being filled in, before it is appended to a table.
     *
     * <p>Not something to build by hand: the four subclasses name the columns
     * they own, and their setters are the reason a word cannot end up in a
     * number column.
     */
    public abstract static class Row {

        private final Column[] schema;
        private final Map<String, Object> values = new LinkedHashMap<String, Object>();

        Row(Column[] schema) {
            this.schema = schema;
        }

        /** The columns this row fills, in the order they are written. */
        public List<String> columns() {
            return namesOf(schema);
        }

        /**
         * Appends this row to a table of the matching shape.
         *
         * @throws IllegalArgumentException when the table's columns are not the
         *         ones this row fills, which catches a diagnosis row appended to
         *         a frames table and a column declared in one place and not the
         *         other
         */
        public void appendTo(ResultsTable table) {
            if (table == null) {
                throw new IllegalArgumentException("A row needs a table to be appended to.");
            }
            List<String> expected = namesOf(schema);
            List<String> found = Arrays.asList(
                    table.getHeadings() == null ? new String[0] : table.getHeadings());
            if (!expected.equals(found)) {
                throw new IllegalArgumentException("This row fills the columns " + expected
                        + ", and the table it was given holds " + found + ". Build the table with"
                        + " RegDriftTables so both come from the same declaration.");
            }
            table.incrementCounter();
            int row = table.size() - 1;
            for (Column column : schema) {
                Object value = values.get(column.name);
                if (column.text) {
                    table.setValue(column.name, row, value == null ? "" : (String) value);
                } else {
                    table.setValue(column.name, row,
                            value == null ? Double.NaN : ((Double) value).doubleValue());
                }
            }
        }

        Row put(String column, double value) {
            require(column, false);
            values.put(column, Double.valueOf(value));
            return this;
        }

        Row put(String column, String value) {
            require(column, true);
            values.put(column, value == null ? "" : value);
            return this;
        }

        private void require(String column, boolean text) {
            for (Column declared : schema) {
                if (!declared.name.equals(column)) continue;
                if (declared.text != text) {
                    throw new IllegalStateException("Column '" + column + "' is declared as "
                            + (declared.text ? "words" : "numbers")
                            + " and was written as the other kind.");
                }
                return;
            }
            throw new IllegalStateException("Column '" + column + "' is not one of " + columns()
                    + ". A setter names a column that RegDriftTables does not declare.");
        }
    }

    /** One declared column: its name, and whether it holds words or numbers. */
    private static final class Column {

        private final String name;
        private final boolean text;

        private Column(String name, boolean text) {
            this.name = name;
            this.text = text;
        }
    }

    private static Column number(String name) {
        return new Column(name, false);
    }

    private static Column text(String name) {
        return new Column(name, true);
    }

    private static List<String> namesOf(Column[] schema) {
        List<String> names = new ArrayList<String>(schema.length);
        for (Column column : schema) {
            names.add(column.name);
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * An empty table whose columns exist already.
     *
     * <p>{@code getFreeColumn} is what declares a column before any row does,
     * which is the whole point: the headings are settled here, so a column first
     * written in stage 12 lands in the position this class gives it rather than
     * on the end.
     */
    private static ResultsTable build(Column[] schema) {
        ResultsTable table = new ResultsTable();
        for (Column column : schema) {
            table.getFreeColumn(column.name);
        }
        return table;
    }
}
