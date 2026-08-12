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
 * What happened at one frame of the scored arm.
 *
 * <p>The {@code status} column of the frames table. An enum in code and a word
 * in the file: a frame that could not be measured says which way it could not
 * be measured, so a reader can tell a refusal from a failure to converge
 * without guessing from the numbers beside it.
 *
 * <p>Five values, and none of them means "nothing happened here". A frame whose
 * measurement was fine is {@link #OK}, and every other value is a stated reason
 * somebody can act on.
 */
public enum FrameStatus {

    /** Measured, and the measurement stands. */
    OK("ok"),

    /** The frames overlapped too little for a displacement to mean anything. */
    REFUSED_LOW_OVERLAP("refused_low_overlap"),

    /** The frame was resampled at a fractional shift rather than moved whole. */
    INTERPOLATED("interpolated"),

    /** The displacement reached the edge of the search box, so it is a floor. */
    AT_SHIFT_BOUND("at_shift_bound"),

    /** The search ran out of iterations before it settled. */
    NOT_CONVERGED("not_converged");

    private final String tableValue;

    FrameStatus(String tableValue) {
        this.tableValue = tableValue;
    }

    /** The word written into the {@code status} column. */
    public String tableValue() {
        return tableValue;
    }

    /**
     * Reads a {@code status} word back.
     *
     * @throws IllegalArgumentException naming the value it was given and every
     *         value it would have accepted
     */
    public static FrameStatus parse(String text) {
        String trimmed = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (FrameStatus status : values()) {
            if (status.tableValue.equals(trimmed)) return status;
        }
        throw new IllegalArgumentException("Frame status has an unrecognized value '"
                + (text == null ? "" : text.trim()) + "'. Valid values are: " + validValues() + ".");
    }

    /** Every accepted status word, comma-separated, in declaration order. */
    public static String validValues() {
        StringBuilder sb = new StringBuilder();
        for (FrameStatus status : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(status.tableValue);
        }
        return sb.toString();
    }
}
