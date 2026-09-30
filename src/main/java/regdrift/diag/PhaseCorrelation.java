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

    /**
     * Width of the Gaussian weight on the normalised cross-power spectrum, in
     * radians per pixel (Nyquist is pi).
     *
     * <p><b>Why phase correlation needs one here.</b> A camera pixel integrates
     * over its area, and so does binning. A frame moved by a fraction {@code f} of
     * a pixel is then, to a close approximation, each pixel mixed with its
     * neighbour in proportions {@code 1 - f} and {@code f}. That mixing shifts low
     * frequencies by {@code f} and high ones by less - at Nyquist not at all -
     * and plain phase correlation gives every frequency the same vote, so a
     * slow drift read short. Measured on the synthetic drifting recordings in
     * {@code SubpixelDriftTest}: with no weight, {@code drift_rate_px} read 62-79%
     * of a true 0.2-1.7 px per frame at bin 4 even with an exact peak; with
     * this weight, 99-101%.
     *
     * <p>0.5 is the middle of the three widths measured. 0.3 read a single pair
     * slightly closer and over-read one bin-4 case by 5%; 0.8 under-read by up to
     * 6% at bin 1. The weight is applied after the magnitude is normalised, so
     * phase correlation stays insensitive to contrast.
     *
     * <p><b>Only the sub-pixel step sees it.</b> The whole-pixel peak is still
     * found on the unweighted surface. Weighting that surface too let phase
     * correlation lock on to a narrow-band recording that 0.1.0 could not read,
     * so it agreed with the pyramid search there and the recording no longer
     * reached {@code estimators_disagree} ({@code VerdictTest}). The two
     * estimators are meant to fail independently, so the weight is kept out of
     * which peak is chosen.
     */
    static final double SPECTRAL_WEIGHT_SIGMA = 0.5;

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
        double[] weight = new double[n];
        for (int k = 0; k < n; k++) {
            double w = 2 * Math.PI * frequency(k, n) / n;
            weight[k] = Math.exp(-w * w / (2 * SPECTRAL_WEIGHT_SIGMA * SPECTRAL_WEIGHT_SIGMA));
        }
        // The whole-pixel peak is found on the unweighted surface, exactly as 0.1.0 found it, so
        // the weight cannot change which peak is chosen: phase correlation keeps its own view of
        // a recording, independent of the pyramid search it is checked against. The weighted
        // spectrum is kept for the sub-pixel step alone, which reads the surface between samples.
        double[] spectrumRe = new double[ar.length];
        double[] spectrumIm = new double[ar.length];
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
            double g = normalise ? weight[i % n] * weight[i / n] : 1.0;
            spectrumRe[i] = br[i] * g;
            spectrumIm[i] = bi[i] * g;
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
        double[] refined = refine(spectrumRe, spectrumIm, n, px, py);
        double dx = refined[0];
        double dy = refined[1];
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

    // ------------------------------------------------------- the sub-pixel step

    /**
     * How far from the sampled peak the sub-pixel step looks, in pixels.
     *
     * <p>More than one pixel because the sampled peak comes from the unweighted
     * surface and the refinement reads the weighted one: when the true shift sits
     * near a half pixel the two can round to neighbouring whole pixels. At 0.6 the
     * B9 recording at (1.5, -1.0) px per frame read 89% of its movement; at 1.1 it
     * reads within 1%.
     */
    static final double REFINE_REACH = 1.1;
    /** Spacing of the first look along each axis, in pixels. */
    static final double REFINE_COARSE_STEP = 0.05;
    /** Spacing of the second look, around the best point of the first. */
    static final double REFINE_FINE_STEP = 0.0025;
    /** Rounds of refining x on the peak's row and then y on its column. */
    static final int REFINE_ROUNDS = 2;

    /**
     * Where the correlation surface peaks between its samples, read from the
     * spectrum rather than fitted to three samples.
     *
     * <p><b>Why not a parabola.</b> 0.1.0 put a parabola through the peak and its
     * two neighbours. The surface of a translated image is a narrow sinc-like
     * peak, not a parabola, and the fit pulls every answer toward the nearest
     * whole pixel: on a 96 x 96 test pair a true 0.3 px read 0.10 and a true
     * 0.6 px read 0.78. Summed over a recording that is steady drift read short
     * (defects B8 and B9).
     *
     * <p><b>What this does instead.</b> The inverse transform gives the surface
     * at whole pixels; the same sum evaluated at a fractional position gives it
     * anywhere, exactly, with no model of its shape. That is the upsampled-DFT
     * refinement of Guizar-Sicairos, Thurman and Fienup (2008, Opt. Lett.
     * 33:156), done along one axis at a time: x along the row through the
     * current y, then y along the column through the new x, twice. Each line is
     * searched on a 0.05 px grid within {@link #REFINE_REACH} of the sampled peak,
     * then on a 0.0025 px grid around the best point, and the last step is a
     * parabola through three points of that fine grid, where the surface is
     * smooth enough for a parabola to be exact to well under a thousandth of a
     * pixel.
     *
     * @return {@code {x, y}} in the surface's own indices, not yet unwrapped
     */
    private static double[] refine(double[] re, double[] im, int n, int px, int py) {
        double x = px;
        double y = py;
        for (int round = 0; round < REFINE_ROUNDS; round++) {
            x = refineAxis(re, im, n, true, y, px);
            y = refineAxis(re, im, n, false, x, py);
        }
        return new double[]{x, y};
    }

    /**
     * The highest point of the surface along one line, within
     * {@link #REFINE_REACH} of the sampled peak.
     *
     * @param horizontal true to search x along the row at {@code across}; false to
     *                   search y along the column at {@code across}
     * @param centre     the sampled peak's index on the searched axis
     */
    private static double refineAxis(double[] re, double[] im, int n, boolean horizontal,
                                     double across, int centre) {
        // Collapse the other axis first: line[k] = sum over j of R(j, k) e^{2 pi i f(j) across / n},
        // so each point on the line then costs n multiplications rather than n^2.
        double[] tr = new double[n];
        double[] ti = new double[n];
        for (int j = 0; j < n; j++) {
            double angle = 2 * Math.PI * frequency(j, n) * across / n;
            tr[j] = Math.cos(angle);
            ti[j] = Math.sin(angle);
        }
        double[] lr = new double[n];
        double[] li = new double[n];
        if (horizontal) {
            for (int v = 0; v < n; v++) {
                double cr = tr[v];
                double ci = ti[v];
                int row = v * n;
                for (int u = 0; u < n; u++) {
                    double r = re[row + u];
                    double i = im[row + u];
                    lr[u] += r * cr - i * ci;
                    li[u] += r * ci + i * cr;
                }
            }
        } else {
            for (int v = 0; v < n; v++) {
                int row = v * n;
                double sr = 0;
                double si = 0;
                for (int u = 0; u < n; u++) {
                    double r = re[row + u];
                    double i = im[row + u];
                    sr += r * tr[u] - i * ti[u];
                    si += r * ti[u] + i * tr[u];
                }
                lr[v] = sr;
                li[v] = si;
            }
        }

        int coarse = (int) Math.round(REFINE_REACH / REFINE_COARSE_STEP);
        double best = centre;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int k = -coarse; k <= coarse; k++) {
            double p = centre + k * REFINE_COARSE_STEP;
            double value = lineValue(lr, li, n, p);
            if (value > bestValue) {
                bestValue = value;
                best = p;
            }
        }

        int fine = (int) Math.round(REFINE_COARSE_STEP / REFINE_FINE_STEP);
        double[] values = new double[2 * fine + 1];
        int top = fine;
        for (int k = -fine; k <= fine; k++) {
            values[k + fine] = lineValue(lr, li, n, best + k * REFINE_FINE_STEP);
            if (values[k + fine] > values[top]) top = k + fine;
        }
        double offset = 0;
        if (top > 0 && top < values.length - 1) {
            offset = vertex(values[top - 1], values[top], values[top + 1]);
        }
        double found = best + (top - fine + offset) * REFINE_FINE_STEP;
        return Math.max(centre - REFINE_REACH, Math.min(centre + REFINE_REACH, found));
    }

    /**
     * The surface along one collapsed line at position {@code p}: the real part
     * of {@code sum_k line[k] e^{2 pi i f(k) p / n}}. The rotation is advanced by
     * one fixed step per term, restarted at the negative frequencies, so a point
     * costs four trigonometric calls rather than {@code 2n}.
     */
    private static double lineValue(double[] lr, double[] li, int n, double p) {
        double step = 2 * Math.PI * p / n;
        double sc = Math.cos(step);
        double ss = Math.sin(step);
        double sum = 0;
        int half = n / 2;
        double c = 1;
        double s = 0;
        for (int k = 0; k < half; k++) {
            sum += lr[k] * c - li[k] * s;
            double nc = c * sc - s * ss;
            s = c * ss + s * sc;
            c = nc;
        }
        double start = -half * step;
        c = Math.cos(start);
        s = Math.sin(start);
        for (int k = half; k < n; k++) {
            sum += lr[k] * c - li[k] * s;
            double nc = c * sc - s * ss;
            s = c * ss + s * sc;
            c = nc;
        }
        return sum;
    }

    /** Signed frequency of transform index {@code k}: 0..n/2-1, then -n/2..-1. */
    private static int frequency(int k, int n) {
        return k < n / 2 ? k : k - n;
    }

    /**
     * Offset of a parabola's vertex from the middle of three equally spaced
     * samples, clamped to half a spacing.
     */
    private static double vertex(double before, double at, double after) {
        double denom = before - 2 * at + after;
        if (Math.abs(denom) < 1e-300) return 0;
        double d = 0.5 * (before - after) / denom;
        return Math.max(-0.5, Math.min(0.5, d));
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
