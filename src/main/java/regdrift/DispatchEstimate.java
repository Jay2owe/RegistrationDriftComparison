/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Roughly what a comparison is about to cost, worked out before any engine is
 * dispatched.
 *
 * <p>The everyday version: the sign at the trailhead saying about four hours. It
 * is not a promise, it is enough to decide with, and it says which parts of it
 * were paced out and which were guessed from the map.
 *
 * <h2>What is added up</h2>
 *
 * <p>Two halves, kept apart in the answer because one of them is measured and
 * the other is much less so:
 *
 * <ul>
 *   <li><b>The engines.</b> One measured figure per arm - see
 *       {@link #ARM_SECONDS_PER_MEGAPIXEL_FRAME}, and read its note, because the
 *       obvious source for this figure is the wrong one. The bundled
 *       calibration's {@code cpu_ms_per_pair} is what the
 *       {@code expected_seconds} column carries and it is <b>not</b> what an arm
 *       costs: it timed one engine driven frame pair by frame pair from Java with
 *       no ImageJ around it, and an arm drives an engine over the whole recording
 *       through ImageJ's command table. Measured, the two are sixty times
 *       apart.</li>
 *   <li><b>This plugin's own work.</b> Measuring the recording's own movement
 *       once, recovering each arm's transforms, and scoring each arm. It is
 *       proportional to frames times megapixels times arms, and the constants
 *       below were measured rather than assumed.</li>
 * </ul>
 *
 * <h2>Processor seconds, and what that does to the estimate</h2>
 *
 * <p>Everything here is processor time, because everything ranked or tabulated in
 * this plugin is (defect D6). On a machine running several workers the elapsed
 * time is smaller than this figure, sometimes by a lot; on a machine that is busy
 * doing something else it is larger. The estimate says so, because somebody
 * watching a clock is watching the elapsed one.
 */
public final class DispatchEstimate {

    /**
     * Processor seconds one phase-correlation pass costs per frame, per megapixel
     * of the <b>padded</b> frame area.
     *
     * <p>Padded, not raw, and that is the whole reason this constant is usable.
     * A phase correlation transforms a frame at the next power of two on each
     * side, so a 512-pixel frame and a 768-pixel frame cost the same per frame
     * as a 1024-pixel one would: the raw pixel count is not what the work scales
     * with, and a figure per raw megapixel is out by a factor of four between
     * two ordinary recordings. Measured on three library entries of different
     * sizes and lengths, this reads 0.722, 0.726 and 0.717 - see
     * {@code docs/D13_ESTIMATE.md} for the runs.
     *
     * <p>Both the shared-movement pass and each arm's transform recovery cost one
     * of these.
     */
    public static final double ESTIMATOR_SECONDS_PER_PADDED_MEGAPIXEL_FRAME = 0.72;

    /**
     * Processor seconds one arm's scoring walk costs per frame, per megapixel of
     * frame.
     *
     * <p>The fused arbiter pass, which warps a control plane over the whole frame
     * and then walks five planes over the valid margin. It reads 0.201, 0.098 and
     * 0.146 on the same three entries, and the spread is the margin: a recording
     * that drifted a long way has much less of its frame left to walk over. The
     * middle of that range is used, which is inside a factor of 1.5 of both ends
     * and is in any case the smaller half of a comparison's own cost - the
     * estimator passes above are three to thirteen times larger.
     */
    public static final double SCORING_SECONDS_PER_MEGAPIXEL_FRAME = 0.14;

    /**
     * Processor seconds one engine arm costs per frame, per megapixel of frame.
     *
     * <p><b>Not from the bundled calibration</b>, and the reason is worth the
     * paragraph because the calibration is the obvious place to look. Its
     * {@code cpu_ms_per_pair} figures come from TurboReg driven one frame pair at
     * a time from Java, with no ImageJ around it. An arm drives an engine over
     * the whole recording through ImageJ's own command table, which is a
     * different piece of work: on {@code library/04_drift} the calibration's
     * arithmetic predicts 0.3 s for a StackReg arm and the arm cost about 22 s.
     * Using the one to estimate the other put the whole figure out by a factor of
     * two.
     *
     * <p>Measured instead, on one real comparison: three engines - StackReg 2.0.1,
     * MultiStackReg 1.46.5 and Linear Stack Alignment with SIFT (mpicbg 1.6.0) -
     * over 48 frames of 512 x 512, in one Fiji, on 2026-08-14. All three landed
     * near 22 processor seconds, which is 1.75 per frame per megapixel. See
     * {@code docs/D13_ESTIMATE.md}.
     *
     * <p>Three engines on one recording on one machine is a thin measurement and
     * the sentence a person reads says so. It is an estimate offered so that
     * nobody starts a ten-minute run by accident, and it is inside a factor of
     * two on the run it was taken from, which is what it is for.
     */
    public static final double ARM_SECONDS_PER_MEGAPIXEL_FRAME = 1.75;

    /** Under this many processor seconds, a comparison is not worth a warning. */
    public static final double WORTH_WARNING_ABOUT_SECONDS = 20.0;

    private final List<Arm> arms;
    private final int frames;
    private final int width;
    private final int height;
    private final double measurementSeconds;

    private DispatchEstimate(Builder b) {
        this.arms = Collections.unmodifiableList(new ArrayList<Arm>(b.arms));
        this.frames = b.frames;
        this.width = b.width;
        this.height = b.height;
        this.measurementSeconds = measurementCost(b.frames, b.width, b.height, b.arms.size());
    }

    /**
     * An estimate for a comparison over one recording.
     *
     * @param frames how many time points
     * @param width  the frame width in pixels, at native resolution
     * @param height the frame height in pixels, at native resolution
     */
    public static Builder builder(int frames, int width, int height) {
        return new Builder(frames, width, height);
    }

    /** One engine about to be driven, and what an arm of it is expected to cost. */
    public static final class Arm {

        private final String engineName;
        private final double expectedSeconds;

        private Arm(String engineName, double expectedSeconds) {
            this.engineName = engineName;
            this.expectedSeconds = expectedSeconds;
        }

        /** The engine's own spelling of its name. */
        public String engineName() {
            return engineName;
        }

        /** What this arm is expected to cost in processor seconds. */
        public double expectedSeconds() {
            return expectedSeconds;
        }
    }

    /** The engines about to be driven, in the order they will run. */
    public List<Arm> arms() {
        return arms;
    }

    /** How many arms will run. */
    public int armCount() {
        return arms.size();
    }

    /** What one arm is counted at, in processor seconds. */
    public double secondsPerArm() {
        return armCost(frames, width, height);
    }

    /** The engines' share of the total, in processor seconds. */
    public double engineSeconds() {
        return arms.size() * secondsPerArm();
    }

    /** This plugin's own share: measuring, recovering and scoring. */
    public double measurementSeconds() {
        return measurementSeconds;
    }

    /** The whole comparison, in processor seconds. */
    public double totalSeconds() {
        return engineSeconds() + measurementSeconds;
    }

    /** True when this is long enough to be worth stopping somebody over. */
    public boolean worthWarningAbout() {
        return totalSeconds() >= WORTH_WARNING_ABOUT_SECONDS;
    }

    /**
     * The whole thing in finished prose, which is what a person is shown before a
     * comparison starts.
     *
     * <p>States the total, what it covers, which figures came out of the bundled
     * calibration and which are stand-ins, and that stopping here runs nothing.
     */
    public String text() {
        StringBuilder said = new StringBuilder();
        said.append(String.format(Locale.US, "This comparison drives %d engine%s over %d frame%s"
                        + " of %d x %d, and is expected to cost about %s of processor time in"
                        + " total.",
                arms.size(), arms.size() == 1 ? "" : "s", frames, frames == 1 ? "" : "s",
                width, height, duration(totalSeconds())));
        said.append(String.format(Locale.US, " About %s of that is the engines themselves and %s is"
                        + " this plugin measuring the recording and scoring what each engine"
                        + " produced.",
                duration(engineSeconds()), duration(measurementSeconds)));
        said.append(String.format(Locale.US, " Each engine is counted at %s, which is what three"
                        + " engines cost on one recording of this size when this figure was"
                        + " taken - a stand-in rather than a measurement of the engines in front"
                        + " of you, and thin enough that this whole figure is worth reading as"
                        + " minutes rather than as a number.",
                duration(secondsPerArm())));
        said.append(" Processor time is not the time on the clock: with several workers the"
                + " comparison finishes sooner than this, and on a busy machine it takes longer.");
        said.append(" Engines run one after another, and stopping the run stops before the next"
                + " engine rather than in the middle of one.");
        said.append(" Stopping here runs nothing at all.");
        return said.toString();
    }

    /** A short line for the status bar, when the whole passage is too much. */
    public String shortText() {
        return String.format(Locale.US, "%d engine%s, about %s of processor time",
                arms.size(), arms.size() == 1 ? "" : "s", duration(totalSeconds()));
    }

    @Override
    public String toString() {
        return shortText();
    }

    /**
     * A duration a person reads, rather than a number of seconds with four
     * decimal places on it.
     */
    public static String duration(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) return "an unknown time";
        if (seconds < 10) return String.format(Locale.US, "%.1f s", seconds);
        if (seconds < 90) return String.format(Locale.US, "%.0f s", seconds);
        if (seconds < 5400) return String.format(Locale.US, "%.0f min", seconds / 60.0);
        return String.format(Locale.US, "%.1f hours", seconds / 3600.0);
    }

    /** What one arm is expected to cost on this recording, in processor seconds. */
    static double armCost(int frames, int width, int height) {
        double megapixels = (double) Math.max(1, width) * Math.max(1, height) / 1e6;
        return ARM_SECONDS_PER_MEGAPIXEL_FRAME * megapixels * Math.max(0, frames);
    }

    /**
     * What this plugin's own passes cost: one shared movement pass over the
     * recording, then one transform recovery and one scoring walk per arm.
     *
     * <p>{@code 1 + arms} estimator passes, because the movement of the recording
     * itself is measured once for the control every arm shares, and each arm's
     * own transforms are then recovered from what it produced.
     */
    static double measurementCost(int frames, int width, int height, int arms) {
        double megapixels = (double) Math.max(1, width) * Math.max(1, height) / 1e6;
        double padded = (double) paddedSide(width) * paddedSide(height) / 1e6;
        double frameCount = Math.max(0, frames);
        double perEstimatorPass =
                ESTIMATOR_SECONDS_PER_PADDED_MEGAPIXEL_FRAME * padded * frameCount;
        double perScoringPass = SCORING_SECONDS_PER_MEGAPIXEL_FRAME * megapixels * frameCount;
        return perEstimatorPass * (1 + arms) + perScoringPass * arms;
    }

    /** The next power of two at or above one side, which is what a transform works at. */
    static int paddedSide(int side) {
        int padded = 1;
        while (padded < Math.max(1, side)) {
            padded <<= 1;
        }
        return padded;
    }

    /** Builds a {@link DispatchEstimate}. */
    public static final class Builder {

        private final List<Arm> arms = new ArrayList<Arm>();
        private final int frames;
        private final int width;
        private final int height;

        private Builder(int frames, int width, int height) {
            this.frames = frames;
            this.width = width;
            this.height = height;
        }

        /**
         * One engine about to be driven.
         *
         * <p>Every arm is counted at the same figure, because that is the whole
         * of what has been measured about what an arm costs - see
         * {@link #ARM_SECONDS_PER_MEGAPIXEL_FRAME}. The engine is named so that
         * the estimate can say which engines it covers.
         *
         * @param engineName the engine's own spelling of its name
         */
        public Builder arm(String engineName) {
            arms.add(new Arm(engineName, armCost(frames, width, height)));
            return this;
        }

        /** Builds the estimate. */
        public DispatchEstimate build() {
            return new DispatchEstimate(this);
        }
    }
}
