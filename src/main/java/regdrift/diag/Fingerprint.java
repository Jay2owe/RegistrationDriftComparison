/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import regdrift.Cancellation;
import regdrift.internal.PairScheduler;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything one recording's movement measures out at, produced by one call.
 *
 * <p>The everyday version: a doctor's set of vitals. Several instruments, one
 * visit, one sheet of paper - and the sheet says which instruments were used,
 * what units they read in, and how much of the patient was actually looked at.
 * Nothing on it is a diagnosis; that comes next, and it is made from these
 * numbers rather than from a fresh look.
 *
 * <h2>Two passes, and one bound for the whole recording</h2>
 *
 * <p>Measuring a displacement needs to be told how far to look, and the honest
 * order is to find that out first. So:
 *
 * <pre>
 * PASS 1  phase correlation over exactly the pairs the sampler will measure
 *         -&gt; ONE global search bound, from the window pairs and the bridge
 *            pairs together
 * PASS 2  both estimators over the same pairs, with that bound
 *         -&gt; per-pair displacements, agreement, descriptors, bridges
 * PASS 3  the pixels of those same frames, on the coordinator
 *         -&gt; localisability, frame correlation, intensity trend, bright share
 * </pre>
 *
 * <p>Deriving the bound per window instead is the mistake {@link WindowSampler}
 * exists to prevent, and it is derived once here for the same reason: a bound
 * that changed between the windows would make the descriptors describe the
 * search box rather than the recording.
 *
 * <h2>The measurement scale is chosen here, and it is defect D12's home</h2>
 *
 * <p>Localisability is the fall in frame-to-frame correlation when one frame is
 * displaced <em>one pixel</em>, so what "one pixel" means is part of the number.
 * A fingerprint therefore does not measure at whatever resolution the user's
 * image happens to be. It bins to {@link #MEASUREMENT_BIN}, which is the factor
 * the motion survey ran at - recovered in {@code docs/D12_MEASUREMENT.md} by
 * inverting two columns of {@code motion_survey.csv} that hold the same step in
 * binned and in real pixels - and it writes that factor beside every number it
 * hands out.
 *
 * <p><b>Fixing the scale did not rescue the threshold, and D12 is carried
 * open.</b> Measured at bin 4 on the twelve library recordings, the shipped
 * {@link Localisability#WARN_BELOW} still warns on three of the four entries
 * that register best, and no cut at bin 1, 2, 4 or 8 separates the recordings
 * that register from the recordings that do not. So this class reports
 * localisability with its scale and {@link Verdict} does not threshold it. The
 * evidence, and what would be needed to close D12, are in
 * {@code docs/D12_MEASUREMENT.md}.
 *
 * <h2>Constant cost</h2>
 *
 * <p>Every pass here runs over the sampler's pairs and the frames those pairs
 * name - 35 pairs and 36 frames at the measured default, whether the recording
 * is 48 frames or 5,000. Nothing walks the whole recording, including the three
 * pixel statistics in pass 3, which is why they are computed over the sampled
 * frames rather than over every frame.
 *
 * <h2>Determinism</h2>
 *
 * <p>Both parallel passes hand their answers back in index order, and every
 * reduction here folds them in that order - including the derived global bound,
 * which is the number every later measurement depends on. Serial, two-worker and
 * full-width runs produce the same bits. Pass 3 is serial on the coordinator,
 * because it is cheap at the measurement scale and a serial fold has no ordering
 * question to get wrong.
 */
public final class Fingerprint {

    /**
     * The scale a fingerprint measures at: a 4 x 4 pixel mean.
     *
     * <p>The motion survey's own binning factor, recovered from
     * {@code motion_survey.csv} rather than assumed - three high-precision rows
     * bracket 4 and exclude 3 and 5, and the derivation is in
     * {@code docs/D12_MEASUREMENT.md}. Fixing it here is what stops a
     * measurement from meaning one thing on a 512 x 512 crop and another on a
     * 2048 x 2048 field.
     *
     * <p>It is a factor, not a physical size, because an ImageJ stack does not
     * reliably say how large a pixel is. {@code measured_at_bin} therefore
     * reports the factor, and two recordings from different instruments are not
     * comparable through it. That limitation is part of defect D12.
     */
    public static final int MEASUREMENT_BIN = 4;

    /**
     * The measured plane is never binned below this on either axis.
     *
     * <p>Binning a 96 x 96 recording by four leaves 24 x 24, in which the
     * sampler's search bound is most of the frame and the descriptors describe
     * the edges. Below this size the factor is reduced until the plane fits,
     * and {@code measured_at_bin} reports the factor actually used.
     */
    public static final int MIN_MEASURED_SIDE_PX = 64;

    /**
     * A plane smaller than this on either axis carries no one-pixel
     * displacement to measure, and the sampler has nowhere to put a window.
     *
     * <p>This is the only structural refusal in the measurement, and it is what
     * {@link Verdict} turns into {@code not_registrable} - never a low number.
     */
    public static final int MIN_MEASURABLE_SIDE_PX = 2;

    /**
     * How far above the dim half of a frame a pixel has to sit to count toward
     * {@link #brightFraction()}.
     *
     * <p>Four robust standard deviations, and the choice is not a knife edge.
     * Swept over the twelve library recordings at 3, 4, 6 and 8, the answer is
     * <b>exactly zero on ten of them at every one of those values</b>, and
     * non-zero on the two the library itself records as having a bright intruder
     * - {@code 11_unresolved_moving_artefact} (0.0144, 0.0048, 0.0007, 0.0001)
     * and {@code 12_long_baseline_9d} (0.0030, 0.0003, 0, 0). The set of
     * recordings this finds does not move with the constant; only how much of
     * each one it counts does. Four is taken because it still finds
     * {@code 12}'s intruder while a frame of pure noise would put about one
     * pixel in thirty thousand past it, which is nothing.
     *
     * <p>The quantity feeds the intensity-ceiling <em>advice</em> and never a
     * setting - defect D4. The sweep is in the stage 09 completion note.
     */
    static final double BRIGHT_SIGMAS = 4.0;

    /** Turns a quartile spread into the standard deviation of a normal distribution. */
    private static final double QUARTILE_TO_SIGMA = 1 / 0.674489750196082;

    private final Frames.Bin measuredAt;
    private final int channel;
    private final int slice;
    private final int width;
    private final int height;
    private final int frameCount;
    private final WindowSampler.Plan plan;
    private final WindowSampler.Bound bound;
    private final Estimators.Result estimates;
    private final MotionDescriptors motion;
    private final Localisability.Result localisability;
    private final double agreementPx;
    private final int comparedPairs;
    private final double frameCorrelation;
    private final double log2Trend;
    private final double brightFraction;
    private final int framesRead;
    private final boolean measurablePlane;

    private Fingerprint(Builder b) {
        this.agreementPx = b.agreementPx;
        this.comparedPairs = b.comparedPairs;
        this.measuredAt = b.measuredAt;
        this.channel = b.channel;
        this.slice = b.slice;
        this.width = b.width;
        this.height = b.height;
        this.frameCount = b.frameCount;
        this.plan = b.plan;
        this.bound = b.bound;
        this.estimates = b.estimates;
        this.motion = b.motion;
        this.localisability = b.localisability;
        this.frameCorrelation = b.frameCorrelation;
        this.log2Trend = b.log2Trend;
        this.brightFraction = b.brightFraction;
        this.framesRead = b.framesRead;
        this.measurablePlane = b.measurablePlane;
    }

    // --------------------------------------------------------- choosing a scale

    /**
     * The scale a recording of this size is measured at: {@link #MEASUREMENT_BIN},
     * reduced only far enough that neither axis falls below
     * {@link #MIN_MEASURED_SIDE_PX}.
     *
     * <p>Deterministic in the two sizes and nothing else, so two runs over the
     * same recording measure at the same scale, and a saved
     * {@code measured_at_bin} can be reproduced from the image alone.
     */
    public static Frames.Bin binFor(int nativeWidth, int nativeHeight) {
        int shortest = Math.min(Math.max(0, nativeWidth), Math.max(0, nativeHeight));
        int factor = MEASUREMENT_BIN;
        while (factor > 1 && shortest / factor < MIN_MEASURED_SIDE_PX) {
            factor--;
        }
        return Frames.Bin.factor(factor);
    }

    // ------------------------------------------------------------ the measurement

    /**
     * Measure one recording, at the scale its frames were opened at.
     *
     * @param frames       the planes to measure, already carrying the channel,
     *                     the Z choice and the scale. Read on the calling
     *                     thread, because ImageJ state belongs to the
     *                     coordinator
     * @param plan         which frame pairs, from {@link WindowSampler#plan}
     * @param workers      0 or less to decide automatically, 1 to force serial.
     *                     Serial, two-worker and full-width runs give the same
     *                     bits, the derived global bound included
     * @param progress     told as pairs complete across both estimator passes;
     *                     may be called from any thread
     * @param cancellation looked at between pairs
     * @return never {@code null}, and never without the scale it was measured at
     * @throws java.util.concurrent.CancellationException if the run was stopped
     *         part-way. The coordinator turns that into the typed reason a
     *         caller sees; it never leaves the plugin
     */
    public static Fingerprint measure(Frames frames, WindowSampler.Plan plan, int workers,
                                      PairScheduler.Progress progress, Cancellation cancellation) {
        if (frames == null) throw new IllegalArgumentException("no frames to measure");
        if (plan == null) throw new IllegalArgumentException("no plan to measure");
        Frames.Bin scale = frames.bin();
        if (scale == null) {
            throw new IllegalArgumentException("these frames do not say what scale they were"
                    + " sampled at, and a fingerprint is not comparable across two scales."
                    + " See defect D12");
        }
        if (plan.frameCount() > frames.count()) {
            throw new IllegalArgumentException("the plan covers " + plan.frameCount()
                    + " frames and the source holds " + frames.count());
        }
        PairScheduler.Progress reported = progress == null ? PairScheduler.Progress.NONE : progress;
        Cancellation stop = cancellation == null ? Cancellation.never() : cancellation;

        Builder b = new Builder();
        b.measuredAt = scale;
        b.channel = frames.channel();
        b.slice = frames.slice();
        b.width = frames.width();
        b.height = frames.height();
        b.frameCount = frames.count();
        b.plan = plan;
        b.measurablePlane = b.width >= MIN_MEASURABLE_SIDE_PX && b.height >= MIN_MEASURABLE_SIDE_PX;
        b.localisability = Localisability.at(Double.NaN, scale);

        if (!b.measurablePlane) {
            // Structural, and checked before anything is measured: a plane this small carries no
            // one-pixel displacement to detect, and there is nowhere to place a window. The
            // fingerprint still exists and still states its scale - it says what could not be done,
            // rather than being absent.
            b.bound = null;
            b.estimates = null;
            b.motion = null;
            b.frameCorrelation = Double.NaN;
            b.log2Trend = Double.NaN;
            b.brightFraction = Double.NaN;
            b.framesRead = 0;
            return new Fingerprint(b);
        }

        int[][] pairs = plan.pairs();
        Halves halves = new Halves(reported, pairs.length);

        b.bound = WindowSampler.bound(frames, plan, workers, halves.first(), stop);
        b.estimates = Estimators.measure(frames, pairs, b.bound.px(), workers,
                halves.second(), stop);
        describeInNativePixels(scale, b);

        pixelPass(frames, plan, scale, stop, b);
        return new Fingerprint(b);
    }

    /**
     * Converts every displacement out of the scale it was measured at and back
     * into the image's own pixels, then describes the movement and works out how
     * far apart the two estimators were.
     *
     * <p><b>This is defect D12 in a second place, and it is the same mistake.</b>
     * Every threshold in {@link MotionDescriptors} - the knock floor at 3 px, the
     * severity bands at 2, 8 and 32 px - came from a survey that binned its
     * frames to estimate and then multiplied the displacements back by the
     * binning factor before describing them
     * ({@code MotionSurvey.java:185, :202}). They are native-pixel thresholds.
     * Handing them displacements measured at bin 4 would shift every severity by
     * two bands and change every knock decision, and nothing would have
     * complained.
     *
     * <p>The conversion is arithmetic, not a recalibration: a displacement of two
     * pixels on frames binned four ways <em>is</em> eight pixels of the original
     * image. The converted values carry {@link Frames.Bin#none()}, because that
     * is now the scale they are expressed in, and
     * {@link #displacementsAt()} says so out loud.
     */
    private static void describeInNativePixels(Frames.Bin scale, Builder b) {
        double factor = scale.factor();
        List<Estimators.PairEstimate> measured = b.estimates.pairs();
        List<Estimator.Displacement> byPhase =
                new java.util.ArrayList<Estimator.Displacement>(measured.size());
        double[] apart = new double[Math.max(1, measured.size())];
        int compared = 0;
        for (int i = 0; i < measured.size(); i++) {
            Estimators.PairEstimate pair = measured.get(i);
            byPhase.add(inNativePixels(pair.byPhaseCorrelation(), factor));
            double d = pair.differencePx();
            if (!Double.isNaN(d)) apart[compared++] = d * factor;
        }
        b.motion = MotionDescriptors.of(b.plan, byPhase,
                MotionDescriptors.Source.PHASE_CORRELATION);
        b.agreementPx = median(apart, compared);
        b.comparedPairs = compared;
    }

    /** The same displacement, expressed in the pixels of the original image. */
    private static Estimator.Displacement inNativePixels(Estimator.Displacement measured,
                                                         double factor) {
        return Estimator.Displacement.of(measured.dx() * factor, measured.dy() * factor,
                measured.status(), Frames.Bin.none());
    }

    /**
     * Pass three: the statistics that come from the pixels rather than from a
     * displacement, over the same frames the two estimator passes read.
     *
     * <p>Serial, on the coordinator. At the measurement scale it is a few
     * hundred milliseconds on the frames a sampler names, and running it here
     * means the numbers cannot depend on how many workers a run was given.
     */
    private static void pixelPass(Frames frames, WindowSampler.Plan plan, Frames.Bin scale,
                                  Cancellation stop, Builder b) {
        int[][] pairs = plan.pairs();
        // Every frame the plan names, read once, in ascending frame order.
        Map<Integer, float[]> planes = new TreeMap<Integer, float[]>();
        for (int p = 0; p < pairs.length; p++) {
            for (int side = 0; side < 2; side++) {
                Integer t = Integer.valueOf(pairs[p][side]);
                if (planes.containsKey(t)) continue;
                if (stop.canceled()) {
                    throw new java.util.concurrent.CancellationException("canceled");
                }
                planes.put(t, frames.plane(t.intValue()));
            }
        }
        b.framesRead = planes.size();
        int width = b.width;
        int height = b.height;

        // Localisability and frame correlation over the WITHIN-WINDOW pairs only. A bridge spans a
        // gap of many frames and is not a consecutive pair, and both of these are defined on a
        // single step.
        int windowPairs = plan.windowPairCount();
        double[] localisability = new double[Math.max(1, windowPairs)];
        double[] correlation = new double[Math.max(1, windowPairs)];
        int localised = 0;
        int correlated = 0;
        for (int p = 0; p < windowPairs; p++) {
            float[] a = planes.get(Integer.valueOf(pairs[p][0]));
            float[] c = planes.get(Integer.valueOf(pairs[p][1]));
            double d = Localisability.ofPair(a, c, width, height, scale).value();
            if (!Double.isNaN(d)) localisability[localised++] = d;
            double r = Localisability.correlation(a, c);
            if (!Double.isNaN(r)) correlation[correlated++] = r;
        }
        b.localisability = Localisability.at(mean(localisability, localised), scale);
        // The contract's frame_correlation is the median, not the mean: one transition where a
        // bubble crossed the field should not move a summary of thirty-four others.
        b.frameCorrelation = median(correlation, correlated);

        // The intensity trend, fitted against the real frame index so the gaps between windows are
        // spanned rather than closed up, and reported as the change across the whole recording.
        int n = planes.size();
        double[] frameIndex = new double[n];
        double[] logMean = new double[n];
        int usable = 0;
        for (Map.Entry<Integer, float[]> entry : planes.entrySet()) {
            double mean = definedMean(entry.getValue());
            if (Double.isNaN(mean) || !(mean > 0)) continue;
            frameIndex[usable] = entry.getKey().doubleValue();
            logMean[usable] = Math.log(mean) / Math.log(2);
            usable++;
        }
        double slope = slope(frameIndex, logMean, usable);
        b.log2Trend = Double.isNaN(slope) ? Double.NaN : slope * (b.frameCount - 1);

        // The bright share, per frame, then the median across frames. Spread is measured from the
        // DIM half of the frame - the gap between the median and the lower quartile - because a
        // bright artefact is exactly what is being looked for, and a spread measured over the whole
        // frame would be inflated by the thing it is meant to find.
        double[] bright = new double[n];
        int measured = 0;
        for (Map.Entry<Integer, float[]> entry : planes.entrySet()) {
            double share = brightShare(entry.getValue());
            if (!Double.isNaN(share)) bright[measured++] = share;
        }
        b.brightFraction = median(bright, measured);
    }

    // ------------------------------------------------------------ what it measured

    /**
     * The effective pixel size the <b>pixels</b> were sampled at, and the scale
     * {@link #localisability()} belongs to.
     *
     * <p>Never {@code null}, and written into the {@code measured_at_bin} column
     * beside {@link #localisability()} every time. A localisability without this
     * beside it is defect D12.
     *
     * <p><b>Not the scale the displacements are in.</b> Those are converted back
     * to the image's own pixels, because that is where every threshold that
     * reads them was calibrated and because it is what a user comparing a number
     * against their own stack means by "pixels". {@link #displacementsAt()} is
     * the accessor for that, and it always reads native.
     */
    public Frames.Bin measuredAt() {
        return measuredAt;
    }

    /**
     * The pixel size every <b>displacement</b> here is expressed in: the
     * image's own pixels, always.
     *
     * <p>{@link #agreementPx()} and every number on {@link #motion()} are in
     * these. The estimators worked at {@link #measuredAt()} and their answers
     * were multiplied back by that factor, exactly as the survey those
     * thresholds came from did.
     */
    public Frames.Bin displacementsAt() {
        return Frames.Bin.none();
    }

    /**
     * The fall in frame-to-frame correlation under a one-pixel displacement,
     * averaged over the within-window pairs, with the scale it was measured at.
     *
     * <p><b>Reported, and not thresholded.</b> Nothing in {@link Verdict} routes
     * on it. See defect D12 and {@code docs/D12_MEASUREMENT.md}.
     */
    public Localisability.Result localisability() {
        return localisability;
    }

    /**
     * Median Pearson correlation between the frames of a within-window pair.
     *
     * <p><b>Reported, never routed on.</b> It ranks the library almost inversely
     * to the outcome: it reads 0.163 and 0.198 on two of the entries that
     * register best and 0.88-0.89 on three that do not.
     */
    public double frameCorrelation() {
        return frameCorrelation;
    }

    /**
     * Median distance between the two independent estimators, in the pixels of
     * {@link #displacementsAt()} - the {@code agreement_px} column, and the
     * confidence signal the verdict routes on.
     *
     * <p>The image's own pixels, not the ones the estimators worked in. Measured
     * that way, the gap between the library recordings whose two estimators
     * tracked each other and the three where they parted lands in the same place
     * at bin 1 and at bin 4, so the verdict does not change when the measurement
     * scale does. {@link #estimates()} holds the same numbers in the pixels they
     * were measured in.
     *
     * <p>{@link Double#NaN} when no pair was read by both, which is itself the
     * finding.
     */
    public double agreementPx() {
        return agreementPx;
    }

    /**
     * The fitted change in global intensity across the whole recording, in log2
     * units. Negative is fading.
     *
     * <p>{@link Double#NaN} when fewer than two of the sampled frames carry a
     * positive mean, which is the only case where the logarithm has nothing to
     * work on.
     */
    public double log2Trend() {
        return log2Trend;
    }

    /**
     * The share of a frame sitting far above the dim half of its own intensity
     * distribution, taken as the median across the sampled frames.
     *
     * <p>The input to the intensity-ceiling advice, and to nothing else. There
     * is no setting anywhere in this plugin that a ceiling can be turned on
     * with - defect D4.
     */
    public double brightFraction() {
        return brightFraction;
    }

    /** How the movement is described. {@code null} when nothing could be measured. */
    public MotionDescriptors motion() {
        return motion;
    }

    /** Every pair, measured by both estimators. {@code null} when nothing was measured. */
    public Estimators.Result estimates() {
        return estimates;
    }

    /** The one global search bound, and what it was derived from. {@code null} when unmeasured. */
    public WindowSampler.Bound bound() {
        return bound;
    }

    /** Where the windows fell, and which pairs that made. */
    public WindowSampler.Plan plan() {
        return plan;
    }

    /** The 1-based channel this was measured on. */
    public int channel() {
        return channel;
    }

    /** The 1-based Z slice, or {@link Frames#PROJECT_Z} when Z was projected. */
    public int slice() {
        return slice;
    }

    /** Plane width at {@link #measuredAt()}. */
    public int width() {
        return width;
    }

    /** Plane height at {@link #measuredAt()}. */
    public int height() {
        return height;
    }

    /** Frames on the recording's time axis. */
    public int frameCount() {
        return frameCount;
    }

    /** How many frame pairs were measured. Constant in the recording's length. */
    public int measuredPairs() {
        return plan.measuredPairs();
    }

    /**
     * How many consecutive frame pairs the recording holds, measured or not.
     * The denominator of "35 of 107 pairs measured".
     */
    public int availablePairs() {
        return Math.max(0, frameCount - 1);
    }

    /** How many distinct frames were read to produce this. */
    public int framesRead() {
        return framesRead;
    }

    /** How many pairs both estimators read, and so how many the agreement is over. */
    public int comparedPairs() {
        return comparedPairs;
    }

    /**
     * False when the measured plane is too small to carry a one-pixel
     * displacement or to place a window in.
     *
     * <p>The one structural refusal in the measurement, and the only thing
     * {@link Verdict} is allowed to answer {@code not_registrable} to. A low
     * number is never this.
     */
    public boolean measurablePlane() {
        return measurablePlane;
    }

    /** True when at least one frame pair was read by both estimators. */
    public boolean measured() {
        return comparedPairs() > 0;
    }

    /** The two estimators' published names, in the order their columns appear. */
    public List<String> estimatorNames() {
        return Estimators.names();
    }

    /**
     * Everything a reader needs to repeat this measurement, in finished
     * sentences: the scale, the channel, where the windows sat, how many pairs
     * of how many exist, the derived bound and the two estimators.
     */
    public String provenance() {
        StringBuilder out = new StringBuilder();
        out.append("Channel ").append(channel)
                .append(slice == Frames.PROJECT_Z
                        ? ", projected across Z" : ", Z slice " + slice)
                .append(", measured at ").append(measuredAt.provenance())
                .append(" (measured_at_bin=").append(measuredAt.factor()).append("), ")
                .append(width).append(" x ").append(height).append(" px. ");
        if (!measurablePlane) {
            out.append("The measured plane is smaller than ").append(MIN_MEASURABLE_SIDE_PX)
                    .append(" x ").append(MIN_MEASURABLE_SIDE_PX)
                    .append(" px, so no displacement could be measured in it.");
            return out.toString();
        }
        out.append(capitalize(plan.provenance()))
                .append(" of ").append(availablePairs())
                .append(availablePairs() == 1 ? " pair" : " pairs")
                .append(" the recording holds; ").append(framesRead)
                .append(" frames read. ")
                .append(capitalize(bound.provenance())).append(". ")
                .append("Estimators: ").append(join(estimatorNames())).append(", ")
                .append(comparedPairs()).append(" of ").append(measuredPairs())
                .append(" pairs read by both. ")
                .append("Every displacement is reported in the image's own pixels; the estimators"
                        + " worked at bin ").append(measuredAt.factor())
                .append(" and their answers were multiplied back by it. ")
                .append(String.format(Locale.US,
                        "Localisability %.4f at bin %d, reported and not thresholded (defect D12).",
                        localisability.value(), measuredAt.factor()));
        return out.toString();
    }

    @Override
    public String toString() {
        if (!measurablePlane) {
            return "Fingerprint[plane too small to measure, " + width + "x" + height
                    + " at " + measuredAt + "]";
        }
        return String.format(Locale.US,
                "Fingerprint[%s, localisability %.4f at bin %d, agreement %.3f px, %s]",
                motion.label().render(), localisability.value(), measuredAt.factor(),
                agreementPx(), motion.severity().word());
    }

    // ------------------------------------------------------------- the arithmetic

    /** Mean of the first {@code n} entries, or NaN when there are none. */
    private static double mean(double[] values, int n) {
        if (n <= 0) return Double.NaN;
        double total = 0;
        for (int i = 0; i < n; i++) total += values[i];
        return total / n;
    }

    /** The middle of the first {@code n} entries. Sorts a copy. */
    private static double median(double[] values, int n) {
        if (n <= 0) return Double.NaN;
        double[] sorted = Arrays.copyOf(values, n);
        Arrays.sort(sorted);
        return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    /** Mean of the pixels that carry a measurement. NaN when none do. */
    private static double definedMean(float[] plane) {
        double total = 0;
        int counted = 0;
        for (int i = 0; i < plane.length; i++) {
            float v = plane[i];
            if (Float.isNaN(v)) continue;
            total += v;
            counted++;
        }
        return counted == 0 ? Double.NaN : total / counted;
    }

    /** Least-squares slope of {@code y} against {@code x} over the first {@code n} entries. */
    private static double slope(double[] x, double[] y, int n) {
        if (n < 2) return Double.NaN;
        double meanX = 0;
        double meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += x[i];
            meanY += y[i];
        }
        meanX /= n;
        meanY /= n;
        double numerator = 0;
        double denominator = 0;
        for (int i = 0; i < n; i++) {
            numerator += (x[i] - meanX) * (y[i] - meanY);
            denominator += (x[i] - meanX) * (x[i] - meanX);
        }
        return denominator > 0 ? numerator / denominator : Double.NaN;
    }

    /**
     * The share of one frame's measured pixels sitting more than
     * {@link #BRIGHT_SIGMAS} robust standard deviations above its own median,
     * where the spread comes from the median and the lower quartile only.
     *
     * @return {@link Double#NaN} when the frame carries fewer than four measured
     *         pixels, or when its dim half is perfectly flat and so gives no
     *         spread to measure against
     */
    private static double brightShare(float[] plane) {
        double[] values = new double[plane.length];
        int n = 0;
        for (int i = 0; i < plane.length; i++) {
            float v = plane[i];
            if (Float.isNaN(v)) continue;
            values[n++] = v;
        }
        if (n < 4) return Double.NaN;
        double[] sorted = Arrays.copyOf(values, n);
        Arrays.sort(sorted);
        double q1 = sorted[n / 4];
        double q2 = sorted[n / 2];
        double sigma = (q2 - q1) * QUARTILE_TO_SIGMA;
        if (!(sigma > 0)) return Double.NaN;
        double ceiling = q2 + BRIGHT_SIGMAS * sigma;
        int above = 0;
        for (int i = n - 1; i >= 0; i--) {
            if (sorted[i] <= ceiling) break;
            above++;
        }
        return (double) above / n;
    }

    private static String join(List<String> words) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) out.append(i == words.size() - 1 ? " and " : ", ");
            out.append(words.get(i));
        }
        return out.toString();
    }

    private static String capitalize(String sentence) {
        if (sentence == null || sentence.isEmpty()) return "";
        return Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
    }

    /**
     * Splits one progress report across the two estimator passes, so a caller
     * sees one bar going from nothing to everything rather than two going from
     * nothing to half.
     */
    private static final class Halves {

        private final PairScheduler.Progress delegate;
        private final int pairs;

        Halves(PairScheduler.Progress delegate, int pairs) {
            this.delegate = delegate;
            this.pairs = pairs;
        }

        PairScheduler.Progress first() {
            return offsetBy(0);
        }

        PairScheduler.Progress second() {
            return offsetBy(pairs);
        }

        private PairScheduler.Progress offsetBy(final int offset) {
            final int total = 2 * pairs;
            return new PairScheduler.Progress() {
                @Override
                public void update(int done, int ignoredTotal) {
                    delegate.update(offset + done, total);
                }
            };
        }
    }

    /** Everything the constructor needs, filled in one place and in one order. */
    private static final class Builder {

        private Frames.Bin measuredAt;
        private int channel;
        private int slice;
        private int width;
        private int height;
        private int frameCount;
        private WindowSampler.Plan plan;
        private WindowSampler.Bound bound;
        private Estimators.Result estimates;
        private MotionDescriptors motion;
        private Localisability.Result localisability;
        private double agreementPx = Double.NaN;
        private int comparedPairs;
        private double frameCorrelation = Double.NaN;
        private double log2Trend = Double.NaN;
        private double brightFraction = Double.NaN;
        private int framesRead;
        private boolean measurablePlane;
    }
}
