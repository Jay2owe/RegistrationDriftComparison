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
 * Which measurement decides how a registered arm scored.
 *
 * <p>One value ships in v0.1.0. It compares the temporal standard deviation of
 * the registered stack against a control that was resampled by the same
 * fractional shift and then shifted back, so the smoothing that bilinear
 * interpolation gives away for free cannot flatter an engine (defect D11).
 *
 * <p>A single-value enum rather than a boolean because adding a second arbiter
 * later is a change nobody's macro notices, whereas removing a value that was
 * released is a change every macro that used it notices. The option is
 * therefore released with exactly the arbiter that has been measured.
 */
public enum Arbiter {

    /** Temporal standard deviation against an interpolation-matched control. */
    SD_VS_CONTROL("sd_vs_control");

    private final String macroValue;

    Arbiter(String macroValue) {
        this.macroValue = macroValue;
    }

    /** The word this arbiter is spelled with in a macro. */
    public String macroValue() {
        return macroValue;
    }

    /**
     * Reads an {@code arbiter} macro value.
     *
     * @throws IllegalArgumentException naming the value and the valid ones
     */
    public static Arbiter parse(String text) {
        String trimmed = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (Arbiter arbiter : values()) {
            if (arbiter.macroValue.equals(trimmed)) return arbiter;
        }
        throw new IllegalArgumentException("Macro option 'arbiter' has an unrecognized value '"
                + (text == null ? "" : text.trim()) + "'. Valid values are: " + validValues() + ".");
    }

    /** Every accepted {@code arbiter} value, comma-separated. */
    public static String validValues() {
        StringBuilder sb = new StringBuilder();
        for (Arbiter arbiter : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(arbiter.macroValue);
        }
        return sb.toString();
    }
}
