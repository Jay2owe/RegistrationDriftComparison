/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.advise;

import regdrift.autofix.EngineId;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The measured benchmark, bundled inside this jar and read once.
 *
 * <p>The everyday version: a printed table of test results that ships inside
 * the tool, so that two people running the tool a year apart are quoting the
 * same page of the same edition.
 *
 * <h2>Frozen, and why that is the whole point</h2>
 *
 * <p>The three files are copies taken on 2026-08-13 of a benchmark run on
 * 2026-08-11, and <b>nothing edits them</b>. A recommendation whose evidence
 * file can change underneath it is not evidence: two users would get different
 * answers from the same recording with no way to tell why, which is exactly the
 * failure this plugin exists to fix in other tools. A later benchmark run that
 * produces better numbers is a <em>new</em> bundled file with a new date in its
 * name and a line in the changelog, beside these rather than instead of them.
 *
 * <p>The research folder they were copied from holds newer files dated
 * 2026-08-12 and 2026-08-13. Those are work in progress belonging to somebody
 * else and are deliberately not here.
 *
 * <h2>What the table can and cannot say about an engine</h2>
 *
 * <p>Only one installable engine was ever driven on these pixels:
 * {@code TurboReg (StackReg engine)} in {@code thirdparty_2026-08-11.csv}. So
 * that is the one arm {@link #armFor} answers with, and it answers for two
 * catalogue entries because the run is literally both of them - see
 * {@link #armFor} for the reasoning, and for the three arms that look as though
 * they should map onto an engine and deliberately do not.
 *
 * <h2>Every timing here is CPU time</h2>
 *
 * <p>Defect D6. A prior run of this benchmark reported one arm at 237,000 ms
 * per pair, which was a laptop standby being counted as computation. Nothing in
 * this class or anything downstream of it reads a wall clock.
 */
public final class CalibrationTable {

    /**
     * Where the bundled files sit inside the jar.
     *
     * <p>House rule 15: shading rewrites bytecode references <em>and</em> any
     * string that looks like a package being relocated. This path resembles
     * neither {@code sc.fiji.oc3d.core} nor {@code sc.fiji.autofix.core}, so it
     * survives the rewrite intact. Do not move these files under a folder named
     * after either.
     */
    public static final String RESOURCE_FOLDER = "/calibration/";

    /** 216 arms: nine estimators, two reconciliations, four conditions, three seeds. */
    public static final String BENCHMARK_FILE = "benchmark_2026-08-11.csv";

    /** 24 arms: a real third-party plugin on the same pixels. The engine evidence. */
    public static final String THIRD_PARTY_FILE = "thirdparty_2026-08-11.csv";

    /** 180 arms: the intensity ceiling swept from the 99.5th percentile to the 85th. */
    public static final String SATURATION_FILE = "saturation_2026-08-11.csv";

    /** The three bundled files, in the order they are read. */
    public static final List<String> FILES = Collections.unmodifiableList(Arrays.asList(
            BENCHMARK_FILE, THIRD_PARTY_FILE, SATURATION_FILE));

    /**
     * The one calibration sentence, shown wherever a recommendation is - defect
     * D10.
     *
     * <p>Not a footnote. Three phase-contrast frames from one instrument is a
     * narrow calibration, and presenting what it produces as a general
     * recommendation would overstate it by more than the recommendation is
     * worth.
     */
    public static final String CALIBRATION_SET =
            "Calibrated on three IncuCyte phase-contrast seed frames of 48 frames each, spanning"
                    + " localisability 0.017 to 0.160 measured at a 4 x 4 pixel mean, under four"
                    + " constructed conditions. Bundled frozen as " + BENCHMARK_FILE + ", "
                    + THIRD_PARTY_FILE + " and " + SATURATION_FILE + ". Every figure it produces"
                    + " is CPU time and measured error on those three frames, not a promise about"
                    + " a recording it has never seen.";

    /**
     * The arm in {@link #THIRD_PARTY_FILE}, spelled as that file spells it.
     *
     * <p>A real {@code ij.plugin.PlugIn} driven pair by pair from Java through
     * the same pair plan as every other arm in the benchmark - no macro, no
     * window, no proxy.
     */
    public static final String THIRD_PARTY_ARM = "TurboReg (StackReg engine)";

    /**
     * The reconciliation the recommendation is drawn from: a plain consecutive
     * chain.
     *
     * <p>The benchmark also ran every arm through redundant cross-correlation,
     * which measures 4.45x more pairs and reconciles them by least squares.
     * Those rows are in the bundled file and are <b>not</b> read here, because
     * no engine in this plugin's catalogue can produce them: the file's own
     * notes describe the reconciled row as what the engine would score
     * <em>if</em> it had multi-lag reconciliation, which it cannot. Quoting it
     * would credit an engine with an improvement belonging to a reconciliation
     * nobody can run it with.
     */
    public static final String CHAIN = "CHAIN";

    /** The conditions the benchmark constructed, spelled as the files spell them. */
    public enum Condition {

        /** Independent per-frame noise at 10% of the frame's standard deviation. */
        CLEAN,

        /** A 4x intensity ramp across the recording - 2.0 in log2 units. */
        GAIN_FADE,

        /** The same patches, filled with a constant bright value over 10% of the field. */
        CHANGE_BLOCKS,

        /** 10% of the field replaced with real content from a nearby offset. */
        CHANGE_MOVED;

        /** The condition a file's token names, or null when it names none. */
        public static Condition of(String token) {
            String wanted = token == null ? "" : token.trim();
            for (Condition condition : values()) {
                if (condition.name().equalsIgnoreCase(wanted)) return condition;
            }
            return null;
        }
    }

    private static CalibrationTable bundled;

    private final List<Row> rows;
    private final Set<String> seeds;

    private CalibrationTable(List<Row> rows) {
        this.rows = Collections.unmodifiableList(rows);
        Set<String> found = new LinkedHashSet<String>();
        for (Row row : rows) found.add(row.seed());
        this.seeds = Collections.unmodifiableSet(found);
    }

    /**
     * The bundled table, parsed once and shared.
     *
     * <p>Reads three files out of this jar. Opens no connection, writes nothing,
     * and works the same on a Fiji with no engine installed at all.
     *
     * @throws IllegalStateException when a bundled file is absent from the jar
     *         or cannot be parsed. That is a broken build rather than anything a
     *         user did, and it is said loudly rather than degraded into an empty
     *         table that would silently rank nothing
     */
    public static synchronized CalibrationTable bundled() {
        if (bundled == null) {
            List<Row> all = new ArrayList<Row>();
            for (String file : FILES) all.addAll(read(file));
            bundled = new CalibrationTable(all);
        }
        return bundled;
    }

    /** Every row of every bundled file, in file order then file line order. */
    public List<Row> rows() {
        return rows;
    }

    /** Every row that came out of one bundled file. */
    public List<Row> rowsFrom(String file) {
        List<Row> found = new ArrayList<Row>();
        for (Row row : rows) {
            if (row.file().equals(file)) found.add(row);
        }
        return Collections.unmodifiableList(found);
    }

    /** The seed frames the benchmark ran on, in first-mention order. */
    public Set<String> seeds() {
        return seeds;
    }

    /**
     * The measured arm that describes one engine under one condition, or
     * {@code null} when the benchmark measured none.
     *
     * <h2>The two engines that have one</h2>
     *
     * <p><b>StackReg</b> is a loop over TurboReg that registers each slice to
     * the one before it, so the {@code CHAIN} rows of
     * {@link #THIRD_PARTY_FILE} <em>are</em> StackReg's own strategy driven
     * through StackReg's own estimator. That is the strongest mapping in this
     * whole table: the row is not like the engine, it is the engine.
     *
     * <p><b>TurboReg</b> is the class the benchmark actually loaded and called -
     * {@code run("-align")}, then its refined landmark positions read back and
     * subtracted. The same rows therefore describe it, and the two engines
     * carry identical figures because one measurement produced both. The
     * benchmark cannot separate them and this class does not pretend it can.
     *
     * <h2>The three arms that look as though they should map and do not</h2>
     *
     * <ul>
     *   <li><b>{@code SSD no gain (StackReg family)}</b> is a stand-in for
     *       StackReg written inside the benchmark's own engine. It tracked the
     *       real plugin closely - 7.96 px against 7.79 px under the fade - but
     *       the real plugin was run, so quoting a stand-in for it would be
     *       weaker evidence chosen over stronger for no reason.</li>
     *   <li><b>{@code phase correlation}</b> is the benchmark's own
     *       implementation. Correct 3D drift is also phase-correlation drift
     *       correction, and mapping one onto the other would be a claim about
     *       somebody else's plugin drawn from code they did not write.</li>
     *   <li>Every <b>{@code log-ratio}</b> arm belongs to the registration
     *       engine this plugin's author wrote. It has no update site, so it
     *       cannot be installed, and an engine that cannot be installed is not a
     *       recommendation. It is not in the catalogue and it is not named in
     *       any output.</li>
     * </ul>
     *
     * <p>Every other catalogue engine - MultiStackReg, Correct 3D drift,
     * Descriptor-based registration, Register Virtual Stack Slices, Linear Stack
     * Alignment with SIFT, Image Stabilizer, Fast4DReg, NanoJ-Core - was never
     * run on these pixels. They are ranked after the engines that were, and the
     * row says so in as many words.
     */
    public Arm armFor(EngineId engine, Condition condition) {
        if (engine == null || condition == null) return null;
        if (engine != EngineId.TURBOREG && engine != EngineId.STACKREG) return null;
        List<Row> arm = new ArrayList<Row>();
        for (Row row : rows) {
            if (!row.file().equals(THIRD_PARTY_FILE)) continue;
            if (!THIRD_PARTY_ARM.equals(row.estimator())) continue;
            if (row.condition() != condition) continue;
            if (!CHAIN.equals(row.reconciliation())) continue;
            arm.add(row);
        }
        return arm.isEmpty() ? null : new Arm(engine, condition, arm);
    }

    /** Which conditions {@link #armFor} can answer for an engine that has an arm. */
    public List<Condition> measuredConditions(EngineId engine) {
        List<Condition> found = new ArrayList<Condition>();
        for (Condition condition : Condition.values()) {
            if (armFor(engine, condition) != null) found.add(condition);
        }
        return Collections.unmodifiableList(found);
    }

    // ------------------------------------------------------------------ reading

    private static List<Row> read(String file) {
        String resource = RESOURCE_FOLDER + file;
        InputStream in = CalibrationTable.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("The bundled calibration file '" + resource + "' is not"
                    + " in this jar, so no recommendation can be traced to a measurement. This is a"
                    + " broken build: the file lives under src/main/resources" + RESOURCE_FOLDER
                    + " and must survive packaging.");
        }
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            try {
                return parse(file, reader);
            } finally {
                reader.close();
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException("The bundled calibration file '" + resource
                    + "' could not be read: " + unreadable, unreadable);
        }
    }

    /**
     * One file into rows.
     *
     * <p>Split on commas with no quote handling, which is correct here and is
     * checked rather than assumed: the tool that wrote these files states that
     * no label in them holds a comma, and {@code CalibrationTableTest} asserts
     * every line has as many fields as the header. A row that does not is a
     * corrupted bundled file and stops the load rather than becoming a partly
     * filled measurement.
     */
    private static List<Row> parse(String file, BufferedReader reader) throws IOException {
        String headerLine = reader.readLine();
        if (headerLine == null) {
            throw new IllegalStateException("The bundled calibration file '" + file
                    + "' has no header line.");
        }
        String[] header = headerLine.split(",", -1);
        Map<String, Integer> column = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < header.length; i++) {
            column.put(header[i].trim(), Integer.valueOf(i));
        }

        List<Row> found = new ArrayList<Row>();
        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split(",", -1);
            if (fields.length != header.length) {
                throw new IllegalStateException("The bundled calibration file '" + file
                        + "' has " + fields.length + " fields on line " + lineNumber + " and "
                        + header.length + " column names. A frozen evidence file that no longer"
                        + " parses is a corrupted jar, not something to work around.");
            }
            found.add(new Row(file, column, fields));
        }
        return found;
    }

    private static String text(Map<String, Integer> column, String[] fields, String name) {
        Integer at = column.get(name);
        if (at == null) return "";
        return fields[at.intValue()].trim();
    }

    private static double number(Map<String, Integer> column, String[] fields, String name) {
        String value = text(column, fields, name);
        if (value.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException notANumber) {
            // 'none' in saturation_pct and floor_pct, which is a state and not a quantity.
            return Double.NaN;
        }
    }

    // -------------------------------------------------------------- the values

    /** One measured arm of the benchmark: one seed, one condition, one estimator. */
    public static final class Row {

        private final String file;
        private final String seed;
        private final Condition condition;
        private final String estimator;
        private final String reconciliation;
        private final double frames;
        private final double pairs;
        private final double cpuMsPerPair;
        private final double medianErrPx;
        private final double p90ErrPx;
        private final double maxErrPx;
        private final String saturation;
        private final double saturationPercentile;

        private Row(String file, Map<String, Integer> column, String[] fields) {
            this.file = file;
            this.seed = text(column, fields, "seed");
            this.condition = Condition.of(text(column, fields, "condition"));
            this.estimator = text(column, fields, "estimator");
            this.reconciliation = text(column, fields, "reconciliation");
            this.frames = number(column, fields, "frames");
            this.pairs = number(column, fields, "pairs");
            this.cpuMsPerPair = number(column, fields, "cpu_ms_per_pair");
            this.medianErrPx = number(column, fields, "median_err_px");
            this.p90ErrPx = number(column, fields, "p90_err_px");
            this.maxErrPx = number(column, fields, "max_err_px");
            this.saturation = text(column, fields, "saturation_pct");
            this.saturationPercentile = number(column, fields, "saturation_pct");
        }

        /** Which bundled file this row came out of. */
        public String file() {
            return file;
        }

        /** The seed frame the stack was built from. */
        public String seed() {
            return seed;
        }

        /** The constructed condition, or null when the file names one this build does not know. */
        public Condition condition() {
            return condition;
        }

        /** The estimator or plugin arm, spelled as the file spells it. */
        public String estimator() {
            return estimator;
        }

        /** {@code CHAIN} or {@code RCC}. */
        public String reconciliation() {
            return reconciliation;
        }

        /** Frames in the arm's stack. NaN in a file that does not record it. */
        public double frames() {
            return frames;
        }

        /** Frame pairs measured. */
        public double pairs() {
            return pairs;
        }

        /** CPU milliseconds per pair - never wall-clock, defect D6. */
        public double cpuMsPerPair() {
            return cpuMsPerPair;
        }

        /** Median displacement error against the exact truth, in pixels. */
        public double medianErrPx() {
            return medianErrPx;
        }

        /** 90th-percentile displacement error, in pixels. */
        public double p90ErrPx() {
            return p90ErrPx;
        }

        /** Largest displacement error, in pixels. */
        public double maxErrPx() {
            return maxErrPx;
        }

        /** The intensity ceiling this arm ran with, as the file spells it - often {@code none}. */
        public String saturation() {
            return saturation;
        }

        /** The ceiling as a percentile, or NaN when the arm ran without one. */
        public double saturationPercentile() {
            return saturationPercentile;
        }

        @Override
        public String toString() {
            return file + "[" + seed + " " + condition + " " + estimator + " "
                    + reconciliation + "]";
        }
    }

    /**
     * What one engine measured out at under one condition, across the seeds.
     *
     * <h2>The middle seed, and the range beside it</h2>
     *
     * <p>{@link #medianErrPx()} is the <b>median across the seeds</b> and not
     * their mean, and the choice matters. The three seeds were chosen to
     * <em>span</em> localisability rather than sampled from anything, so their
     * mean is a weighted statement about a spanning set: under the fade the
     * three read 4.58, 0.21 and 0.26 px, and the mean of 1.68 describes none of
     * them. The benchmark's own notes say so - the collapse is a property of the
     * fade on weak structure, and a three-seed mean hides that.
     *
     * <p>So the middle seed is reported and {@link #lowestErrPx()} and
     * {@link #highestErrPx()} travel with it, in the reason a user reads, rather
     * than the spread being averaged away.
     */
    public static final class Arm {

        private final EngineId engine;
        private final Condition condition;
        private final List<Row> rows;
        private final double medianErrPx;
        private final double lowestErrPx;
        private final double highestErrPx;
        private final double cpuMsPerPair;

        private Arm(EngineId engine, Condition condition, List<Row> rows) {
            this.engine = engine;
            this.condition = condition;
            this.rows = Collections.unmodifiableList(new ArrayList<Row>(rows));
            double[] errors = new double[rows.size()];
            double[] costs = new double[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                errors[i] = rows.get(i).medianErrPx();
                costs[i] = rows.get(i).cpuMsPerPair();
            }
            double[] sorted = errors.clone();
            Arrays.sort(sorted);
            this.medianErrPx = median(sorted);
            this.lowestErrPx = sorted[0];
            this.highestErrPx = sorted[sorted.length - 1];
            double[] sortedCosts = costs.clone();
            Arrays.sort(sortedCosts);
            this.cpuMsPerPair = median(sortedCosts);
        }

        private static double median(double[] sorted) {
            int n = sorted.length;
            if (n == 0) return Double.NaN;
            return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
        }

        /** The engine these rows describe. */
        public EngineId engine() {
            return engine;
        }

        /** The condition these rows were measured under. */
        public Condition condition() {
            return condition;
        }

        /** The rows themselves, one per seed. */
        public List<Row> rows() {
            return rows;
        }

        /** How many seeds contributed. */
        public int seeds() {
            return rows.size();
        }

        /** The middle seed's median error, in pixels. */
        public double medianErrPx() {
            return medianErrPx;
        }

        /** The lowest of the per-seed medians, in pixels. */
        public double lowestErrPx() {
            return lowestErrPx;
        }

        /** The highest of the per-seed medians, in pixels. */
        public double highestErrPx() {
            return highestErrPx;
        }

        /** The middle seed's CPU milliseconds per pair. Never wall-clock. */
        public double cpuMsPerPair() {
            return cpuMsPerPair;
        }

        /** Which bundled file these rows came out of. */
        public String file() {
            return rows.get(0).file();
        }

        /** The arm as the file spells it. */
        public String estimator() {
            return rows.get(0).estimator();
        }

        /**
         * The measured row this arm is, in one clause a sceptical reader can go
         * and check.
         */
        public String reason() {
            return String.format(Locale.US,
                    "median error %.3f px across %d phase-contrast seeds (%.3f to %.3f) on the %s"
                            + " condition, %.1f CPU ms per frame pair (%s, %s row)",
                    medianErrPx, seeds(), lowestErrPx, highestErrPx, condition.name(),
                    cpuMsPerPair, file(), reconciliationWord());
        }

        private String reconciliationWord() {
            return rows.get(0).reconciliation();
        }

        @Override
        public String toString() {
            return engine + " " + condition + " " + reason();
        }
    }
}
