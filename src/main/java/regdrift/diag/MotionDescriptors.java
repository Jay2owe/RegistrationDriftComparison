/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Turns a set of measured frame-to-frame displacements into a description of the
 * movement: how fast the field drifts, how far it strays about that drift, how
 * big the steps are, whether anything knocked the stage, and a label and a
 * severity for the two of those a person reads first.
 *
 * <p>The everyday version: given a record of where a boat sat every minute, say
 * whether it is being carried by a current, bobbing on the spot, wandering with
 * no particular destination, or was hit by a wave - and how badly.
 *
 * <h2>Everything is fitted inside a window, never across the whole recording</h2>
 *
 * <p>Each window gets its own straight line. The residual - what is left after
 * that line is removed - is what the step statistics are computed from, and the
 * steps are the differences between consecutive residual positions <b>inside a
 * window only</b>.
 *
 * <p>Drift is reported as a <b>rate</b>, in pixels per frame, taken as the median
 * of the per-window rates. It is not one line fitted across the whole recording,
 * for two reasons. A long baseline accumulates discontinuities, so a recording
 * whose drift is not straight has no single honest drift figure and this plugin
 * does not invent one. And a knock inside one window bends that window's line
 * badly; taking the median of three windows means a single bent one does not
 * become the recording's drift figure.
 *
 * <p>{@link #windowRatesPx()} exposes the individual windows, so a reader who
 * wants to know whether the three agreed can look.
 *
 * <h2>Bridges are reported, never folded in</h2>
 *
 * <p>A bridge spans a gap of many frames. It is not a step, and averaging it in
 * with the steps would make every window sample look as though it contained a
 * jump. So a bridge appears in {@link #bridgeMaxPx()} and {@link #bridgeSpan()}
 * and nowhere else - never in {@code wander}, never in {@code step_rms_px},
 * never in {@code step_max_px}.
 *
 * <p>It does contribute one thing: a bridge whose displacement is larger than the
 * local drift rate explains counts as a knock <em>in that gap</em>, which is
 * actionable, because the usual fix for a knock is to cut those frames.
 *
 * <h2>Knock presence, and never a knock count</h2>
 *
 * <p>See {@link MotionLabel.Component#KNOCK}. The threshold is computed from the
 * sampled steps, so it moves when the sample moves, and the count moves with it -
 * one library recording read one, two, four and seven knocks across six samplings
 * of itself. Presence was stable across all six. There is no accessor here that
 * returns a number of knocks, and a test fails if one appears. Defect D13.
 *
 * <p>Promoted from the private {@code Descriptors}, {@code label} and
 * {@code severity} of {@code MotionSurvey} in the Log-Ratio Registration research
 * repository, by way of the harness that measured the sampler. The spectral
 * routine that sat beside them is deliberately not carried - defect D3, and see
 * {@link MotionLabel}.
 */
public final class MotionDescriptors {

    /** A step this large is a knock however small the typical step is. */
    static final double KNOCK_FLOOR_PX = 3.0;

    /** A step this many times the typical step is a knock however small it is. */
    static final double KNOCK_MULTIPLE = 6.0;

    /**
     * Above this, the excursion is growing with time rather than shaking about a
     * centre.
     *
     * <p><b>Measured against window length on 2026-08-14, and the answer is that a
     * twelve-frame window cannot see a walk.</b> Median of twenty-five built
     * 200-frame trajectories, measured on the trajectories themselves so window
     * length is the only thing varying:
     *
     * <table>
     *   <caption>{@code wander} by window length</caption>
     *   <tr><th>trajectory</th><th>K=12</th><th>K=24</th><th>K=48</th>
     *       <th>K=100</th><th>K=200</th></tr>
     *   <tr><td>pure random walk</td><td>0.96</td><td>1.25</td><td>1.64</td>
     *       <td>2.63</td><td>3.41</td></tr>
     *   <tr><td>pure jitter</td><td>0.66</td><td>0.68</td><td>0.69</td>
     *       <td>0.70</td><td>0.70</td></tr>
     * </table>
     *
     * <p>A random walk only reaches this threshold at about a hundred frames. With
     * the shipped {@code W=3, K=12} sampler, {@link MotionLabel.Component#WALK} is
     * therefore <b>effectively unreachable</b>, and a wandering recording is
     * labelled {@code JITTER}. That is not a wrong threshold - twelve frames of a
     * random walk genuinely have not wandered anywhere - and it was not lowered,
     * because separating 0.96 from 0.66 would be fitting a threshold to two
     * synthetic traces across a margin of 0.3. It is a stated limit of the sampled
     * fingerprint. Evidence and the decision: {@code VALIDATION.md}.
     */
    static final double WANDER_WALK = 2.5;

    /** Drift this many times the residual excursion is what makes drift a component. */
    static final double DRIFT_DOMINANCE = 3.0;

    /**
     * How far past {@link #DRIFT_DOMINANCE} the ratio has to sit before dominance
     * is claimed at all.
     *
     * <p>Chosen, not measured. The resampling measurement showed that a label
     * ordered on which side of {@code DRIFT_DOMINANCE} the ratio fell flips
     * between samplings of the same recording; it did not measure how wide a band
     * around that threshold is unsafe. A factor of 1.5 either way is a stated
     * guess at that width, and {@code motion_dominant} reads {@code unclear}
     * inside it.
     *
     * <p><b>Measured on 2026-08-14, and 1.5 is too narrow by every recording in
     * the library.</b> The drift-to-excursion ratio was taken across five sampler
     * shapes of each of the twelve recordings. <b>All twelve cross
     * {@code DRIFT_DOMINANCE} under resampling</b>; the spread runs from 3.1x to
     * 140x, and the margin each recording would need to hold its own crossing back
     * as {@code unclear} runs from <b>2.00 to 26.29</b>, median 4.46. The shipped
     * 1.5 is below the smallest of them.
     *
     * <p><b>It was not widened</b>, and that is a decision rather than an
     * oversight: 26.29 fits the worst of twelve recordings from one instrument,
     * which is how defect D12 happened, and the median still leaves half the
     * library uncovered. The measurement says something stronger than any number
     * would - a windowed sample cannot reproduce which component dominates, on any
     * of these recordings. Dominance is a reported column and nothing routes on
     * it; D13 took it out of the label's identity for this reason. v0.2.0 either
     * widens it against a measurement across instruments or withdraws the column,
     * the way {@code PERIODIC} and the knock count were withdrawn. Evidence:
     * {@code VALIDATION.md}.
     */
    static final double DOMINANCE_MARGIN = 1.5;

    /** Below this much residual excursion, drift is the only component worth naming. */
    static final double RESIDUAL_FLOOR_PX = 0.5;

    /** Movement below this is mild, in pixels. */
    static final double MILD_PX = 2.0;

    /** Movement below this is moderate, in pixels. */
    static final double MODERATE_PX = 8.0;

    /** Movement below this is severe; at or above it, extreme. */
    static final double SEVERE_PX = 32.0;

    /** Which estimator's answers a trace is built from. */
    public enum Source {

        /** {@link PhaseCorrelation}. */
        PHASE_CORRELATION(PhaseCorrelation.NAME),

        /** {@link PyramidSsd}. */
        PYRAMID_SSD(PyramidSsd.NAME);

        private final String word;

        Source(String word) {
            this.word = word;
        }

        /** The estimator's published name, for the provenance. */
        public String word() {
            return word;
        }
    }

    /**
     * How large the movement is, in words.
     *
     * <p>From the larger of the two displacements a person actually faces: how far
     * the field walks away over the recording, and the biggest single step.
     */
    public enum Severity {

        /** Under 2 px. */
        MILD("mild"),

        /** Under 8 px. */
        MODERATE("moderate"),

        /** Under 32 px. */
        SEVERE("severe"),

        /** 32 px or more. */
        EXTREME("extreme"),

        /** No frame pair could be read, so there is no movement to size. */
        NOT_MEASURED("not_measured");

        private final String word;

        Severity(String word) {
            this.word = word;
        }

        /** The word written into the {@code severity} column. US English, lower case. */
        public String word() {
            return word;
        }
    }

    private final WindowSampler.Plan plan;
    private final Frames.Bin measuredAt;
    private final String source;
    private final double[] windowRatesPx;
    private final double driftRatePx;
    private final double driftPx;
    private final double residualRmsPx;
    private final double stepRmsPx;
    private final double stepMaxPx;
    private final double medianStepPx;
    private final double knockThresholdPx;
    private final double bridgeMaxPx;
    private final double bridgeExcessPx;
    private final String bridgeSpan;
    private final double wander;
    private final int measuredSteps;
    private final int refusedPairs;
    private final MotionLabel label;
    private final Severity severity;

    private MotionDescriptors(Builder b) {
        this.plan = b.plan;
        this.measuredAt = b.measuredAt;
        this.source = b.source;
        this.windowRatesPx = b.windowRatesPx;
        this.driftRatePx = b.driftRatePx;
        this.driftPx = b.driftPx;
        this.residualRmsPx = b.residualRmsPx;
        this.stepRmsPx = b.stepRmsPx;
        this.stepMaxPx = b.stepMaxPx;
        this.medianStepPx = b.medianStepPx;
        this.knockThresholdPx = b.knockThresholdPx;
        this.bridgeMaxPx = b.bridgeMaxPx;
        this.bridgeExcessPx = b.bridgeExcessPx;
        this.bridgeSpan = b.bridgeSpan;
        this.wander = b.wander;
        this.measuredSteps = b.measuredSteps;
        this.refusedPairs = b.refusedPairs;
        this.label = b.label;
        this.severity = b.severity;
    }

    /**
     * Describe the movement one estimator saw over a sampler's pairs.
     *
     * @param plan    the placement the displacements were measured over
     * @param perPair one displacement per entry of {@link WindowSampler.Plan#pairs()},
     *                in that order. A displacement the estimator refused - a
     *                featureless pair - is left out of the statistics rather than
     *                entering them as a measured zero
     */
    public static MotionDescriptors of(WindowSampler.Plan plan,
                                       List<Estimator.Displacement> perPair) {
        return of(plan, perPair, "one estimator");
    }

    /**
     * Describe a trace one named estimator produced, when the caller has the
     * displacements in hand rather than a whole {@link Estimators.Result}.
     *
     * <p>What the caller usually has in hand, and the reason this overload
     * exists: displacements converted out of the scale they were <em>measured</em>
     * at and back into the image's own pixels. Every threshold on this class -
     * {@link #KNOCK_FLOOR_PX}, {@link #MILD_PX}, {@link #MODERATE_PX},
     * {@link #SEVERE_PX} - came from a survey that binned its frames to estimate
     * and then multiplied the displacements back by the binning factor before
     * describing them ({@code MotionSurvey.java:185, :202}). They are therefore
     * <b>native-pixel thresholds</b>, and handing this class binned displacements
     * would shift every severity and every knock decision by the binning factor.
     * See defect D12: it is the same error in a second place.
     *
     * @param source which estimator produced them, for the provenance
     */
    public static MotionDescriptors of(WindowSampler.Plan plan,
                                       List<Estimator.Displacement> perPair, Source source) {
        if (source == null) {
            throw new IllegalArgumentException("a trace is built from one named estimator; there"
                    + " is no unnamed one");
        }
        return of(plan, perPair, source.word());
    }

    /**
     * Describe the movement one of the two estimators saw, taken from a run of
     * both.
     *
     * <p>Which one is a decision, not a detail, so it is named rather than
     * averaged: the two estimators are independent on purpose, and folding their
     * answers together would leave nothing for {@code agreement_px} to be the
     * spread of.
     */
    public static MotionDescriptors of(WindowSampler.Plan plan, Estimators.Result estimates,
                                       Source source) {
        if (estimates == null) throw new IllegalArgumentException("no estimates to describe");
        if (source == null) {
            throw new IllegalArgumentException("a trace is built from one named estimator; there"
                    + " is no unnamed one");
        }
        List<Estimators.PairEstimate> measured = estimates.pairs();
        List<Estimator.Displacement> column =
                new ArrayList<Estimator.Displacement>(measured.size());
        for (int i = 0; i < measured.size(); i++) {
            column.add(source == Source.PHASE_CORRELATION
                    ? measured.get(i).byPhaseCorrelation()
                    : measured.get(i).byPyramidSsd());
        }
        return of(plan, column, source.word());
    }

    private static MotionDescriptors of(WindowSampler.Plan plan,
                                        List<Estimator.Displacement> perPair, String source) {
        if (plan == null) throw new IllegalArgumentException("no plan to describe");
        if (perPair == null) throw new IllegalArgumentException("no displacements to describe");
        if (perPair.size() != plan.measuredPairs()) {
            throw new IllegalArgumentException("this plan measures " + plan.measuredPairs()
                    + " frame pairs and " + perPair.size() + " displacements were given");
        }
        return new Builder(plan, perPair, source).build();
    }

    // --------------------------------------------------------- the numbers

    /**
     * How fast the field drifts, in pixels per frame - the {@code drift_rate_px}
     * column.
     *
     * <p>The median of the per-window rates. {@link Double#NaN} when no window
     * could be read.
     */
    public double driftRatePx() {
        return driftRatePx;
    }

    /** The rate each window on its own reported. {@link Double#NaN} for a window nothing was read in. */
    public double[] windowRatesPx() {
        return windowRatesPx.clone();
    }

    /**
     * What {@link #driftRatePx()} carries across the whole recording, in pixels.
     *
     * <p>Not a column, and deliberately so: it is the rate multiplied out over the
     * frame count, which assumes the drift is straight all the way. It exists
     * because the label and the severity are defined against a displacement rather
     * than a rate, and it is stated here rather than hidden inside them.
     */
    public double driftPx() {
        return driftPx;
    }

    /** RMS excursion about the fitted line, in pixels. Pooled over the windows. */
    public double residualRmsPx() {
        return residualRmsPx;
    }

    /**
     * How far the field strays relative to how fast it moves - the {@code wander}
     * column.
     *
     * <p>About 0.7 for white jitter, where each position is independent of the
     * last; several for a random walk, where the excursion grows with time.
     * Telling those apart is the whole reason this number exists, and it is why
     * the pairs inside a window are genuinely consecutive rather than strided.
     */
    public double wander() {
        return wander;
    }

    /** Root-mean-square step, in pixels - the {@code step_rms_px} column. */
    public double stepRmsPx() {
        return stepRmsPx;
    }

    /** Largest step inside a window, in pixels - the {@code step_max_px} column. */
    public double stepMaxPx() {
        return stepMaxPx;
    }

    /** The typical step the knock threshold is built from, in pixels. */
    public double medianStepPx() {
        return medianStepPx;
    }

    /**
     * The size a step had to beat to count as a knock, in pixels.
     *
     * <p>Reported because it is a property of <em>this sample</em>, not of the
     * recording. It is the reason a knock count would be meaningless. See D13.
     */
    public double knockThresholdPx() {
        return knockThresholdPx;
    }

    /**
     * True when at least one step, or one bridge, was far larger than the rest.
     *
     * <p>The only knock accessor there is.
     */
    public boolean knockPresent() {
        return label.knockPresent();
    }

    /**
     * Largest displacement across a gap between windows, in pixels - the
     * {@code bridge_max_px} column. {@link Double#NaN} when there are no gaps, or
     * when none of them could be read.
     */
    public double bridgeMaxPx() {
        return bridgeMaxPx;
    }

    /**
     * How much of the largest gap movement the local drift rate does not explain,
     * in pixels. What the knock rule is applied to for a gap.
     */
    public double bridgeExcessPx() {
        return bridgeExcessPx;
    }

    /**
     * The frames the largest gap movement sits between, as {@code "12-19"} - the
     * {@code bridge_span} column. Empty when there are no gaps.
     *
     * <p>Frames are numbered from 1, the way the stack numbers them. This is the
     * resolution the sampler has on <em>when</em> something happened: the movement
     * is somewhere in that range and the sampler cannot say where.
     */
    public String bridgeSpan() {
        return bridgeSpan;
    }

    /** How many steps were measured, and so how many the statistics are over. */
    public int measuredSteps() {
        return measuredSteps;
    }

    /** How many pairs the estimator refused, and so contributed nothing. */
    public int refusedPairs() {
        return refusedPairs;
    }

    /** True when at least one step was measured from pixels. */
    public boolean measured() {
        return measuredSteps > 0;
    }

    /** The unordered component set, and the dominance note beside it. */
    public MotionLabel label() {
        return label;
    }

    /** How large the movement is, in words. */
    public Severity severity() {
        return severity;
    }

    /** The effective pixel size every number here is expressed in. */
    public Frames.Bin measuredAt() {
        return measuredAt;
    }

    /** The placement these numbers were measured over. */
    public WindowSampler.Plan plan() {
        return plan;
    }

    /** One sentence naming what produced these numbers, for the saved notes. */
    public String provenance() {
        if (!measured()) {
            return "no movement could be described: none of the " + plan.measuredPairs()
                    + " frame pairs could be read by " + source + ", over "
                    + plan.provenance();
        }
        return String.format(Locale.US, "%s over %s, by %s at %s",
                label.render(), plan.provenance(), source, measuredAt.provenance());
    }

    @Override
    public String toString() {
        if (!measured()) return "MotionDescriptors[" + MotionLabel.NOT_MEASURED + "]";
        return String.format(Locale.US,
                "MotionDescriptors[%s, dominant %s, %s, drift %.3f px/frame, wander %.2f,"
                        + " step rms %.2f px, step max %.2f px, knock %s]",
                label.render(), label.dominantWord(), severity.word(), driftRatePx, wander,
                stepRmsPx, stepMaxPx, knockPresent() ? "present" : "absent");
    }

    // ------------------------------------------------------- the arithmetic

    /** Everything the constructor needs, worked out in one place and in one order. */
    private static final class Builder {

        private final WindowSampler.Plan plan;
        private final String source;
        private Frames.Bin measuredAt;
        private double[] windowRatesPx;
        private double driftRatePx = Double.NaN;
        private double driftPx = Double.NaN;
        private double residualRmsPx = Double.NaN;
        private double stepRmsPx = Double.NaN;
        private double stepMaxPx = Double.NaN;
        private double medianStepPx = Double.NaN;
        private double knockThresholdPx = Double.NaN;
        private double bridgeMaxPx = Double.NaN;
        private double bridgeExcessPx = Double.NaN;
        private String bridgeSpan = "";
        private double wander = Double.NaN;
        private int measuredSteps;
        private int refusedPairs;
        private MotionLabel label = MotionLabel.notMeasured();
        private Severity severity = Severity.NOT_MEASURED;

        private final List<Estimator.Displacement> perPair;

        Builder(WindowSampler.Plan plan, List<Estimator.Displacement> perPair, String source) {
            this.plan = plan;
            this.perPair = perPair;
            this.source = source;
        }

        MotionDescriptors build() {
            int w = plan.windows();
            int k = plan.framesPerWindow();
            int frames = plan.frameCount();

            for (int i = 0; i < perPair.size(); i++) {
                Estimator.Displacement d = perPair.get(i);
                if (d == null) {
                    throw new IllegalArgumentException("displacement " + i + " is missing; a pair"
                            + " that could not be measured is a stated refusal, not a null");
                }
                if (measuredAt == null) {
                    measuredAt = d.measuredAt();
                } else if (!measuredAt.equals(d.measuredAt())) {
                    throw new IllegalArgumentException("these displacements were measured at two"
                            + " different pixel sizes, " + measuredAt + " and " + d.measuredAt()
                            + ", and cannot be described together. See defect D12");
                }
                if (!d.defined()) refusedPairs++;
            }

            double[] rateX = new double[w];
            double[] rateY = new double[w];
            boolean[] usable = new boolean[w];
            windowRatesPx = new double[w];
            Arrays.fill(windowRatesPx, Double.NaN);

            double[] steps = new double[Math.max(1, plan.windowPairCount())];
            double residualSumSq = 0;
            int residualCount = 0;
            double stepSumSq = 0;
            double largestStep = 0;

            for (int window = 0; window < w; window++) {
                double[] x = new double[k];
                double[] y = new double[k];
                boolean[] stepMeasured = new boolean[k];
                int measuredHere = 0;
                int first = plan.firstPairOfWindow(window);
                double meanDx = 0;
                double meanDy = 0;
                for (int j = 1; j < k; j++) {
                    Estimator.Displacement d = perPair.get(first + j - 1);
                    stepMeasured[j] = d.defined();
                    if (!stepMeasured[j]) continue;
                    measuredHere++;
                    meanDx += d.dx();
                    meanDy += d.dy();
                }
                if (measuredHere == 0) continue;
                meanDx /= measuredHere;
                meanDy /= measuredHere;
                for (int j = 1; j < k; j++) {
                    // A refused pair (a blank or featureless frame on either side of it) is
                    // given the window's mean measured step rather than none. Counted as no
                    // movement it bends the local trace flat for that step, and the drift fitted
                    // through it comes out low - one blank frame in eight read as about half the
                    // real drift. It stays out of the step statistics below: counted there it
                    // would drag the typical step down, and with it the threshold every knock is
                    // judged against. A window with every pair measured is unchanged.
                    Estimator.Displacement d = perPair.get(first + j - 1);
                    x[j] = x[j - 1] + (stepMeasured[j] ? d.dx() : meanDx);
                    y[j] = y[j - 1] + (stepMeasured[j] ? d.dy() : meanDy);
                }

                double bx = slope(x);
                double by = slope(y);
                rateX[window] = bx;
                rateY[window] = by;
                usable[window] = true;
                windowRatesPx[window] = Math.hypot(bx, by);

                double[] rx = detrend(x, bx);
                double[] ry = detrend(y, by);
                for (int j = 0; j < k; j++) {
                    residualSumSq += rx[j] * rx[j] + ry[j] * ry[j];
                    residualCount++;
                }
                for (int j = 1; j < k; j++) {
                    if (!stepMeasured[j]) continue;
                    double step = Math.hypot(rx[j] - rx[j - 1], ry[j] - ry[j - 1]);
                    steps[measuredSteps++] = step;
                    stepSumSq += step * step;
                    if (step > largestStep) largestStep = step;
                }
            }

            if (measuredSteps == 0) {
                if (measuredAt == null) measuredAt = Frames.Bin.none();
                return new MotionDescriptors(this);
            }

            residualRmsPx = Math.sqrt(residualSumSq / residualCount);
            stepRmsPx = Math.sqrt(stepSumSq / measuredSteps);
            stepMaxPx = largestStep;
            medianStepPx = median(steps, measuredSteps);
            knockThresholdPx = Math.max(KNOCK_FLOOR_PX, KNOCK_MULTIPLE * medianStepPx);
            wander = stepRmsPx > 1e-9 ? residualRmsPx / stepRmsPx : 0;

            // The median across windows, not the mean: with three windows, one bent by a knock does
            // not get to be the recording's drift figure.
            driftRatePx = Math.hypot(medianOfUsable(rateX, usable), medianOfUsable(rateY, usable));
            driftPx = driftRatePx * (frames - 1);

            boolean knock = stepMaxPx > knockThresholdPx;
            knock |= describeBridges(rateX, rateY, usable);

            EnumSet<MotionLabel.Component> components = components(knock);
            label = MotionLabel.of(components, dominant(components));
            severity = severityOf(Math.max(driftPx, stepMaxPx));
            return new MotionDescriptors(this);
        }

        /**
         * The gaps: how far the field moved across each, and how much of that the
         * window's own drift rate does not explain.
         *
         * @return true when a gap moved further than the knock rule allows
         */
        private boolean describeBridges(double[] rateX, double[] rateY, boolean[] usable) {
            boolean knock = false;
            double largest = 0;
            double largestExcess = 0;
            boolean any = false;
            for (int i = 0; i < plan.bridgeCount(); i++) {
                Estimator.Displacement d = perPair.get(plan.bridgePairIndex(i));
                if (!d.defined()) continue;
                int[] gap = plan.bridge(i);
                double magnitude = d.magnitude();
                if (!any || magnitude > largest) {
                    largest = magnitude;
                    // Numbered from 1, as the stack numbers its frames.
                    bridgeSpan = (gap[0] + 1) + "-" + (gap[1] + 1);
                }
                any = true;
                // What drift alone would carry across this gap, at the rate the window just before
                // it was moving. Anything beyond that happened in the gap, and the sampler cannot
                // say where in it.
                double rate = usable[i] ? Math.hypot(rateX[i], rateY[i]) : driftRatePx;
                double excess = Math.abs(magnitude - rate * (gap[1] - gap[0]));
                if (excess > largestExcess) largestExcess = excess;
                if (excess > knockThresholdPx) knock = true;
            }
            if (any) {
                bridgeMaxPx = largest;
                bridgeExcessPx = largestExcess;
            }
            return knock;
        }

        /**
         * Which components the movement is made of.
         *
         * <p>The membership rules are the research harness's, unchanged. What
         * changed is that the answer is a set: the harness turned the same three
         * branches into an ordered string, and the order was a knife edge that
         * flipped between samplings of one recording. See D13.
         */
        private EnumSet<MotionLabel.Component> components(boolean knock) {
            EnumSet<MotionLabel.Component> found =
                    EnumSet.noneOf(MotionLabel.Component.class);
            boolean drifts = driftPx > MILD_PX / 2;
            if (driftPx > DRIFT_DOMINANCE * residualRmsPx && drifts) {
                found.add(MotionLabel.Component.DRIFT);
                if (residualRmsPx > RESIDUAL_FLOOR_PX) {
                    found.add(wander > WANDER_WALK
                            ? MotionLabel.Component.WALK : MotionLabel.Component.JITTER);
                }
            } else {
                found.add(wander > WANDER_WALK
                        ? MotionLabel.Component.WALK : MotionLabel.Component.JITTER);
                if (drifts) found.add(MotionLabel.Component.DRIFT);
            }
            if (knock) found.add(MotionLabel.Component.KNOCK);
            return found;
        }

        /**
         * Which component the movement is mostly made of, or {@code null} when the
         * margin is inside the band a resampling would move it across.
         *
         * <p>One continuous component and it is trivially the dominant one. Two,
         * and the comparison is the drift-to-excursion ratio against the same
         * threshold that decided the set - but with {@link #DOMINANCE_MARGIN}
         * either side of it held back as {@code unclear}, because that threshold
         * is exactly where the ordered label used to flip.
         */
        private MotionLabel.Component dominant(EnumSet<MotionLabel.Component> found) {
            boolean drift = found.contains(MotionLabel.Component.DRIFT);
            MotionLabel.Component other = found.contains(MotionLabel.Component.WALK)
                    ? MotionLabel.Component.WALK
                    : found.contains(MotionLabel.Component.JITTER)
                    ? MotionLabel.Component.JITTER : null;
            if (!drift) return other;
            if (other == null) return MotionLabel.Component.DRIFT;
            double ratio = residualRmsPx > 0
                    ? driftPx / residualRmsPx : Double.POSITIVE_INFINITY;
            if (ratio > DRIFT_DOMINANCE * DOMINANCE_MARGIN) return MotionLabel.Component.DRIFT;
            if (ratio < DRIFT_DOMINANCE / DOMINANCE_MARGIN) return other;
            return null;
        }

        private static Severity severityOf(double total) {
            if (total < MILD_PX) return Severity.MILD;
            if (total < MODERATE_PX) return Severity.MODERATE;
            return total < SEVERE_PX ? Severity.SEVERE : Severity.EXTREME;
        }

        /** Least-squares slope per frame, over a window's own frame positions. */
        private static double slope(double[] v) {
            int n = v.length;
            double meanT = (n - 1) / 2.0;
            double meanV = 0;
            for (int t = 0; t < n; t++) meanV += v[t];
            meanV /= n;
            double numerator = 0;
            double denominator = 0;
            for (int t = 0; t < n; t++) {
                numerator += (t - meanT) * (v[t] - meanV);
                denominator += (t - meanT) * (t - meanT);
            }
            return denominator > 0 ? numerator / denominator : 0;
        }

        /** What is left of a window once its own straight line is taken out. */
        private static double[] detrend(double[] v, double b) {
            int n = v.length;
            double meanT = (n - 1) / 2.0;
            double meanV = 0;
            for (int t = 0; t < n; t++) meanV += v[t];
            meanV /= n;
            double[] out = new double[n];
            for (int t = 0; t < n; t++) out[t] = v[t] - (meanV + b * (t - meanT));
            return out;
        }

        private static double medianOfUsable(double[] values, boolean[] usable) {
            double[] kept = new double[values.length];
            int count = 0;
            for (int i = 0; i < values.length; i++) {
                if (usable[i]) kept[count++] = values[i];
            }
            return median(kept, count);
        }

        /** The middle of the first {@code n} entries. Sorts a copy. */
        private static double median(double[] values, int n) {
            if (n <= 0) return Double.NaN;
            double[] sorted = Arrays.copyOf(values, n);
            Arrays.sort(sorted);
            return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
        }
    }
}
