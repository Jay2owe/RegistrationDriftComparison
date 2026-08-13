/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

/**
 * How much frame-to-frame correlation a recording loses when one frame is
 * displaced a single pixel.
 *
 * <p>The everyday version: slide one photograph a hair's breadth over the one
 * underneath it and see how much worse the match gets. If it barely changes,
 * nothing in the picture pins down where it should sit, and no method will line
 * the two up. If it drops away sharply, the picture has something to line up on.
 *
 * <p><b>What it is for.</b> Deciding, before anything is registered, whether this
 * channel can be registered at all - and which of several channels to measure
 * from. It is computed from the pixels alone, needs no transform, no truth and no
 * second method, and costs one extra correlation per frame pair.
 *
 * <p><b>Why plain frame correlation is the wrong criterion.</b> Correlation near
 * zero does rule out photon-starved noise, which is what it was introduced for.
 * But it is equally near one for a smooth, featureless blob on a smooth
 * background, and a smooth blob cannot be localised either - for the opposite
 * reason. Ranking by correlation on the first recording surveyed chose a
 * saturated fluorescence channel, on which two independent estimators then
 * disagreed by 15.7 px per step. What registration needs is a correlation that
 * <em>falls away sharply</em> when the frames are misaligned, because that fall
 * is the gradient a solver descends.
 *
 * <p><b>What the numbers mean.</b> Across 24 real recordings the measure
 * separated the usable from the unusable by an order of magnitude, and predicted
 * the agreement between two independent estimators - which is the closest thing
 * to ground truth available without injecting known motion:
 *
 * <table border="1">
 *   <caption>Survey of 24 recordings, {@code library/survey/motion_survey.csv}</caption>
 *   <tr><th>recordings</th><th>localisability</th><th>agreement of two independent methods</th></tr>
 *   <tr><td>13</td><td>0.086 - 0.284</td><td>0.53 - 0.68 px</td></tr>
 *   <tr><td>11</td><td>0.008 - 0.031</td><td>1.6 - 18.6 px</td></tr>
 * </table>
 *
 * <p>The gap between the two bands is where {@link #WARN_BELOW} sits. It is a
 * warning threshold, not a refusal: a low value means no method will localise
 * this channel well, not that this one has failed. That is defect D7 and it is
 * settled.
 *
 * <h2>The scale is part of the number, and this is defect D12</h2>
 *
 * <p>One pixel at native resolution is a far smaller relative displacement than
 * one pixel after binning, so this quantity is scale-dependent and any threshold
 * on it is scale-specific. {@link #WARN_BELOW} was calibrated on the survey's
 * <em>binned</em> frames. Measured on the twelve library recordings unbinned,
 * eleven of twelve fall below it - including the four with the largest reduction -
 * and two read negative, which is the measure saying it is being used outside the
 * regime it was defined in.
 *
 * <p>So there is no way to get a number out of this class without the
 * {@link Frames.Bin} it was measured at. That is not tidiness; it is the one
 * structural defence against the defect happening again. <b>Stage 09 owns the
 * decision</b> about which scale the verdict measures at and whether the
 * threshold survives it.
 *
 * <p><b>The threshold is calibrated on real, noisy recordings, and that is a real
 * caveat.</b> The measure asks whether a one-pixel error is visible in these
 * pixels. On noise-free synthetic content it can read low and registration still
 * succeed, because with no noise even a shallow correlation fall is a clean
 * gradient. Read the threshold as "on data like the survey's", not as a law.
 *
 * <p>Ported from {@code logratio\core\Localisability.java} in the Log-Ratio
 * Registration research repository, with the measurement scale added to every
 * result. It measures pixels; it holds no registration criterion.
 */
public final class Localisability {

    /**
     * Below this, warn that the channel is poorly localisable.
     *
     * <p>Placed in the empty gap between the two bands measured across the survey
     * - 0.031 was the highest of the eleven unusable recordings and 0.086 the
     * lowest of the thirteen usable ones. Nothing was measured between them, so
     * the threshold is not fitted to a boundary case.
     *
     * <p><b>Calibrated on the survey's binned frames.</b> See the class note on
     * D12 before comparing anything measured at another scale with it.
     */
    public static final double WARN_BELOW = 0.05;

    private Localisability() {
    }

    /**
     * Localisability of a whole recording, at the scale its frames are sampled
     * at: the mean over consecutive frame pairs of the fall in correlation caused
     * by displacing the second frame one pixel, averaged over the two axes.
     *
     * <p>Consecutive pairs, always. A chain of single steps is what makes a jitter
     * distinguishable from a walk; pairs at longer separations measure something
     * else.
     *
     * @return a result whose value is {@link Double#NaN} when fewer than two
     *         frames carry a defined correlation. Never {@code null}, and never
     *         without its scale
     */
    public static Result of(FrameSource source) {
        if (source == null) throw new IllegalArgumentException("no frames to measure");
        return of(source, source.bin());
    }

    /**
     * Localisability of a whole recording, stating the scale explicitly.
     *
     * @param bin the scale the caller believes these frames are at
     * @throws IllegalArgumentException when {@code bin} is missing, or disagrees
     *         with the scale the frames say they were sampled at. A measurement
     *         labelled with a scale it was not taken at is the defect this whole
     *         signature exists to prevent
     */
    public static Result of(FrameSource source, Frames.Bin bin) {
        if (source == null) throw new IllegalArgumentException("no frames to measure");
        Frames.Bin scale = requireScale(bin);
        Frames.Bin sampled = source.bin();
        if (sampled != null && !sampled.equals(scale)) {
            throw new IllegalArgumentException("these frames were sampled at " + sampled
                    + " and the measurement was asked for at " + scale
                    + "; localisability is not comparable across two scales");
        }
        int n = source.count();
        int w = source.width();
        int h = source.height();
        if (n < 2) return new Result(Double.NaN, scale);
        double total = 0;
        int pairs = 0;
        float[] prev = source.plane(0);
        for (int t = 1; t < n; t++) {
            float[] cur = source.plane(t);
            double d = pairValue(prev, cur, w, h);
            if (!Double.isNaN(d)) {
                total += d;
                pairs++;
            }
            prev = cur;
        }
        return new Result(pairs > 0 ? total / pairs : Double.NaN, scale);
    }

    /**
     * Localisability of one frame pair. Exposed so a caller can rank frames, or
     * spot the transition where a recording stops being registrable, rather than
     * only the recording as a whole.
     *
     * @param bin the scale these two planes were sampled at. Required
     */
    public static Result ofPair(float[] a, float[] b, int width, int height, Frames.Bin bin) {
        return new Result(pairValue(a, b, width, height), requireScale(bin));
    }

    /**
     * A value measured elsewhere, paired with the scale it was measured at.
     *
     * <p>For reading a number back out of a saved table, and for tests that state
     * a value from the survey. There is deliberately no way to make a
     * {@link Result} without saying what scale it belongs to.
     */
    public static Result at(double value, Frames.Bin bin) {
        return new Result(value, requireScale(bin));
    }

    private static Frames.Bin requireScale(Frames.Bin bin) {
        if (bin == null) {
            throw new IllegalArgumentException("a localisability value needs the scale it was"
                    + " measured at; pass Frames.Bin.none() for native resolution. See defect D12");
        }
        return bin;
    }

    private static double pairValue(float[] a, float[] b, int width, int height) {
        if (a == null || b == null) throw new IllegalArgumentException("no planes to measure");
        if (a.length != b.length || a.length != width * height) {
            throw new IllegalArgumentException("expected two " + width + "x" + height + " planes");
        }
        double at = correlation(a, b);
        if (Double.isNaN(at)) return Double.NaN;
        double shiftedX = correlation(a, roll(b, width, height, 1, 0));
        double shiftedY = correlation(a, roll(b, width, height, 0, 1));
        if (Double.isNaN(shiftedX) || Double.isNaN(shiftedY)) return Double.NaN;
        return at - 0.5 * (shiftedX + shiftedY);
    }

    /** Whole-pixel shift with edge clamping. Only ever one pixel, so the clamp is immaterial. */
    private static float[] roll(float[] a, int w, int h, int dx, int dy) {
        float[] out = new float[a.length];
        for (int y = 0; y < h; y++) {
            int sy = y + dy < 0 ? 0 : (y + dy >= h ? h - 1 : y + dy);
            int srow = sy * w;
            int drow = y * w;
            for (int x = 0; x < w; x++) {
                int sx = x + dx < 0 ? 0 : (x + dx >= w ? w - 1 : x + dx);
                out[drow + x] = a[srow + sx];
            }
        }
        return out;
    }

    /**
     * Pearson correlation over the pixels defined in both planes.
     *
     * <p>NaN pixels are skipped in pairs rather than treated as zero: a masked
     * margin is a common way to mark "not measured", and letting it into the sums
     * would make two frames look correlated because their masks agree.
     *
     * <p>Package-private on purpose. A correlation is a different quantity from a
     * localisability and does not carry a scale of its own - it is whatever the
     * two planes handed to it are - so it is not part of this package's public
     * surface, where every measured number arrives with the scale it was taken
     * at. {@link ChannelRanker} reports it beside the ranking.
     *
     * @return {@link Double#NaN} when fewer than two pixels are defined in both,
     *         or when either plane is constant
     */
    static double correlation(float[] a, float[] b) {
        double ma = 0;
        double mb = 0;
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (Float.isNaN(a[i]) || Float.isNaN(b[i])) continue;
            ma += a[i];
            mb += b[i];
            n++;
        }
        if (n < 2) return Double.NaN;
        ma /= n;
        mb /= n;
        double saa = 0;
        double sbb = 0;
        double sab = 0;
        for (int i = 0; i < a.length; i++) {
            if (Float.isNaN(a[i]) || Float.isNaN(b[i])) continue;
            double da = a[i] - ma;
            double db = b[i] - mb;
            saa += da * da;
            sbb += db * db;
            sab += da * db;
        }
        double denom = Math.sqrt(saa * sbb);
        return denom > 0 ? sab / denom : Double.NaN;
    }

    /**
     * A localisability number and the scale it was measured at, which is the only
     * form this package hands one out in.
     */
    public static final class Result {

        private final double value;
        private final Frames.Bin measuredAt;

        Result(double value, Frames.Bin measuredAt) {
            this.value = value;
            this.measuredAt = measuredAt;
        }

        /**
         * The fall in correlation under a one-pixel displacement, at
         * {@link #measuredAt()}. {@link Double#NaN} when nothing could be
         * measured - a flat frame has no correlation to lose.
         */
        public double value() {
            return value;
        }

        /** The effective pixel size this was measured at. Never {@code null}. */
        public Frames.Bin measuredAt() {
            return measuredAt;
        }

        /** True when a number was measured at all. */
        public boolean defined() {
            return !Double.isNaN(value);
        }

        /**
         * True when this is a measured number below {@link #WARN_BELOW}. NaN does
         * not warn.
         *
         * <p><b>Read defect D12 and stage 09 before wiring this into anything a
         * user sees.</b> The threshold was calibrated on binned frames and this
         * result may not have been measured at that scale; on the twelve library
         * recordings unbinned it fires on eleven, including the four with the
         * largest reduction. Stage 09 owns whether the registrability verdict is
         * allowed to use it and at what scale. It is kept here because the
         * upstream API has it and the survey's test carries across with it.
         */
        public boolean poor() {
            return value < WARN_BELOW;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Result)) return false;
            Result o = (Result) other;
            return Double.compare(value, o.value) == 0 && measuredAt.equals(o.measuredAt);
        }

        @Override
        public int hashCode() {
            return Double.hashCode(value) * 31 + measuredAt.hashCode();
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "localisability %.4f at %s%s",
                    value, measuredAt.provenance(), poor() ? " (poorly localisable)" : "");
        }
    }
}
