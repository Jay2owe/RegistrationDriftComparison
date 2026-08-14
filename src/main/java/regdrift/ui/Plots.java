/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import ij.gui.Plot;
import regdrift.Trace;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.util.List;

/**
 * Turns the curves a run measured into ImageJ plots.
 *
 * <p>The split matters and is worth stating: a run hands back
 * {@link Trace} objects, which are numbers and axis labels, and this is the
 * single place they become a window. {@code RegDrift.run} cannot open a plot -
 * that is asserted against its compiled form - so a headless caller, a macro
 * with {@code hide_display} set and a batch loop all get the same numbers and
 * nothing appears.
 *
 * <p>Which curves are drawn without being asked for is the trace's own answer,
 * not this class's: the motion trace is what somebody came for, and the
 * intensity trend is the check they go looking for when a fitted number reads
 * oddly.
 */
public final class Plots {

    /** The first curve of a trace, which is the one a reader looks at. */
    private static final Color PRIMARY = new Color(0x1f, 0x77, 0xb4);

    /** The second, and the third. */
    private static final Color SECOND = new Color(0xd6, 0x27, 0x28);
    private static final Color THIRD = new Color(0x2c, 0xa0, 0x2c);

    private Plots() {
    }

    /**
     * Draws one trace.
     *
     * @param trace what was measured. Never null
     * @return the plot, ready to be shown or saved. Never null
     */
    public static Plot build(Trace trace) {
        if (trace == null) {
            throw new IllegalArgumentException("a plot is drawn from a measured curve, and none"
                    + " was given");
        }
        Plot plot = new Plot(trace.title(), trace.xLabel(), trace.yLabel());
        List<Trace.Series> series = trace.series();
        StringBuilder legend = new StringBuilder();
        for (int i = 0; i < series.size(); i++) {
            Trace.Series one = series.get(i);
            plot.setColor(colorFor(i));
            plot.addPoints(one.x(), one.y(), shapeFor(trace, i));
            if (legend.length() > 0) legend.append('\n');
            legend.append(one.name());
        }
        plot.setColor(Color.BLACK);
        if (series.size() > 1) plot.addLegend(legend.toString());
        // The trace's note is a sentence rather than a caption - what was measured, over which
        // frames, at what scale. It goes in the results view and in the saved record, where there
        // is room for it, rather than across the middle of a plot.
        return plot;
    }

    /**
     * Draws one trace and puts it on the screen.
     *
     * <p>Does nothing at all where there is no screen, so a run on a machine
     * without one is not a run that throws.
     */
    public static void show(Trace trace) {
        if (trace == null || GraphicsEnvironment.isHeadless()) return;
        build(trace).show();
    }

    /**
     * Draws every trace a run said should be drawn.
     *
     * @param traces what the run measured, in the order it produced them
     */
    public static void showDefaults(List<Trace> traces) {
        if (traces == null || GraphicsEnvironment.isHeadless()) return;
        for (Trace trace : traces) {
            if (trace.shownByDefault()) show(trace);
        }
    }

    /**
     * How a curve is drawn: a line where the points are consecutive, and separate
     * marks where they are not.
     *
     * <p>A gap in a sampled measurement joined by a line claims a reading nobody
     * took, which is what the second curve of a windowed motion trace is - steps
     * across the gaps between windows, drawn as crosses so they cannot be read as
     * a continuous path.
     */
    private static int shapeFor(Trace trace, int index) {
        if (trace.kind() == Trace.Kind.MOTION && trace.series().size() > 1 && index == 1
                && "step across a gap between windows".equals(trace.series().get(1).name())) {
            return Plot.CROSS;
        }
        return Plot.LINE;
    }

    private static Color colorFor(int index) {
        if (index == 0) return PRIMARY;
        if (index == 1) return SECOND;
        return THIRD;
    }
}
