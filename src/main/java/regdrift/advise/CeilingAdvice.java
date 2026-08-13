/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.advise;

import java.util.Locale;

/**
 * What this plugin says about excluding a frame's brightest pixels, and the
 * warning that always says it with it.
 *
 * <p><b>Defect D4, and it is the one place in this plugin where the tempting
 * thing is the wrong thing.</b> Read this note before touching anything here.
 *
 * <h2>Why it is tempting</h2>
 *
 * <p>Marking every pixel above the 90th percentile of its own frame invalid,
 * before anything else looks at the frame, takes the benchmark's worst column
 * from 4.755 px of median error to 0.055 px - a factor of 86 - and runs 35%
 * faster while doing it. Both numbers are in the bundled
 * {@code saturation_2026-08-11.csv} and {@code benchmark_2026-08-11.csv} and
 * {@code CeilingAdviceTest} recomputes them from those files rather than trusting
 * this sentence. A setting that improves a result by 86x and costs negative time
 * is a setting somebody will switch on by default.
 *
 * <h2>Why it is wrong</h2>
 *
 * <p>It works for one reason: in those recordings the bright thing <em>is</em>
 * the artefact, and it covers a known tenth of the field. Cut at the 90th
 * percentile and the artefact is exactly what goes. Change either half of that
 * sentence and the result inverts:
 *
 * <ul>
 *   <li><b>Where the sample is itself the brightest tenth</b> - any fluorescence
 *       recording of cell bodies - the same cut deletes the sample. All three
 *       benchmark seeds are phase contrast, so <b>the benchmark cannot see that
 *       failure</b>. It was measured separately, on one real fluorescence frame
 *       that is not part of this bundled calibration, and there the same cut
 *       made the clean and the fading figures worse rather than better.</li>
 *   <li><b>Where the cut does not reach the artefact</b> it is not a partial
 *       win, it is a loss. Measured on this same fixture: cutting nothing gives
 *       4.755 px, cutting at the 99th percentile gives 4.786 px and at the
 *       99.5th 4.507 px - no change worth having, while real signal is thrown
 *       away - and cutting at the 95th, which removes half the artefact, still
 *       gives 1.312 px. There is no partial credit; the whole question is
 *       whether the cut reaches the artefact.</li>
 * </ul>
 *
 * <h2>So there is no setting</h2>
 *
 * <p>This class produces a <b>sentence</b>. There is no method anywhere in this
 * plugin that switches a ceiling on, no macro option that does, and
 * {@code bright_fraction} is never converted into a percentile that anything
 * acts on. {@code CeilingAdviceTest} asserts that from compiled bytecode across
 * every package, so a later contributor who adds one will find out from the
 * build rather than from a user whose fluorescence recording came back empty.
 *
 * <p>Every string this class returns carries {@link #CONTRAINDICATION}. Advice
 * that travelled without its warning would be worse than no advice, because it
 * would read as a recommendation.
 */
public final class CeilingAdvice {

    /**
     * The share of a frame below which the implied cut is shallower than
     * anything the benchmark measured working.
     *
     * <p>One in a hundred pixels implies a cut at the 99th percentile, and the
     * 99th and 99.5th percentiles are precisely where the sweep measured no
     * useful change against cutting nothing at all.
     */
    public static final double SHALLOWEST_USEFUL_SHARE = 0.01;

    /** Below this share there is nothing standing out to describe. */
    public static final double NOTHING_STANDS_OUT = 1e-6;

    /**
     * The warning that goes with every sentence this class produces.
     *
     * <p>Written once, here, so that the dialog, the saved notes and any later
     * results panel cannot end up carrying the advice without it.
     */
    public static final String CONTRAINDICATION =
            "This is a description, not a setting: nothing in this plugin switches an intensity"
                    + " ceiling on, and this measurement was taken on three phase-contrast"
                    + " recordings in which the bright structure was debris rather than the sample."
                    + " Where the sample is itself the brightest part of the frame - any"
                    + " fluorescence recording of cell bodies - the same cut removes the sample,"
                    + " and the three seeds behind this figure are all phase contrast, so this"
                    + " calibration cannot see that failure. Cutting at the 99th or 99.5th"
                    + " percentile was measured to be no improvement on cutting nothing at all,"
                    + " because it strips real signal and leaves the artefact behind. Set a ceiling"
                    + " by hand, in whichever tool you register with, when you can see the artefact"
                    + " and can say what share of the frame it covers.";

    private CeilingAdvice() {
    }

    /**
     * What to say about a recording whose frames put this share of their pixels
     * far above the rest.
     *
     * @param brightFraction the {@code bright_fraction} column: the share of a
     *                       frame sitting far above the dim half of its own
     *                       intensity distribution. {@link Double#NaN} when it
     *                       could not be measured
     * @return a finished sentence, always ending in {@link #CONTRAINDICATION}.
     *         Never empty and never null
     */
    public static String text(double brightFraction) {
        if (Double.isNaN(brightFraction)) {
            return "This recording's bright share could not be measured, so there is nothing to"
                    + " say about an intensity ceiling on it. " + CONTRAINDICATION;
        }
        if (brightFraction < NOTHING_STANDS_OUT) {
            return "No structure in these frames stands far above the rest of the frame, so there"
                    + " is nothing an intensity ceiling would exclude here. " + CONTRAINDICATION;
        }
        double percent = 100.0 * brightFraction;
        double percentile = 100.0 - percent;
        if (brightFraction < SHALLOWEST_USEFUL_SHARE) {
            return String.format(Locale.US,
                    "A bright structure covers about %s of each frame. A ceiling that reached it"
                            + " would sit near the %s percentile, and cuts that shallow were"
                            + " measured to be no improvement on cutting nothing at all, so there"
                            + " is nothing to advise here. %s",
                    share(percent), ordinal(percentile), CONTRAINDICATION);
        }
        return String.format(Locale.US,
                "A bright structure covers about %s of each frame. A ceiling near the %s percentile"
                        + " of each frame's own intensities would exclude it. %s",
                share(percent), ordinal(percentile), CONTRAINDICATION);
    }

    /**
     * True when this recording has a bright structure large enough that a
     * ceiling reaching it is something the benchmark measured.
     *
     * <p>A caller deciding whether to give the sentence a line of its own. The
     * sentence is produced either way - see {@link #text} - because "nothing
     * stands out here" is itself worth reading beside a column that says 0.
     */
    public static boolean worthShowing(double brightFraction) {
        return !Double.isNaN(brightFraction) && brightFraction >= SHALLOWEST_USEFUL_SHARE;
    }

    /** A percentage with as few decimals as it needs, up to two. */
    private static String share(double percent) {
        if (percent >= 10 || percent == Math.rint(percent)) {
            return String.format(Locale.US, "%.0f%%", percent);
        }
        if (percent >= 1) return String.format(Locale.US, "%.1f%%", percent);
        return String.format(Locale.US, "%.2f%%", percent);
    }

    /**
     * A percentile as a person names one: {@code 90th}, {@code 99.5th}.
     *
     * <p>Rounded to a tenth, because that is the resolution the sweep in
     * {@code saturation_2026-08-11.csv} was run at and a ceiling quoted to four
     * decimals would claim a precision the evidence has not got.
     */
    private static String ordinal(double percentile) {
        double tenths = Math.rint(percentile * 10.0) / 10.0;
        if (tenths != Math.rint(tenths)) {
            return String.format(Locale.US, "%.1fth", tenths);
        }
        long whole = (long) Math.rint(tenths);
        long lastTwo = Math.abs(whole) % 100;
        if (lastTwo >= 11 && lastTwo <= 13) return whole + "th";
        switch ((int) (Math.abs(whole) % 10)) {
            case 1:
                return whole + "st";
            case 2:
                return whole + "nd";
            case 3:
                return whole + "rd";
            default:
                return whole + "th";
        }
    }
}
