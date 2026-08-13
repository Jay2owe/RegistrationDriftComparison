/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.score;

import regdrift.Cancellation;
import regdrift.FrameStatus;
import regdrift.diag.Estimator;
import regdrift.diag.FrameSource;
import regdrift.diag.Frames;
import regdrift.diag.PhaseCorrelation;
import regdrift.internal.PairScheduler;
import regdrift.internal.Transform;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Scores one registered recording against an interpolation-matched control.
 *
 * <p>Every comparison this plugin makes ends here. Ranking engines, rating a
 * recording somebody else registered, and the {@code score} mode all reduce to
 * one question: <em>is this recording more stable than it was, by more than the
 * resampling alone explains?</em>
 *
 * <p>The qualifier is the whole class. Resampling smooths, smoothing lowers
 * temporal standard deviation for free, and on the twelve library entries the
 * smoothing alone accounts for between a fifth and a third of it. Scored against
 * the raw recording, a method that did nothing but blur would look like a good
 * one. So every arm is scored against the control {@link ControlWarp} builds -
 * the same recording shifted by the fractional part only of each frame's
 * transform - and the reported quantity is {@code sd_vs_control}. <b>Raw temporal
 * standard deviation is never computed here and never reported anywhere.</b>
 *
 * <h2>What is measured, in one walk</h2>
 *
 * <p>Three quantities used to cost three passes over the same pixels. They are
 * fused into one walk per frame pair:
 *
 * <pre>
 * for each frame t:
 *     one loop over the pixels inside the valid margin, accumulating
 *         residual_before[t]   raw[t] against raw[t-1]
 *         residual_after[t]    registered[t] against registered[t-1]
 *         the temporal-SD contribution of registered[t]
 *         the temporal-SD contribution of control[t]
 * </pre>
 *
 * <p>Every accumulator is a {@code double}, and the fused result is
 * <b>numerically identical</b> to running the three separately - not close,
 * identical. {@code ArbiterFusedPassTest} asserts it bit for bit, because a fused
 * pass is exactly where a quiet numerical difference creeps in, and a tolerance
 * there would hide the bug rather than the noise.
 *
 * <h2>Residual mismatch, and why it is in pixels</h2>
 *
 * <p>The two residual columns are frame-to-frame mismatch expressed as an
 * equivalent displacement. For a small shift {@code d}, the brightness change at
 * a pixel is about {@code d} times the local slope of the picture, so
 * {@code |d| = sqrt(2 * sum(dI^2) / sum(|grad I|^2))} over the frame - the same
 * first-order relation every gradient-based aligner is built on. Reporting it in
 * pixels is what makes {@code residual_removed} mean "this arm took out four
 * pixels of mismatch", and what makes it comparable with the recommender's
 * {@code expected_error_px}.
 *
 * <p>It is an approximation and it is blind in the same place the temporal
 * standard deviation is blind: it cannot tell misregistration from the sample
 * genuinely changing. That limit is stated rather than repaired - see below.
 *
 * <h2>Brightness is equalised first, and that is not a nicety</h2>
 *
 * <p>On a recording that fades by a factor of two across its length, every
 * pixel's brightness halves, and that swamps everything a few pixels of drift
 * contribute. Each frame is therefore scaled to the first frame's median before
 * anything is measured - by <b>its own median</b>, computed from the pixels, never
 * by any intensity trend this plugin fitted, so the arbiter never depends on the
 * thing it is judging.
 *
 * <h2>Where the arbiter runs out</h2>
 *
 * <p>Temporal standard deviation measures how still the field is held. It cannot
 * separate misregistration from the sample genuinely changing, and it does not
 * need to while frames are minutes apart, because cells barely move in that time.
 * At a long baseline they do. On {@code library/12_long_baseline_9d} - frames two
 * hours apart over nine days - the figure read -0.2% while the registration was
 * demonstrably right. <b>That is why {@link Separation#CANNOT_SEPARATE} exists.</b>
 * Where the control and the result are within noise, this class says so instead
 * of producing a ranking it cannot justify. No pixel-variance criterion can do
 * better, because at that baseline the pixels genuinely differ.
 *
 * <h2>Which thread, and why the merge is indexed</h2>
 *
 * <p>Frames are read on the coordinator, because they come from an ImageJ stack.
 * The walk runs on a bounded, run-owned pool, parallel over frames, in batches
 * the size of the worker budget so that memory stays bounded however long the
 * recording is. Each frame writes into its own pre-sized buffers, and the
 * coordinator adds them into the running totals <b>in frame order</b>, never in
 * the order workers happened to finish. Floating-point addition is not
 * associative, so a reduction folded in completion order would give a different
 * last digit on every run - and a recording would have two scores, neither of
 * them the measurement. Serial, two-worker and full-width runs are bit-identical,
 * and {@code ArbiterFusedPassTest} holds them to it.
 *
 * <h2>Memory</h2>
 *
 * <p>Five planes are live per frame pair - raw previous, raw current, registered
 * previous, registered current, and the control this frame warps - plus four
 * {@code double} accumulator buffers the size of the valid margin. On a 2048x2048
 * 32-bit recording that is around 200 MB per worker, which is why
 * {@link PairScheduler#workersFor} is given the real figure rather than a guess:
 * on a default Fiji heap its clamp is the only thing between this run and an
 * out-of-memory error.
 *
 * <p>The arbiter mathematics is lifted from {@code logratio\ValidationRun.java} in
 * the Log-Ratio Registration research repository. The research harness around it -
 * the plotting, the CSV writing, the fixture management - is deliberately left
 * there.
 */
public final class Arbiter {

    /**
     * A {@code sd_vs_control} smaller than this many percent, either way, is
     * reported as {@link Separation#CANNOT_SEPARATE} rather than as a result.
     *
     * <p><b>Where the number comes from.</b> All twelve entries of the Log-Ratio
     * Registration library were re-measured by this class, and they sort into a
     * list with one wide gap in it:
     *
     * <pre>
     *   -50.7  -40.3  -37.5  -37.2  -29.6  -14.4  -12.1  -5.3     plainly improved
     *                                                   -3.8      the smallest kept result
     *   ------------------------------------------------------    the gap, and the cut
     *                                              -2.1  -0.3  +0.3
     * </pre>
     *
     * <p>A cut at <b>3 percent</b> falls in that gap. It clears the largest null
     * result (-2.1) by a factor of 1.4 and the smallest kept result (-3.8) by a
     * factor of 1.3, and there is nothing between 2.1 and 3.8 to be near it. It
     * puts the two entries the library itself labels unresolved -
     * {@code 10_unresolved_methods_disagree} at +0.3 and
     * {@code 11_unresolved_moving_artefact} at -2.1 - on the "cannot separate"
     * side, and {@code 12_long_baseline_9d} at -0.3 with them, which is the entry
     * where the arbiter is known to run out. See {@link MotionPreservation} for
     * the first of those and this class's javadoc for the last.
     *
     * <p>The figures reproduce the library's own to within 0.4 percentage points,
     * nine of the twelve exactly; {@code docs/D11_MEASUREMENT.md} holds the
     * comparison.
     *
     * <p>The threshold is not tuned to make a ranking appear, and it is not
     * derived from the arm being scored.
     *
     * <p><b>Two-fold, not a hundred-fold.</b> Arms that take out a stated share of
     * the same real drift were built on three of the entries and scored. On
     * {@code 04_drift}, removing all of it reads -50.7 percent and removing half of
     * it reads -10.9 - forty points apart, thirteen times this threshold - and
     * {@code 02_jitter} and {@code 06_knock} behave the same way. Even a tenth less
     * drift removed moves the figure by twice the threshold. The floor is at about
     * a quarter of the drift, where the arbiter correctly says it cannot separate.
     * So on its own terms it ranks arms that differ two-fold comfortably; whether
     * two <em>real</em> engines differing two-fold separate is a different question,
     * because they differ in where they put the error as well as in how much of it
     * there is, and stage 15 measures that on real arms.
     */
    public static final double CANNOT_SEPARATE_PERCENT = 3.0;

    /**
     * Roughly how many pixels a frame's median is taken from. A median needs no
     * more than a subsample, and sorting the whole of a 2048x2048 frame once per
     * frame would cost more than the walk it prepares for.
     */
    public static final int MEDIAN_SAMPLES = 40_000;

    /** Below this share of the frame surviving the valid margin, a frame is refused. */
    public static final double MIN_VALID_FRACTION = 0.5;

    private Arbiter() {
    }

    /** What the arbiter was able to say about an arm. */
    public enum Separation {

        /** The registered recording is stiller than the control by more than noise. */
        IMPROVED("improved"),

        /** The registered recording moves <em>more</em> than the control does. */
        WORSE("worse"),

        /**
         * The two are within noise of each other. Not a tie and not a failure: a
         * statement that this measurement cannot tell them apart, and that a
         * ranking built on it would not be justified.
         */
        CANNOT_SEPARATE("cannot_separate");

        private final String word;

        Separation(String word) {
            this.word = word;
        }

        /** The word written into a saved table. US English, lower case. */
        public String word() {
            return word;
        }
    }

    /**
     * The rule itself, applied to a bare {@code sd_vs_control} percentage.
     *
     * <p>Separate from {@link Scoring#separation()} so that the threshold can be
     * held to the twelve measured library figures directly, without a recording
     * having to be scored to do it. Nothing here depends on the arm being judged.
     */
    public static Separation separationOf(double sdVsControlPercent) {
        if (Double.isNaN(sdVsControlPercent)) return Separation.CANNOT_SEPARATE;
        if (Math.abs(sdVsControlPercent) < CANNOT_SEPARATE_PERCENT) {
            return Separation.CANNOT_SEPARATE;
        }
        return sdVsControlPercent < 0 ? Separation.IMPROVED : Separation.WORSE;
    }

    // ------------------------------------------------------------------ scoring

    /**
     * Score a registered recording against the control built from its own
     * transforms.
     *
     * @param raw           the recording as it arrived
     * @param registered    the same recording after an engine ran, same size and
     *                      same length
     * @param cumulative    one transform per frame, as the registration applied
     *                      them - the motion of the content from the reference
     *                      frame to that frame. {@link #recover} produces these
     *                      when the engine did not report any
     * @param interpolation how the registered recording was resampled; the
     *                      control is resampled the same way
     * @param workers       0 or less to decide automatically, 1 to force serial
     * @param progress      told as frames complete; may be called from any thread
     * @param cancellation  looked at between frames
     * @return never {@code null}
     * @throws IllegalArgumentException when the two recordings do not match, or
     *         when the control would resample nothing - see
     *         {@link ControlWarp#requireAnInterpolatingControl}
     * @throws java.util.concurrent.CancellationException if the run was stopped
     *         part-way
     */
    public static Scoring score(FrameSource raw, FrameSource registered, Transform[] cumulative,
                                ControlWarp.Interpolation interpolation, int workers,
                                PairScheduler.Progress progress, Cancellation cancellation) {
        if (raw == null || registered == null) {
            throw new IllegalArgumentException("scoring needs the recording before and after");
        }
        final int n = raw.count();
        final int width = raw.width();
        final int height = raw.height();
        if (registered.count() != n || registered.width() != width
                || registered.height() != height) {
            throw new IllegalArgumentException("the registered recording is " + registered.width()
                    + "x" + registered.height() + " over " + registered.count()
                    + " frames and the raw recording is " + width + "x" + height + " over " + n
                    + " frames; a scored pair is the same recording before and after");
        }
        if (n < 2) {
            throw new IllegalArgumentException("scoring needs at least two frames, and this"
                    + " recording holds " + n);
        }
        if (cumulative == null || cumulative.length != n) {
            throw new IllegalArgumentException("scoring needs one transform per frame: "
                    + n + " were expected and "
                    + (cumulative == null ? "none" : Integer.toString(cumulative.length))
                    + " were given");
        }
        final ControlWarp.Interpolation resampling =
                interpolation == null ? ControlWarp.Interpolation.BILINEAR : interpolation;

        final Transform[] control = ControlWarp.fractionalOf(cumulative);
        ControlWarp.requireAnInterpolatingControl(control, resampling);
        final ControlWarp.Margin margin = ControlWarp.validMargin(cumulative, width, height,
                resampling);
        final int croppedWidth = margin.croppedWidth(width);
        final int croppedHeight = margin.croppedHeight(height);
        final int pixels = croppedWidth * croppedHeight;
        if (pixels <= 0) {
            throw new IllegalArgumentException("the transforms leave no part of the frame real in"
                    + " every frame, so there is nothing to score");
        }

        // The reference every frame's brightness is equalised to: the first frame's median, taken
        // once per stack on the coordinator so that no worker has to agree with another about it.
        if (cancellation.canceled()) throw canceled();
        final float[] firstRaw = raw.plane(0);
        final float[] firstRegistered = registered.plane(0);
        final float[] firstControl = ControlWarp.warp(firstRaw, width, height, control[0],
                resampling, 0f);
        final double referenceRaw = frameMedian(firstRaw, width, height, margin);
        final double referenceRegistered = frameMedian(firstRegistered, width, height, margin);
        final double referenceControl = frameMedian(firstControl, width, height, margin);

        final double[] sumRegistered = new double[pixels];
        final double[] sumSqRegistered = new double[pixels];
        final double[] sumControl = new double[pixels];
        final double[] sumSqControl = new double[pixels];
        final double[] residualBefore = new double[n];
        final double[] residualAfter = new double[n];
        Arrays.fill(residualBefore, Double.NaN);
        Arrays.fill(residualAfter, Double.NaN);

        final int used = PairScheduler.workersFor(n, workers, memoryPerFrame(width, height, pixels),
                0);
        final int batch = Math.max(1, used);

        for (int base = 0; base < n; base += batch) {
            if (cancellation.canceled()) throw canceled();
            final int count = Math.min(batch, n - base);
            final int start = base;
            // Read on the coordinator: these come from an ImageJ stack, and ImageJ state is the
            // coordinator's. Workers see float arrays and hand buffers back.
            final float[][] rawWindow = window(raw, start, count, cancellation);
            final float[][] registeredWindow = window(registered, start, count, cancellation);
            List<Pass> passes = PairScheduler.map(count, used, new PairScheduler.Task<Pass>() {
                @Override
                public Pass run(int index) {
                    int t = start + index;
                    float[] rawPrevious = rawWindow[index];
                    float[] rawCurrent = rawWindow[index + 1];
                    float[] registeredPrevious = registeredWindow[index];
                    float[] registeredCurrent = registeredWindow[index + 1];
                    float[] controlCurrent = ControlWarp.warp(rawCurrent, width, height,
                            control[t], resampling, 0f);
                    return walk(rawPrevious, rawCurrent, registeredPrevious, registeredCurrent,
                            controlCurrent, width, height, margin,
                            referenceRaw, referenceRegistered, referenceControl);
                }
            }, offsetBy(progress, start, n), cancellation);

            // The merge is over the frame index, never over completion order: floating-point
            // addition is not associative, and a reduction folded as workers report in would put a
            // different last digit on every run of the same recording.
            for (int index = 0; index < count; index++) {
                Pass pass = passes.get(index);
                int t = start + index;
                residualBefore[t] = pass.residualBefore;
                residualAfter[t] = pass.residualAfter;
                for (int o = 0; o < pixels; o++) {
                    sumRegistered[o] += pass.sumRegistered[o];
                    sumSqRegistered[o] += pass.sumSqRegistered[o];
                    sumControl[o] += pass.sumControl[o];
                    sumSqControl[o] += pass.sumSqControl[o];
                }
            }
        }
        if (cancellation.canceled()) throw canceled();

        double totalRegistered = 0;
        double totalControl = 0;
        long counted = 0;
        for (int o = 0; o < pixels; o++) {
            double sdRegistered = standardDeviation(sumRegistered[o], sumSqRegistered[o], n);
            double sdControl = standardDeviation(sumControl[o], sumSqControl[o], n);
            if (Double.isNaN(sdRegistered) || Double.isNaN(sdControl)) continue;
            totalRegistered += sdRegistered;
            totalControl += sdControl;
            counted++;
        }
        double meanSdRegistered = counted > 0 ? totalRegistered / counted : Double.NaN;
        double meanSdControl = counted > 0 ? totalControl / counted : Double.NaN;

        double validFraction = (double) pixels / ((double) width * height);
        return new Scoring(n, width, height, cumulative, control, resampling, margin,
                residualBefore, residualAfter, meanSdRegistered, meanSdControl, validFraction,
                MotionPreservation.of(pathPx(cumulative), width, height,
                        MotionPreservation.dominance(firstRaw, width, height)));
    }

    /**
     * The fused walk: one loop over the pixels of one frame pair, four
     * accumulators.
     *
     * <p>Package-private, and the single place all three measurements are taken.
     * {@code ArbiterFusedPassTest} runs the same arithmetic as three separate
     * loops and asserts the answers are the same bits.
     *
     * @param rawPrevious        {@code null} for the first frame, which has no
     *                           pair before it but still contributes to both
     *                           standard deviations
     * @param registeredPrevious likewise
     */
    static Pass walk(float[] rawPrevious, float[] rawCurrent,
                     float[] registeredPrevious, float[] registeredCurrent,
                     float[] controlCurrent, int width, int height, ControlWarp.Margin margin,
                     double referenceRaw, double referenceRegistered, double referenceControl) {
        int croppedWidth = margin.croppedWidth(width);
        int croppedHeight = margin.croppedHeight(height);
        int pixels = croppedWidth * croppedHeight;
        Pass pass = new Pass(pixels);

        double scaleRawCurrent = scaleFor(referenceRaw, rawCurrent, width, height, margin);
        double scaleRawPrevious = rawPrevious == null
                ? Double.NaN : scaleFor(referenceRaw, rawPrevious, width, height, margin);
        double scaleRegistered = scaleFor(referenceRegistered, registeredCurrent, width, height,
                margin);
        double scaleRegisteredPrevious = registeredPrevious == null
                ? Double.NaN
                : scaleFor(referenceRegistered, registeredPrevious, width, height, margin);
        double scaleControl = scaleFor(referenceControl, controlCurrent, width, height, margin);
        boolean hasPair = rawPrevious != null && registeredPrevious != null;

        double diffSqBefore = 0;
        double gradSqRaw = 0;
        double diffSqAfter = 0;
        double gradSqRegistered = 0;

        int top = margin.top();
        int bottom = height - margin.bottom();
        int left = margin.left();
        int right = width - margin.right();
        for (int y = top; y < bottom; y++) {
            int row = y * width;
            int outRow = (y - top) * croppedWidth;
            int above = (y > 0 ? y - 1 : y) * width;
            int below = (y + 1 < height ? y + 1 : y) * width;
            for (int x = left; x < right; x++) {
                int i = row + x;
                int o = outRow + (x - left);
                int west = row + (x > 0 ? x - 1 : x);
                int east = row + (x + 1 < width ? x + 1 : x);

                double registeredValue = registeredCurrent[i] * scaleRegistered;
                double controlValue = controlCurrent[i] * scaleControl;
                pass.sumRegistered[o] = registeredValue;
                pass.sumSqRegistered[o] = registeredValue * registeredValue;
                pass.sumControl[o] = controlValue;
                pass.sumSqControl[o] = controlValue * controlValue;

                if (!hasPair) continue;
                double before = rawCurrent[i] * scaleRawCurrent
                        - rawPrevious[i] * scaleRawPrevious;
                double after = registeredValue
                        - registeredPrevious[i] * scaleRegisteredPrevious;
                double rawGx = 0.5 * (rawCurrent[east] - rawCurrent[west]) * scaleRawCurrent;
                double rawGy = 0.5 * (rawCurrent[below + x] - rawCurrent[above + x])
                        * scaleRawCurrent;
                double regGx = 0.5 * (registeredCurrent[east] - registeredCurrent[west])
                        * scaleRegistered;
                double regGy = 0.5 * (registeredCurrent[below + x] - registeredCurrent[above + x])
                        * scaleRegistered;
                if (isFinite(before) && isFinite(rawGx) && isFinite(rawGy)) {
                    diffSqBefore += before * before;
                    gradSqRaw += rawGx * rawGx + rawGy * rawGy;
                }
                if (isFinite(after) && isFinite(regGx) && isFinite(regGy)) {
                    diffSqAfter += after * after;
                    gradSqRegistered += regGx * regGx + regGy * regGy;
                }
            }
        }
        pass.residualBefore = hasPair ? equivalentShift(diffSqBefore, gradSqRaw) : Double.NaN;
        pass.residualAfter = hasPair ? equivalentShift(diffSqAfter, gradSqRegistered) : Double.NaN;
        return pass;
    }

    /**
     * A frame-to-frame brightness change turned into the shift that would have
     * produced it, in pixels. See this class's javadoc for the relation.
     */
    static double equivalentShift(double diffSq, double gradSq) {
        if (!(gradSq > 0) || !isFinite(diffSq)) return Double.NaN;
        return Math.sqrt(2.0 * diffSq / gradSq);
    }

    /**
     * The median of one frame inside the valid margin, from a strided subsample.
     *
     * <p>Public because the brightness equalisation is part of what this arbiter
     * published, not an internal convenience: a reader reproducing
     * {@code sd_vs_control} needs the same scaling, and
     * {@code ArbiterFusedPassTest} builds its three separate passes from it.
     */
    public static double frameMedian(float[] plane, int width, int height,
                                     ControlWarp.Margin margin) {
        if (plane == null || plane.length != width * height) return Double.NaN;
        long inside = margin.croppedPixels(width, height);
        int stride = Math.max(1, (int) Math.round(Math.sqrt((double) inside / MEDIAN_SAMPLES)));
        int top = margin.top();
        int bottom = height - margin.bottom();
        int left = margin.left();
        int right = width - margin.right();
        int capacity = 0;
        for (int y = top; y < bottom; y += stride) {
            for (int x = left; x < right; x += stride) capacity++;
        }
        if (capacity == 0) return Double.NaN;
        float[] sample = new float[capacity];
        int at = 0;
        for (int y = top; y < bottom; y += stride) {
            int row = y * width;
            for (int x = left; x < right; x += stride) {
                float v = plane[row + x];
                if (Float.isNaN(v)) continue;
                sample[at++] = v;
            }
        }
        if (at == 0) return Double.NaN;
        float[] sorted = Arrays.copyOf(sample, at);
        Arrays.sort(sorted);
        return sorted[at / 2];
    }

    /** The multiplier that brings one frame onto the reference median. */
    private static double scaleFor(double reference, float[] plane, int width, int height,
                                   ControlWarp.Margin margin) {
        if (plane == null) return Double.NaN;
        double median = frameMedian(plane, width, height, margin);
        return median > 0 && isFinite(reference) ? reference / median : 1.0;
    }

    /**
     * Temporal standard deviation of one pixel, from its running sums.
     *
     * <p>The sums are the fused walk's whole output for this pixel, so this is
     * where they turn into a spread. Clamped at zero: the difference of two large
     * nearly-equal doubles can land a hair below it.
     */
    private static double standardDeviation(double sum, double sumSq, int frames) {
        double mean = sum / frames;
        double variance = sumSq / frames - mean * mean;
        if (Double.isNaN(variance)) return Double.NaN;
        return Math.sqrt(Math.max(0, variance));
    }

    /** Total distance the registration walked over the recording, in pixels. */
    static double pathPx(Transform[] cumulative) {
        double total = 0;
        for (int t = 1; t < cumulative.length; t++) {
            Transform a = cumulative[t - 1] == null ? Transform.IDENTITY : cumulative[t - 1];
            Transform b = cumulative[t] == null ? Transform.IDENTITY : cumulative[t];
            total += Math.hypot(b.dx - a.dx, b.dy - a.dy);
        }
        return total;
    }

    /** Straight-line distance from the first frame's transform to the last, in pixels. */
    static double netPx(Transform[] cumulative) {
        if (cumulative.length == 0) return 0;
        Transform a = cumulative[0] == null ? Transform.IDENTITY : cumulative[0];
        Transform b = cumulative[cumulative.length - 1] == null
                ? Transform.IDENTITY : cumulative[cumulative.length - 1];
        return Math.hypot(b.dx - a.dx, b.dy - a.dy);
    }

    /**
     * Bytes one frame's task needs live: four {@code double} accumulator buffers
     * the size of the valid margin, the control plane it warps, and its share of
     * the five frame planes a pair keeps open. This is the figure defect D9's
     * clamp is applied to, and on a large recording it is the figure that decides
     * how many workers the run gets.
     */
    static long memoryPerFrame(int width, int height, int pixels) {
        return 32L * pixels + 24L * width * height;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static java.util.concurrent.CancellationException canceled() {
        return new java.util.concurrent.CancellationException("canceled");
    }

    /**
     * Frames {@code base - 1} through {@code base + count - 1}, read on the
     * coordinator. Entry 0 is the frame before the batch, or {@code null} at the
     * start of the recording.
     */
    private static float[][] window(FrameSource source, int base, int count,
                                    Cancellation cancellation) {
        float[][] planes = new float[count + 1][];
        if (base > 0) planes[0] = source.plane(base - 1);
        for (int k = 0; k < count; k++) {
            if (cancellation.canceled()) throw canceled();
            planes[k + 1] = source.plane(base + k);
        }
        return planes;
    }

    /** A progress reporter for one batch that counts in whole-recording terms. */
    private static PairScheduler.Progress offsetBy(final PairScheduler.Progress progress,
                                                   final int offset, final int total) {
        if (progress == null) return PairScheduler.Progress.NONE;
        return new PairScheduler.Progress() {
            @Override
            public void update(int done, int ignored) {
                progress.update(offset + done, total);
            }
        };
    }

    // ---------------------------------------------------------------- recovery

    /**
     * Works out what a registration did, when it did not say.
     *
     * <p>The {@code score} mode rates a recording somebody else's plugin
     * produced, and most of them report nothing about the shifts they applied.
     * Phase correlation between the raw frame and the registered frame recovers
     * each one directly: a feature at {@code p} before is at {@code p - cum[t]}
     * after, so the displacement measured from raw to registered is the transform
     * negated.
     *
     * <p><b>Measured at native resolution, always.</b> The control needs the
     * <em>fractional part</em> of each transform, and a fraction measured on
     * binned pixels and multiplied back up is not the fraction the engine
     * resampled with. See defect D12 for why the scale travels with every number
     * in this plugin.
     *
     * @param workers 0 or less to decide automatically, 1 to force serial
     */
    public static Recovered recover(FrameSource raw, FrameSource registered, int workers,
                                    PairScheduler.Progress progress, Cancellation cancellation) {
        if (raw == null || registered == null) {
            throw new IllegalArgumentException("recovering the shifts needs the recording before"
                    + " and after");
        }
        final int n = raw.count();
        final int width = raw.width();
        final int height = raw.height();
        if (registered.count() != n || registered.width() != width
                || registered.height() != height) {
            throw new IllegalArgumentException("the two recordings are different shapes, so no"
                    + " shift between them would mean anything");
        }
        final Frames.Bin scale = raw.bin();
        final double maxShift = Math.max(8.0, 0.25 * Math.min(width, height));
        final PhaseCorrelation estimator = new PhaseCorrelation();
        final Transform[] cumulative = new Transform[n];
        final Estimator.Status[] statuses = new Estimator.Status[n];

        int used = PairScheduler.workersFor(n, workers, 24L * width * height, 0);
        int batch = Math.max(1, used);
        for (int base = 0; base < n; base += batch) {
            if (cancellation.canceled()) throw canceled();
            final int count = Math.min(batch, n - base);
            final int start = base;
            final float[][] before = new float[count][];
            final float[][] after = new float[count][];
            for (int k = 0; k < count; k++) {
                before[k] = raw.plane(start + k);
                after[k] = registered.plane(start + k);
            }
            List<Estimator.Displacement> found = PairScheduler.map(count, used,
                    new PairScheduler.Task<Estimator.Displacement>() {
                        @Override
                        public Estimator.Displacement run(int index) {
                            return estimator.shift(before[index], after[index], width, height,
                                    maxShift, scale);
                        }
                    }, offsetBy(progress, start, n), cancellation);
            for (int index = 0; index < count; index++) {
                Estimator.Displacement d = found.get(index);
                // The displacement runs from raw to registered, and a transform describes the
                // motion the registration undid, so the sign turns over here. Getting this
                // backwards produces a control that adds the drift back rather than leaving it,
                // which is plausible in every table and wrong in every number.
                cumulative[start + index] = d.defined()
                        ? Transform.translation(-d.dx(), -d.dy())
                        : Transform.IDENTITY;
                statuses[start + index] = d.status();
            }
        }
        return new Recovered(cumulative, statuses, maxShift, scale);
    }

    /** The transforms a registration applied, worked out from its own output. */
    public static final class Recovered {

        private final Transform[] cumulative;
        private final Estimator.Status[] statuses;
        private final double maxShift;
        private final Frames.Bin measuredAt;

        Recovered(Transform[] cumulative, Estimator.Status[] statuses, double maxShift,
                  Frames.Bin measuredAt) {
            this.cumulative = cumulative;
            this.statuses = statuses;
            this.maxShift = maxShift;
            this.measuredAt = measuredAt;
        }

        /** One transform per frame, ready for {@link #score}. */
        public Transform[] cumulative() {
            return cumulative.clone();
        }

        /** How the search ended at one frame. Never {@code null}. */
        public Estimator.Status statusOf(int frame) {
            return statuses[frame];
        }

        /** The search bound every frame was measured under, in pixels. */
        public double maxShiftPx() {
            return maxShift;
        }

        /** The effective pixel size these transforms are expressed in. */
        public Frames.Bin measuredAt() {
            return measuredAt;
        }

        /** How many frames came back on the search bound, so their shift is a floor. */
        public int framesAtBound() {
            int at = 0;
            for (int t = 0; t < statuses.length; t++) {
                if (statuses[t] == Estimator.Status.AT_SHIFT_BOUND) at++;
            }
            return at;
        }

        /** One sentence naming what produced these, for the saved record. */
        public String provenance() {
            return String.format(Locale.US, "shifts recovered by phase correlation between the raw"
                            + " and registered recordings, %d frames, bound %.1f px at %s",
                    cumulative.length, maxShift, measuredAt.provenance());
        }
    }

    // ------------------------------------------------------------- the answers

    /** One frame's contribution: two residuals and four per-pixel buffers. */
    static final class Pass {

        final double[] sumRegistered;
        final double[] sumSqRegistered;
        final double[] sumControl;
        final double[] sumSqControl;
        double residualBefore = Double.NaN;
        double residualAfter = Double.NaN;

        Pass(int pixels) {
            this.sumRegistered = new double[pixels];
            this.sumSqRegistered = new double[pixels];
            this.sumControl = new double[pixels];
            this.sumSqControl = new double[pixels];
        }
    }

    /**
     * What the arbiter measured about one arm.
     *
     * <p><b>{@link #sdVsControl()} is the effect of registration and the only
     * quantity here that is.</b> The raw recording's temporal standard deviation
     * is not on this object, is not in the tables and was never computed: quoting
     * it would credit every method with the resampling that any method gets for
     * free (defect D11).
     */
    public static final class Scoring {

        private final int frames;
        private final int width;
        private final int height;
        private final Transform[] cumulative;
        private final Transform[] control;
        private final ControlWarp.Interpolation interpolation;
        private final ControlWarp.Margin margin;
        private final double[] residualBefore;
        private final double[] residualAfter;
        private final double meanSdRegistered;
        private final double meanSdControl;
        private final double validFraction;
        private final MotionPreservation motion;

        Scoring(int frames, int width, int height, Transform[] cumulative, Transform[] control,
                ControlWarp.Interpolation interpolation, ControlWarp.Margin margin,
                double[] residualBefore, double[] residualAfter, double meanSdRegistered,
                double meanSdControl, double validFraction, MotionPreservation motion) {
            this.frames = frames;
            this.width = width;
            this.height = height;
            this.cumulative = cumulative;
            this.control = control;
            this.interpolation = interpolation;
            this.margin = margin;
            this.residualBefore = residualBefore;
            this.residualAfter = residualAfter;
            this.meanSdRegistered = meanSdRegistered;
            this.meanSdControl = meanSdControl;
            this.validFraction = validFraction;
            this.motion = motion;
        }

        /** How many frames were scored. */
        public int frames() {
            return frames;
        }

        /**
         * The one number that is the effect of registration: how much lower the
         * registered recording's temporal standard deviation is than the
         * interpolation-matched control's, as a fraction.
         *
         * <p>{@code -0.506} means half again as still as the control - the figure
         * {@code library/04_drift} reproduces. Zero means the method achieved
         * exactly what resampling alone achieves, which is the correct score for a
         * method that only blurred.
         */
        public double sdVsControl() {
            return meanSdControl > 0 ? meanSdRegistered / meanSdControl - 1 : Double.NaN;
        }

        /** {@link #sdVsControl()} as a percentage, which is how it is read. */
        public double sdVsControlPercent() {
            return 100 * sdVsControl();
        }

        /**
         * Mean per-pixel temporal standard deviation of the registered recording,
         * inside the valid margin, after brightness equalisation.
         *
         * <p>Here so that {@link #sdVsControl()} can be checked against its two
         * parts. It is not an effect on its own and does not go in a table: on its
         * own it says nothing about what registration did, because the control is
         * the only thing it means anything against.
         */
        public double meanSdRegistered() {
            return meanSdRegistered;
        }

        /** The same figure for the control. Read only against the one above it. */
        public double meanSdControl() {
            return meanSdControl;
        }

        /**
         * Whether the arbiter can tell the registered recording and the control
         * apart at all.
         *
         * @see Arbiter#CANNOT_SEPARATE_PERCENT
         */
        public Separation separation() {
            return separationOf(sdVsControlPercent());
        }

        /** The sentence that goes with {@link #separation()}, for a user to read. */
        public String separationText() {
            double percent = sdVsControlPercent();
            switch (separation()) {
                case IMPROVED:
                    return String.format(Locale.US, "Temporal standard deviation is %.1f%% lower"
                            + " than an interpolation-matched control, so that much is"
                            + " attributable to holding the field still rather than to the"
                            + " resampling every method gets for free.", -percent);
                case WORSE:
                    return String.format(Locale.US, "Temporal standard deviation is %.1f%% higher"
                            + " than an interpolation-matched control, so this arm left the"
                            + " recording less still than resampling alone would have.", percent);
                case CANNOT_SEPARATE:
                default:
                    if (Double.isNaN(percent)) {
                        return "Nothing in this recording could be scored against the control, so"
                                + " no ranking is produced.";
                    }
                    return String.format(Locale.US, "Cannot separate: this arm and an"
                            + " interpolation-matched control differ by %.1f%%, inside the %.1f%%"
                            + " the library says is noise, so no ranking is produced. That is not"
                            + " a tie - it means temporal standard deviation cannot tell them"
                            + " apart on this recording. Where frames are far enough apart that"
                            + " the sample itself moves between them, no pixel-variance measure"
                            + " can.", percent, CANNOT_SEPARATE_PERCENT);
            }
        }

        /** Frame-to-frame mismatch before registration, in pixels, frame by frame. */
        public double[] residualBefore() {
            return residualBefore.clone();
        }

        /** Frame-to-frame mismatch after registration, in pixels, frame by frame. */
        public double[] residualAfter() {
            return residualAfter.clone();
        }

        /** The middle of {@link #residualBefore()}, which is the column value. */
        public double medianResidualBefore() {
            return median(residualBefore);
        }

        /** The middle of {@link #residualAfter()}, which is the column value. */
        public double medianResidualAfter() {
            return median(residualAfter);
        }

        /** How much mismatch the arm took out, in pixels. Negative means it added some. */
        public double residualRemoved() {
            return medianResidualBefore() - medianResidualAfter();
        }

        /** Total distance the registration walked over the recording, in pixels. */
        public double pathPx() {
            return Arbiter.pathPx(cumulative);
        }

        /** Straight-line distance from the first frame to the last, in pixels. */
        public double netPx() {
            return Arbiter.netPx(cumulative);
        }

        /** The transforms this scoring was given. */
        public Transform[] cumulative() {
            return cumulative.clone();
        }

        /** The control's transforms: the fractional part of each of the above. */
        public Transform[] control() {
            return control.clone();
        }

        /** How the registered recording and the control were both resampled. */
        public ControlWarp.Interpolation interpolation() {
            return interpolation;
        }

        /** The region real in every frame, which is all that was measured. */
        public ControlWarp.Margin margin() {
            return margin;
        }

        /** Share of the frame inside that margin. The {@code valid_fraction} column. */
        public double validFraction() {
            return validFraction;
        }

        /** What happened at one frame. Never {@code null}. */
        public FrameStatus statusOf(int frame) {
            if (validFraction < MIN_VALID_FRACTION) return FrameStatus.REFUSED_LOW_OVERLAP;
            Transform t = frame < cumulative.length ? cumulative[frame] : null;
            return ControlWarp.pathFor(t, interpolation) == ControlWarp.Path.RESAMPLED
                    ? FrameStatus.INTERPOLATED : FrameStatus.OK;
        }

        /** How many frames carried a status other than {@code ok}. */
        public int framesFlagged() {
            int flagged = 0;
            for (int t = 0; t < frames; t++) {
                if (statusOf(t) != FrameStatus.OK) flagged++;
            }
            return flagged;
        }

        /**
         * Whether this arm may have followed the sample rather than the field, and
         * the caveat that travels with the answer either way.
         */
        public MotionPreservation motion() {
            return motion;
        }

        /** One sentence naming what produced these numbers, for the saved record. */
        public String provenance() {
            return String.format(Locale.US, "sd_vs_control over %d frames of %dx%d, %s"
                            + " resampling, %s, control = fractional part only of each frame's"
                            + " transform (defect D11)",
                    frames, width, height, interpolation.words(), margin);
        }

        private static double median(double[] values) {
            int counted = 0;
            for (int i = 0; i < values.length; i++) {
                if (!Double.isNaN(values[i])) counted++;
            }
            if (counted == 0) return Double.NaN;
            double[] finite = new double[counted];
            int at = 0;
            for (int i = 0; i < values.length; i++) {
                if (!Double.isNaN(values[i])) finite[at++] = values[i];
            }
            Arrays.sort(finite);
            return counted % 2 == 1
                    ? finite[counted / 2]
                    : 0.5 * (finite[counted / 2 - 1] + finite[counted / 2]);
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s: sd_vs_control %+.1f%% (%s), residual %.3f -> %.3f px",
                    provenance(), sdVsControlPercent(), separation().word(),
                    medianResidualBefore(), medianResidualAfter());
        }
    }
}
