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
 * Displacement by matching phase in the frequency domain. The first of the two
 * independent estimators.
 *
 * <p>The everyday version: instead of sliding one photograph over the other and
 * looking for the position that matches, break both pictures into ripples of
 * every wavelength, ask each ripple how far it has moved, and let all of them
 * vote at once. Because the vote is about <em>where</em> each ripple sits and
 * not <em>how strong</em> it is, turning the lamp down does not change the
 * answer.
 *
 * <p>Published prior art, and old: Kuglin and Hines, <i>IEEE Conference on
 * Cybernetics and Society</i>, 1975. It shares nothing with the log-ratio
 * criterion this plugin's author wrote - different domain, different objective,
 * no gain model, no robust weighting, no pyramid - and it shares nothing with
 * {@link PyramidSsd} either. That is the point of having it.
 *
 * <p>It carries its own radix-2 transform because {@code ij} is this plugin's
 * only dependency and {@code ij} has no Fourier transform worth calling. About
 * two hundred lines of textbook Cooley-Tukey is a smaller price than a second
 * jar the user has to install.
 *
 * <h2>Two things here look like details and are not</h2>
 *
 * <p><b>The spectral floor is relative</b> - see {@link #SPECTRAL_FLOOR}. Two
 * tests failed when it was absolute.
 *
 * <p><b>The border taper is flat in the middle</b> - see {@link #taper}. A full
 * Hann window, which is what everybody reaches for, biased every answer to about
 * 70% of the truth.
 *
 * <p>Ported near-verbatim from {@code logratio\PhaseCorrelation.java} in the
 * Log-Ratio Registration research repository, where it was test-scope only. The
 * port adds three things and changes nothing else: pixels marked
 * {@link Float#NaN} are treated as unmeasured rather than poisoning the mean;
 * the per-frame half of the work is split out as {@link #transform} so a caller
 * measuring many pairs can transform each frame once; and the answer arrives as
 * an {@link Estimator.Displacement}, with the search bound applied and a status
 * on it, instead of a bare pair of numbers.
 */
public final class PhaseCorrelation implements Estimator {

    /** The published name of the method. */
    public static final String NAME = "phase correlation";

    /**
     * A frequency must carry at least this fraction of its own image's peak
     * amplitude to vote.
     *
     * <p><b>Relative, and that is the whole point.</b> Normalising the
     * cross-power spectrum gives every frequency equal weight, which is what
     * makes phase correlation insensitive to contrast - and also means a
     * frequency holding nothing but windowing leakage arrives with weight one
     * and a meaningless phase. With an absolute floor instead of this one, two of
     * the tests carried across with this file failed: a half-pixel shift came
     * back as 0.25 px, and scaling one frame by 0.4 moved a 3 px answer to
     * 0.36 px. The second failure is the diagnostic - an absolute threshold is
     * not scale-free, so changing the brightness changed which empty frequencies
     * were admitted, in a routine whose entire selling point as a baseline is
     * that brightness does not matter to it.
     */
    private static final double SPECTRAL_FLOOR = 1e-6;

    /** Total fraction of each axis given over to the border taper, split between the two edges. */
    private static final double TAPER_FRACTION = 0.25;

    /** Real and imaginary halves of a transformed frame, in that order. */
    private static final int RE = 0;
    private static final int IM = 1;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Displacement of the content from {@code a} to {@code b}.
     *
     * <p>Transforms both frames and then correlates them. A caller measuring many
     * pairs over overlapping frames should use {@link #transform} once per frame
     * and {@link #shiftOf} per pair instead; this method is the single-pair
     * convenience and the {@link Estimator} contract.
     */
    @Override
    public Displacement shift(float[] a, float[] b, int width, int height, double maxShift,
                              Frames.Bin bin) {
        int n = transformSize(width, height);
        return shiftOf(transform(a, width, height), transform(b, width, height), n, maxShift, bin);
    }

    /**
     * Side length of the square the transform runs on: the next power of two that
     * holds the frame on both axes.
     */
    public static int transformSize(int width, int height) {
        return Math.max(nextPowerOfTwo(width), nextPowerOfTwo(height));
    }

    /**
     * The per-frame half of the work: mean-subtract inside the frame, taper its
     * border, zero-pad to a square power of two, and transform.
     *
     * <p>Split out so that a run measuring many pairs transforms each frame once
     * rather than once per pair it appears in. The result depends on the frame
     * alone, so it caches cleanly.
     *
     * <p>The window is not optional. A rectangular edge is a step discontinuity
     * to the transform, and its spectrum swamps a few pixels of real translation.
     *
     * @return {@code {real, imaginary}}, each {@code transformSize^2} long
     */
    public static double[][] transform(float[] plane, int width, int height) {
        if (plane == null) throw new IllegalArgumentException("no plane to transform");
        if (plane.length != width * height) {
            throw new IllegalArgumentException("expected a " + width + "x" + height
                    + " plane, got " + plane.length + " values");
        }
        int n = transformSize(width, height);
        double[] re = new double[n * n];
        double[] im = new double[n * n];
        window(plane, re, width, height, n);
        fft2(re, im, n, false);
        return new double[][]{re, im};
    }

    /**
     * Displacement of the content from the frame behind {@code a} to the frame
     * behind {@code b}, from their transforms.
     *
     * @param n        the side length both transforms were taken at
     * @param maxShift bound on the magnitude of the answer, in the pixels the two
     *                 frames were sampled at
     * @param bin      that pixel size. Required
     */
    public static Displacement shiftOf(double[][] a, double[][] b, int n, double maxShift,
                                       Frames.Bin bin) {
        if (a == null || b == null) throw new IllegalArgumentException("no transforms to correlate");
        if (a.length != 2 || b.length != 2 || a[RE].length != n * n || b[RE].length != n * n) {
            throw new IllegalArgumentException("expected two " + n + "x" + n + " transforms");
        }
        if (!(maxShift > 0)) {
            throw new IllegalArgumentException("the search bound must be a positive number of"
                    + " pixels, was " + maxShift);
        }
        // A frame with no variation at all transforms to nothing: every coefficient, the constant
        // one included, is zero once the mean is subtracted. There is no phase to match, so the
        // honest answer is the identity with a reason on it rather than whichever bin of an
        // all-zero surface happens to be scanned first. Defect D8.
        // Each amplitude is taken once here and handed on: correlate needs the same two figures
        // for its spectral floor, and on a 512 x 512 frame each one is a quarter of a million
        // square roots.
        double amplitudeA = peakAmplitude(a);
        double amplitudeB = peakAmplitude(b);
        if (amplitudeA <= 0 || amplitudeB <= 0) {
            return Displacement.identity(Status.NO_STRUCTURE, bin);
        }

        double[] peak = correlate(a, b, n, true, amplitudeA, amplitudeB);
        double magnitude = Math.hypot(peak[0], peak[1]);
        if (magnitude > maxShift) {
            // Reported on the bound, not truncated quietly. The direction is what the pixels said;
            // the length is all the caller allowed, and the status says so.
            double f = maxShift / magnitude;
            return Displacement.of(peak[0] * f, peak[1] * f, Status.AT_SHIFT_BOUND, bin);
        }
        return Displacement.of(peak[0], peak[1], Status.OK, bin);
    }

    /**
     * The correlation peak, in pixels, with no bound applied. The sign convention
     * is the project's: the displacement of the content from {@code a} to
     * {@code b}.
     *
     * @param normalise true for phase correlation; false for plain
     *                  cross-correlation, which leaves the cross-power spectrum
     *                  unnormalised so high-energy low frequencies dominate the
     *                  peak. Kept as the baseline that isolates what the phase
     *                  normalisation itself buys - and it is also the only one of
     *                  these that is not invariant to a change of brightness
     */
    static double[] peak(float[] a, float[] b, int width, int height, boolean normalise) {
        int n = transformSize(width, height);
        double[][] fa = transform(a, width, height);
        double[][] fb = transform(b, width, height);
        return correlate(fa, fb, n, normalise, peakAmplitude(fa), peakAmplitude(fb));
    }

    /** The correlation peak with the phase normalisation on, which is the method proper. */
    static double[] peak(float[] a, float[] b, int width, int height) {
        return peak(a, b, width, height, true);
    }

    // ------------------------------------------------------------ the method

    private static double[] correlate(double[][] a, double[][] b, int n, boolean normalise,
                                      double amplitudeA, double amplitudeB) {
        double[] ar = a[RE];
        double[] ai = a[IM];
        double[] br = b[RE].clone();
        double[] bi = b[IM].clone();

        // R = F_b * conj(F_a), normalised to unit magnitude. The inverse transform of that is a
        // delta at the displacement itself, which is why phase correlation is insensitive to
        // contrast: every frequency contributes equally regardless of how much energy the image has
        // there.
        //
        // Each spectrum is thresholded against its OWN peak before that, so a frequency neither
        // image actually has is dropped rather than promoted to full weight.
        double floorA = SPECTRAL_FLOOR * amplitudeA;
        double floorB = SPECTRAL_FLOOR * amplitudeB;
        for (int i = 0; i < ar.length; i++) {
            if (normalise && (Math.hypot(ar[i], ai[i]) < floorA
                    || Math.hypot(br[i], bi[i]) < floorB)) {
                br[i] = 0;
                bi[i] = 0;
                continue;
            }
            double re = br[i] * ar[i] + bi[i] * ai[i];
            double im = bi[i] * ar[i] - br[i] * ai[i];
            double mag = normalise ? Math.hypot(re, im) : 1.0;
            if (mag <= 0) {
                br[i] = 0;
                bi[i] = 0;
                continue;
            }
            br[i] = re / mag;
            bi[i] = im / mag;
        }
        fft2(br, bi, n, true);

        int at = 0;
        double highest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < br.length; i++) {
            if (br[i] > highest) {
                highest = br[i];
                at = i;
            }
        }
        int py = at / n;
        int px = at % n;
        double dx = px + parabolic(br, n, px, py, true);
        double dy = py + parabolic(br, n, px, py, false);
        // The correlation surface is periodic, so a displacement to the left appears near the far
        // edge.
        if (dx > n / 2.0) dx -= n;
        if (dy > n / 2.0) dy -= n;
        return new double[]{dx, dy};
    }

    /** Largest coefficient magnitude in a transform. Zero when the frame had no variation. */
    private static double peakAmplitude(double[][] f) {
        double[] re = f[RE];
        double[] im = f[IM];
        double highest = 0;
        for (int i = 0; i < re.length; i++) {
            highest = Math.max(highest, Math.hypot(re[i], im[i]));
        }
        return highest;
    }

    /**
     * Sub-pixel offset of the peak from a parabola through its two neighbours.
     *
     * <p>Clamped to half a pixel. An unclamped fit on a flat or double-peaked
     * surface can return a large offset from three nearly equal samples, which
     * would be a confident answer built on nothing.
     */
    private static double parabolic(double[] s, int n, int px, int py, boolean horizontal) {
        int before = horizontal ? wrap(px - 1, n) + py * n : px + wrap(py - 1, n) * n;
        int at = px + py * n;
        int after = horizontal ? wrap(px + 1, n) + py * n : px + wrap(py + 1, n) * n;
        double denom = s[before] - 2 * s[at] + s[after];
        if (Math.abs(denom) < 1e-20) return 0;
        double d = 0.5 * (s[before] - s[after]) / denom;
        return Math.max(-0.5, Math.min(0.5, d));
    }

    private static int wrap(int i, int n) {
        return (i + n) % n;
    }

    /**
     * Mean-subtract inside the real region, taper its border, zero-pad the rest.
     *
     * <p><b>Unmeasured pixels.</b> {@link Float#NaN} marks a pixel that was never
     * measured. It is left out of the mean and then written as zero, which is the
     * same thing the padding around the frame is: no contribution, rather than a
     * value invented for it. Upstream this file never saw one, because the
     * research repository handed it planes with a validity mask beside them;
     * here the planes arrive from {@link FrameSource} and a masked margin is an
     * ordinary thing for a user's recording to have.
     */
    private static void window(float[] src, double[] dst, int w, int h, int n) {
        double total = 0;
        int measured = 0;
        for (int i = 0; i < src.length; i++) {
            if (Float.isNaN(src[i])) continue;
            total += src[i];
            measured++;
        }
        double mean = measured > 0 ? total / measured : 0;
        double[] wx = taper(w);
        double[] wy = taper(h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = src[y * w + x];
                dst[y * n + x] = Float.isNaN(v) ? 0.0 : (v - mean) * wx[x] * wy[y];
            }
        }
    }

    /**
     * Cosine taper over the outer {@link #TAPER_FRACTION} of each axis, flat in
     * the middle.
     *
     * <p><b>Not a Hann window, and the difference is a 30% error.</b> A Hann
     * window modulates the whole frame, so the two windowed frames are no longer
     * translations of one another - {@code w(x)a(x-d)} is not
     * {@code w(x-d)a(x-d)} - and the correlation peak is pulled toward zero.
     * Measured on the tests carried across with this file, a full Hann returned
     * 2.77 px for a true 4 px shift and 2.09 px for a true 3 px: the right
     * direction, uniformly about 70% of the right size, which is the kind of
     * error that survives a sanity check and quietly biases everything
     * downstream. A flat interior leaves the translation intact and the taper
     * still removes the edge discontinuity that the transform would otherwise
     * read as broadband signal.
     */
    private static double[] taper(int n) {
        double[] t = new double[n];
        int edge = Math.max(1, (int) Math.round(TAPER_FRACTION * n / 2.0));
        for (int i = 0; i < n; i++) {
            int d = Math.min(i, n - 1 - i);
            t[i] = d >= edge ? 1.0 : 0.5 - 0.5 * Math.cos(Math.PI * (d + 0.5) / edge);
        }
        return t;
    }

    static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    /**
     * Columns copied out together in the column pass: a block of neighbouring
     * columns is read row by row, so each cache line fetched is used whole rather
     * than for one value. Every column still goes through {@link #fft} alone, so
     * the arithmetic, and every bit of the result, is what one column at a time
     * gave.
     */
    private static final int COLUMN_BLOCK = 16;

    /** In-place 2-D transform of a square {@code n x n} array stored row-major. */
    static void fft2(double[] re, double[] im, int n, boolean inverse) {
        double[][] twiddles = twiddles(n, inverse);
        double[] tr = new double[n];
        double[] ti = new double[n];
        for (int y = 0; y < n; y++) {
            System.arraycopy(re, y * n, tr, 0, n);
            System.arraycopy(im, y * n, ti, 0, n);
            fft(tr, ti, inverse, twiddles);
            System.arraycopy(tr, 0, re, y * n, n);
            System.arraycopy(ti, 0, im, y * n, n);
        }
        int block = Math.min(COLUMN_BLOCK, n);
        double[][] cr = new double[block][n];
        double[][] ci = new double[block][n];
        for (int x0 = 0; x0 < n; x0 += block) {
            for (int y = 0; y < n; y++) {
                int row = y * n + x0;
                for (int c = 0; c < block; c++) {
                    cr[c][y] = re[row + c];
                    ci[c][y] = im[row + c];
                }
            }
            for (int c = 0; c < block; c++) fft(cr[c], ci[c], inverse, twiddles);
            for (int y = 0; y < n; y++) {
                int row = y * n + x0;
                for (int c = 0; c < block; c++) {
                    re[row + c] = cr[c][y];
                    im[row + c] = ci[c][y];
                }
            }
        }
    }

    /** In-place iterative radix-2 Cooley-Tukey. {@code re.length} must be a power of two. */
    static void fft(double[] re, double[] im, boolean inverse) {
        fft(re, im, inverse, twiddles(re.length, inverse));
    }

    /**
     * The rotation factors every butterfly of a length-{@code n} transform uses:
     * for the stage of span {@code len}, factor {@code k} sits at
     * {@code len / 2 + k}.
     *
     * <p>Built by the same recurrence, in the same order, that the butterfly loop
     * used to run inline and restart for every block of a stage, so each factor
     * is bit for bit the value it was. Taking them from a table instead saves
     * that recomputation, which was two fifths of the multiplications.
     */
    static double[][] twiddles(int n, boolean inverse) {
        double[] tr = new double[Math.max(1, n)];
        double[] ti = new double[Math.max(1, n)];
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            int half = len / 2;
            double cr = 1;
            double ci = 0;
            for (int k = 0; k < half; k++) {
                tr[half + k] = cr;
                ti[half + k] = ci;
                double nr = cr * wr - ci * wi;
                ci = cr * wi + ci * wr;
                cr = nr;
            }
        }
        return new double[][]{tr, ti};
    }

    private static void fft(double[] re, double[] im, boolean inverse, double[][] twiddles) {
        int n = re.length;
        double[] twr = twiddles[RE];
        double[] twi = twiddles[IM];
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len / 2;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; k++) {
                    double cr = twr[half + k];
                    double ci = twi[half + k];
                    int u = i + k;
                    int v = u + half;
                    double vr = re[v] * cr - im[v] * ci;
                    double vi = re[v] * ci + im[v] * cr;
                    re[v] = re[u] - vr;
                    im[v] = im[u] - vi;
                    re[u] += vr;
                    im[u] += vi;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }
}
