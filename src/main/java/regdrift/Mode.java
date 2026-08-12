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
 * What a run is being asked to do.
 *
 * <p>The five modes are the {@code mode} macro option, and the text each one
 * carries is the word a user types. Those words are part of the published macro
 * grammar: a released name cannot be changed without breaking somebody's
 * script, so they are fixed here and nowhere else.
 *
 * <p>The dialog wording for each mode belongs to the dialog, not here, so the
 * two never grow separate copies of the same sentence.
 */
public enum Mode {

    /** Measure the movement and stop. */
    DIAGNOSE("diagnose"),

    /** Measure, then name the engines the measurements support. The default. */
    DIAGNOSE_AND_RECOMMEND("diagnose_recommend"),

    /** Measure, recommend, then run one engine and return the registered stack. */
    APPLY("apply"),

    /** Run several engines on the same recording and score each arm. */
    COMPARE("compare"),

    /** Score a stack somebody else already registered, against its source. */
    SCORE("score");

    private final String macroValue;

    Mode(String macroValue) {
        this.macroValue = macroValue;
    }

    /** The word this mode is spelled with in a macro. */
    public String macroValue() {
        return macroValue;
    }

    /**
     * Reads a {@code mode} macro value.
     *
     * @throws IllegalArgumentException naming the value it was given and every
     *         value it would have accepted
     */
    public static Mode parse(String text) {
        String trimmed = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (Mode mode : values()) {
            if (mode.macroValue.equals(trimmed)) return mode;
        }
        throw new IllegalArgumentException("Macro option 'mode' has an unrecognized value '"
                + (text == null ? "" : text.trim()) + "'. Valid values are: " + validValues() + ".");
    }

    /** Every accepted {@code mode} value, comma-separated, in declaration order. */
    public static String validValues() {
        StringBuilder sb = new StringBuilder();
        for (Mode mode : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(mode.macroValue);
        }
        return sb.toString();
    }
}
