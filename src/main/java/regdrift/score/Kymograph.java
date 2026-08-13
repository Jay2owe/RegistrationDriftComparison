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
import ij.process.ByteProcessor;
import regdrift.diag.FrameSource;

/**
 * The before-and-after panel: one line of the picture, drawn once per frame,
 * stacked down the page.
 *
 * <p>A kymograph takes a single row of pixels and plots it against time - like
 * cutting one thin strip out of every frame of a film and gluing the strips
 * together in order. A feature that stays put draws a straight vertical stripe; a
 * feature that drifts draws a slanted one; a knock draws a step. Put the raw
 * recording beside the registered one and the difference between a slanted smear
 * and a set of straight lines is visible at a glance.
 *
 * <p><b>This is the one quality-control output that works as a still</b>, which
 * is why it is the figure panel for the README and the wiki page: a movie of a
 * registration cannot be put in a paper, and a plot of the shifts shows what the
 * plugin measured rather than what the pixels did.
 *
 * <h2>What it is not</h2>
 *
 * <p>It is not a measurement and nothing is ranked on it. It carries no axis, no
 * scale bar and no text, because drawing any of those would need the ImageJ
 * classes that open windows, and everything in this package is sealed against
 * them so that the plugin's Java entry point stays headless.
 *
 * <p>Ported from the kymograph panel in {@code logratio\ValidationRun.java}. The
 * research harness's file writing, downsampling and colour tables stayed there.
 */
public final class Kymograph {

    /** Columns of white between the two halves, so the join cannot be misread as data. */
    public static final int GAP = 4;

    /** Brightness is stretched between these two percentiles of the pixels shown. */
    private static final double LOW_PERCENTILE = 0.5;
    private static final double HIGH_PERCENTILE = 99.5;

    private Kymograph() {
    }

    /**
     * Build the panel.
     *
     * <p>The row drawn is the middle row of the valid margin, which is the part of
     * the frame that is real in every frame - a row through the strip a drifting
     * recording vacated would be a picture of the fill value.
     *
     * <p>Both halves are stretched with <b>one</b> pair of limits, taken from the
     * raw half, so the two sides are directly comparable. Stretching each half to
     * its own range would make a flatter registered half look brighter rather than
     * stiller.
     *
     * @param raw        the recording as it arrived. Read on the calling thread
     * @param registered the same recording after an engine ran
     * @param margin     the region real in every frame; pass
     *                   {@link ControlWarp.Margin#none()} for the whole frame
     * @param title      the image title, US English
     * @return an 8-bit image, {@code 2 * croppedWidth + GAP} across and one row
     *         per frame. Never {@code null}, never shown, never saved
     * @throws IllegalArgumentException when the two recordings do not match
     */
    public static ImagePlus beforeAndAfter(FrameSource raw, FrameSource registered,
                                           ControlWarp.Margin margin, String title) {
        if (raw == null || registered == null) {
            throw new IllegalArgumentException("a before-and-after panel needs both recordings");
        }
        int frames = raw.count();
        int width = raw.width();
        int height = raw.height();
        if (registered.count() != frames || registered.width() != width
                || registered.height() != height) {
            throw new IllegalArgumentException("the two recordings are different shapes, so they"
                    + " cannot be drawn side by side");
        }
        ControlWarp.Margin inside = margin == null ? ControlWarp.Margin.none() : margin;
        int croppedWidth = inside.croppedWidth(width);
        int croppedHeight = inside.croppedHeight(height);
        if (croppedWidth <= 0 || croppedHeight <= 0 || frames <= 0) {
            throw new IllegalArgumentException("nothing is real in every frame, so there is no row"
                    + " to draw");
        }
        int row = inside.top() + croppedHeight / 2;

        float[][] before = new float[frames][];
        float[][] after = new float[frames][];
        for (int t = 0; t < frames; t++) {
            before[t] = line(raw.plane(t), width, row, inside.left(), croppedWidth);
            after[t] = line(registered.plane(t), width, row, inside.left(), croppedWidth);
        }

        double[] limits = limits(before);
        int panelWidth = 2 * croppedWidth + GAP;
        ByteProcessor panel = new ByteProcessor(panelWidth, frames);
        for (int t = 0; t < frames; t++) {
            for (int x = 0; x < croppedWidth; x++) {
                panel.set(x, t, grey(before[t][x], limits[0], limits[1]));
                panel.set(croppedWidth + GAP + x, t, grey(after[t][x], limits[0], limits[1]));
            }
            for (int x = 0; x < GAP; x++) {
                panel.set(croppedWidth + x, t, 255);
            }
        }
        ImageStack stack = new ImageStack(panelWidth, frames);
        stack.addSlice("raw | registered", panel);
        return new ImagePlus(title == null || title.trim().isEmpty()
                ? "Kymograph before and after" : title, stack);
    }

    /** One row of one frame, cropped to the valid margin. */
    private static float[] line(float[] plane, int width, int row, int left, int croppedWidth) {
        float[] out = new float[croppedWidth];
        System.arraycopy(plane, row * width + left, out, 0, croppedWidth);
        return out;
    }

    /** The two brightnesses black and white are pinned to. */
    private static double[] limits(float[][] rows) {
        int total = 0;
        for (int t = 0; t < rows.length; t++) total += rows[t].length;
        float[] all = new float[total];
        int at = 0;
        for (int t = 0; t < rows.length; t++) {
            for (int x = 0; x < rows[t].length; x++) {
                float v = rows[t][x];
                if (Float.isNaN(v)) continue;
                all[at++] = v;
            }
        }
        if (at == 0) return new double[]{0, 1};
        float[] sorted = java.util.Arrays.copyOf(all, at);
        java.util.Arrays.sort(sorted);
        double low = sorted[index(sorted.length, LOW_PERCENTILE)];
        double high = sorted[index(sorted.length, HIGH_PERCENTILE)];
        return new double[]{low, high > low ? high : low + 1};
    }

    private static int index(int length, double percentile) {
        int k = (int) Math.round(percentile / 100.0 * (length - 1));
        return Math.max(0, Math.min(length - 1, k));
    }

    /**
     * One brightness as an 8-bit grey, with a mild gamma so faint structure stays
     * visible when the panel is printed rather than scrolled.
     */
    private static int grey(double value, double low, double high) {
        if (Double.isNaN(value)) return 0;
        double f = (value - low) / Math.max(1e-9, high - low);
        f = Math.pow(Math.max(0, Math.min(1, f)), 0.7);
        return (int) Math.round(255 * f);
    }
}
