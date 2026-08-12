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
 * Which channel the movement is measured on: a 1-based index, or {@link #AUTO}.
 *
 * <p>{@code AUTO} means the choice has not been made yet, not that it does not
 * matter. The run ranks the channels and writes the one it picked, and why, into
 * the provenance record, so a diagnosis can be repeated deliberately by naming
 * the index the automatic ranking chose.
 *
 * <p>A small value type rather than a bare {@code int} so that "not chosen yet"
 * and "channel 0" cannot be confused: there is no channel 0, and every accessor
 * here is 1-based, matching what ImageJ shows the user.
 */
public final class Channel {

    /** Let the channel ranking choose. */
    public static final Channel AUTO = new Channel(0);

    /** The macro value {@link #AUTO} is written as. */
    public static final String AUTO_VALUE = "auto";

    private final int index;

    private Channel(int index) {
        this.index = index;
    }

    /**
     * A specific channel, counting from 1 as ImageJ does.
     *
     * @throws IllegalArgumentException when the index is below 1
     */
    public static Channel of(int oneBasedIndex) {
        if (oneBasedIndex < 1) {
            throw new IllegalArgumentException("Macro option 'channel' must be '" + AUTO_VALUE
                    + "' or a channel number of 1 or more (channel=" + oneBasedIndex + ").");
        }
        return new Channel(oneBasedIndex);
    }

    /** True when the channel is still to be chosen by the ranking. */
    public boolean isAuto() {
        return index == 0;
    }

    /** The 1-based channel index, or {@code 0} when this is {@link #AUTO}. */
    public int index() {
        return index;
    }

    /** The text this channel is spelled with in a macro. */
    public String toMacroValue() {
        return isAuto() ? AUTO_VALUE : Integer.toString(index);
    }

    /**
     * Reads a {@code channel} macro value: {@code auto}, or a number.
     *
     * @throws IllegalArgumentException naming the option and the value
     */
    public static Channel parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (AUTO_VALUE.equals(trimmed.toLowerCase(Locale.ROOT))) return AUTO;
        try {
            return of(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Macro option 'channel' must be '" + AUTO_VALUE
                    + "' or a channel number (channel='" + trimmed + "').", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Channel)) return false;
        return index == ((Channel) other).index;
    }

    @Override
    public int hashCode() {
        return index;
    }

    @Override
    public String toString() {
        return "channel=" + toMacroValue();
    }
}
