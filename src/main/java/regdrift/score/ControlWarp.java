/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.score;

import regdrift.internal.Transform;

/**
 * Shifts one plane by one transform, and builds the control every score in this
 * plugin is measured against.
 *
 * <h2>What the control is, in one paragraph</h2>
 *
 * <p>Resampling a picture at a fractional offset means working out what each new
 * pixel would have shown from the four pixels around it, and that averaging
 * smooths the picture very slightly - like reading a printed page through a sheet
 * of tracing paper. Smoothing lowers how much a pixel's brightness varies from
 * frame to frame, which is the number this plugin scores registration on. So a
 * method that did nothing at all except resample every frame would still look
 * like an improvement.
 *
 * <p>That is defect D11, and this class is the defence against it. The
 * <b>control</b> is the recording shifted by the <b>fractional part only</b> of
 * each frame's transform: always less than half a pixel, so no systematic drift
 * is taken out, but every frame goes through exactly the same resampling that the
 * registered recording went through. Scoring the registered recording against the
 * control rather than against the raw recording therefore measures holding the
 * field still, and nothing else.
 *
 * <pre>
 *   double fx = dx - Math.round(dx);      // fractional part ONLY
 *   double fy = dy - Math.round(dy);
 *   control[t] = warp(raw[t], fx, fy);    // same interpolator, same code path
 * </pre>
 *
 * <p><b>An identity warp is not a control.</b> A control whose every transform is
 * zero - or any whole number of pixels - takes the block-copy path below, does no
 * resampling at all, and re-opens the entire defect while looking like a control
 * in every table. {@link #requireAnInterpolatingControl} refuses it by name, with
 * the reason written out.
 *
 * <h2>The block-copy path, kept deliberately</h2>
 *
 * <p>A whole-pixel shift needs no resampling: the pixels are copied, row by row,
 * to their new place. That is not only much faster, it is <em>bit-exact</em> - a
 * registered 16-bit recording still holds exactly the original counts. The path
 * is kept, and {@code WarperExactnessTest} asserts both halves of that claim.
 *
 * <h2>Which thread</h2>
 *
 * <p>Everything here is a pure function of float arrays. Nothing touches ImageJ,
 * nothing is cached and nothing is shared, so a worker thread may call any of it.
 * The control planes are built one per frame, on the frame's own worker, inside
 * {@link Arbiter}'s single walk - which is what "parallel over frames" means for
 * this class.
 *
 * <p>Lifted from {@code logratio\core\Warper.java} in the Log-Ratio Registration
 * research repository, which is where the sign convention, the block-copy path
 * and the valid-margin arithmetic were written and tested. It is a resampler and
 * carries no registration criterion: shifting a picture is the same arithmetic
 * whichever method decided how far to shift it.
 */
public final class ControlWarp {

    /** Cubic convolution parameter. -0.5 is Catmull-Rom: interpolating, C1, no free ringing. */
    private static final double CUBIC_A = -0.5;

    /** A translation this close to a whole number of pixels is treated as one. */
    private static final double WHOLE_PIXEL_TOLERANCE = 1e-9;

    private ControlWarp() {
    }

    /** How a plane is resampled when the shift is not a whole number of pixels. */
    public enum Interpolation {

        /** Round to whole pixels and copy. Bit-exact, and it resamples nothing. */
        NONE("nearest neighbor"),

        /** Bilinear: the four surrounding pixels, weighted. What most engines use. */
        BILINEAR("bilinear"),

        /** Cubic convolution, Catmull-Rom. Sharper; can overshoot at hard edges. */
        BICUBIC("bicubic");

        private final String words;

        Interpolation(String words) {
            this.words = words;
        }

        /** The name a person reads, US English, for the saved record. */
        public String words() {
            return words;
        }
    }

    /**
     * Which of the two routes through {@link #warp} a shift takes.
     *
     * <p>Named rather than inferred, because the whole control argument rests on
     * the registered recording and the control taking the <em>same</em> one, and
     * "same code path" is a claim a test should be able to assert directly rather
     * than guess at from the pixels that came out.
     */
    public enum Path {

        /** Whole-pixel shift: rows copied, nothing resampled, bit-exact. */
        BLOCK_COPY,

        /** Fractional shift or a rotation: every output pixel is resampled. */
        RESAMPLED
    }

    // --------------------------------------------------------- the control itself

    /**
     * The fractional part of every transform, and nothing else.
     *
     * <p>Each entry keeps only what is left after the whole pixels are taken out,
     * so every value is in {@code (-0.5, 0.5]} along each axis. Rotation is
     * dropped: a fraction of a degree resamples in the same way a fraction of a
     * pixel does, and keeping it would let the control remove part of the
     * rotation the registration removed, which is exactly what a control must not
     * do.
     *
     * @param cumulative one transform per frame, as the registration applied them.
     *                   A {@code null} entry is read as no movement
     * @return a fresh array the same length. Never {@code null}
     */
    public static Transform[] fractionalOf(Transform[] cumulative) {
        if (cumulative == null) {
            throw new IllegalArgumentException("a control is built from the transforms the"
                    + " registration applied, and none were given");
        }
        Transform[] control = new Transform[cumulative.length];
        for (int t = 0; t < cumulative.length; t++) {
            Transform c = cumulative[t];
            if (c == null) {
                control[t] = Transform.IDENTITY;
                continue;
            }
            control[t] = Transform.translation(c.dx - Math.round(c.dx), c.dy - Math.round(c.dy));
        }
        return control;
    }

    /**
     * Refuses a control that would resample nothing.
     *
     * <p>The failure this catches looks like success: every table fills in, every
     * number is finite, and {@code sd_vs_control} silently becomes
     * {@code sd_vs_raw} - which flatters every method, including one that only
     * blurred. So it is refused here, by name, rather than left to be noticed in
     * the figures.
     *
     * @param control       what {@link #fractionalOf} produced
     * @param interpolation how the control will be resampled
     * @throws IllegalArgumentException naming the defect, when no frame of the
     *         control would be resampled
     */
    public static void requireAnInterpolatingControl(Transform[] control,
                                                     Interpolation interpolation) {
        if (control == null || control.length == 0) {
            throw new IllegalArgumentException("a control needs one transform per frame, and none"
                    + " were given. See defect D11");
        }
        for (int t = 0; t < control.length; t++) {
            if (pathFor(control[t], interpolation) == Path.RESAMPLED) return;
        }
        throw new IllegalArgumentException("This control does no interpolation at all: every one of"
                + " its " + control.length + " frames is a whole-pixel shift, which takes the"
                + " block-copy path and resamples nothing. An identity warp is not a control - it"
                + " leaves sd_vs_control measuring the raw recording, which is the defect the"
                + " control exists to close (D11). Either the registration this is scoring moved"
                + " nothing, or the transforms handed in have already been rounded to whole"
                + " pixels.");
    }

    /**
     * Which route this transform takes at this interpolation.
     *
     * <p>{@link Path#BLOCK_COPY} when there is no rotation and the translation is
     * a whole number of pixels, or when {@link Interpolation#NONE} was asked for
     * and the shift is rounded to whole pixels. Otherwise every output pixel is
     * resampled.
     */
    public static Path pathFor(Transform t, Interpolation interpolation) {
        if (t == null) return Path.BLOCK_COPY;
        if (interpolation == Interpolation.NONE && t.isPureTranslation()) return Path.BLOCK_COPY;
        if (t.isPureTranslation()
                && Math.abs(t.dx - Math.round(t.dx)) < WHOLE_PIXEL_TOLERANCE
                && Math.abs(t.dy - Math.round(t.dy)) < WHOLE_PIXEL_TOLERANCE) {
            return Path.BLOCK_COPY;
        }
        return Path.RESAMPLED;
    }

    // -------------------------------------------------------------------- warping

    /**
     * Shift one plane, into a fresh array.
     *
     * @see #warp(float[], float[], int, int, Transform, Interpolation, float)
     */
    public static float[] warp(float[] src, int width, int height, Transform t,
                               Interpolation interpolation, float fill) {
        float[] dst = new float[width * height];
        warp(src, dst, width, height, t, interpolation, fill);
        return dst;
    }

    /**
     * Shift one plane into {@code dst}, which is filled completely.
     *
     * <p><b>Sign convention, the same one the whole plugin uses.</b> A transform
     * describes the motion of the picture's <em>content</em>. Holding the field
     * still therefore means sampling frame {@code t} at {@code t(x)} to produce
     * output pixel {@code x} - so pass the transform the registration measured,
     * not its inverse. A feature that drifted right comes back to where it
     * started.
     *
     * @param t             the transform to sample through; {@code null} copies
     * @param interpolation how to resample a fractional shift
     * @param fill          what an output pixel with no source becomes
     * @throws IllegalArgumentException when either array is the wrong length
     */
    public static void warp(float[] src, float[] dst, int width, int height, Transform t,
                            Interpolation interpolation, float fill) {
        if (src == null || dst == null) {
            throw new IllegalArgumentException("a warp needs a source plane and a destination");
        }
        if (src.length != width * height || dst.length != width * height) {
            throw new IllegalArgumentException("plane arrays must be " + (width * height)
                    + " long for a " + width + "x" + height + " frame, and were " + src.length
                    + " and " + dst.length);
        }
        if (t == null) {
            System.arraycopy(src, 0, dst, 0, src.length);
            return;
        }
        if (pathFor(t, interpolation) == Path.BLOCK_COPY) {
            blockCopy(src, dst, width, height,
                    (int) Math.round(t.dx), (int) Math.round(t.dy), fill);
            return;
        }
        double[] out = new double[2];
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                t.apply(x, y, cx, cy, out);
                switch (interpolation) {
                    case NONE:
                        // A rotation cannot be reduced to a block copy, so "no interpolation" is
                        // honoured with nearest neighbor rather than quietly upgraded to bilinear.
                        dst[row + x] = nearest(src, width, height, out[0], out[1], fill);
                        break;
                    case BICUBIC:
                        dst[row + x] = bicubic(src, width, height, out[0], out[1], fill);
                        break;
                    case BILINEAR:
                    default:
                        dst[row + x] = bilinear(src, width, height, out[0], out[1], fill);
                        break;
                }
            }
        }
    }

    /**
     * Whole-pixel shift with no resampling. Output pixel {@code (x, y)} takes
     * source {@code (x + ix, y + iy)}; everything outside becomes {@code fill}.
     *
     * <p>Package-private so {@code WarperExactnessTest} can drive it directly and
     * compare it against the public path, which is how "the integer path is
     * bit-exact" is asserted rather than assumed.
     */
    static void blockCopy(float[] src, float[] dst, int width, int height, int ix, int iy,
                          float fill) {
        java.util.Arrays.fill(dst, fill);
        if (Math.abs(ix) >= width || Math.abs(iy) >= height) return;
        int y0 = Math.max(0, -iy);
        int y1 = Math.min(height, height - iy);
        int x0 = Math.max(0, -ix);
        int x1 = Math.min(width, width - ix);
        int len = x1 - x0;
        if (len <= 0) return;
        for (int y = y0; y < y1; y++) {
            System.arraycopy(src, (y + iy) * width + x0 + ix, dst, y * width + x0, len);
        }
    }

    private static float nearest(float[] a, int w, int h, double x, double y, float fill) {
        int ix = (int) Math.round(x);
        int iy = (int) Math.round(y);
        if (ix < 0 || iy < 0 || ix >= w || iy >= h) return fill;
        return a[iy * w + ix];
    }

    private static float bilinear(float[] a, int w, int h, double x, double y, float fill) {
        if (!(x >= 0 && y >= 0 && x <= w - 1 && y <= h - 1)) return fill;
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = x0 + 1 < w ? x0 + 1 : x0;
        int y1 = y0 + 1 < h ? y0 + 1 : y0;
        double fx = x - x0;
        double fy = y - y0;
        int r0 = y0 * w;
        int r1 = y1 * w;
        double top = a[r0 + x0] + fx * (a[r0 + x1] - a[r0 + x0]);
        double bot = a[r1 + x0] + fx * (a[r1 + x1] - a[r1 + x0]);
        return (float) (top + fy * (bot - top));
    }

    private static float bicubic(float[] a, int w, int h, double x, double y, float fill) {
        if (!(x >= 0 && y >= 0 && x <= w - 1 && y <= h - 1)) return fill;
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        double fx = x - ix;
        double fy = y - iy;
        double sum = 0;
        for (int m = -1; m <= 2; m++) {
            double wy = cubic(m - fy);
            if (wy == 0) continue;
            int yy = clamp(iy + m, h);
            double row = 0;
            for (int n = -1; n <= 2; n++) {
                row += cubic(n - fx) * a[yy * w + clamp(ix + n, w)];
            }
            sum += wy * row;
        }
        return (float) sum;
    }

    private static double cubic(double t) {
        double x = Math.abs(t);
        if (x < 1) {
            return ((CUBIC_A + 2) * x - (CUBIC_A + 3)) * x * x + 1;
        }
        if (x < 2) {
            return ((CUBIC_A * x - 5 * CUBIC_A) * x + 8 * CUBIC_A) * x - 4 * CUBIC_A;
        }
        return 0;
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }

    // -------------------------------------------------------------- the margin

    /**
     * The region that is real in every frame after warping.
     *
     * <p>Registration fills the strip a frame moved away from, so any statistic
     * computed there is measuring the fill rather than the sample. A recording
     * that drifts 6 px over its length leaves a genuine 6 px strip, not a
     * formality, and an arbiter that averaged over it would be scoring the fill
     * value against itself - which is perfectly still, and therefore looks like a
     * flawless registration.
     *
     * <p>Exact for pure translation. When a rotation is present the valid region
     * is not a rectangle, so each margin is padded by {@code |theta|} times the
     * half-diagonal: conservative, and it over-crops rather than admitting fill.
     */
    public static Margin validMargin(Transform[] cumulative, int width, int height,
                                     Interpolation interpolation) {
        int top = 0;
        int bottom = 0;
        int left = 0;
        int right = 0;
        // Resampling reads one pixel beyond the sample for bilinear and two for bicubic, so the
        // trustworthy region shrinks by that much on every side.
        int reach = interpolation == Interpolation.NONE ? 0
                : (interpolation == Interpolation.BILINEAR ? 1 : 2);
        double halfDiag = 0.5 * Math.hypot(width, height);
        if (cumulative != null) {
            for (int i = 0; i < cumulative.length; i++) {
                Transform t = cumulative[i];
                if (t == null) continue;
                int iy = (int) Math.round(t.dy);
                int ix = (int) Math.round(t.dx);
                int pad = reach + (int) Math.ceil(Math.abs(t.theta) * halfDiag);
                top = Math.max(top, Math.max(0, -iy) + pad);
                bottom = Math.max(bottom, Math.max(0, iy) + pad);
                left = Math.max(left, Math.max(0, -ix) + pad);
                right = Math.max(right, Math.max(0, ix) + pad);
            }
        }
        // Never crop away the whole frame, however wild the transforms.
        top = Math.min(top, Math.max(0, height / 2 - 1));
        bottom = Math.min(bottom, Math.max(0, height / 2 - 1));
        left = Math.min(left, Math.max(0, width / 2 - 1));
        right = Math.min(right, Math.max(0, width / 2 - 1));
        return new Margin(top, bottom, left, right);
    }

    /** Rows and columns that are real in every registered frame. */
    public static final class Margin {

        private final int top;
        private final int bottom;
        private final int left;
        private final int right;

        Margin(int top, int bottom, int left, int right) {
            this.top = top;
            this.bottom = bottom;
            this.left = left;
            this.right = right;
        }

        /** A margin of nothing: the whole frame is real. */
        public static Margin none() {
            return new Margin(0, 0, 0, 0);
        }

        public int top() {
            return top;
        }

        public int bottom() {
            return bottom;
        }

        public int left() {
            return left;
        }

        public int right() {
            return right;
        }

        /** True when nothing had to be cropped away. */
        public boolean isEmpty() {
            return top == 0 && bottom == 0 && left == 0 && right == 0;
        }

        public int croppedWidth(int width) {
            return Math.max(0, width - left - right);
        }

        public int croppedHeight(int height) {
            return Math.max(0, height - top - bottom);
        }

        /** How many pixels of a {@code width x height} frame are inside this margin. */
        public long croppedPixels(int width, int height) {
            return (long) croppedWidth(width) * croppedHeight(height);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Margin)) return false;
            Margin m = (Margin) other;
            return top == m.top && bottom == m.bottom && left == m.left && right == m.right;
        }

        @Override
        public int hashCode() {
            return ((top * 31 + bottom) * 31 + left) * 31 + right;
        }

        @Override
        public String toString() {
            return "margin[top=" + top + " bottom=" + bottom + " left=" + left
                    + " right=" + right + "]";
        }
    }
}
