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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The macro line for a folder run: how {@code Registration Batch...} is written
 * into the recorder and read back.
 *
 * <p>Five names belong to a folder and nothing else - {@code folder},
 * {@code pattern}, {@code group}, {@code recursive} and {@code workers}. The
 * rest are the single-recording grammar's own names with the same meaning, and
 * they are written and read by that grammar
 * ({@link RegDriftMacroOptions}, {@link RegDriftMacroOptionsParser}), so a
 * setting cannot mean one thing on a single recording and another on a folder.
 *
 * <p>A filename pattern is a regular expression, so unlike every other value it
 * may carry backslashes. It may not carry a square bracket or a quotation mark:
 * ImageJ's option syntax has no way to write either inside a bracketed value. A
 * pattern with a character class therefore runs from the dialog but is not
 * recorded, and the status bar says why.
 */
public final class RegDriftBatchMacro {

    /** The folder of recordings. Required. */
    public static final String FOLDER = "folder";

    /** The filename pattern, a regular expression. */
    public static final String PATTERN = "pattern";

    /** Which capture group labels a recording, or {@code none}. */
    public static final String GROUP = "group";

    /** Whether sub-folders are read as well. */
    public static final String RECURSIVE = "recursive";

    /** How many recordings are measured beside each other, or {@code auto}. */
    public static final String WORKERS = "workers";

    /** The single-recording names a folder run reads, in the order they are written. */
    public static final List<String> SHARED = Collections.unmodifiableList(Arrays.asList(
            RegDriftMacroOptions.MODE, RegDriftMacroOptions.ENGINES,
            RegDriftMacroOptions.APPLY_ENGINE, RegDriftMacroOptions.WINDOWS,
            RegDriftMacroOptions.WINDOW_FRAMES, RegDriftMacroOptions.ADVISE_CEILING,
            RegDriftMacroOptions.SAVE_ROOT));

    private static final List<String> OWN = Collections.unmodifiableList(Arrays.asList(
            FOLDER, PATTERN, GROUP, RECURSIVE, WORKERS));

    private RegDriftBatchMacro() {
    }

    /** Every name a folder line may carry, for a message that lists them. */
    public static String optionNameList() {
        Set<String> names = new LinkedHashSet<String>(OWN);
        names.addAll(SHARED);
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) out.append(", ");
            out.append(name);
        }
        return out.toString();
    }

    /**
     * Reads a folder line into a request that has not been built yet, so the
     * caller can add the switch Esc pulls.
     *
     * @throws IllegalArgumentException naming what could not be accepted
     */
    public static RegDriftBatchParameters.Builder parse(String optionsText) {
        String folder = null;
        String pattern = null;
        int group = RegDriftBatchParameters.DEFAULT_GROUP;
        boolean recursive = false;
        int workers = 0;
        StringBuilder shared = new StringBuilder();
        Set<String> seen = new HashSet<String>();
        for (String token : MacroOptions.strictTokens(optionsText)) {
            int eq = token.indexOf('=');
            String key = (eq < 0 ? token : token.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
            String value = eq < 0 ? null : unbracket(token.substring(eq + 1).trim());
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Macro option '" + key + "' was given more than"
                        + " once. Give it once, with the value you want.");
            }
            if (FOLDER.equals(key)) {
                folder = required(key, value);
            } else if (PATTERN.equals(key)) {
                pattern = required(key, value);
            } else if (GROUP.equals(key)) {
                group = groupOf(required(key, value));
            } else if (RECURSIVE.equals(key)) {
                recursive = flag(key, value);
            } else if (WORKERS.equals(key)) {
                workers = workersOf(required(key, value));
            } else if (SHARED.contains(key)) {
                if (shared.length() > 0) shared.append(' ');
                shared.append(token);
            } else {
                throw new IllegalArgumentException("Unknown macro option '" + key + "' for a folder"
                        + " run. Valid options are: " + optionNameList() + ".");
            }
        }
        if (folder == null || folder.trim().isEmpty()) {
            throw new IllegalArgumentException("A folder run from a macro needs the folder, written"
                    + " folder=[<path>]. The macro recorder writes the whole line for you: run"
                    + " Registration Batch... once from the menu with the recorder open.");
        }
        RegDriftMacroOptions options = RegDriftMacroOptionsParser.parse(shared.toString());
        RegDriftBatchParameters.Builder builder = RegDriftBatchParameters.builder(new File(folder))
                .groupCapture(group)
                .recursive(recursive)
                .mode(options.getMode())
                .engines(options.getEngines())
                .applyEngine(options.getApplyEngine())
                .windows(options.getWindows())
                .windowFrames(options.getWindowFrames())
                .adviseCeiling(options.isAdviseCeiling())
                .movieWorkers(workers)
                .saveRoot(options.getSaveRoot());
        if (pattern != null) builder.pattern(pattern);
        return builder;
    }

    /**
     * Writes a folder request as the option text of a macro line.
     *
     * @throws IllegalArgumentException when a value cannot be written in this
     *         grammar, naming it. The run itself is not affected
     */
    public static String toMacroOptions(RegDriftBatchParameters parameters) {
        List<String> tokens = new ArrayList<String>();
        tokens.add(bracketed(FOLDER, RegDriftMacroOptions.requireWritable(FOLDER,
                RegDriftMacroOptions.forwardSlashes(parameters.folder().getPath()))));
        tokens.add(bracketed(PATTERN, patternWritable(parameters.pattern())));
        tokens.add(GROUP + "=" + (parameters.groupCapture() <= 0 ? "none"
                : Integer.toString(parameters.groupCapture())));
        tokens.add(RECURSIVE + "=" + parameters.recursive());
        tokens.add(WORKERS + "=" + (parameters.movieWorkers() <= 0 ? "auto"
                : Integer.toString(parameters.movieWorkers())));

        RegDriftMacroOptions shared = new RegDriftMacroOptions();
        shared.setMode(parameters.mode());
        shared.setEngines(parameters.engines());
        shared.setApplyEngine(parameters.applyEngine());
        shared.setWindows(parameters.windows());
        shared.setWindowFrames(parameters.windowFrames());
        shared.setAdviseCeiling(parameters.adviseCeiling());
        shared.setSaveRoot(parameters.saveRoot());
        for (String token : MacroOptions.strictTokens(shared.toMacroOptions())) {
            int eq = token.indexOf('=');
            String key = eq < 0 ? token : token.substring(0, eq);
            if (SHARED.contains(key)) tokens.add(token);
        }
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (out.length() > 0) out.append(' ');
            out.append(token);
        }
        return out.toString();
    }

    /**
     * The option text as it has to appear between the quotation marks of a
     * macro's {@code run(...)}: a backslash in a pattern is doubled, because
     * the macro language reads a single one as the start of an escape.
     */
    public static String inMacroString(String optionsText) {
        return optionsText.replace("\\", "\\\\");
    }

    private static String patternWritable(String pattern) {
        String text = pattern == null ? "" : pattern;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == ']' || c == '"' || Character.isISOControl(c)) {
                throw new IllegalArgumentException("the filename pattern '" + text + "' holds a"
                        + " square bracket or a quotation mark, which a macro line has no way to"
                        + " write. The folder still runs; to record it, write the pattern without a"
                        + " character class, for example (A|B|C) in place of [ABC].");
            }
        }
        return text;
    }

    private static String bracketed(String key, String value) {
        return key + "=[" + value + "]";
    }

    private static String unbracket(String raw) {
        if (raw.length() >= 2 && raw.charAt(0) == '[' && raw.charAt(raw.length() - 1) == ']') {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String required(String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("Macro option '" + key + "' needs a value, written '"
                    + key + "=<value>'.");
        }
        return value;
    }

    private static boolean flag(String key, String value) {
        if (value == null) return true;
        String text = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text)) return true;
        if ("false".equals(text)) return false;
        throw new IllegalArgumentException("Macro option '" + key + "' must be true or false ("
                + key + "='" + value + "').");
    }

    private static int groupOf(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(text) || "0".equals(text)) return 0;
        try {
            int group = Integer.parseInt(text);
            if (group > 0) return group;
        } catch (NumberFormatException notANumber) {
            // Falls through to the sentence below.
        }
        throw new IllegalArgumentException("Macro option '" + GROUP + "' must be a capture group"
                + " number of 1 or more, or 'none' (" + GROUP + "='" + value + "').");
    }

    private static int workersOf(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        if ("auto".equals(text)) return 0;
        try {
            int workers = Integer.parseInt(text);
            if (workers > 0) return workers;
        } catch (NumberFormatException notANumber) {
            // Falls through to the sentence below.
        }
        throw new IllegalArgumentException("Macro option '" + WORKERS + "' must be 'auto' or a"
                + " number of recordings of 1 or more (" + WORKERS + "='" + value + "').");
    }
}
