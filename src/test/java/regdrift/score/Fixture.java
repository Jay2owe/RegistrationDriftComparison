/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.score;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import regdrift.diag.FrameSource;
import regdrift.diag.Frames;
import regdrift.internal.Transform;

/**
 * Recordings with a drift that is known exactly, for the arbiter's tests.
 *
 * <p>The raw recording is cut out of one large textured field at a moving,
 * <b>fractional</b> offset, so the displacement between any two frames is exact by
 * construction and the fractional part is genuinely there - which is the whole
 * point, since a control that resamples nothing is the defect these tests exist to
 * catch.
 *
 * <p>It is a separate fixture from {@code regdrift.diag.Synth} rather than a
 * shared one. That one builds frames an estimator has to read; this one builds
 * frames whose displacement is <em>given</em>, and merging the two would mean a
 * helper with a flag for each caller.
 */
final class Fixture {

    private Fixture() {
    }

    /** A smoothed random field: structure at every scale, and no formula to be in sympathy with. */
    static float[] texture(int w, int h, long seed, int passes) {
        java.util.Random rng = new java.util.Random(seed);
        float[] f = new float[w * h];
        for (int i = 0; i < f.length; i++) f[i] = (float) (1200 + 300 * rng.nextGaussian());
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

    /**
     * A {@code w x h} window sampled out of a larger field at a real-valued
     * origin.
     *
     * <p>Output pixel {@code (x, y)} shows the field at {@code (x + ox, y + oy)},
     * so moving the origin by {@code +d} moves the content by {@code -d} - the
     * same sign convention {@link ControlWarp} uses.
     */
    static float[] sampled(float[] field, int fieldWidth, int fieldHeight,
                           double ox, double oy, int w, int h) {
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = bilinear(field, fieldWidth, fieldHeight, x + ox, y + oy);
            }
        }
        return out;
    }

    private static float bilinear(float[] a, int w, int h, double x, double y) {
        double cx = Math.max(0, Math.min(w - 1.0, x));
        double cy = Math.max(0, Math.min(h - 1.0, y));
        int x0 = (int) cx;
        int y0 = (int) cy;
        int x1 = x0 + 1 < w ? x0 + 1 : x0;
        int y1 = y0 + 1 < h ? y0 + 1 : y0;
        double fx = cx - x0;
        double fy = cy - y0;
        double top = a[y0 * w + x0] + fx * (a[y0 * w + x1] - a[y0 * w + x0]);
        double bot = a[y1 * w + x0] + fx * (a[y1 * w + x1] - a[y1 * w + x0]);
        return (float) (top + fy * (bot - top));
    }

    /**
     * A recording that drifts by {@code (stepX, stepY)} per frame, and the
     * transforms that describe it.
     *
     * <p>The steps are deliberately not whole numbers: a whole-pixel drift takes
     * the block-copy path, resamples nothing, and would leave the control an
     * identity warp.
     */
    static Drifting drifting(int side, int frames, double stepX, double stepY, long seed) {
        int field = side + 64;
        float[] world = texture(field, field, seed, 3);
        float[][] raw = new float[frames][];
        Transform[] cumulative = new Transform[frames];
        for (int t = 0; t < frames; t++) {
            double ox = 32 + stepX * t;
            double oy = 32 + stepY * t;
            raw[t] = sampled(world, field, field, ox, oy, side, side);
            cumulative[t] = Transform.translation(-stepX * t, -stepY * t);
        }
        return new Drifting(side, raw, cumulative);
    }

    /** A raw recording, the transforms that describe its drift, and what can be made from them. */
    static final class Drifting {

        final int side;
        final float[][] raw;
        final Transform[] cumulative;

        Drifting(int side, float[][] raw, Transform[] cumulative) {
            this.side = side;
            this.raw = raw;
            this.cumulative = cumulative;
        }

        FrameSource rawSource() {
            return source(side, side, raw);
        }

        /** The recording actually registered: every frame warped back by its own transform. */
        FrameSource registeredSource(ControlWarp.Interpolation interpolation) {
            float[][] out = new float[raw.length][];
            for (int t = 0; t < raw.length; t++) {
                out[t] = ControlWarp.warp(raw[t], side, side, cumulative[t], interpolation, 0f);
            }
            return source(side, side, out);
        }

        /**
         * A recording that was <b>only blurred</b>: every frame resampled by the
         * fractional part of its transform and by nothing else, which is exactly
         * what the control is. Scored against the control it must come out at
         * zero, and scored against the raw recording it would come out looking
         * like a good registration. That difference is defect D11.
         */
        FrameSource blurredOnlySource(ControlWarp.Interpolation interpolation) {
            Transform[] fractional = ControlWarp.fractionalOf(cumulative);
            float[][] out = new float[raw.length][];
            for (int t = 0; t < raw.length; t++) {
                out[t] = ControlWarp.warp(raw[t], side, side, fractional[t], interpolation, 0f);
            }
            return source(side, side, out);
        }

        ImagePlus rawStack(String title) {
            return stack(title, side, side, raw);
        }

        ImagePlus registeredStack(String title, ControlWarp.Interpolation interpolation) {
            float[][] out = new float[raw.length][];
            for (int t = 0; t < raw.length; t++) {
                out[t] = ControlWarp.warp(raw[t], side, side, cumulative[t], interpolation, 0f);
            }
            return stack(title, side, side, out);
        }
    }

    /** A {@link FrameSource} over a fixed list of planes, at native resolution. */
    static FrameSource source(final int w, final int h, final float[][] planes) {
        return new FrameSource() {
            @Override
            public int count() {
                return planes.length;
            }

            @Override
            public int width() {
                return w;
            }

            @Override
            public int height() {
                return h;
            }

            @Override
            public Frames.Bin bin() {
                return Frames.Bin.none();
            }

            @Override
            public float[] plane(int frame) {
                return planes[frame].clone();
            }
        };
    }

    /** A plain single-channel stack: its slices are the time axis. */
    static ImagePlus stack(String title, int w, int h, float[][] planes) {
        ImageStack images = new ImageStack(w, h);
        for (int t = 0; t < planes.length; t++) {
            images.addSlice("t" + (t + 1), new FloatProcessor(w, h, planes[t].clone(), null));
        }
        return new ImagePlus(title, images);
    }
}
