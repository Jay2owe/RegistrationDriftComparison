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
 * Whether the movement in a recording can be registered at all.
 *
 * <p>Three of the four are warnings and none of them refuses to proceed: the
 * localisability threshold warns, it never blocks a run (defect D7 in the build
 * plan's ledger). {@code NOT_REGISTRABLE} is the strongest thing said, and it is
 * still said as a verdict beside a number, not as a locked door.
 *
 * <p>This type carries the table token for each verdict and nothing else. The
 * finished sentence a user reads is written once, in the stage that decides the
 * verdict, so the wording and the type never drift into two versions of the
 * same claim.
 */
public enum Verdict {

    /** The movement is measurable and the estimators agree about it. */
    REGISTRABLE("registrable"),

    /** There may be too little structure to localize; reported, not refused. */
    WARN_LOW_STRUCTURE("warn_low_structure"),

    /** The two independent estimators describe different movement. */
    ESTIMATORS_DISAGREE("estimators_disagree"),

    /** Nothing in the recording tracks frame to frame. */
    NOT_REGISTRABLE("not_registrable");

    private final String tableValue;

    Verdict(String tableValue) {
        this.tableValue = tableValue;
    }

    /** The token written into the {@code verdict} column of the diagnosis table. */
    public String tableValue() {
        return tableValue;
    }

    /**
     * Reads a {@code verdict} table token back into a verdict.
     *
     * @throws IllegalArgumentException naming the token and the valid ones
     */
    public static Verdict parse(String text) {
        String trimmed = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (Verdict verdict : values()) {
            if (verdict.tableValue.equals(trimmed)) return verdict;
        }
        throw new IllegalArgumentException("Unrecognized verdict '"
                + (text == null ? "" : text.trim()) + "'. Valid verdicts are: " + validValues() + ".");
    }

    /** Every verdict token, comma-separated, in declaration order. */
    public static String validValues() {
        StringBuilder sb = new StringBuilder();
        for (Verdict verdict : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(verdict.tableValue);
        }
        return sb.toString();
    }
}
