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
import sc.fiji.oc3d.core.io.CsvWriter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a batch produced: one row per recording, in the order the folder was
 * read, and one line summarising the lot.
 *
 * <h2>Rows, not results</h2>
 *
 * <p>A row is a handful of numbers and sentences, not the whole
 * {@link RegDriftResult} the run produced. That is deliberate and it is what
 * makes a batch over two hundred recordings finish: a comparison hands back a
 * registered recording and a before-and-after panel, and two hundred of those
 * held at once is a folder that runs a machine out of memory rather than one
 * that produces a table. Each result is written where it was asked to be
 * written, its row is taken from it, and it is let go of before the next
 * recording starts.
 *
 * <p><b>Every number in a row comes from the same code path as the single-image
 * mode.</b> Nothing here measures anything. A batch that computed its own
 * version of a figure would disagree with the single-recording run sooner or
 * later, and nobody would know which of the two was right.
 *
 * <h2>Order does not depend on who finished first</h2>
 *
 * <p>Rows carry the index the recording was discovered at and are held in that
 * order, whether the batch ran one movie at a time or several. A table whose row
 * order depended on which recording finished first would be a different
 * scientific output on every run.
 *
 * <h2>A recording that failed is a row, not the end of the batch</h2>
 *
 * <p>One file that is not an image, or is an image with no time axis, or sits at
 * a path this system will not open, produces a row carrying a typed
 * {@link Failure} and the batch carries on. The whole batch failing is reserved
 * for something that stops it starting at all - no folder, a pattern that reads
 * as nothing, a mode a folder cannot answer - and then {@link #failure()} says
 * which.
 */
public final class RegDriftBatchResult {

    /** The label the aggregate line carries in the {@code group} column. */
    public static final String AGGREGATE_LABEL = "(aggregate)";

    /** What a row says, in the order the columns are written. */
    public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "index", "group", "file", "relative_path", "mode", "status", "verdict",
            "motion_label", "motion_dominant", "severity", "channel", "measured_at_bin",
            "recommended_engine", "calibration", "expected_error_px", "expected_seconds",
            "top_engine", "sd_vs_control", "cpu_seconds", "saved", "detail"));

    private final RegDriftBatchParameters parameters;
    private final List<MovieRow> rows;
    private final List<File> skipped;
    private final Map<String, List<File>> groups;
    private final int movieWorkers;
    private final String workerNote;
    private final File savedUnder;
    private final boolean stopped;
    private final Failure failure;

    private RegDriftBatchResult(Builder builder) {
        this.parameters = builder.parameters;
        this.rows = Collections.unmodifiableList(new ArrayList<MovieRow>(builder.rows));
        this.skipped = Collections.unmodifiableList(new ArrayList<File>(builder.skipped));
        this.groups = Collections.unmodifiableMap(
                new LinkedHashMap<String, List<File>>(builder.groups));
        this.movieWorkers = builder.movieWorkers;
        this.workerNote = builder.workerNote;
        this.savedUnder = builder.savedUnder;
        this.stopped = builder.stopped;
        this.failure = builder.failure;
    }

    /** A builder for the result of a batch over these settings. */
    public static Builder builder(RegDriftBatchParameters parameters) {
        return new Builder(parameters);
    }

    /** A batch that could not be started, and the typed reason. */
    public static RegDriftBatchResult failed(RegDriftBatchParameters parameters, Failure failure) {
        if (failure == null) {
            throw new IllegalArgumentException(
                    "A batch that could not start needs a typed reason a caller can branch on.");
        }
        return builder(parameters).failure(failure).build();
    }

    /** The settings this batch was given. */
    public RegDriftBatchParameters parameters() {
        return parameters;
    }

    /** One row per recording, in the order the folder was read. */
    public List<MovieRow> rows() {
        return rows;
    }

    /** The files the pattern did not match, in the order they were found. */
    public List<File> skipped() {
        return skipped;
    }

    /**
     * The grouping this batch used: label to the files carrying it.
     *
     * <p>The same map {@link RegDriftBatchRunner#preview} renders, produced by
     * the same call, so the preview somebody read before starting and the
     * grouping the run used cannot be two different things.
     */
    public Map<String, List<File>> groups() {
        return groups;
    }

    /** How many recordings were in flight at once. */
    public int movieWorkers() {
        return movieWorkers;
    }

    /** Why that many, in words. */
    public String workerNote() {
        return workerNote;
    }

    /** The folder the results were written under, or null when none were. */
    public File savedUnder() {
        return savedUnder;
    }

    /**
     * True when somebody stopped this batch part way through.
     *
     * <p>Not a failure. The recordings already finished keep their rows and every
     * recording the batch never reached carries a row of its own saying so, which
     * is what stops a folder of two hundred that stopped at forty from reading as
     * a folder of forty.
     */
    public boolean stopped() {
        return stopped;
    }

    /** Why the batch could not be started. Null when it was. */
    public Failure failure() {
        return failure;
    }

    /** True when the batch ran. Individual recordings may still have failed. */
    public boolean isSuccess() {
        return failure == null;
    }

    /** How many recordings produced a measurement. */
    public int finishedCount() {
        int finished = 0;
        for (MovieRow row : rows) {
            if (row.isSuccess()) finished++;
        }
        return finished;
    }

    /** How many recordings did not. */
    public int failedCount() {
        return rows.size() - finishedCount();
    }

    // ------------------------------------------------------------ the lines

    /** One line per recording plus the aggregate, in the order they are written. */
    public List<List<String>> summaryLines() {
        List<List<String>> lines = new ArrayList<List<String>>(rows.size() + 1);
        for (MovieRow row : rows) {
            lines.add(row.cells());
        }
        lines.add(aggregateCells());
        return lines;
    }

    /**
     * One row per recording plus the aggregate, as a table.
     *
     * <p>The same cells {@link #summaryLines()} writes, so the table somebody
     * looks at and the file a script reads are the same thing rendered twice.
     */
    public ResultsTable table() {
        ResultsTable table = new ResultsTable();
        for (List<String> line : summaryLines()) {
            table.incrementCounter();
            for (int c = 0; c < COLUMNS.size(); c++) {
                table.addValue(COLUMNS.get(c), c < line.size() ? line.get(c) : "");
            }
        }
        return table;
    }

    /**
     * The last line: what the folder as a whole came to.
     *
     * <p>Each column carries the same kind of thing it carries on a recording's
     * own line - the verdict shared by the most recordings, the engine ranked
     * first for the most of them, the middle figure rather than a total - and the
     * {@code detail} column says so in words, because a column that means one
     * thing on twenty lines and another on the twenty-first is how a summary file
     * gets misread.
     */
    public List<String> aggregateCells() {
        List<String> cells = new ArrayList<String>(COLUMNS.size());
        int finished = finishedCount();
        cells.add("");
        cells.add(AGGREGATE_LABEL);
        cells.add(rows.size() + (rows.size() == 1 ? " recording" : " recordings"));
        cells.add(parameters == null || parameters.folder() == null
                ? "" : parameters.folder().getAbsolutePath());
        cells.add(parameters == null ? "" : parameters.mode().macroValue());
        cells.add(finished + " of " + rows.size() + " measured");
        cells.add(commonest(Column.VERDICT));
        cells.add(commonest(Column.MOTION_LABEL));
        cells.add(commonest(Column.MOTION_DOMINANT));
        cells.add(commonest(Column.SEVERITY));
        cells.add("");
        cells.add("");
        cells.add(commonest(Column.RECOMMENDED_ENGINE));
        cells.add(commonest(Column.CALIBRATION));
        cells.add(number(median(Column.EXPECTED_ERROR_PX)));
        cells.add(number(median(Column.EXPECTED_SECONDS)));
        cells.add(commonest(Column.TOP_ENGINE));
        cells.add(number(median(Column.SD_VS_CONTROL)));
        cells.add(number(median(Column.CPU_SECONDS)));
        cells.add(savedUnder == null ? "nothing was written" : savedUnder.getAbsolutePath());
        cells.add(aggregateDetail(finished));
        return cells;
    }

    private String aggregateDetail(int finished) {
        StringBuilder said = new StringBuilder();
        said.append("This line is the folder, not a recording. ").append(rows.size())
                .append(rows.size() == 1 ? " recording matched" : " recordings matched")
                .append(" the pattern '")
                .append(parameters == null ? "" : parameters.pattern()).append("'");
        if (!skipped.isEmpty()) {
            said.append(", and ").append(skipped.size())
                    .append(skipped.size() == 1 ? " file did not" : " files did not");
        }
        said.append(". ").append(finished).append(" produced a measurement and ")
                .append(rows.size() - finished)
                .append(rows.size() - finished == 1 ? " did not" : " did not")
                .append("; each of those carries its own reason on its own line. The text columns"
                        + " here hold the value shared by the most recordings and the number"
                        + " columns hold the middle value, not a total. ")
                .append(workerNote);
        return said.toString();
    }

    /** Which column a summary reads from, for the aggregate line. */
    private enum Column {
        VERDICT, MOTION_LABEL, MOTION_DOMINANT, SEVERITY, RECOMMENDED_ENGINE, CALIBRATION,
        TOP_ENGINE, EXPECTED_ERROR_PX, EXPECTED_SECONDS, SD_VS_CONTROL, CPU_SECONDS
    }

    private String textOf(MovieRow row, Column column) {
        switch (column) {
            case VERDICT:
                return row.verdict == null ? "" : row.verdict.tableValue();
            case MOTION_LABEL:
                return row.motionLabel;
            case MOTION_DOMINANT:
                return row.motionDominant;
            case SEVERITY:
                return row.severity;
            case RECOMMENDED_ENGINE:
                return row.recommendedEngine;
            case CALIBRATION:
                return row.calibration;
            case TOP_ENGINE:
                return row.topEngine;
            default:
                return "";
        }
    }

    private double valueOf(MovieRow row, Column column) {
        switch (column) {
            case EXPECTED_ERROR_PX:
                return row.expectedErrorPx;
            case EXPECTED_SECONDS:
                return row.expectedSeconds;
            case SD_VS_CONTROL:
                return row.hasFigure ? row.sdVsControlPercent : Double.NaN;
            case CPU_SECONDS:
                return row.cpuSeconds;
            default:
                return Double.NaN;
        }
    }

    /**
     * The value the most recordings share, or an empty string when none of them
     * carry one.
     *
     * <p>Ties go to whichever was seen first, which is the order the folder was
     * read in and is therefore the same on every run.
     */
    private String commonest(Column column) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (MovieRow row : rows) {
            String value = textOf(row, column);
            if (value == null || value.isEmpty()) continue;
            Integer seen = counts.get(value);
            counts.put(value, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
        }
        String best = "";
        int most = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue().intValue() > most) {
                most = entry.getValue().intValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    /** The middle value across the recordings that produced one, or NaN. */
    private double median(Column column) {
        List<Double> found = new ArrayList<Double>();
        for (MovieRow row : rows) {
            double value = valueOf(row, column);
            if (!Double.isNaN(value)) found.add(Double.valueOf(value));
        }
        if (found.isEmpty()) return Double.NaN;
        Collections.sort(found);
        int middle = found.size() / 2;
        if (found.size() % 2 == 1) return found.get(middle).doubleValue();
        return 0.5 * (found.get(middle - 1).doubleValue() + found.get(middle).doubleValue());
    }

    private static String number(double value) {
        return Double.isNaN(value) ? "" : CsvWriter.number(value);
    }

    // -------------------------------------------------------------- one row

    /**
     * What one recording of a batch came to.
     *
     * <p>Built by the coordinator from a finished {@link RegDriftResult}, or from
     * a typed reason when the recording could not be run at all.
     */
    public static final class MovieRow {

        private final int index;
        private final File file;
        private final String relativePath;
        private final String group;
        private final Mode mode;
        private final Failure failure;

        private Verdict verdict;
        private String verdictReason = "";
        private String motionLabel = "";
        private String motionDominant = "";
        private String severity = "";
        private int channel = Provenance.CHANNEL_UNRESOLVED;
        private int measuredAtBin;
        private String recommendedEngine = "";
        private String calibration = "";
        private double expectedErrorPx = Double.NaN;
        private double expectedSeconds = Double.NaN;
        private String topEngine = "";
        private double sdVsControlPercent = Double.NaN;
        private boolean hasFigure;
        private double cpuSeconds = Double.NaN;
        private String saved = "";

        private MovieRow(int index, File file, String relativePath, String group, Mode mode,
                         Failure failure) {
            this.index = index;
            this.file = file;
            this.relativePath = relativePath;
            this.group = group;
            this.mode = mode;
            this.failure = failure;
        }

        /**
         * The row for a recording that could not be run at all.
         *
         * @param failure the typed reason, which is never null: a row that said
         *                nothing about why it is empty is the silent hole this
         *                whole type exists to avoid
         */
        public static MovieRow failed(int index, File file, String relativePath, String group,
                                      Mode mode, Failure failure) {
            if (failure == null) {
                throw new IllegalArgumentException("A failed recording needs a typed reason.");
            }
            return new MovieRow(index, file, relativePath, group, mode, failure);
        }

        /** The row for a recording that ran, read out of what the run produced. */
        public static MovieRow of(int index, File file, String relativePath, String group,
                                  RegDriftResult result) {
            Mode mode = result.parameters().mode();
            if (!result.isSuccess()) {
                return failed(index, file, relativePath, group, mode, result.failure());
            }
            MovieRow row = new MovieRow(index, file, relativePath, group, mode, null);
            row.verdict = result.verdict();
            row.verdictReason = result.verdictReason();
            Provenance provenance = result.provenance();
            if (provenance != null) {
                row.channel = provenance.channel();
                row.measuredAtBin = provenance.measuredAtBin();
            }
            int diagnosisRow = diagnosisRowFor(result.diagnosis(), row.channel);
            row.motionLabel = RegDriftTables.cellText(result.diagnosis(), "motion_label",
                    diagnosisRow);
            row.motionDominant = RegDriftTables.cellText(result.diagnosis(), "motion_dominant",
                    diagnosisRow);
            row.severity = RegDriftTables.cellText(result.diagnosis(), "severity", diagnosisRow);
            for (Recommendation ranked : result.ranked()) {
                if (ranked.rank() != 1) continue;
                row.recommendedEngine = ranked.engine();
                row.calibration = ranked.calibration().tableValue();
                row.expectedErrorPx = ranked.expectedErrorPx();
                row.expectedSeconds = ranked.expectedSeconds();
                break;
            }
            double cpu = 0;
            boolean anyCpu = false;
            for (ArmOutcome arm : result.arms()) {
                if (arm.cpuSeconds() > 0) {
                    cpu += arm.cpuSeconds();
                    anyCpu = true;
                }
                if (row.topEngine.isEmpty() && arm.rank() == 1 && arm.hasFigure()) {
                    row.topEngine = arm.engineName();
                    row.sdVsControlPercent = arm.sdVsControlPercent();
                    row.hasFigure = true;
                }
            }
            if (anyCpu) row.cpuSeconds = cpu;
            return row;
        }

        /** Records what writing this recording's results came to. */
        public MovieRow saved(String saved) {
            this.saved = saved == null ? "" : saved;
            return this;
        }

        /** Where this recording sat in the order the folder was read. */
        public int index() {
            return index;
        }

        /** The file this row is about. */
        public File file() {
            return file;
        }

        /** Its path below the batch folder, with {@code /} separators. */
        public String relativePath() {
            return relativePath;
        }

        /** The label the filename pattern gave it. */
        public String group() {
            return group;
        }

        /** What this recording was asked to do. */
        public Mode mode() {
            return mode;
        }

        /** Why this recording produced nothing. Null when it produced something. */
        public Failure failure() {
            return failure;
        }

        /** True when this recording produced a measurement. */
        public boolean isSuccess() {
            return failure == null;
        }

        /** Whether the movement can be registered. Null when nothing was measured. */
        public Verdict verdict() {
            return verdict;
        }

        /** The finished sentence behind the verdict. */
        public String verdictReason() {
            return verdictReason;
        }

        /** The motion label, as the diagnosis table spells it. */
        public String motionLabel() {
            return motionLabel;
        }

        /** The engine the ranking put first, or an empty string. */
        public String recommendedEngine() {
            return recommendedEngine;
        }

        /** The engine that scored highest here, or an empty string. */
        public String topEngine() {
            return topEngine;
        }

        /** Its figure against the shared control, as a percentage. NaN when none. */
        public double sdVsControlPercent() {
            return sdVsControlPercent;
        }

        /** True when an arm produced a figure. */
        public boolean hasFigure() {
            return hasFigure;
        }

        /** What writing this recording's results came to. */
        public String saved() {
            return saved;
        }

        /** This row's cells, in the order {@link #COLUMNS} gives. */
        public List<String> cells() {
            List<String> cells = new ArrayList<String>(COLUMNS.size());
            cells.add(Integer.toString(index + 1));
            cells.add(group);
            cells.add(file == null ? "" : file.getName());
            cells.add(relativePath);
            cells.add(mode == null ? "" : mode.macroValue());
            cells.add(isSuccess() ? "ok" : failure.kind().name().toLowerCase(Locale.ROOT));
            cells.add(verdict == null ? "" : verdict.tableValue());
            cells.add(motionLabel);
            cells.add(motionDominant);
            cells.add(severity);
            cells.add(channel == Provenance.CHANNEL_UNRESOLVED ? "" : Integer.toString(channel));
            cells.add(measuredAtBin == 0 ? "" : Integer.toString(measuredAtBin));
            cells.add(recommendedEngine);
            cells.add(calibration);
            cells.add(number(expectedErrorPx));
            cells.add(number(expectedSeconds));
            cells.add(topEngine);
            cells.add(hasFigure ? number(sdVsControlPercent) : "");
            cells.add(number(cpuSeconds));
            cells.add(saved);
            cells.add(isSuccess() ? verdictReason : failure.message());
            return cells;
        }

        @Override
        public String toString() {
            return (index + 1) + " " + (file == null ? "" : file.getName()) + " [" + group + "] "
                    + (isSuccess() ? "ok" : failure.kind().toString());
        }

        /** Which diagnosis row describes the channel the run settled on, or -1. */
        private static int diagnosisRowFor(ResultsTable diagnosis, int channel) {
            if (diagnosis == null || diagnosis.size() == 0) return -1;
            if (diagnosis.size() == 1) return 0;
            for (int row = 0; row < diagnosis.size(); row++) {
                String value = RegDriftTables.cellText(diagnosis, "channel", row);
                try {
                    if ((int) Double.parseDouble(value) == channel) return row;
                } catch (NumberFormatException notANumber) {
                    // A row that does not say which channel it is cannot be the one.
                }
            }
            return -1;
        }
    }

    /** Builds a {@link RegDriftBatchResult}. */
    public static final class Builder {

        private final RegDriftBatchParameters parameters;
        private final List<MovieRow> rows = new ArrayList<MovieRow>();
        private final List<File> skipped = new ArrayList<File>();
        private final Map<String, List<File>> groups = new LinkedHashMap<String, List<File>>();
        private int movieWorkers = 1;
        private String workerNote = "";
        private File savedUnder;
        private boolean stopped;
        private Failure failure;

        private Builder(RegDriftBatchParameters parameters) {
            if (parameters == null) {
                throw new IllegalArgumentException(
                        "A batch result needs the settings the batch was given.");
            }
            this.parameters = parameters;
        }

        /** The rows, in the order the folder was read. */
        public Builder rows(List<MovieRow> rows) {
            this.rows.clear();
            if (rows != null) {
                for (MovieRow row : rows) {
                    if (row != null) this.rows.add(row);
                }
            }
            return this;
        }

        /** The files the pattern did not match. */
        public Builder skipped(List<File> skipped) {
            this.skipped.clear();
            if (skipped != null) this.skipped.addAll(skipped);
            return this;
        }

        /** The grouping the run used. */
        public Builder groups(Map<String, List<File>> groups) {
            this.groups.clear();
            if (groups != null) this.groups.putAll(groups);
            return this;
        }

        /** How many recordings were in flight at once, and why that many. */
        public Builder workers(int movieWorkers, String workerNote) {
            this.movieWorkers = movieWorkers;
            this.workerNote = workerNote == null ? "" : workerNote;
            return this;
        }

        /** The folder the results were written under. */
        public Builder savedUnder(File savedUnder) {
            this.savedUnder = savedUnder;
            return this;
        }

        /** Whether somebody stopped this batch part way through. */
        public Builder stopped(boolean stopped) {
            this.stopped = stopped;
            return this;
        }

        /** Why the batch could not be started. */
        public Builder failure(Failure failure) {
            this.failure = failure;
            return this;
        }

        /** Builds the result. */
        public RegDriftBatchResult build() {
            return new RegDriftBatchResult(this);
        }
    }
}
