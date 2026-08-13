/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;

/**
 * Synthetic frames with an exactly known displacement.
 *
 * <p>The frame is an <b>analytic</b> function of position - a sum of low-frequency sinusoids - so a
 * shifted frame is produced by evaluating the same function at shifted coordinates rather than by
 * resampling an image. That distinction is the whole point: resampling to build the fixture would
 * make every recovery test partly a test of the interpolator, and a sub-pixel result could not be
 * attributed to the estimator. Here the ground truth is exact at any real-valued shift.
 *
 * <p>The frequencies are low enough that decimation does not alias them, and the amplitude is chosen
 * so every pixel is comfortably positive.
 *
 * <p>Ported from {@code logratio\core\Synth.java} in the Log-Ratio Registration research repository,
 * without the pyramid helpers, which belong to a registration criterion this plugin does not carry.
 */
final class Synth {

    private Synth() {
    }

    /**
     * A frame whose <b>content has moved by {@code (dx, dy)}</b> relative to
     * {@code frame(w,h,0,0)}.
     *
     * <p>Note the minus signs. Moving content to the right means <em>sampling further left</em>: a
     * feature at {@code x} in the reference sits at {@code x + dx} here, so this pixel must show what
     * the reference showed at {@code x - dx}.
     */
    static float[] frame(int w, int h, double dx, double dy) {
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = (float) value(x - dx, y - dy, w, h);
            }
        }
        return out;
    }

    /**
     * The analytic image. Several incommensurate spatial frequencies in both axes so the
     * autocorrelation has one clear peak.
     */
    private static double value(double x, double y, int w, int h) {
        double u = 2 * Math.PI * x / w;
        double v = 2 * Math.PI * y / h;
        double s = 0;
        s += 1.00 * Math.sin(3 * u + 0.4) * Math.cos(2 * v - 0.7);
        s += 0.70 * Math.sin(5 * u - 1.1) * Math.cos(7 * v + 0.2);
        s += 0.45 * Math.cos(11 * u + 0.9) * Math.sin(6 * v + 1.3);
        s += 0.30 * Math.sin(13 * u + 2.1);
        s += 0.30 * Math.cos(9 * v - 0.5);
        return 2000.0 + 600.0 * s;
    }

    /** A wide, smooth Gaussian bump. High frame correlation, nothing to localise. */
    static float[] blob(int w, int h) {
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = (x - w / 2.0) / (0.45 * w);
                double dy = (y - h / 2.0) / (0.45 * h);
                out[y * w + x] = (float) (1000 + 2000 * Math.exp(-(dx * dx + dy * dy)));
            }
        }
        return out;
    }

    /** Flat: no structure at all, and the degenerate case the measure must not claim anything about. */
    static float[] flat(int w, int h, float level) {
        float[] out = new float[w * h];
        java.util.Arrays.fill(out, level);
        return out;
    }

    /** A {@link FrameSource} over a fixed list of planes, at a stated scale. */
    static FrameSource source(final int w, final int h, final Frames.Bin bin,
                              final float[]... planes) {
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
                return bin;
            }

            @Override
            public float[] plane(int frame) {
                return planes[frame].clone();
            }
        };
    }

    /** A {@link FrameSource} at native resolution. */
    static FrameSource source(int w, int h, float[]... planes) {
        return source(w, h, Frames.Bin.none(), planes);
    }

    /**
     * A hyperstack laid out the way ImageJ lays one out: channel fastest, then Z, then time.
     *
     * @param planes indexed {@code [channel][frame]}, one plane per timepoint per channel
     */
    static ImagePlus hyperstack(String title, int w, int h, float[][][] planes) {
        int channels = planes.length;
        int frames = planes[0].length;
        ImageStack stack = new ImageStack(w, h);
        for (int t = 0; t < frames; t++) {
            for (int c = 0; c < channels; c++) {
                stack.addSlice("c" + (c + 1) + "t" + (t + 1),
                        new FloatProcessor(w, h, planes[c][t].clone(), null));
            }
        }
        ImagePlus imp = new ImagePlus(title, stack);
        imp.setDimensions(channels, 1, frames);
        imp.setOpenAsHyperStack(channels > 1);
        return imp;
    }

    /** A plain single-channel stack: its slices are the time axis. */
    static ImagePlus stack(String title, int w, int h, float[]... planes) {
        ImageStack images = new ImageStack(w, h);
        for (int t = 0; t < planes.length; t++) {
            images.addSlice("t" + (t + 1), new FloatProcessor(w, h, planes[t].clone(), null));
        }
        return new ImagePlus(title, images);
    }

    /** The mean of a plane, skipping pixels marked as not measured. */
    static double mean(float[] plane) {
        double total = 0;
        int counted = 0;
        for (int i = 0; i < plane.length; i++) {
            if (Float.isNaN(plane[i])) continue;
            total += plane[i];
            counted++;
        }
        return counted == 0 ? Double.NaN : total / counted;
    }
}
