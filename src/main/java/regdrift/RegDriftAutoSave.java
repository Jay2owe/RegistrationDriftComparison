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
import ij.measure.ResultsTable;
import sc.fiji.oc3d.core.io.CsvWriter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Writes a run's results into a folder tree, with a {@code README.txt} that says
 * what everything in it is.
 *
 * <p><b>The one class in this plugin that writes a file.</b> Not a style rule: a
 * plugin whose analysis can write to disk cannot honestly promise a headless
 * caller that {@link RegDrift#run} leaves the filesystem alone, and that promise
 * is asserted from bytecode. So the writing lives here, and this class is
 * reached from the entry classes and the batch runner - the two places that know
 * a person asked for it. {@link RegDrift} does not call it and does not name it.
 *
 * <p>The tree:
 *
 * <pre>
 * &lt;root&gt;/RegistrationDriftComparison/
 *   README.txt
 *   diagnosis/&lt;title&gt;_diagnosis.csv
 *   recommendation/&lt;title&gt;_recommendation.csv
 *   comparison/&lt;title&gt;_comparison.csv
 *   frames/&lt;title&gt;_frames.csv
 *   registered/&lt;title&gt;_&lt;engine&gt;.tif
 *   qc/&lt;title&gt;_kymograph.tif
 *   summary.csv
 * </pre>
 *
 * <h2>How {@code summary.csv} grows</h2>
 *
 * <p>Each run appends one line. The awkward case is a folder an older build of
 * this plugin already wrote into, whose {@code summary.csv} has a different set
 * of columns. Two ways to handle it, and one of them is quietly wrong: reordering
 * the new line to fit the old header drops any column the old header lacks, and
 * a results file that silently loses a measurement is worse than one that
 * crashes, because nobody notices. So the old file is left exactly as it is and
 * this run appends to {@code summary_2.csv} instead - then {@code summary_3.csv},
 * and so on until a file is found whose header is the set being written, or a
 * name that is free. Every summary file can then be read with one header, and
 * nothing is rewritten or dropped. The {@code README.txt} says so, in those
 * words, beside the files.
 *
 * <h2>Path length</h2>
 *
 * <p>Windows refuses a path of 260 characters or more, and
 * {@code <root>/RegistrationDriftComparison/recommendation/<title>_recommendation.csv}
 * reaches that with a microscope-generated title inside a synchronized folder.
 * Every path is measured before anything is created, so the answer is a named
 * path and a length rather than a half-written tree and an IO exception.
 */
public final class RegDriftAutoSave {

    /** The folder this plugin makes inside the save root. */
    public static final String TREE_FOLDER = "RegistrationDriftComparison";

    /** The file that says what the rest of the tree is. */
    public static final String README_FILE = "README.txt";

    /** One line per run, appended across runs. */
    public static final String SUMMARY_FILE = "summary.csv";

    /** One row per channel. */
    public static final String DIAGNOSIS_FOLDER = "diagnosis";

    /** One row per candidate engine. */
    public static final String RECOMMENDATION_FOLDER = "recommendation";

    /** One row per arm that ran. */
    public static final String COMPARISON_FOLDER = "comparison";

    /** One row per frame of the scored arm. */
    public static final String FRAMES_FOLDER = "frames";

    /** The registered stack, in apply mode. Filled from stage 13. */
    public static final String REGISTERED_FOLDER = "registered";

    /** Before-and-after panels. Filled from stage 12. */
    public static final String QC_FOLDER = "qc";

    /** Every folder the tree holds, in the order the README lists them. */
    public static final List<String> FOLDERS = Collections.unmodifiableList(Arrays.asList(
            DIAGNOSIS_FOLDER, RECOMMENDATION_FOLDER, COMPARISON_FOLDER, FRAMES_FOLDER,
            REGISTERED_FOLDER, QC_FOLDER));

    /** The columns of {@code summary.csv}, in the order they are written. */
    public static final List<String> SUMMARY_COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "run_utc", "plugin_version", "image", "mode", "settings", "channel",
            "measured_at_bin", "motion_label", "motion_dominant", "severity", "verdict",
            "verdict_reason", "recommended_engine", "calibration", "expected_error_px",
            "expected_seconds", "arbiter", "status"));

    /**
     * The longest path this system accepts, or {@code 0} where there is no such
     * limit worth enforcing.
     */
    public static final int WINDOWS_PATH_LIMIT = 259;

    /** A byte-order mark leading a file belongs to the file, not to a column name. */
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    /** Where a wrapped column list starts on each line of the README. */
    private static final String README_INDENT = "        ";

    /** How wide a README line is allowed to get before it wraps. */
    private static final int README_WIDTH = 76;

    /** How many differently shaped summary files one folder may accumulate. */
    private static final int MAX_SUMMARY_FILES = 99;

    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private RegDriftAutoSave() {
    }

    /**
     * Writes a run's results under the save root its settings name.
     *
     * @return what was written, or a typed reason it could not be. Never null
     */
    public static Report save(RegDriftResult result) {
        if (result == null) {
            throw new IllegalArgumentException("Auto-save needs a result to write.");
        }
        if (!result.parameters().hasSaveRoot()) {
            return Report.failed(null, Failure.of(Failure.Kind.INVALID_PARAMETERS,
                    "Nothing was saved: no folder was given. Set '"
                            + RegDriftMacroOptions.SAVE_ROOT + "' to the folder the results should"
                            + " be written under."));
        }
        return save(new File(result.parameters().saveRoot()), result);
    }

    /**
     * Writes a run's results under a chosen folder.
     *
     * @param saveRoot the folder the {@code RegistrationDriftComparison} tree is
     *                 made inside
     * @param result   the run to write. A run that produced no table writes the
     *                 tree, the {@code README.txt} and its summary line, and says
     *                 in that line why there is nothing else
     * @return what was written, or a typed reason it could not be. Never null
     */
    public static Report save(File saveRoot, RegDriftResult result) {
        if (result == null) {
            throw new IllegalArgumentException("Auto-save needs a result to write.");
        }
        if (saveRoot == null) {
            return Report.failed(null, Failure.of(Failure.Kind.INVALID_PARAMETERS,
                    "Nothing was saved: no folder was given."));
        }

        File tree = new File(saveRoot, TREE_FOLDER);
        String title = fileNameFor(result.parameters().image());
        List<File> planned = plannedFiles(tree, title, result);

        Failure tooLong = checkPathLengths(tree, planned);
        if (tooLong != null) return Report.failed(tree, tooLong);

        List<File> written = new ArrayList<File>();
        try {
            makeTree(tree);
            File summary = summaryTarget(tree);
            writeReadme(new File(tree, README_FILE), result, summary);
            written.add(new File(tree, README_FILE));

            written.addAll(writeTables(tree, title, result));
            appendSummary(summary, summaryRow(result));
            written.add(summary);
            return Report.saved(tree, written, summary);
        } catch (TreeTooCrowded crowded) {
            return Report.failed(tree, Failure.of(Failure.Kind.SAVE_FAILED, crowded.getMessage()));
        } catch (IOException problem) {
            return Report.failed(tree, Failure.of(Failure.Kind.SAVE_FAILED,
                    "The results could not be written under '" + tree.getAbsolutePath() + "': "
                            + describe(problem) + " Check the folder exists and can be written to."));
        } catch (SecurityException refused) {
            return Report.failed(tree, Failure.of(Failure.Kind.SAVE_FAILED,
                    "This computer refused to write under '" + tree.getAbsolutePath() + "': "
                            + describe(refused) + " Choose a folder you can write to."));
        }
    }

    /**
     * The longest path this system accepts, or {@code 0} when it enforces no
     * limit this plugin has to work around.
     */
    public static int pathLimit() {
        return File.separatorChar == '\\' ? WINDOWS_PATH_LIMIT : 0;
    }

    /**
     * An image title turned into a filename stem.
     *
     * <p>Strips the extension a title usually carries from being opened off
     * disk, and replaces everything a filename cannot hold. A title that leaves
     * nothing behind becomes {@code untitled}, so two such recordings share one
     * file rather than one of them having no file at all.
     */
    public static String fileNameFor(ImagePlus image) {
        String title = image == null || image.getTitle() == null ? "" : image.getTitle().trim();
        String stem = title.replaceAll("(?i)\\.(tif|tiff|png|jpg|jpeg|zip|nd2|czi|lif)$", "");
        stem = stem.replaceAll("[^A-Za-z0-9._-]", "_");
        while (stem.startsWith(".")) {
            stem = stem.substring(1);
        }
        return stem.isEmpty() ? "untitled" : stem;
    }

    // --------------------------------------------------------------- the tree

    private static List<File> plannedFiles(File tree, String title, RegDriftResult result) {
        List<File> planned = new ArrayList<File>();
        planned.add(new File(tree, README_FILE));
        planned.add(new File(tree, SUMMARY_FILE));
        if (result.diagnosis() != null) {
            planned.add(csv(tree, DIAGNOSIS_FOLDER, title, DIAGNOSIS_FOLDER));
        }
        if (result.recommendation() != null) {
            planned.add(csv(tree, RECOMMENDATION_FOLDER, title, RECOMMENDATION_FOLDER));
        }
        if (result.comparison() != null) {
            planned.add(csv(tree, COMPARISON_FOLDER, title, COMPARISON_FOLDER));
        }
        if (result.frames() != null) {
            planned.add(csv(tree, FRAMES_FOLDER, title, FRAMES_FOLDER));
        }
        return planned;
    }

    private static File csv(File tree, String folder, String title, String suffix) {
        return new File(new File(tree, folder), title + "_" + suffix + ".csv");
    }

    /**
     * Measures every path before anything is created, so a tree is either
     * written whole or not started.
     */
    private static Failure checkPathLengths(File tree, List<File> planned) {
        int limit = pathLimit();
        if (limit <= 0) return null;
        List<File> all = new ArrayList<File>(planned);
        for (String folder : FOLDERS) {
            all.add(new File(tree, folder));
        }
        for (File file : all) {
            String path = file.getAbsolutePath();
            if (path.length() <= limit) continue;
            return Failure.of(Failure.Kind.PATH_TOO_LONG, "This file cannot be created because its"
                    + " path is " + path.length() + " characters and this system stops at " + limit
                    + ": " + path + ". Save to a folder closer to the drive root, or shorten the"
                    + " image title.");
        }
        return null;
    }

    private static void makeTree(File tree) throws IOException {
        makeDirectory(tree);
        for (String folder : FOLDERS) {
            makeDirectory(new File(tree, folder));
        }
    }

    private static void makeDirectory(File directory) throws IOException {
        if (directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("the folder '" + directory.getAbsolutePath()
                    + "' could not be created.");
        }
    }

    private static List<File> writeTables(File tree, String title, RegDriftResult result)
            throws IOException {
        List<File> written = new ArrayList<File>();
        written.addAll(writeTable(csv(tree, DIAGNOSIS_FOLDER, title, DIAGNOSIS_FOLDER),
                result.diagnosis()));
        written.addAll(writeTable(csv(tree, RECOMMENDATION_FOLDER, title, RECOMMENDATION_FOLDER),
                result.recommendation()));
        written.addAll(writeTable(csv(tree, COMPARISON_FOLDER, title, COMPARISON_FOLDER),
                result.comparison()));
        written.addAll(writeTable(csv(tree, FRAMES_FOLDER, title, FRAMES_FOLDER), result.frames()));
        return written;
    }

    /**
     * One table as CSV, headings first.
     *
     * <p>Written row by row rather than through {@code CsvWriter.write} because
     * that helper writes an empty file for a table with no rows, and a results
     * file with no header line is one a script cannot read at all. The escaping
     * is still the family's, so a title holding a comma behaves the same here as
     * everywhere else.
     */
    private static List<File> writeTable(File file, ResultsTable table) throws IOException {
        if (table == null) return Collections.emptyList();
        String[] headings = table.getHeadings() == null ? new String[0] : table.getHeadings();
        CsvWriter out = new CsvWriter(file);
        try {
            out.row(headings);
            for (int row = 0; row < table.size(); row++) {
                List<String> cells = new ArrayList<String>(headings.length);
                for (String heading : headings) {
                    cells.add(RegDriftTables.cellText(table, heading, row));
                }
                out.row(cells);
            }
        } finally {
            out.close();
        }
        return Collections.singletonList(file);
    }

    // ------------------------------------------------------------ summary.csv

    /**
     * Which summary file this run appends to.
     *
     * <p>{@code summary.csv} when its header is the set being written, or when
     * it is not there yet. Otherwise the next numbered file whose header matches
     * or which does not exist - see the class note for why the older file is left
     * alone rather than reshaped.
     */
    private static File summaryTarget(File tree) throws IOException {
        for (int n = 1; n <= MAX_SUMMARY_FILES; n++) {
            File candidate = new File(tree, summaryName(n));
            if (!candidate.isFile() || candidate.length() == 0) return candidate;
            List<String> header = readHeader(candidate);
            if (header == null || header.equals(SUMMARY_COLUMNS)) return candidate;
        }
        throw new TreeTooCrowded("'" + tree.getAbsolutePath() + "' already holds "
                + MAX_SUMMARY_FILES + " summary files written with different column sets, and no"
                + " free name is left. Move the old ones somewhere else, or save to a new folder.");
    }

    private static String summaryName(int index) {
        return index == 1 ? SUMMARY_FILE : "summary_" + index + ".csv";
    }

    /**
     * The column names on a summary file's first line, or {@code null} when it
     * has no line at all.
     *
     * <p>A file this plugin cannot read - written in another encoding, or not a
     * summary file - comes back as an empty list, which matches no column set,
     * so the run moves to the next name instead of overwriting somebody's file
     * or abandoning the save.
     */
    private static List<String> readHeader(File file) {
        try {
            BufferedReader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
            try {
                String first = in.readLine();
                return first == null ? null : parseCsvLine(first);
            } finally {
                in.close();
            }
        } catch (IOException unreadable) {
            return Collections.emptyList();
        }
    }

    /**
     * Appends one line, writing the header first when the file is new.
     *
     * <p>A file whose last line has no line break gets one before the new line
     * is added, so a run interrupted mid-write does not silently join two runs
     * into one row.
     */
    private static void appendSummary(File file, List<String> row) throws IOException {
        boolean fresh = !file.isFile() || file.length() == 0;
        boolean needsBreak = !fresh && !endsWithLineBreak(file);
        BufferedWriter out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            if (needsBreak) out.newLine();
            if (fresh) {
                out.write(csvLine(SUMMARY_COLUMNS));
                out.newLine();
            }
            out.write(csvLine(row));
            out.newLine();
        } finally {
            out.close();
        }
    }

    private static boolean endsWithLineBreak(File file) throws IOException {
        RandomAccessFile in = new RandomAccessFile(file, "r");
        try {
            if (in.length() == 0) return true;
            in.seek(in.length() - 1);
            int last = in.read();
            return last == '\n' || last == '\r';
        } finally {
            in.close();
        }
    }

    /** One line of the summary, in the order {@link #SUMMARY_COLUMNS} gives. */
    private static List<String> summaryRow(RegDriftResult result) {
        RegDriftParameters parameters = result.parameters();
        Provenance provenance = result.provenance();
        Recommendation first = firstRanked(result);
        int channel = provenance == null ? Provenance.CHANNEL_UNRESOLVED : provenance.channel();
        int diagnosisRow = diagnosisRowFor(result.diagnosis(), channel);

        List<String> row = new ArrayList<String>(SUMMARY_COLUMNS.size());
        row.add(RUN_STAMP.format(Instant.now()));
        row.add(RegDrift.VERSION);
        row.add(parameters.image() == null ? "" : parameters.image().getTitle());
        row.add(parameters.mode().macroValue());
        row.add(settingsLine(parameters));
        row.add(channel == Provenance.CHANNEL_UNRESOLVED
                ? parameters.channel().toMacroValue() : Integer.toString(channel));
        row.add(provenance == null ? "" : Integer.toString(provenance.measuredAtBin()));
        row.add(RegDriftTables.cellText(result.diagnosis(), "motion_label", diagnosisRow));
        row.add(RegDriftTables.cellText(result.diagnosis(), "motion_dominant", diagnosisRow));
        row.add(RegDriftTables.cellText(result.diagnosis(), "severity", diagnosisRow));
        row.add(result.verdict() == null ? "" : result.verdict().tableValue());
        row.add(result.verdictReason());
        row.add(first == null ? "" : first.engine());
        row.add(first == null ? "" : first.calibration().tableValue());
        row.add(first == null ? "" : CsvWriter.number(first.expectedErrorPx()));
        row.add(first == null ? "" : CsvWriter.number(first.expectedSeconds()));
        row.add(parameters.arbiter().macroValue());
        row.add(result.isSuccess() ? "ok" : result.failure().kind().name().toLowerCase(
                java.util.Locale.ROOT));
        return row;
    }

    private static Recommendation firstRanked(RegDriftResult result) {
        for (Recommendation ranked : result.ranked()) {
            if (ranked.rank() == 1) return ranked;
        }
        return null;
    }

    /**
     * Which diagnosis row describes the channel the run settled on, or
     * {@code -1} when there is no way to tell.
     */
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

    /**
     * The settings as a macro line.
     *
     * <p>A window title holding a bracket cannot be written in the macro grammar
     * at all, and the record of a run is not the place to give up over it: the
     * line is replaced by the reason it could not be written, and the rest of
     * the tree is still saved.
     */
    private static String settingsLine(RegDriftParameters parameters) {
        try {
            return RegDriftMacroOptions.from(parameters).toMacroOptions();
        } catch (IllegalArgumentException unwritable) {
            return "(these settings cannot be written as a macro line: "
                    + describe(unwritable) + ")";
        }
    }

    // -------------------------------------------------------------- README.txt

    private static void writeReadme(File file, RegDriftResult result, File summary)
            throws IOException {
        RegDriftParameters parameters = result.parameters();
        Provenance provenance = result.provenance();
        String newline = System.getProperty("line.separator", "\n");
        StringBuilder text = new StringBuilder();

        line(text, newline, "Registration and Drift Comparison");
        line(text, newline, "=================================");
        line(text, newline, "");
        line(text, newline, "Written by version " + RegDrift.VERSION + " on "
                + RUN_STAMP.format(Instant.now()) + ".");
        line(text, newline, "");

        line(text, newline, "WHAT IS IN THIS FOLDER");
        line(text, newline, "  " + DIAGNOSIS_FOLDER + "/<title>_" + DIAGNOSIS_FOLDER + ".csv");
        line(text, newline, "      One row per channel: what the movement in that recording looks");
        line(text, newline, "      like, and whether it can be registered. Columns:");
        columns(text, newline, RegDriftTables.DIAGNOSIS_COLUMNS);
        line(text, newline, "  " + RECOMMENDATION_FOLDER + "/<title>_" + RECOMMENDATION_FOLDER
                + ".csv");
        line(text, newline, "      One row per candidate engine, ranked, with the measured row it");
        line(text, newline, "      came from and a macro line you can copy. Columns:");
        columns(text, newline, RegDriftTables.RECOMMENDATION_COLUMNS);
        line(text, newline, "  " + COMPARISON_FOLDER + "/<title>_" + COMPARISON_FOLDER + ".csv");
        line(text, newline, "      One row per engine that was actually run, with what it cost in");
        line(text, newline, "      CPU seconds and what it left behind. Columns:");
        columns(text, newline, RegDriftTables.COMPARISON_COLUMNS);
        line(text, newline, "  " + FRAMES_FOLDER + "/<title>_" + FRAMES_FOLDER + ".csv");
        line(text, newline, "      One row per frame of the scored arm. Columns:");
        columns(text, newline, RegDriftTables.FRAMES_COLUMNS);
        line(text, newline, "  " + REGISTERED_FOLDER + "/<title>_<engine>.tif");
        line(text, newline, "      The registered stack. Written in apply mode; this folder stays");
        line(text, newline, "      empty in the other modes.");
        line(text, newline, "  " + QC_FOLDER + "/<title>_kymograph.tif");
        line(text, newline, "      A before-and-after panel, when one was asked for.");
        line(text, newline, "  " + SUMMARY_FILE);
        line(text, newline, "      One line per run, appended across runs. See below.");
        line(text, newline, "");

        line(text, newline, "SETTINGS USED");
        line(text, newline, "  These are the settings of the most recent run written here. Every");
        line(text, newline, "  run's settings are kept in its own line of the summary file, in the");
        line(text, newline, "  'settings' column, so replacing this file loses nothing.");
        line(text, newline, "");
        line(text, newline, "  Image:  " + (parameters.image() == null
                ? "" : parameters.image().getTitle()));
        line(text, newline, "  Mode:   " + parameters.mode().macroValue());
        line(text, newline, "  Macro:  " + settingsLine(parameters));
        line(text, newline, "");

        line(text, newline, "PLUGIN VERSION");
        line(text, newline, "  " + RegDrift.VERSION);
        line(text, newline, "");

        line(text, newline, "REGISTRATION ENGINES FOUND");
        Map<String, String> engines = provenance == null
                ? Collections.<String, String>emptyMap() : provenance.engineVersions();
        if (engines.isEmpty()) {
            line(text, newline, "  This run looked for no engines, so none is recorded.");
        } else {
            for (Map.Entry<String, String> engine : engines.entrySet()) {
                line(text, newline, "  " + engine.getKey() + "  " + engine.getValue());
            }
        }
        line(text, newline, "");

        line(text, newline, "CALIBRATION SET");
        String calibration = provenance == null ? "" : provenance.calibrationSet();
        line(text, newline, "  " + (calibration.isEmpty()
                ? "No calibration table was consulted by this run." : calibration));
        line(text, newline, "");

        line(text, newline, "MEASUREMENT SCALE");
        line(text, newline, "  The 'localisability' column is the fall in frame-to-frame");
        line(text, newline, "  correlation under a one-pixel displacement, and one pixel means");
        line(text, newline, "  something different after binning than it does at native");
        line(text, newline, "  resolution. Each diagnosis row therefore carries the scale it was");
        line(text, newline, "  measured at in 'measured_at_bin', and two rows measured at");
        line(text, newline, "  different values are not comparable.");
        line(text, newline, "  This run measured at: " + (provenance == null
                ? "not recorded" : Integer.toString(provenance.measuredAtBin())));
        line(text, newline, "");

        line(text, newline, "HOW " + SUMMARY_FILE + " GROWS");
        line(text, newline, "  A run appends one line to " + SUMMARY_FILE + " when that file's");
        line(text, newline, "  header is the column set this version writes, or when the file is");
        line(text, newline, "  not there yet. When an earlier version of this plugin wrote it with");
        line(text, newline, "  a different set of columns, that file is left exactly as it is and");
        line(text, newline, "  the run appends to summary_2.csv instead, then summary_3.csv, and");
        line(text, newline, "  so on. Nothing already written is reshaped and no column is");
        line(text, newline, "  dropped, so each summary file can be read with a single header and");
        line(text, newline, "  a column added in a later version never shifts an older file's");
        line(text, newline, "  values sideways into the wrong column.");
        line(text, newline, "  This run appended to: " + summary.getName());
        line(text, newline, "  Columns:");
        columns(text, newline, SUMMARY_COLUMNS);
        line(text, newline, "");

        line(text, newline, "READING THE NUMBERS");
        line(text, newline, "  Displacements are in pixels, never in microns. Timings are CPU");
        line(text, newline, "  seconds, so they do not change with how busy the machine was.");
        line(text, newline, "  'residual_before' and 'residual_after' are mismatch, not error");
        line(text, newline, "  against a known answer: the true registration is unknown, and no");
        line(text, newline, "  column here claims otherwise. 'sd_vs_control' compares an arm");
        line(text, newline, "  against a control resampled the same way, so the blur that comes");
        line(text, newline, "  free with interpolation cannot flatter it.");

        Files.write(file.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void line(StringBuilder text, String newline, String content) {
        text.append(content).append(newline);
    }

    /**
     * A column list, wrapped so it stays readable in a fixed-width viewer.
     *
     * <p>Eighteen column names on one line is a schema nobody reads. They are
     * broken at whole names, never inside one, so a name can still be found by
     * searching the file for it.
     */
    private static void columns(StringBuilder text, String newline, List<String> names) {
        StringBuilder row = new StringBuilder(README_INDENT);
        for (String name : names) {
            String piece = row.length() > README_INDENT.length() ? ", " + name : name;
            if (row.length() + piece.length() > README_WIDTH) {
                line(text, newline, row.toString() + ",");
                row = new StringBuilder(README_INDENT).append(name);
            } else {
                row.append(piece);
            }
        }
        line(text, newline, row.toString());
    }

    // -------------------------------------------------------------- machinery

    private static String csvLine(List<String> fields) {
        StringBuilder line = new StringBuilder();
        for (String field : fields) {
            if (line.length() > 0) line.append(',');
            line.append(CsvWriter.quote(field));
        }
        return line.toString();
    }

    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        // A leading byte-order mark belongs to the file, not to the first column name.
        String text = !line.isEmpty() && line.charAt(0) == BYTE_ORDER_MARK
                ? line.substring(1) : line;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    field.append(c);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    /** An exception's own sentence, or its type when it did not carry one. */
    private static String describe(Exception problem) {
        String message = problem.getMessage();
        return message == null || message.trim().isEmpty()
                ? problem.getClass().getSimpleName() : message.trim();
    }

    /** Thrown when a folder has accumulated more summary shapes than it can name. */
    private static final class TreeTooCrowded extends IOException {

        private static final long serialVersionUID = 1L;

        private TreeTooCrowded(String message) {
            super(message);
        }
    }

    /**
     * What a save did, or the typed reason it did nothing.
     *
     * <p>A save that could not happen says so here rather than by returning an
     * empty list of files, which a batch loop would read as success.
     */
    public static final class Report {

        private final File tree;
        private final List<File> written;
        private final File summaryFile;
        private final Failure failure;

        private Report(File tree, List<File> written, File summaryFile, Failure failure) {
            this.tree = tree;
            this.written = Collections.unmodifiableList(new ArrayList<File>(written));
            this.summaryFile = summaryFile;
            this.failure = failure;
        }

        static Report saved(File tree, List<File> written, File summaryFile) {
            return new Report(tree, written, summaryFile, null);
        }

        static Report failed(File tree, Failure failure) {
            return new Report(tree, Collections.<File>emptyList(), null, failure);
        }

        /** The {@code RegistrationDriftComparison} folder. Null when none was chosen. */
        public File tree() {
            return tree;
        }

        /** Every file this save wrote or appended to. Empty when it could not. */
        public List<File> written() {
            return written;
        }

        /** The summary file this run appended to. Null when nothing was appended. */
        public File summaryFile() {
            return summaryFile;
        }

        /** Why nothing was saved. Null when it was. */
        public Failure failure() {
            return failure;
        }

        /** True when everything asked for was written. */
        public boolean isSuccess() {
            return failure == null;
        }
    }
}
