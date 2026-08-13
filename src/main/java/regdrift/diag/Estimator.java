/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import java.util.Locale;

/**
 * One way of answering "how far did the picture move between these two frames?"
 *
 * <p>Two of these are implemented, and they are deliberately unrelated to each
 * other: {@link PhaseCorrelation} works in the frequency domain and matches
 * phase, {@link PyramidSsd} works in the image domain and minimises squared
 * differences coarse to fine. Neither shares any machinery with the other, and
 * neither shares any with the registration method this plugin's author wrote.
 * That is the whole reason there are two, and a bytecode test asserts it.
 *
 * <p><b>Why two.</b> The distance between their two answers is the confidence
 * signal in the diagnosis - the {@code agreement_px} column. One estimator has
 * no way to say "I am not sure"; two independent ones disagreeing is exactly
 * that statement, and it is measured rather than asserted.
 *
 * <h2>The sign convention, which is easy to get exactly backwards</h2>
 *
 * <p>{@code shift(a, b, ...)} returns the displacement <b>of the content</b>
 * from {@code a} to {@code b}. If a feature sits at {@code p} in {@code a} and
 * at {@code p + d} in {@code b}, the answer is {@code d}. Swapping the two
 * frames negates the answer.
 *
 * <p>A sign flip here would produce a motion trace that is exactly wrong and
 * completely plausible - the same magnitudes, the same shape, the drift running
 * the other way - so both implementations are asserted against a fixture whose
 * displacement is known by construction, and against each other.
 *
 * <h2>The search bound is an argument, never a field</h2>
 *
 * <p>{@code maxShift} arrives with each call. No implementation stores one, and
 * none derives one from the pair in front of it. The bound is a property of the
 * whole recording, worked out once by the sampler in stage 08 from the windows
 * and the bridges together; a bound derived per pair gave a quiet stretch of a
 * recording a bound near the floor, which then clamped the genuine motion in it,
 * and the descriptors ended up describing the search box instead of the movie.
 *
 * <h2>The scale is an argument too</h2>
 *
 * <p>A displacement in pixels means nothing without the size of a pixel, and
 * binned frames have larger pixels than native ones. Every call therefore states
 * the {@link Frames.Bin} its planes were sampled at, and every answer carries it
 * back out. {@code maxShift} is in those same pixels. See defect D12.
 */
public interface Estimator {

    /**
     * The published name of the method, for the provenance a reader can check.
     * Not a label to switch on.
     */
    String name();

    /**
     * Displacement of the content from {@code a} to {@code b}, in the pixels the
     * two planes were sampled at.
     *
     * @param a         the earlier plane, row-major, {@code width * height} long.
     *                  {@link Float#NaN} marks a pixel with no measurement
     * @param b         the later plane, the same size
     * @param maxShift  bound on the magnitude of the answer, in the same pixels.
     *                  An answer sitting on it comes back
     *                  {@link Status#AT_SHIFT_BOUND} rather than silently
     *                  truncated
     * @param bin       the scale these planes were sampled at. Never {@code null};
     *                  pass {@link Frames.Bin#none()} for native resolution
     * @return never {@code null}. A pair with nothing to localise in it comes
     *         back as the identity with {@link Status#NO_STRUCTURE}, which is a
     *         stated refusal to guess rather than a measurement
     */
    Displacement shift(float[] a, float[] b, int width, int height, double maxShift,
                       Frames.Bin bin);

    /**
     * What an estimator concluded, beyond the two numbers.
     *
     * <p>None of these means "nothing happened". Every value is a sentence
     * somebody can act on, which is why a displacement is a small object rather
     * than a {@code double[]} that would have to say "could not tell" with
     * {@code NaN}.
     */
    enum Status {

        /** The search settled inside the bound. The displacement stands. */
        OK("ok"),

        /**
         * The answer sits on {@code maxShift}, so the true displacement may be
         * larger. A floor, not a measurement.
         */
        AT_SHIFT_BOUND("at_shift_bound"),

        /**
         * The search spent its whole budget and was still moving. The
         * displacement is the furthest the search got, which is worth having and
         * is not settled.
         */
        NOT_CONVERGED("not_converged"),

        /**
         * The frames carry nothing that pins a displacement down - flat,
         * saturated, all background, no measured pixels at all, or too little
         * measured area in common - so the identity is returned by decision
         * rather than by measurement.
         *
         * <p>This is defect D8 stated as a value. Before it existed, a flat
         * 192x192 pair came back as a confident 45 px displacement, which was
         * the corner of the search box. A tool that fingerprints whatever a user
         * opens meets featureless frames constantly, and a confident wrong answer
         * on one poisons the whole recommendation rather than one alignment.
         */
        NO_STRUCTURE("no_structure");

        private final String word;

        Status(String word) {
            this.word = word;
        }

        /** The word written into a saved table. US English, lower case. */
        public String word() {
            return word;
        }

        /** True when pixels, rather than a decision, produced the displacement. */
        public boolean measured() {
            return this != NO_STRUCTURE;
        }
    }

    /**
     * A displacement, what an estimator concluded about it, and the pixel size it
     * is expressed in.
     *
     * <p>Immutable, and there is deliberately no way to make one without the
     * scale: a displacement of 3 measured on frames binned four ways is twelve
     * native pixels, and a number that has lost track of which of those it is
     * cannot be compared with anything. See defect D12.
     */
    final class Displacement {

        private final double dx;
        private final double dy;
        private final Status status;
        private final Frames.Bin measuredAt;

        private Displacement(double dx, double dy, Status status, Frames.Bin measuredAt) {
            this.dx = dx;
            this.dy = dy;
            this.status = status;
            this.measuredAt = measuredAt;
        }

        /**
         * A measured displacement.
         *
         * @param bin the scale it was measured at. Required
         */
        public static Displacement of(double dx, double dy, Status status, Frames.Bin bin) {
            if (status == null) {
                throw new IllegalArgumentException("a displacement says how it ended; there is no"
                        + " unlabelled one");
            }
            return new Displacement(dx, dy, status, requireScale(bin));
        }

        /**
         * Zero, with the reason it is zero.
         *
         * <p>What a featureless pair comes back as. Zero here is a scored
         * incumbent that nothing beat, not an absence of an answer.
         */
        public static Displacement identity(Status status, Frames.Bin bin) {
            return of(0, 0, status, bin);
        }

        private static Frames.Bin requireScale(Frames.Bin bin) {
            if (bin == null) {
                throw new IllegalArgumentException("a displacement needs the scale its pixels were"
                        + " measured at; pass Frames.Bin.none() for native resolution."
                        + " See defect D12");
            }
            return bin;
        }

        /** Movement of the content along x, positive to the right. */
        public double dx() {
            return dx;
        }

        /** Movement of the content along y, positive downward, as ImageJ counts rows. */
        public double dy() {
            return dy;
        }

        /** How far the content moved, in the pixels of {@link #measuredAt()}. */
        public double magnitude() {
            return Math.hypot(dx, dy);
        }

        /** How the search ended. Never {@code null}. */
        public Status status() {
            return status;
        }

        /** The effective pixel size this displacement is expressed in. */
        public Frames.Bin measuredAt() {
            return measuredAt;
        }

        /** True when pixels produced this, rather than a refusal to guess. */
        public boolean defined() {
            return status.measured();
        }

        /**
         * Distance between this answer and another one, in the same pixels.
         *
         * <p>{@link Double#NaN} when either answer was not measured, or when the
         * two were measured at different scales - two displacements at different
         * pixel sizes are not comparable, and quietly comparing them is how a
         * confidence signal becomes fiction.
         */
        public double distanceTo(Displacement other) {
            if (other == null || !defined() || !other.defined()) return Double.NaN;
            if (!measuredAt.equals(other.measuredAt)) return Double.NaN;
            return Math.hypot(dx - other.dx, dy - other.dy);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Displacement)) return false;
            Displacement o = (Displacement) other;
            return Double.compare(dx, o.dx) == 0
                    && Double.compare(dy, o.dy) == 0
                    && status == o.status
                    && measuredAt.equals(o.measuredAt);
        }

        @Override
        public int hashCode() {
            int hash = Double.hashCode(dx);
            hash = hash * 31 + Double.hashCode(dy);
            hash = hash * 31 + status.hashCode();
            return hash * 31 + measuredAt.hashCode();
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "(%.3f, %.3f) px %s at %s",
                    dx, dy, status.word(), measuredAt.provenance());
        }
    }
}
