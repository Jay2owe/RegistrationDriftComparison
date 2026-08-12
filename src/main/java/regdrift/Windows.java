/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import java.util.Locale;

/**
 * How many stretches of consecutive frames the movement is measured over.
 *
 * <p>Measuring every consecutive frame pair of a long recording is the honest
 * thing to do and takes 30-100 seconds on a 500-frame recording, which is far
 * too slow for the first thing anybody runs. Instead a few stretches of
 * consecutive frames are measured, evenly spaced across the recording, plus one
 * pair bridging each gap between them. Think of reading three paragraphs spread
 * through a book rather than skimming every third word: the sentences you do
 * read are whole ones.
 *
 * <p><b>{@link #AUTO_COUNT} is three, and it is a measurement, not a guess.</b>
 * Six configurations were run against every entry of the twelve-recording
 * library on 2026-08-12; three windows of twelve frames was the one that
 * reproduced the whole-recording movement label on all twelve. Four windows
 * measures <em>more</em> pairs and reproduces eleven, because each extra window
 * adds a join and the error at the joins grows faster than the extra coverage
 * removes it. Anything that changes this number should have to beat that run.
 *
 * <p>{@link #ALL_PAIRS} is the escape hatch, spelled {@code windows=0}. It means
 * measure every consecutive pair in the recording - the opposite of no windows
 * at all, which is why the constant is named for what it does.
 */
public final class Windows {

    /**
     * Windows used when the option is left at {@code auto}.
     *
     * <p>Three. Measured, see the class note.
     */
    public static final int AUTO_COUNT = 3;

    /**
     * The count that means "measure every consecutive pair", spelled
     * {@code windows=0} in a macro.
     */
    public static final int ALL_PAIRS = 0;

    /** The macro value {@link #auto()} is written as. */
    public static final String AUTO_VALUE = "auto";

    private static final Windows AUTO = new Windows(true, AUTO_COUNT);
    private static final Windows EVERY_PAIR = new Windows(false, ALL_PAIRS);

    private final boolean auto;
    private final int count;

    private Windows(boolean auto, int count) {
        this.auto = auto;
        this.count = count;
    }

    /** Let the measured default decide, which is {@link #AUTO_COUNT}. */
    public static Windows auto() {
        return AUTO;
    }

    /** Measure every consecutive pair in the recording. */
    public static Windows allPairs() {
        return EVERY_PAIR;
    }

    /**
     * A stated number of windows. Zero means {@link #allPairs()}, which is what
     * {@code windows=0} means in a macro.
     *
     * @throws IllegalArgumentException when the count is negative
     */
    public static Windows of(int count) {
        if (count == ALL_PAIRS) return EVERY_PAIR;
        if (count < 0) {
            throw new IllegalArgumentException("Macro option 'windows' must be '" + AUTO_VALUE
                    + "', 0 for every consecutive pair, or a positive number of windows (windows="
                    + count + ").");
        }
        return new Windows(false, count);
    }

    /** True when the count has been left to the measured default. */
    public boolean isAuto() {
        return auto;
    }

    /** True when every consecutive pair is to be measured. */
    public boolean isAllPairs() {
        return !auto && count == ALL_PAIRS;
    }

    /**
     * The window count this resolves to: {@link #AUTO_COUNT} when auto,
     * {@link #ALL_PAIRS} when every pair is wanted, and the stated count
     * otherwise. Check {@link #isAllPairs()} before treating the number as a
     * count of windows.
     */
    public int resolvedCount() {
        return auto ? AUTO_COUNT : count;
    }

    /** The text this is spelled with in a macro. */
    public String toMacroValue() {
        return auto ? AUTO_VALUE : Integer.toString(count);
    }

    /**
     * Reads a {@code windows} macro value: {@code auto}, {@code 0}, or a count.
     *
     * @throws IllegalArgumentException naming the option and the value
     */
    public static Windows parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (AUTO_VALUE.equals(trimmed.toLowerCase(Locale.ROOT))) return AUTO;
        try {
            return of(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Macro option 'windows' must be '" + AUTO_VALUE
                    + "', 0 for every consecutive pair, or a number of windows (windows='"
                    + trimmed + "').", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Windows)) return false;
        Windows that = (Windows) other;
        return auto == that.auto && count == that.count;
    }

    @Override
    public int hashCode() {
        return (auto ? 1 : 0) * 31 + count;
    }

    @Override
    public String toString() {
        return "windows=" + toMacroValue();
    }
}
