/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One curve a run measured, as numbers rather than as a picture.
 *
 * <p>The everyday version: a run hands back the readings, and something else
 * decides whether to draw them. {@link RegDrift#run} cannot open a plot window -
 * that is asserted from its compiled form - so what it produces is this, and the
 * menu entry turns it into an {@code ij.gui.Plot} when a person is there to look
 * at one. A headless caller gets the same numbers and draws nothing.
 *
 * <p>That split has a second use beyond keeping the facade quiet: the numbers
 * behind a plot can be asserted in a test on a machine with no display, so what
 * the curve says is checked rather than the fact that a window appeared.
 *
 * <p>Every trace names its own axes and carries {@link #shownByDefault()}, which
 * is the published default for that kind of plot rather than a preference: the
 * motion trace is drawn in the measuring modes and the intensity trend is not,
 * because one of them is what somebody came for and the other is a check they
 * ask for when a number looks odd.
 */
public final class Trace {

    /** Which curve this is, so a caller can pick one out without matching a title. */
    public enum Kind {

        /**
         * How far the recording moved between the frames that were measured.
         *
         * <p>In the measuring modes this is the step size at each sampled frame
         * pair, because a windowed sample has no continuous chain to draw. Where a
         * whole-recording chain exists - anything that scored an arm - it is the
         * cumulative displacement instead, and {@link #yLabel()} says which.
         */
        MOTION,

        /**
         * Mean brightness against frame number, in log2 units.
         *
         * <p>The points {@code log2_trend} was fitted through, so a fitted number
         * can be looked at rather than taken on trust.
         */
        INTENSITY
    }

    /** One named curve inside a trace: two arrays of the same length. */
    public static final class Series {

        private final String name;
        private final double[] x;
        private final double[] y;

        /**
         * @param name what the legend calls this curve
         * @param x    the horizontal readings
         * @param y    the vertical readings, one per {@code x}
         */
        public Series(String name, double[] x, double[] y) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("a plotted curve needs a name");
            }
            if (x == null || y == null || x.length != y.length) {
                throw new IllegalArgumentException("a plotted curve needs one vertical reading per"
                        + " horizontal one, and '" + name + "' was given "
                        + (x == null ? "none" : Integer.toString(x.length)) + " and "
                        + (y == null ? "none" : Integer.toString(y.length)));
            }
            this.name = name.trim();
            this.x = x.clone();
            this.y = y.clone();
        }

        /** What the legend calls this curve. */
        public String name() {
            return name;
        }

        /** The horizontal readings. */
        public double[] x() {
            return x.clone();
        }

        /** The vertical readings. */
        public double[] y() {
            return y.clone();
        }

        /** How many points this curve holds. */
        public int size() {
            return x.length;
        }
    }

    private final Kind kind;
    private final String title;
    private final String xLabel;
    private final String yLabel;
    private final List<Series> series;
    private final boolean shownByDefault;
    private final String note;

    private Trace(Kind kind, String title, String xLabel, String yLabel, List<Series> series,
                  boolean shownByDefault, String note) {
        this.kind = kind;
        this.title = title;
        this.xLabel = xLabel;
        this.yLabel = yLabel;
        this.series = Collections.unmodifiableList(new ArrayList<Series>(series));
        this.shownByDefault = shownByDefault;
        this.note = note;
    }

    /**
     * A trace of one or more curves.
     *
     * @param kind           which curve this is
     * @param title          the window title, which is also what a saved file is
     *                       named after
     * @param xLabel         the horizontal axis label
     * @param yLabel         the vertical axis label, which states the units
     * @param series         the curves, at least one
     * @param shownByDefault whether this is drawn without being asked for
     * @param note           one sentence under the plot saying what was measured,
     *                       or empty
     */
    public static Trace of(Kind kind, String title, String xLabel, String yLabel,
                           List<Series> series, boolean shownByDefault, String note) {
        if (kind == null) throw new IllegalArgumentException("a trace needs to say which curve"
                + " it is");
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("a trace needs a title");
        }
        if (series == null || series.isEmpty()) {
            throw new IllegalArgumentException("a trace with no curve in it is an empty window,"
                    + " not a measurement; leave it out instead");
        }
        return new Trace(kind, title.trim(), label(xLabel), label(yLabel), series, shownByDefault,
                note == null ? "" : note.trim());
    }

    /** Which curve this is. */
    public Kind kind() {
        return kind;
    }

    /** The window title. */
    public String title() {
        return title;
    }

    /** The horizontal axis label. */
    public String xLabel() {
        return xLabel;
    }

    /** The vertical axis label, which states the units. */
    public String yLabel() {
        return yLabel;
    }

    /** The curves, in the order they are drawn. Never empty. */
    public List<Series> series() {
        return series;
    }

    /** Whether this is drawn without being asked for. */
    public boolean shownByDefault() {
        return shownByDefault;
    }

    /** One sentence saying what was measured, or empty. */
    public String note() {
        return note;
    }

    /** How many points the largest curve holds. */
    public int size() {
        int largest = 0;
        for (Series one : series) largest = Math.max(largest, one.size());
        return largest;
    }

    @Override
    public String toString() {
        return kind + " '" + title + "', " + series.size() + " curve"
                + (series.size() == 1 ? "" : "s") + ", " + size() + " points";
    }

    private static String label(String value) {
        return value == null ? "" : value.trim();
    }
}
