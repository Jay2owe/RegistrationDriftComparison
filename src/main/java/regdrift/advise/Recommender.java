/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.advise;

import regdrift.Recommendation;
import regdrift.autofix.EngineId;
import regdrift.autofix.EngineRegistry;
import regdrift.diag.Fingerprint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Looks one measured recording up in the bundled table and ranks the engines the
 * measurements support.
 *
 * <p>The everyday version: a lookup in a printed results table, not a model. No
 * fitting, no heuristic, no folklore - a condition is chosen, a row is read, and
 * the row is quoted with the file it came out of so a sceptical reader can go and
 * check it.
 *
 * <h2>The condition mapping is the weakest link in this plugin, and it is here</h2>
 *
 * <p>The bundled files are keyed on conditions the benchmark <em>constructed</em>
 * - {@code CLEAN}, {@code GAIN_FADE}, {@code CHANGE_BLOCKS},
 * {@code CHANGE_MOVED} - and a fingerprint measures a real recording. Deciding
 * which constructed condition a real recording resembles is a <b>judgement, not
 * a measurement</b>, and it is written in one small method,
 * {@link #conditionOf}, so that the judgement is in one place and each branch
 * carries the evidence behind it. When no branch fits cleanly the answer is no
 * condition at all and {@code outside_calibrated_range}, which is the default
 * rather than the exception.
 *
 * <h2>Two axes, and a gap down the middle of each</h2>
 *
 * <p>The mapping reads two numbers, and each one has a measured "ordinary" band,
 * a measured "constructed" value, and nothing in between. A fingerprint landing
 * in a gap flags rather than being rounded to the nearer side:
 *
 * <table>
 *   <caption>The two axes of the condition mapping</caption>
 *   <tr><th></th><th>ordinary, as measured</th><th>constructed, as built</th></tr>
 *   <tr>
 *     <td>{@code log2_trend}</td>
 *     <td>up to {@value #STABLE_TREND_LOG2}: the largest fitted intensity trend
 *         across the twenty-four IncuCyte recordings of the motion survey is
 *         0.3763</td>
 *     <td>2.0 exactly: the benchmark's fade is a 4x ramp. Half of it,
 *         {@value #FADING_TREND_LOG2}, is where this mapping calls a recording
 *         fading</td>
 *   </tr>
 *   <tr>
 *     <td>{@code bright_fraction}</td>
 *     <td>up to {@value #NO_BRIGHT_STRUCTURE}: the largest reading across the
 *         twelve library recordings is 0.0050, on the one the library itself
 *         records as having a bright intruder</td>
 *     <td>0.10 exactly: the benchmark's blocks cover a tenth of the field. Half
 *         of it, {@value #BRIGHT_STRUCTURE}, is where this mapping calls a
 *         recording blocked</td>
 *   </tr>
 * </table>
 *
 * <p>One rule, stated once and applied twice: the ordinary edge is the largest
 * value measured on real recordings, and the constructed edge is half what the
 * benchmark built. Everything between the two is a gap nobody measured, and a
 * recording landing in it is flagged rather than assigned.
 *
 * <h2>What this deliberately does not route on</h2>
 *
 * <p><b>Localisability.</b> Defect D12 measured that no cut on it, at bin 1, 2,
 * 4 or 8, separates the recordings that register from the recordings that do
 * not; at the survey's own scale its rank correlation with the outcome is +0.13.
 * It is not a condition branch here and it never will be. It <em>is</em> read
 * for {@link #calibrationOf}, which is a different question - "is this recording
 * like the ones the table was measured on" - and that answer changes the
 * {@code calibration} column and nothing else: not the verdict, not which
 * engines appear, not their order, not their figures.
 *
 * <p><b>Frame correlation.</b> It ranks the library almost inversely to the
 * outcome. Reported in the diagnosis table, routed on nowhere.
 *
 * <p><b>{@code CHANGE_MOVED}.</b> The benchmark's fourth condition is real
 * structure displaced within the field, and <b>the fingerprint has no
 * measurement that identifies it</b>. So no branch here produces it, its rows sit
 * in the bundled file unread, and that is stated rather than papered over by
 * folding it into {@code CLEAN} - which would be tempting, because for the one
 * engine that was measured the two conditions read 0.29 px and 0.35 px.
 *
 * <h2>What it does route on</h2>
 *
 * <p><b>Estimator agreement</b>, through the verdict. It is the one confidence
 * signal in the fingerprint that was measured to order the library - rank
 * correlation -0.85 against how much registration actually helped each
 * recording. When the two independent estimators disagree about how a recording
 * moved, no lookup keyed on that movement is worth quoting without a flag, so
 * {@code estimators_disagree} puts the whole recommendation outside the
 * calibrated range.
 */
public final class Recommender {

    /**
     * Up to this much fitted intensity change across a recording, in log2 units,
     * is an ordinary recording rather than a fade.
     *
     * <p>Measured: the twenty-four IncuCyte recordings of the motion survey run
     * from -0.0365 to -0.3763 in {@code gain_log2}, and the twelve library
     * recordings from -0.2239 to +0.1118. This is the largest of those, rounded
     * up to two decimals.
     */
    public static final double STABLE_TREND_LOG2 = 0.38;

    /**
     * From this much fitted intensity change, a recording is treated as the
     * benchmark's fading condition.
     *
     * <p>The benchmark builds a 4x ramp, which is 2.0 in log2 units exactly.
     * This is half of it - 2.6x above the largest trend measured on any real
     * recording, and half the size of the thing it stands for.
     */
    public static final double FADING_TREND_LOG2 = 1.0;

    /**
     * Up to this share of a frame sitting far above the rest is an ordinary
     * recording rather than a bright artefact.
     *
     * <p>Measured: ten of the twelve library recordings read exactly zero, and
     * the two the library itself records as having a bright intruder read 0.0048
     * and 0.0003. This is the larger of those, rounded up.
     */
    public static final double NO_BRIGHT_STRUCTURE = 0.005;

    /**
     * From this share, a recording is treated as the benchmark's block
     * condition.
     *
     * <p>The benchmark fills a tenth of the field with constant bright patches.
     * This is half of that, by the same rule as {@link #FADING_TREND_LOG2}.
     */
    public static final double BRIGHT_STRUCTURE = 0.05;

    /**
     * The scale the calibration's own localisability figures were measured at.
     *
     * <p>The three seed values - 0.0170, 0.1260 and 0.1602 - come from the
     * motion survey, which ran at a 4 x 4 pixel mean, and the fingerprint
     * measures at the same factor. A fingerprint forced to a different factor,
     * which happens on a recording too small to bin four ways, is not comparable
     * with them and says so instead of being compared anyway. That is defect
     * D12's whole lesson applied to this class.
     */
    public static final int CALIBRATION_BIN = 4;

    /**
     * The lower of the two regimes the survey found, in localisability at
     * {@link #CALIBRATION_BIN}.
     *
     * <p>Eleven recordings of one IncuCyte plate, running 0.0080 to 0.0314. The
     * low-structure seed, {@code VID47_D3_1} at 0.0170, is one of them.
     */
    public static final double LOW_REGIME_MIN = 0.0080;

    /** The top of that regime. See {@link #LOW_REGIME_MIN}. */
    public static final double LOW_REGIME_MAX = 0.0314;

    /**
     * The upper regime, in localisability at {@link #CALIBRATION_BIN}.
     *
     * <p>Thirteen recordings of the other plate, running 0.0857 to 0.2838. The
     * two well-textured seeds, at 0.1260 and 0.1602, are among them.
     *
     * <p><b>Nothing at all sits between 0.0314 and 0.0857</b>, in the survey or
     * in the seed set - which is very probably an artefact of two plates on one
     * instrument rather than a fact about microscopy. A fingerprint landing in
     * that gap is flagged rather than assigned to the nearer regime, because
     * "nearer" is not a measurement.
     */
    public static final double HIGH_REGIME_MIN = 0.0857;

    /** The top of the upper regime. See {@link #HIGH_REGIME_MIN}. */
    public static final double HIGH_REGIME_MAX = 0.2838;

    private Recommender() {
    }

    /** Whether an engine is on this computer. Supplied by the caller, never probed here. */
    public interface Availability {

        /** What the last look at this computer found for one engine. Never null. */
        Recommendation.Presence presenceOf(EngineId engine);
    }

    /**
     * The five measurements and the one verdict a recommendation is a function
     * of.
     *
     * <p>Everything this class does is decided by these six values and by the
     * bundled files - nothing else about the recording is read. Saying so as a
     * type rather than leaving it implied has a use beyond tidiness: <b>a
     * recommendation can be reproduced from a saved diagnosis row</b>, without
     * the images, because every one of these is a column of that row. Six months
     * later, "why was this movie registered that way" is answerable from the
     * CSV.
     */
    public static final class Request {

        private final int measuredAtBin;
        private final double localisability;
        private final double log2Trend;
        private final double brightFraction;
        private final int frameCount;
        private final regdrift.Verdict verdict;

        private Request(int measuredAtBin, double localisability, double log2Trend,
                        double brightFraction, int frameCount, regdrift.Verdict verdict) {
            this.measuredAtBin = measuredAtBin;
            this.localisability = localisability;
            this.log2Trend = log2Trend;
            this.brightFraction = brightFraction;
            this.frameCount = frameCount;
            this.verdict = verdict;
        }

        /** The six values a fingerprint and its verdict carry. */
        public static Request of(Fingerprint fingerprint, regdrift.diag.Verdict verdict) {
            if (fingerprint == null) {
                throw new IllegalArgumentException("a recommendation is made from a measurement,"
                        + " and none was given");
            }
            return new Request(
                    fingerprint.measuredAt() == null ? 0 : fingerprint.measuredAt().factor(),
                    fingerprint.localisability() == null
                            ? Double.NaN : fingerprint.localisability().value(),
                    fingerprint.log2Trend(),
                    fingerprint.brightFraction(),
                    fingerprint.frameCount(),
                    verdict == null ? null : verdict.kind());
        }

        /**
         * The same six values read off a saved diagnosis row.
         *
         * @param measuredAtBin   the {@code measured_at_bin} column
         * @param localisability  the {@code localisability} column
         * @param log2Trend       the {@code log2_trend} column
         * @param brightFraction  the {@code bright_fraction} column
         * @param frameCount      how many frames the recording holds
         * @param verdict         the {@code verdict} column
         */
        public static Request of(int measuredAtBin, double localisability, double log2Trend,
                                 double brightFraction, int frameCount,
                                 regdrift.Verdict verdict) {
            return new Request(measuredAtBin, localisability, log2Trend, brightFraction,
                    frameCount, verdict);
        }

        /** The scale the pixels were measured at. */
        public int measuredAtBin() {
            return measuredAtBin;
        }

        /** The fall in frame-to-frame correlation under a one-pixel displacement. */
        public double localisability() {
            return localisability;
        }

        /** The fitted intensity change across the recording, in log2 units. */
        public double log2Trend() {
            return log2Trend;
        }

        /** The share of a frame sitting far above the rest of it. */
        public double brightFraction() {
            return brightFraction;
        }

        /** How many frames the recording holds, which is what the CPU figure scales by. */
        public int frameCount() {
            return frameCount;
        }

        /** Whether the movement could be told at all. */
        public regdrift.Verdict verdict() {
            return verdict;
        }
    }

    // ------------------------------------------------------------- the ranking

    /**
     * Rank the candidate engines for one measured recording.
     *
     * @param fingerprint  what the recording measured out at. Never null
     * @param verdict      whether the movement could be told at all, which is
     *                     the one confidence signal measured to order the library
     * @param candidates   which engines to rank, in catalogue order. Never empty:
     *                     the caller resolves {@code engines=installed} to
     *                     something before calling, and says what it resolved to
     * @param availability what this computer has. Reading it fetches nothing
     * @return the ranking, the calibration flag and the reasons behind both
     */
    public static Result rank(Fingerprint fingerprint, regdrift.diag.Verdict verdict,
                              List<EngineId> candidates, Availability availability) {
        return rank(Request.of(fingerprint, verdict), candidates, availability);
    }

    /**
     * Rank the candidate engines for one recording, from the six values a
     * diagnosis row carries.
     *
     * @param request      what was measured. Never null
     * @param candidates   which engines to rank. Never empty
     * @param availability what this computer has. Reading it fetches nothing
     */
    public static Result rank(Request request, List<EngineId> candidates,
                              Availability availability) {
        if (request == null) {
            throw new IllegalArgumentException("a recommendation is made from a measurement, and"
                    + " none was given");
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("a recommendation ranks engines, and none was"
                    + " given. An empty candidate list is a caller's mistake, not an empty table");
        }
        Availability found = availability == null ? UNKNOWN_EVERYWHERE : availability;
        CalibrationTable table = CalibrationTable.bundled();

        Mapping mapping = conditionOf(request.log2Trend(), request.brightFraction());
        Range range = calibrationOf(request, mapping);
        int pairs = Math.max(0, request.frameCount() - 1);

        List<Candidate> ordered = new ArrayList<Candidate>();
        for (int i = 0; i < candidates.size(); i++) {
            EngineId engine = candidates.get(i);
            CalibrationTable.Arm arm = table.armFor(engine, mapping.condition);
            ordered.add(new Candidate(engine, catalogueIndex(engine), arm));
        }
        Collections.sort(ordered);

        List<Recommendation> ranked = new ArrayList<Recommendation>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Candidate candidate = ordered.get(i);
            EngineId engine = candidate.engine;
            Recommendation.Presence presence = found.presenceOf(engine);
            if (presence == null) presence = Recommendation.Presence.UNKNOWN;
            Recipe recipe = Recipe.forEngine(engine);
            ranked.add(Recommendation.builder(EngineRegistry.displayName(engine), i + 1)
                    .reason(reasonFor(candidate, mapping))
                    .expectedErrorPx(candidate.arm == null
                            ? Double.NaN : candidate.arm.medianErrPx())
                    .expectedSeconds(candidate.arm == null
                            ? Double.NaN : candidate.arm.cpuMsPerPair() * pairs / 1000.0)
                    .calibration(range.calibration)
                    .presence(presence)
                    .installSizeMb(Recipe.installSizeMb(engine, presence))
                    .installAction(Recipe.installAction(engine, presence))
                    .menuPath(recipe.menuPath())
                    .macroLine(recipe.macroLine())
                    .build());
        }
        return new Result(Collections.unmodifiableList(ranked), mapping, range,
                CeilingAdvice.text(request.brightFraction()));
    }

    /**
     * The clause naming the measured row a row came from, or naming the absence
     * of one.
     *
     * <p>Three shapes, and each says which of the three situations it is in.
     * "No number" is never left as an empty cell to be read as a zero.
     */
    private static String reasonFor(Candidate candidate, Mapping mapping) {
        if (candidate.arm != null) {
            return candidate.arm.reason();
        }
        if (mapping.condition == null) {
            return "no measured row: " + mapping.reason + " Nothing in the bundled calibration"
                    + " describes a recording like this one, so this row carries no expected"
                    + " figure and this position is the catalogue's order rather than a"
                    + " measurement.";
        }
        return "no measured row: the bundled calibration never ran this engine, so it is placed"
                + " after every engine it did run and is not compared with them.";
    }

    /** Where an engine sits in the catalogue, which is the last tie-break. */
    private static int catalogueIndex(EngineId engine) {
        EngineId[] all = EngineId.values();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == engine) return i;
        }
        return all.length;
    }

    // --------------------------------------------------- the condition mapping

    /**
     * Which constructed condition this recording resembles, or none.
     *
     * <p><b>This is the judgement.</b> Every branch below names the evidence
     * behind it, and the fall-through is "no condition", not a nearest match.
     *
     * <ul>
     *   <li><b>{@code CLEAN}</b> - a stable intensity trend and no bright
     *       structure. The benchmark's clean condition is the seed frame with
     *       independent per-frame noise and nothing else done to it, and all
     *       twelve library recordings and all twenty-four survey recordings sit
     *       in this box on both measurements.</li>
     *   <li><b>{@code GAIN_FADE}</b> - a fitted intensity change at or past
     *       {@value #FADING_TREND_LOG2} in log2, with no bright structure. The
     *       benchmark's fade is a 4x ramp across the recording, which is 2.0 in
     *       log2 exactly.</li>
     *   <li><b>{@code CHANGE_BLOCKS}</b> - a bright structure covering at least
     *       {@value #BRIGHT_STRUCTURE} of each frame, with a stable trend. The
     *       benchmark's blocks are constant bright patches over a tenth of the
     *       field, brighter than anything in the seed.</li>
     *   <li><b>Both at once</b> - no condition. The benchmark introduced one
     *       nuisance at a time and never two, so no row describes a recording
     *       that fades <em>and</em> carries a bright artefact.</li>
     *   <li><b>Either measurement in its gap, or missing</b> - no condition. See
     *       the class note for the two gaps and why neither is rounded across.</li>
     * </ul>
     */
    static Mapping conditionOf(double trend, double bright) {
        Band trendBand = bandOf(Math.abs(trend), STABLE_TREND_LOG2, FADING_TREND_LOG2);
        Band brightBand = bandOf(bright, NO_BRIGHT_STRUCTURE, BRIGHT_STRUCTURE);

        if (trendBand == Band.UNMEASURED) {
            return new Mapping(null, "the intensity trend across this recording could not be"
                    + " fitted, and it is one of the two measurements the condition is chosen"
                    + " from.");
        }
        if (brightBand == Band.UNMEASURED) {
            return new Mapping(null, "the bright share of these frames could not be measured, and"
                    + " it is one of the two measurements the condition is chosen from.");
        }
        if (trendBand == Band.BETWEEN) {
            return new Mapping(null, String.format(Locale.US,
                    "the intensity across this recording changes by %.3f in log2, which is above"
                            + " the %.2f measured on any real recording in the calibration set and"
                            + " below the %.1f this table calls a fade. Nothing was measured in"
                            + " between, so this is not rounded to the nearer side.",
                    trend, STABLE_TREND_LOG2, FADING_TREND_LOG2));
        }
        if (brightBand == Band.BETWEEN) {
            return new Mapping(null, String.format(Locale.US,
                    "a bright structure covers %.4f of each frame, which is above the %.3f measured"
                            + " on any real recording in the calibration set and below the %.2f"
                            + " this table calls a bright artefact. Nothing was measured in"
                            + " between, so this is not rounded to the nearer side.",
                    bright, NO_BRIGHT_STRUCTURE, BRIGHT_STRUCTURE));
        }
        if (trendBand == Band.HIGH && brightBand == Band.HIGH) {
            return new Mapping(null, String.format(Locale.US,
                    "this recording both fades, by %.3f in log2, and carries a bright structure"
                            + " over %.4f of each frame. The benchmark introduced one nuisance at a"
                            + " time and never two together, so no measured row describes it.",
                    trend, bright));
        }
        if (trendBand == Band.HIGH) {
            return new Mapping(CalibrationTable.Condition.GAIN_FADE, String.format(Locale.US,
                    "the intensity across this recording changes by %.3f in log2, at or past the"
                            + " %.1f this table calls a fade; the benchmark's own fade is 2.0."
                            + " No bright structure was found.",
                    trend, FADING_TREND_LOG2));
        }
        if (brightBand == Band.HIGH) {
            return new Mapping(CalibrationTable.Condition.CHANGE_BLOCKS, String.format(Locale.US,
                    "a bright structure covers %.3f of each frame, at or past the %.2f this table"
                            + " calls a bright artefact; the benchmark's own blocks cover 0.10."
                            + " The intensity trend is stable at %.3f in log2.",
                    bright, BRIGHT_STRUCTURE, trend));
        }
        return new Mapping(CalibrationTable.Condition.CLEAN, String.format(Locale.US,
                "the intensity is stable across this recording (%.3f in log2, within %.2f) and no"
                        + " bright structure was found (%.4f of the frame, within %.3f), which is"
                        + " the benchmark's clean condition.",
                trend, STABLE_TREND_LOG2, bright, NO_BRIGHT_STRUCTURE));
    }

    private static Band bandOf(double value, double ordinaryMax, double constructedMin) {
        if (Double.isNaN(value)) return Band.UNMEASURED;
        if (value <= ordinaryMax) return Band.LOW;
        if (value >= constructedMin) return Band.HIGH;
        return Band.BETWEEN;
    }

    // ------------------------------------------------------ the calibration flag

    /**
     * Whether this recording sits inside what the table was measured on - defect
     * D10.
     *
     * <p>Four questions, and any one of them failing flags the whole
     * recommendation. Flagging changes the {@code calibration} column and the
     * sentence beside it and <b>nothing else</b>: the same engines appear in the
     * same order carrying the same figures, because hiding a measurement behind
     * a flag would be its own kind of dishonesty. What the flag says is "this
     * number was measured on recordings unlike yours", which is a thing a reader
     * can weigh.
     *
     * <ol>
     *   <li><b>The measurement scale.</b> The calibration's localisability
     *       figures were taken at a 4 x 4 pixel mean and the comparison below is
     *       only meaningful at the same factor.</li>
     *   <li><b>The two regimes.</b> See {@link #LOW_REGIME_MIN} and
     *       {@link #HIGH_REGIME_MIN}: two bands with a factor of 2.7 of nothing
     *       between them, and a recording in the gap is flagged rather than
     *       assigned to the nearer one.</li>
     *   <li><b>The verdict.</b> {@code estimators_disagree} means this plugin
     *       cannot say how the recording moved, and a lookup keyed on movement
     *       nobody could measure is a guess with a table behind it.</li>
     *   <li><b>The condition.</b> No condition mapped, no measured row.</li>
     * </ol>
     */
    static Range calibrationOf(Request request, Mapping mapping) {
        List<String> outside = new ArrayList<String>();

        int bin = request.measuredAtBin();
        if (bin != CALIBRATION_BIN) {
            outside.add(String.format(Locale.US,
                    "this recording was measured at bin %d and the calibration's own structure"
                            + " figures were measured at bin %d, so the two are not comparable",
                    bin, CALIBRATION_BIN));
        } else {
            double localisability = request.localisability();
            if (Double.isNaN(localisability)) {
                outside.add("this recording's localisability could not be measured, so it cannot"
                        + " be placed against the calibration set");
            } else if (localisability > LOW_REGIME_MAX && localisability < HIGH_REGIME_MIN) {
                outside.add(String.format(Locale.US,
                        "its localisability is %.4f, which falls in the gap between the two"
                                + " regimes the calibration set was drawn from (%.4f to %.4f and"
                                + " %.4f to %.4f, with nothing measured in between); the nearer"
                                + " regime is not picked, because nearer is not a measurement",
                        localisability, LOW_REGIME_MIN, LOW_REGIME_MAX,
                        HIGH_REGIME_MIN, HIGH_REGIME_MAX));
            } else if (localisability < LOW_REGIME_MIN || localisability > HIGH_REGIME_MAX) {
                outside.add(String.format(Locale.US,
                        "its localisability is %.4f, outside the %.4f to %.4f the calibration set"
                                + " spans altogether",
                        localisability, LOW_REGIME_MIN, HIGH_REGIME_MAX));
            }
        }

        if (request.verdict() == regdrift.Verdict.ESTIMATORS_DISAGREE) {
            outside.add("the two independent estimators describe different movement in it, so this"
                    + " plugin cannot say how it moved and a lookup keyed on that movement would be"
                    + " a guess");
        }
        if (request.verdict() == regdrift.Verdict.NOT_REGISTRABLE) {
            outside.add("there is structurally nothing in it to measure");
        }
        if (mapping.condition == null) {
            outside.add(mapping.reason);
        }

        if (outside.isEmpty()) {
            return new Range(Recommendation.Calibration.IN_RANGE,
                    "This recording resembles the recordings the table was measured on: "
                            + mapping.reason);
        }
        StringBuilder why = new StringBuilder("This recording is outside what the table was"
                + " measured on, so the figures beside it are not extrapolated to it and are"
                + " flagged instead. ");
        for (int i = 0; i < outside.size(); i++) {
            why.append(i == 0 ? "First, " : "Also, ").append(outside.get(i)).append(". ");
        }
        return new Range(Recommendation.Calibration.OUTSIDE_CALIBRATED_RANGE,
                why.toString().trim());
    }

    // ---------------------------------------------------------------- the types

    /** Which band of an axis a measurement fell in. */
    private enum Band {
        /** At or below what real recordings measured. */
        LOW,
        /** Above that and below the constructed condition: a gap nobody measured. */
        BETWEEN,
        /** At or past the constructed condition. */
        HIGH,
        /** Not measured at all. */
        UNMEASURED
    }

    /** A condition and the sentence that chose it. */
    static final class Mapping {

        private final CalibrationTable.Condition condition;
        private final String reason;

        Mapping(CalibrationTable.Condition condition, String reason) {
            this.condition = condition;
            this.reason = reason;
        }

        /** The condition, or null when no branch fitted. */
        CalibrationTable.Condition condition() {
            return condition;
        }

        /** Why, in one finished sentence. */
        String reason() {
            return reason;
        }
    }

    /** A calibration flag and the sentence behind it. */
    static final class Range {

        private final Recommendation.Calibration calibration;
        private final String reason;

        Range(Recommendation.Calibration calibration, String reason) {
            this.calibration = calibration;
            this.reason = reason;
        }
    }

    /** One engine on its way into the ranking. */
    private static final class Candidate implements Comparable<Candidate> {

        private final EngineId engine;
        private final int catalogueIndex;
        private final CalibrationTable.Arm arm;

        Candidate(EngineId engine, int catalogueIndex, CalibrationTable.Arm arm) {
            this.engine = engine;
            this.catalogueIndex = catalogueIndex;
            this.arm = arm;
        }

        /**
         * Measured engines first, by measured error, then by measured CPU cost,
         * then by catalogue order.
         *
         * <p>Mechanical all the way down, so the order can be recomputed from
         * the bundled files by anybody who doubts it. An engine the benchmark
         * never ran cannot be placed against one it did, so it goes after all of
         * them in the catalogue's own order, and its row says as much.
         */
        @Override
        public int compareTo(Candidate other) {
            if ((arm == null) != (other.arm == null)) return arm == null ? 1 : -1;
            if (arm != null) {
                int byError = Double.compare(arm.medianErrPx(), other.arm.medianErrPx());
                if (byError != 0) return byError;
                int byCost = Double.compare(arm.cpuMsPerPair(), other.arm.cpuMsPerPair());
                if (byCost != 0) return byCost;
            }
            return catalogueIndex - other.catalogueIndex;
        }
    }

    /** Nothing was looked at, so nothing is claimed about any engine. */
    private static final Availability UNKNOWN_EVERYWHERE = new Availability() {
        @Override
        public Recommendation.Presence presenceOf(EngineId engine) {
            return Recommendation.Presence.UNKNOWN;
        }
    };

    /** Everything one ranking produced. */
    public static final class Result {

        private final List<Recommendation> ranked;
        private final Mapping mapping;
        private final Range range;
        private final String ceilingAdvice;

        private Result(List<Recommendation> ranked, Mapping mapping, Range range,
                       String ceilingAdvice) {
            this.ranked = ranked;
            this.mapping = mapping;
            this.range = range;
            this.ceilingAdvice = ceilingAdvice;
        }

        /** The engines, rank 1 first. Never empty. */
        public List<Recommendation> ranked() {
            return ranked;
        }

        /** Whether this recording sits inside what the table was measured on. */
        public Recommendation.Calibration calibration() {
            return range.calibration;
        }

        /** Why that flag, in finished sentences. Never empty. */
        public String calibrationReason() {
            return range.reason;
        }

        /** The condition the fingerprint was mapped to, or null when none fitted. */
        public CalibrationTable.Condition condition() {
            return mapping.condition;
        }

        /** Why that condition, or why none. Never empty. */
        public String conditionReason() {
            return mapping.reason;
        }

        /** The calibration set, which is shown wherever this ranking is - defect D10. */
        public String calibrationSet() {
            return CalibrationTable.CALIBRATION_SET;
        }

        /** What to say about an intensity ceiling, warning included - defect D4. */
        public String ceilingAdvice() {
            return ceilingAdvice;
        }

        /**
         * The whole thing as finished prose, for the saved notes and for a
         * results panel.
         */
        public String summary() {
            StringBuilder out = new StringBuilder();
            out.append(calibrationSet()).append(' ').append(range.reason);
            return out.toString();
        }
    }

    /** Every engine the catalogue knows about, in catalogue order. */
    public static List<EngineId> everyEngine() {
        return Collections.unmodifiableList(Arrays.asList(EngineId.values()));
    }
}
