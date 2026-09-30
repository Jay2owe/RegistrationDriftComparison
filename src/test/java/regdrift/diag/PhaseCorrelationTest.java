/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The estimator has to be right before it can be a second opinion.
 *
 * <p>Phase correlation cross-checks the other estimator's motion trace, so a bug in it would not
 * present as a failure - it would present as the two methods disagreeing, which reads as a finding
 * about the recording rather than about the code, and the confidence column would quietly stop
 * meaning anything.
 *
 * <p><b>The content here is broadband on purpose.</b> The first version of this test used a sum of
 * three sinusoids, and phase correlation could not recover a 4 px shift from it to better than a
 * factor of two. That was not a bug: normalising the cross-power spectrum gives every frequency
 * equal weight, so an image with only a few frequencies produces a correlation surface that is a sum
 * of a few cosines - broad, oscillatory, and with many near-equal maxima. Phase correlation needs a
 * broad spectrum to localise anything, which real microscope frames have and a test tone does not.
 * The limitation is pinned as its own test at the bottom, because it also explains what the
 * agreement column means on a recording whose field is nearly empty.
 *
 * <p>Carried across from {@code logratio\PhaseCorrelationTest.java} in the Log-Ratio Registration
 * research repository. Every case is the one that was measured there; the only edits are the package,
 * and that the unbounded peak is now called {@code peak} because {@code shift} is the name of the
 * {@link Estimator} method that applies the search bound. The four cases at the bottom cover what the
 * port added.
 */
public class PhaseCorrelationTest {

    private static final int W = 96;
    private static final int H = 80;
    /** Oversampling factor for the sub-pixel frames. A shift of one fine pixel is 1/4 of a coarse one. */
    private static final int FINE = 4;
    private static final int MARGIN = 64;

    /** The oversampled source, built once. Broadband, so the correlation peak is sharp. */
    private static final int FW = FINE * (W + MARGIN);
    private static final int FH = FINE * (H + MARGIN);
    private static final float[] FIELD = field(FW, FH, 20260810L, 3);

    /**
     * A frame at coarse resolution, cut from the oversampled field at a fine-pixel offset.
     *
     * <p><b>This is how to get exact sub-pixel truth without assuming an interpolator.</b> Shifting by
     * a whole fine pixel and then averaging 4x4 blocks down is a quarter-pixel shift at coarse
     * resolution, and the resampling is pixel integration - what a camera sensor does. Interpolating
     * the test data instead would bake a particular kernel into the truth and quietly favour whichever
     * method assumes the smoothest image.
     *
     * <p>Sign convention as everywhere else: content at {@code p} in the unshifted frame sits at
     * {@code p + (dx, dy)} here, which is why the crop origin moves by minus the shift.
     */
    private static float[] frame(double dx, double dy) {
        int ox = FINE * MARGIN / 2 - (int) Math.round(dx * FINE);
        int oy = FINE * MARGIN / 2 - (int) Math.round(dy * FINE);
        float[] out = new float[W * H];
        double norm = 1.0 / (FINE * FINE);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double s = 0;
                for (int j = 0; j < FINE; j++) {
                    int row = (oy + y * FINE + j) * FW + ox + x * FINE;
                    for (int i = 0; i < FINE; i++) s += FIELD[row + i];
                }
                out[y * W + x] = (float) (s * norm);
            }
        }
        return out;
    }

    /**
     * A smoothed random field: broadband, with structure at every scale down to a few pixels.
     *
     * <p>{@code passes} controls how much of the fine detail survives. Too much smoothing and the
     * finest feature becomes coarser than the border taper the transform applies, at which point the
     * taper is the dominant structure in the frame, it does not move, and the correlation peak sits at
     * zero. That is exactly how the first version of this test failed.
     */
    private static float[] field(int w, int h, long seed, int passes) {
        Random rng = new Random(seed);
        float[] f = new float[w * h];
        for (int i = 0; i < f.length; i++) f[i] = (float) (128 + 40 * rng.nextGaussian());
        for (int pass = 0; pass < passes; pass++) {
            float[] t = f.clone();
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    f[y * w + x] = (t[y * w + x] * 4
                            + t[y * w + x - 1] + t[y * w + x + 1]
                            + t[(y - 1) * w + x] + t[(y + 1) * w + x]) / 8f;
                }
            }
        }
        return f;
    }

    private static float[] crop(float[] f, int fw, int ox, int oy) {
        float[] out = new float[W * H];
        for (int y = 0; y < H; y++) {
            System.arraycopy(f, (y + oy) * fw + ox, out, y * W, W);
        }
        return out;
    }

    // --------------------------------------------------------- carried across

    /**
     * A whole-pixel shift taken as two crops of one field: an exact translation, no model assumed.
     *
     * <p>The strongest form of this test, because nothing is interpolated and nothing is generated by
     * a formula the estimator could be in sympathy with. Content at {@code p} in {@code a} sits at
     * {@code p + d} in {@code b} when {@code b}'s crop origin is {@code a}'s minus {@code d}.
     */
    @Test
    public void recoversAnExactCropOffsetWithTheProjectSignConvention() {
        int fw = W + 64;
        int fh = H + 64;
        float[] f = field(fw, fh, 4242L, 2);
        int ox = 32;
        int oy = 32;
        int dx = 5;
        int dy = -4;
        double[] d = PhaseCorrelation.peak(crop(f, fw, ox, oy), crop(f, fw, ox - dx, oy - dy), W, H);
        assertEquals("dx", dx, d[0], 0.10);
        assertEquals("dy", dy, d[1], 0.10);
    }

    @Test
    public void recoversWholePixelShift() {
        double[] d = PhaseCorrelation.peak(frame(0, 0), frame(4, -3), W, H);
        assertEquals("dx", 4.0, d[0], 0.10);
        assertEquals("dy", -3.0, d[1], 0.10);
    }

    /**
     * Sub-pixel recovery, to the tolerance phase correlation actually achieves rather than a hoped-for
     * one.
     *
     * <p>A quarter-pixel truth comes back to within about 0.15 px here, on clean broadband synthetic
     * data with no noise and no intensity change. That was the limit of 0.1.0's parabola through three
     * samples of the peak. 0.2.0 reads the surface between samples from the spectrum instead, and the
     * tolerance is kept as a floor rather than tightened; {@code SubpixelDriftTest} holds the
     * refinement to 5% of a known drift.
     */
    @Test
    public void recoversSubPixelShift() {
        double[] d = PhaseCorrelation.peak(frame(0, 0), frame(2.5, 1.25), W, H);
        assertEquals("dx", 2.5, d[0], 0.25);
        assertEquals("dy", 1.25, d[1], 0.25);
    }

    @Test
    public void isAntisymmetricUnderSwappingTheFrames() {
        float[] a = frame(0, 0);
        float[] b = frame(3, 2);
        double[] ab = PhaseCorrelation.peak(a, b, W, H);
        double[] ba = PhaseCorrelation.peak(b, a, W, H);
        assertEquals("dx", -ab[0], ba[0], 0.05);
        assertEquals("dy", -ab[1], ba[1], 0.05);
    }

    /**
     * A brightness change must not move the peak.
     *
     * <p>It is the test that caught the real defect in this file. With an absolute magnitude floor
     * instead of a relative one, scaling by 0.4 moved a 3 px answer to 0.36 px - brightness changed
     * which near-empty frequencies were admitted, in the one routine whose whole premise is that
     * brightness is irrelevant.
     */
    @Test
    public void isInvariantToAGlobalGain() {
        float[] a = frame(0, 0);
        float[] b = frame(3, -2);
        for (int i = 0; i < b.length; i++) b[i] *= 0.4f;
        double[] d = PhaseCorrelation.peak(a, b, W, H);
        assertEquals("dx", 3.0, d[0], 0.10);
        assertEquals("dy", -2.0, d[1], 0.10);
    }

    @Test
    public void zeroShiftIsZero() {
        double[] d = PhaseCorrelation.peak(frame(0, 0), frame(0, 0), W, H);
        assertEquals(0.0, Math.hypot(d[0], d[1]), 0.02);
    }

    /**
     * Documents the limitation rather than hiding it: a nearly empty spectrum defeats the method.
     *
     * <p>Three sinusoids, a true 4 px shift, and phase correlation cannot get within a pixel of it.
     * This matters for reading the agreement column - on a recording whose field is mostly flat
     * background, a disagreement between the two estimators is expected, and is a statement about the
     * recording rather than about either estimator.
     */
    @Test
    public void aNearlyEmptySpectrumDefeatsIt() {
        float[] a = tone(0, 0);
        float[] b = tone(4, -3);
        double[] d = PhaseCorrelation.peak(a, b, W, H);
        double error = Math.hypot(d[0] - 4, d[1] + 3);
        assertTrue("a three-component image should NOT be recoverable to a pixel; got error "
                + error, error > 1.0);
    }

    private static float[] tone(double dx, double dy) {
        float[] a = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double u = x - dx;
                double v = y - dy;
                a[y * W + x] = (float) (128
                        + 40 * Math.sin(2 * Math.PI * u / 17.0)
                        + 30 * Math.cos(2 * Math.PI * v / 13.0)
                        + 20 * Math.sin(2 * Math.PI * (u + v) / 23.0));
            }
        }
        return a;
    }

    /** The transform must round-trip, or every number downstream is built on a broken kernel. */
    @Test
    public void fftInvertsItself() {
        int n = 64;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) re[i] = Math.sin(i * 0.37) + 0.5 * Math.cos(i * 1.1);
        double[] originalRe = re.clone();
        PhaseCorrelation.fft(re, im, false);
        PhaseCorrelation.fft(re, im, true);
        for (int i = 0; i < n; i++) {
            assertEquals("sample " + i, originalRe[i], re[i], 1e-9);
            assertEquals("imaginary part " + i, 0.0, im[i], 1e-9);
        }
    }

    /**
     * The tabled rotation factors and the blocked column pass were a speed change and nothing else:
     * the 2-D transform must give, bit for bit, what the transform before them gave. The reference
     * below is that earlier code, kept verbatim.
     */
    @Test
    public void fasterTransformIsBitForBitTheEarlierOne() {
        Random random = new Random(20260930L);
        for (int n : new int[]{1, 2, 8, 16, 32, 128}) {
            for (boolean inverse : new boolean[]{false, true}) {
                double[] re = new double[n * n];
                double[] im = new double[n * n];
                for (int i = 0; i < re.length; i++) {
                    re[i] = random.nextGaussian() * 100;
                    im[i] = inverse ? random.nextGaussian() : 0;
                }
                double[] expectRe = re.clone();
                double[] expectIm = im.clone();
                earlierFft2(expectRe, expectIm, n, inverse);
                PhaseCorrelation.fft2(re, im, n, inverse);
                for (int i = 0; i < re.length; i++) {
                    String where = "n=" + n + " inverse=" + inverse + " at " + i;
                    assertEquals(where, Double.doubleToRawLongBits(expectRe[i]),
                            Double.doubleToRawLongBits(re[i]));
                    assertEquals(where, Double.doubleToRawLongBits(expectIm[i]),
                            Double.doubleToRawLongBits(im[i]));
                }
            }
        }
    }

    private static void earlierFft2(double[] re, double[] im, int n, boolean inverse) {
        double[] tr = new double[n];
        double[] ti = new double[n];
        for (int y = 0; y < n; y++) {
            System.arraycopy(re, y * n, tr, 0, n);
            System.arraycopy(im, y * n, ti, 0, n);
            earlierFft(tr, ti, inverse);
            System.arraycopy(tr, 0, re, y * n, n);
            System.arraycopy(ti, 0, im, y * n, n);
        }
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                tr[y] = re[y * n + x];
                ti[y] = im[y * n + x];
            }
            earlierFft(tr, ti, inverse);
            for (int y = 0; y < n; y++) {
                re[y * n + x] = tr[y];
                im[y * n + x] = ti[y];
            }
        }
    }

    private static void earlierFft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
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
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k;
                    int v = i + k + len / 2;
                    double vr = re[v] * cr - im[v] * ci;
                    double vi = re[v] * ci + im[v] * cr;
                    re[v] = re[u] - vr;
                    im[v] = im[u] - vi;
                    re[u] += vr;
                    im[u] += vi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
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

    @Test
    public void nextPowerOfTwoIsExactAtPowersOfTwo() {
        assertEquals(64, PhaseCorrelation.nextPowerOfTwo(64));
        assertEquals(128, PhaseCorrelation.nextPowerOfTwo(65));
        assertEquals(512, PhaseCorrelation.nextPowerOfTwo(384));
        assertTrue(PhaseCorrelation.nextPowerOfTwo(1) >= 1);
    }

    // ------------------------------------------------------ what the port added

    /**
     * The bounded path agrees with the unbounded peak, and says which scale it is in.
     *
     * <p>The bound and the scale are the two things the port added to the signature, and both are
     * arguments rather than settings - see {@link Estimator}.
     */
    @Test
    public void theEstimatorPathReportsThePeakWithItsScale() {
        Frames.Bin two = Frames.Bin.factor(2);
        Estimator.Displacement d = new PhaseCorrelation()
                .shift(frame(0, 0), frame(4, -3), W, H, 12, two);
        assertEquals("dx", 4.0, d.dx(), 0.10);
        assertEquals("dy", -3.0, d.dy(), 0.10);
        assertEquals(Estimator.Status.OK, d.status());
        assertEquals(two, d.measuredAt());
        assertEquals("phase correlation", new PhaseCorrelation().name());
    }

    /**
     * A displacement past the bound comes back on the bound with a status, not truncated in silence.
     *
     * <p>A silently clipped answer is indistinguishable from a real one three pixels short, and it
     * would enter the motion descriptors as though it were measured.
     */
    @Test
    public void aShiftBeyondTheBoundIsReportedAsSittingOnIt() {
        Estimator.Displacement d = new PhaseCorrelation()
                .shift(frame(0, 0), frame(4, -3), W, H, 2.0, Frames.Bin.none());
        assertEquals(Estimator.Status.AT_SHIFT_BOUND, d.status());
        assertEquals("on the bound", 2.0, d.magnitude(), 1e-9);
        assertTrue("and still pointing the way the pixels said", d.dx() > 0 && d.dy() < 0);
    }

    /**
     * Transforming each frame once and correlating the transforms gives the same answer, to the last
     * bit, as transforming per pair.
     *
     * <p>That equality is what lets a run measuring thirty-five pairs over thirty-six frames transform
     * each frame once. If the cached path could differ at all, the diagnosis would depend on how many
     * pairs happened to be asked for.
     */
    @Test
    public void theCachedTransformPathIsBitIdenticalToTheDirectOne() {
        float[] a = frame(0, 0);
        float[] b = frame(3, 2);
        Estimator.Displacement direct = new PhaseCorrelation()
                .shift(a, b, W, H, 20, Frames.Bin.none());
        int n = PhaseCorrelation.transformSize(W, H);
        Estimator.Displacement cached = PhaseCorrelation.shiftOf(
                PhaseCorrelation.transform(a, W, H), PhaseCorrelation.transform(b, W, H),
                n, 20, Frames.Bin.none());
        assertEquals(Double.doubleToRawLongBits(direct.dx()),
                Double.doubleToRawLongBits(cached.dx()));
        assertEquals(Double.doubleToRawLongBits(direct.dy()),
                Double.doubleToRawLongBits(cached.dy()));
    }

    /**
     * A cached transform survives being correlated against several partners.
     *
     * <p>The correlation is done in place upstream, so the port has to copy before it works. If it did
     * not, a frame's first pair would be right and every later pair using the same frame would be
     * measured against a spectrum that had already been overwritten - and the failure would look like
     * the recording moving, not like a bug.
     */
    @Test
    public void aCachedTransformIsNotConsumedByTheFirstPairThatUsesIt() {
        int n = PhaseCorrelation.transformSize(W, H);
        double[][] a = PhaseCorrelation.transform(frame(0, 0), W, H);
        double[][] first = PhaseCorrelation.transform(frame(2, 0), W, H);
        double[][] second = PhaseCorrelation.transform(frame(0, -3), W, H);
        Estimator.Displacement one = PhaseCorrelation.shiftOf(a, first, n, 20, Frames.Bin.none());
        Estimator.Displacement two = PhaseCorrelation.shiftOf(a, second, n, 20, Frames.Bin.none());
        assertEquals("dx of the first pair", 2.0, one.dx(), 0.10);
        assertEquals("dy of the second pair", -3.0, two.dy(), 0.10);
    }

    /**
     * Unmeasured pixels are left out rather than poisoning the mean.
     *
     * <p>Upstream this file never met one, because the research repository handed it planes with a
     * validity mask beside them. Here a plane arrives from {@link FrameSource} and a masked margin is
     * an ordinary thing for a recording to have; a single {@link Float#NaN} would otherwise make the
     * frame mean NaN and the whole transform with it.
     */
    @Test
    public void unmeasuredPixelsDoNotPoisonTheFrame() {
        float[] a = frame(0, 0);
        float[] b = frame(3, -2);
        for (int x = 0; x < W; x++) {
            a[x] = Float.NaN;
            b[x] = Float.NaN;
        }
        Estimator.Displacement d = new PhaseCorrelation().shift(a, b, W, H, 20, Frames.Bin.none());
        assertEquals("dx", 3.0, d.dx(), 0.15);
        assertEquals("dy", -2.0, d.dy(), 0.15);
    }
}
