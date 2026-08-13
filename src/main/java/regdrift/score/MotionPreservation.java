/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.score;

import java.util.Locale;

/**
 * A flag saying a registration may have followed the sample rather than the
 * field - and the caveat that has to be read with it.
 *
 * <h2>What it is for</h2>
 *
 * <p>Registration assumes the picture moved and the contents did not. When one
 * large object in the frame is itself moving - a bubble crossing the field, a
 * piece of debris drifting, a wound edge closing - a registration can lock onto
 * that object and hold <em>it</em> still, sliding the whole field around to do
 * it. The recording then looks beautifully registered and the real biology has
 * been moved off its own coordinates.
 *
 * <p>The everyday version: filming a train from a platform, and stabilising the
 * footage on the train. The result is rock steady and the platform is now
 * flying past.
 *
 * <h2>Why it is a flag and never a verdict</h2>
 *
 * <p>Nothing here can tell a moving artefact from a stage that genuinely walked a
 * long way. {@code library/08_knock_severe} really did travel 872 px of path over
 * 512 px of frame, and its registration is right. So this raises a flag with a
 * sentence attached and stops there; it never downgrades a score, never reorders
 * a ranking, and never turns into a refusal. The caveat is carried on the flag
 * object itself rather than written beside it at the call site, because a flag
 * that gets copied into a table without its caveat is a verdict in everything but
 * name.
 *
 * <h2>The two measurements, and where the numbers came from</h2>
 *
 * <p><b>Recovered path length against frame size.</b> The total distance the
 * registration walked, divided by the short side of the frame. Measured across
 * all twelve entries of the Log-Ratio Registration library, eleven sit between
 * 0.14 and 1.14 - and {@code 11_unresolved_moving_artefact}, the entry a moving
 * object was found in, sits at <b>5.92</b> (4547 px of path across a 768 px
 * frame). The next longest are {@code 08_knock_severe} at 1.14 and
 * {@code 12_long_baseline_9d} at 1.10, and both of those registrations are right.
 * A trigger at {@link #PATH_TRIGGER} separates the one entry from every other
 * with a factor of about three of clearance on each side, and it is deliberately
 * set high: a flag that fires on ordinary drift is a flag nobody reads.
 *
 * <p><b>Single-structure dominance.</b> How much of the frame's contrast sits in
 * one connected bright region. On its own it says nothing - a single well-stained
 * field is dominant and perfectly registrable - so it only raises the flag in
 * company, when the path has already exceeded the frame's short side. It is
 * reported either way, because it is the number that says <em>which</em> reading
 * of a long path is the likely one.
 *
 * <p><b>What dominance did on this library, stated plainly:</b> nothing. All
 * twelve entries are IncuCyte phase contrast, whose texture is fine-grained
 * everywhere, and every one of them measured below 0.003 - including the moving
 * artefact, which is a dark object in a textured field rather than a bright one
 * in an empty field. So on this calibration the flag is carried by path length
 * alone, and the dominance route is untested against real data. It is kept
 * because the failure it describes is real on fluorescence recordings, where one
 * labelled structure genuinely can be most of the frame, and it is reported next
 * to the flag so a reader can see for themselves which of the two fired.
 *
 * <p><b>Localisability cannot do this job and is not meant to.</b> A moving
 * artefact is usually the most localisable thing in the frame - it is exactly
 * what an estimator can pin down. That is why this flag exists separately from
 * the registrability verdict rather than being folded into it.
 */
public final class MotionPreservation {

    /**
     * Path length, as a multiple of the frame's short side, that raises the flag
     * on its own. Derived in this class's javadoc from the twelve library
     * entries.
     */
    public static final double PATH_TRIGGER = 2.0;

    /**
     * Path length, as a multiple of the frame's short side, above which a
     * dominant single structure also raises the flag.
     */
    public static final double PATH_TRIGGER_WITH_DOMINANCE = 1.0;

    /**
     * Share of the frame's contrast in one connected region that counts as one
     * structure dominating it.
     */
    public static final double DOMINANCE_TRIGGER = 0.5;

    private final boolean raised;
    private final double pathPx;
    private final double pathFraction;
    private final double dominance;
    private final int shortSide;

    private MotionPreservation(boolean raised, double pathPx, double pathFraction,
                               double dominance, int shortSide) {
        this.raised = raised;
        this.pathPx = pathPx;
        this.pathFraction = pathFraction;
        this.dominance = dominance;
        this.shortSide = shortSide;
    }

    /**
     * Weigh a recovered path and a dominance figure against the frame.
     *
     * @param pathPx    total distance the registration walked, in pixels
     * @param width     frame width in the same pixels
     * @param height    frame height in the same pixels
     * @param dominance share of the frame's contrast in one connected region, 0
     *                  to 1, or {@link Double#NaN} when it was not measured
     */
    public static MotionPreservation of(double pathPx, int width, int height, double dominance) {
        int shortSide = Math.max(1, Math.min(width, height));
        double fraction = pathPx / shortSide;
        boolean longPath = fraction > PATH_TRIGGER;
        boolean dominatedAndFar = !Double.isNaN(dominance)
                && dominance > DOMINANCE_TRIGGER
                && fraction > PATH_TRIGGER_WITH_DOMINANCE;
        return new MotionPreservation(longPath || dominatedAndFar, pathPx, fraction,
                dominance, shortSide);
    }

    /** What a run that was asked not to flag motion loss carries: measured, never raised. */
    public static MotionPreservation notAsked(double pathPx, int width, int height) {
        int shortSide = Math.max(1, Math.min(width, height));
        return new MotionPreservation(false, pathPx, pathPx / shortSide, Double.NaN, shortSide);
    }

    /** True when the flag is up. Not a verdict, and not a score. */
    public boolean raised() {
        return raised;
    }

    /** Total distance the registration walked, in pixels. */
    public double pathPx() {
        return pathPx;
    }

    /** That distance as a multiple of the frame's short side. */
    public double pathFraction() {
        return pathFraction;
    }

    /**
     * Share of the frame's contrast in the largest connected bright region, 0 to
     * 1. {@link Double#NaN} when it was not measured.
     */
    public double dominance() {
        return dominance;
    }

    /**
     * The sentence that must travel with the flag, whether it is up or down.
     *
     * <p>It says what was measured, what it might mean, and - in its last clause,
     * every time - that this is a flag and not a verdict.
     */
    public String caveat() {
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.US,
                "Recovered path length is %.0f%% of the frame's short side (%.0f px across %d px)",
                100 * pathFraction, pathPx, shortSide));
        if (!Double.isNaN(dominance)) {
            text.append(String.format(Locale.US,
                    ", and one connected structure holds %.0f%% of the frame's contrast",
                    100 * dominance));
        }
        text.append(". On a recording dominated by one large moving structure, a registration can"
                + " follow the structure rather than the field, which looks like an excellent"
                + " registration and moves the sample off its own coordinates. A long path can"
                + " equally mean a stage that really did travel that far. This is a flag, not a"
                + " verdict: nothing here reorders a ranking or changes a score.");
        return text.toString();
    }

    /**
     * How much of a frame's contrast sits in one connected bright region.
     *
     * <p>Everything above halfway between the frame's median and its brightest
     * few percent is taken as structure; the connected groups of those pixels are
     * found with a four-way flood fill; the answer is the largest group's share of
     * the total brightness above the median. A frame with one bright object
     * against a flat background comes back near 1, an evenly textured field comes
     * back small.
     *
     * <p>Deliberately crude, and deliberately not a segmentation. It feeds a flag
     * whose threshold is half, and a more careful method would invite the number
     * to be read as a measurement of the object rather than of the frame.
     *
     * @return 0 to 1, or {@link Double#NaN} when the frame carries no contrast to
     *         divide up
     */
    public static double dominance(float[] plane, int width, int height) {
        if (plane == null || plane.length != width * height || plane.length == 0) {
            return Double.NaN;
        }
        float[] sorted = finiteSorted(plane);
        if (sorted.length < 16) return Double.NaN;
        double median = sorted[sorted.length / 2];
        double high = sorted[(int) Math.min(sorted.length - 1L, Math.round(0.99 * (sorted.length - 1)))];
        if (!(high > median)) return Double.NaN;
        double cut = median + 0.5 * (high - median);

        double totalExcess = 0;
        for (int i = 0; i < plane.length; i++) {
            float v = plane[i];
            if (!isFinite(v)) continue;
            if (v > median) totalExcess += v - median;
        }
        if (!(totalExcess > 0)) return Double.NaN;

        boolean[] seen = new boolean[plane.length];
        int[] stack = new int[plane.length];
        double largest = 0;
        for (int start = 0; start < plane.length; start++) {
            if (seen[start]) continue;
            seen[start] = true;
            float v = plane[start];
            if (!isFinite(v) || v <= cut) continue;
            int top = 0;
            stack[top++] = start;
            double group = 0;
            while (top > 0) {
                int i = stack[--top];
                group += plane[i] - median;
                int x = i % width;
                int y = i / width;
                if (x > 0) top = push(stack, top, seen, plane, cut, i - 1);
                if (x + 1 < width) top = push(stack, top, seen, plane, cut, i + 1);
                if (y > 0) top = push(stack, top, seen, plane, cut, i - width);
                if (y + 1 < height) top = push(stack, top, seen, plane, cut, i + width);
            }
            if (group > largest) largest = group;
        }
        return largest / totalExcess;
    }

    private static int push(int[] stack, int top, boolean[] seen, float[] plane, double cut,
                            int index) {
        if (seen[index]) return top;
        seen[index] = true;
        float v = plane[index];
        if (!isFinite(v) || v <= cut) return top;
        stack[top++] = index;
        return top;
    }

    private static float[] finiteSorted(float[] plane) {
        int counted = 0;
        for (int i = 0; i < plane.length; i++) {
            if (isFinite(plane[i])) counted++;
        }
        float[] out = new float[counted];
        int at = 0;
        for (int i = 0; i < plane.length; i++) {
            if (isFinite(plane[i])) out[at++] = plane[i];
        }
        java.util.Arrays.sort(out);
        return out;
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    @Override
    public String toString() {
        return (raised ? "motion-preservation flag raised: " : "motion-preservation flag down: ")
                + caveat();
    }
}
