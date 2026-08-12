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
 * Which Z slice the movement is measured on: a 1-based index, or
 * {@link #PROJECT} for a maximum projection through Z.
 *
 * <p>Projection is the default because a projection is what most people are
 * looking at when they notice their recording moves, and because one plane of a
 * thick sample can drift through focus while the field itself is still.
 *
 * <p>Same shape as {@link Channel}: 1-based, with {@code 0} reserved for
 * "project", so a plain {@code int} cannot quietly mean both.
 */
public final class Slice {

    /** Measure on a maximum projection through Z. */
    public static final Slice PROJECT = new Slice(0);

    /** The macro value {@link #PROJECT} is written as. */
    public static final String PROJECT_VALUE = "project";

    private final int index;

    private Slice(int index) {
        this.index = index;
    }

    /**
     * A specific Z slice, counting from 1 as ImageJ does.
     *
     * @throws IllegalArgumentException when the index is below 1
     */
    public static Slice of(int oneBasedIndex) {
        if (oneBasedIndex < 1) {
            throw new IllegalArgumentException("Macro option 'slice' must be '" + PROJECT_VALUE
                    + "' or a slice number of 1 or more (slice=" + oneBasedIndex + ").");
        }
        return new Slice(oneBasedIndex);
    }

    /** True when the estimate runs on a projection rather than one plane. */
    public boolean isProject() {
        return index == 0;
    }

    /** The 1-based slice index, or {@code 0} when this is {@link #PROJECT}. */
    public int index() {
        return index;
    }

    /** The text this slice is spelled with in a macro. */
    public String toMacroValue() {
        return isProject() ? PROJECT_VALUE : Integer.toString(index);
    }

    /**
     * Reads a {@code slice} macro value: {@code project}, or a number.
     *
     * @throws IllegalArgumentException naming the option and the value
     */
    public static Slice parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (PROJECT_VALUE.equals(trimmed.toLowerCase(Locale.ROOT))) return PROJECT;
        try {
            return of(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Macro option 'slice' must be '" + PROJECT_VALUE
                    + "' or a slice number (slice='" + trimmed + "').", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Slice)) return false;
        return index == ((Slice) other).index;
    }

    @Override
    public int hashCode() {
        return index;
    }

    @Override
    public String toString() {
        return "slice=" + toMacroValue();
    }
}
