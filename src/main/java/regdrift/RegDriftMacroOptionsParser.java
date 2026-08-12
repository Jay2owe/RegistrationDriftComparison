/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import sc.fiji.oc3d.core.macro.MacroOptions;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the option string a macro passes to either menu entry.
 *
 * <p>Pure text in, settings out: no image has to be open and no window is
 * shown, so the whole macro grammar can be tested without starting Fiji.
 *
 * <h2>What it refuses</h2>
 *
 * A name that is not one of the fifteen is refused, with the fifteen listed. A
 * value that is not one of the values that option takes is refused, with those
 * listed. A name given twice is refused rather than silently resolved to
 * whichever came last. Splitting into tokens goes through the shared strict
 * tokenizer, which also refuses a bracket that never closes, a closing bracket
 * that opened nothing, and a line break inside a value - three shapes that a
 * lenient tokenizer accepts and then quietly reads as something else.
 *
 * <p>Refusing is the point. An option string that a plugin half-understands
 * produces a run that looks like the one that was asked for and is not, which is
 * the failure that survives review and reaches a figure.
 *
 * <h2>What no option can do</h2>
 *
 * <p>No name in the grammar makes this plugin fetch, write or modify software.
 * Repairing a missing engine is a button in a dialog somebody opened, and there
 * is no path to it from here. That is asserted by {@code MacroOptionsParserTest}
 * over the option names and over the compiled bytecode of this class, rather
 * than promised in a comment.
 *
 * <h2>Boolean options</h2>
 *
 * <p>Written as {@code use_roi=true} or {@code use_roi=false}, and also accepted
 * as a bare {@code use_roi}, which is what ImageJ's own recorder writes for a
 * ticked box and therefore what a hand-written macro is likely to carry.
 */
public final class RegDriftMacroOptionsParser {

    private RegDriftMacroOptionsParser() {
    }

    /**
     * Reads an option string. An empty or null string gives the documented
     * defaults.
     *
     * @throws IllegalArgumentException naming what it could not accept and what
     *         it would have accepted instead
     */
    public static RegDriftMacroOptions parse(String optionsText) {
        RegDriftMacroOptions options = new RegDriftMacroOptions();
        Set<String> seen = new HashSet<String>();
        List<String> tokens = MacroOptions.strictTokens(optionsText);
        for (String token : tokens) {
            int eq = token.indexOf('=');
            String key = (eq < 0 ? token : token.substring(0, eq))
                    .trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Macro option token '" + token
                        + "' has no option name before its '='. Valid options are: "
                        + RegDriftMacroOptions.optionNameList() + ".");
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Macro option '" + key
                        + "' was given more than once. Give it once, with the value you want.");
            }
            String value = eq < 0 ? null : decodeValue(key, token.substring(eq + 1).trim());
            apply(options, key, value);
        }
        options.validate();
        return options;
    }

    private static void apply(RegDriftMacroOptions options, String key, String value) {
        if (RegDriftMacroOptions.MODE.equals(key)) {
            options.setMode(Mode.parse(require(key, value)));
        } else if (RegDriftMacroOptions.CHANNEL.equals(key)) {
            options.setChannel(Channel.parse(require(key, value)));
        } else if (RegDriftMacroOptions.SLICE.equals(key)) {
            options.setSlice(Slice.parse(require(key, value)));
        } else if (RegDriftMacroOptions.USE_ROI.equals(key)) {
            options.setUseRoi(flag(key, value));
        } else if (RegDriftMacroOptions.ENGINES.equals(key)) {
            options.setEngines(EngineSelection.parse(require(key, value)));
        } else if (RegDriftMacroOptions.APPLY_ENGINE.equals(key)) {
            options.setApplyEngine(require(key, value));
        } else if (RegDriftMacroOptions.WINDOWS.equals(key)) {
            options.setWindows(Windows.parse(require(key, value)));
        } else if (RegDriftMacroOptions.WINDOW_FRAMES.equals(key)) {
            options.setWindowFrames(WindowFrames.parse(require(key, value)));
        } else if (RegDriftMacroOptions.ARBITER.equals(key)) {
            options.setArbiter(Arbiter.parse(require(key, value)));
        } else if (RegDriftMacroOptions.FLAG_MOTION_LOSS.equals(key)) {
            options.setFlagMotionLoss(flag(key, value));
        } else if (RegDriftMacroOptions.ADVISE_CEILING.equals(key)) {
            options.setAdviseCeiling(flag(key, value));
        } else if (RegDriftMacroOptions.COMPARE_WITH.equals(key)) {
            options.setCompareWith(require(key, value));
        } else if (RegDriftMacroOptions.SAVE_ROOT.equals(key)) {
            options.setSaveRoot(require(key, value));
        } else if (RegDriftMacroOptions.HIDE_DISPLAY.equals(key)) {
            options.setHideDisplay(flag(key, value));
        } else if (RegDriftMacroOptions.SERIAL.equals(key)) {
            options.setSerial(flag(key, value));
        } else {
            throw new IllegalArgumentException("Unknown macro option '" + key
                    + "'. Valid options are: " + RegDriftMacroOptions.optionNameList() + ".");
        }
    }

    /** An option that carries a value, given without one, is a typing mistake. */
    private static String require(String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("Macro option '" + key + "' needs a value, written '"
                    + key + "=<value>'.");
        }
        return value;
    }

    /**
     * A boolean option: {@code key=true}, {@code key=false}, or a bare
     * {@code key} for true.
     */
    private static boolean flag(String key, String value) {
        if (value == null) return true;
        String text = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "yes".equals(text) || "1".equals(text)) return true;
        if ("false".equals(text) || "no".equals(text) || "0".equals(text)) return false;
        throw new IllegalArgumentException("Macro option '" + key + "' must be true or false, or be"
                + " given on its own for true (" + key + "='" + value + "').");
    }

    /**
     * Strips the brackets ImageJ wraps a value containing spaces in, and refuses
     * a value that could not have been written by this plugin.
     */
    private static String decodeValue(String key, String raw) {
        String text = raw;
        if (text.length() >= 2 && text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']') {
            text = text.substring(1, text.length() - 1);
        }
        return RegDriftMacroOptions.requireWritable(key, text);
    }
}
