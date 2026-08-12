/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every setting a macro can name, and the line a macro recorder writes.
 *
 * <p>These fifteen names are the published grammar of the plugin. Once a
 * version ships, renaming one breaks every script anybody wrote against it, so
 * they are decided in one place, checked by a round-trip test, and then left
 * alone. Two of the defaults are measurements rather than preferences:
 * {@code windows=auto} resolves to three and {@code window_frames=auto} to
 * twelve, from the run of 2026-08-12 over the twelve-recording library.
 *
 * <p>Nothing a macro can say here makes this plugin fetch, write or modify
 * software. Repairing a missing engine is a button somebody presses in a dialog
 * they opened, and no option name reaches it. That is asserted over these
 * option names and over the compiled bytecode of this class, rather than
 * promised in a comment.
 *
 * <p>Holds text, not images: {@code compare_with} is a window title or a path
 * here, and becomes an {@link ImagePlus} in {@link RegDriftParameters}. The
 * settings this object holds can therefore be checked, written and replayed
 * without an image being open at all.
 */
public final class RegDriftMacroOptions {

    /** What the run should do. */
    public static final String MODE = "mode";

    /** Which channel the movement is measured on. */
    public static final String CHANNEL = "channel";

    /** Which Z slice, or a projection through Z. */
    public static final String SLICE = "slice";

    /** Whether an ROI on the input restricts which pixels vote. */
    public static final String USE_ROI = "use_roi";

    /** Which engines the run considers. */
    public static final String ENGINES = "engines";

    /** An engine to run, overriding the ranking. */
    public static final String APPLY_ENGINE = "apply_engine";

    /** How many measurement windows. */
    public static final String WINDOWS = "windows";

    /** How many consecutive frames each window holds. */
    public static final String WINDOW_FRAMES = "window_frames";

    /** Which measurement decides how an arm scored. */
    public static final String ARBITER = "arbiter";

    /** Whether arms that flatten real movement are flagged. */
    public static final String FLAG_MOTION_LOSS = "flag_motion_loss";

    /** Whether the run may advise an intensity ceiling. Advice, never a setting. */
    public static final String ADVISE_CEILING = "advise_ceiling";

    /** A second, already-registered stack to score. */
    public static final String COMPARE_WITH = "compare_with";

    /** Where the auto-save tree is written. */
    public static final String SAVE_ROOT = "save_root";

    /** Whether to show no window. */
    public static final String HIDE_DISPLAY = "hide_display";

    /** Whether to use one worker everywhere. */
    public static final String SERIAL = "serial";

    private static final List<String> OPTION_NAMES = Collections.unmodifiableList(Arrays.asList(
            MODE, CHANNEL, SLICE, USE_ROI, ENGINES, APPLY_ENGINE, WINDOWS, WINDOW_FRAMES,
            ARBITER, FLAG_MOTION_LOSS, ADVISE_CEILING, COMPARE_WITH, SAVE_ROOT, HIDE_DISPLAY,
            SERIAL));

    private Mode mode = Mode.DIAGNOSE_AND_RECOMMEND;
    private Channel channel = Channel.AUTO;
    private Slice slice = Slice.PROJECT;
    private boolean useRoi = false;
    private EngineSelection engines = EngineSelection.defaultSelection();
    private String applyEngine = "";
    private Windows windows = Windows.auto();
    private WindowFrames windowFrames = WindowFrames.auto();
    private Arbiter arbiter = Arbiter.SD_VS_CONTROL;
    private boolean flagMotionLoss = true;
    private boolean adviseCeiling = true;
    private String compareWith = "";
    private String saveRoot = "";
    private boolean hideDisplay = false;
    private boolean serial = false;

    /** Every option name, in the order the contract lists them. */
    public static List<String> allOptionNames() {
        return OPTION_NAMES;
    }

    /** Every option name, comma-separated, for a message a user has to act on. */
    public static String optionNameList() {
        StringBuilder sb = new StringBuilder();
        for (String name : OPTION_NAMES) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
        return sb.toString();
    }

    /** True when the name is one of the fifteen. */
    public static boolean isOptionName(String name) {
        return OPTION_NAMES.contains(name);
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DIAGNOSE_AND_RECOMMEND : mode;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel == null ? Channel.AUTO : channel;
    }

    public Slice getSlice() {
        return slice;
    }

    public void setSlice(Slice slice) {
        this.slice = slice == null ? Slice.PROJECT : slice;
    }

    public boolean isUseRoi() {
        return useRoi;
    }

    public void setUseRoi(boolean useRoi) {
        this.useRoi = useRoi;
    }

    public EngineSelection getEngines() {
        return engines;
    }

    public void setEngines(EngineSelection engines) {
        this.engines = engines == null ? EngineSelection.defaultSelection() : engines;
    }

    public String getApplyEngine() {
        return applyEngine;
    }

    public void setApplyEngine(String applyEngine) {
        this.applyEngine = clean(applyEngine);
    }

    public Windows getWindows() {
        return windows;
    }

    public void setWindows(Windows windows) {
        this.windows = windows == null ? Windows.auto() : windows;
    }

    public WindowFrames getWindowFrames() {
        return windowFrames;
    }

    public void setWindowFrames(WindowFrames windowFrames) {
        this.windowFrames = windowFrames == null ? WindowFrames.auto() : windowFrames;
    }

    public Arbiter getArbiter() {
        return arbiter;
    }

    public void setArbiter(Arbiter arbiter) {
        this.arbiter = arbiter == null ? Arbiter.SD_VS_CONTROL : arbiter;
    }

    public boolean isFlagMotionLoss() {
        return flagMotionLoss;
    }

    public void setFlagMotionLoss(boolean flagMotionLoss) {
        this.flagMotionLoss = flagMotionLoss;
    }

    public boolean isAdviseCeiling() {
        return adviseCeiling;
    }

    public void setAdviseCeiling(boolean adviseCeiling) {
        this.adviseCeiling = adviseCeiling;
    }

    /** The window title or path of the second stack, or empty. */
    public String getCompareWith() {
        return compareWith;
    }

    public void setCompareWith(String compareWith) {
        this.compareWith = clean(compareWith);
    }

    public String getSaveRoot() {
        return saveRoot;
    }

    public void setSaveRoot(String saveRoot) {
        this.saveRoot = clean(saveRoot);
    }

    public boolean isHideDisplay() {
        return hideDisplay;
    }

    public void setHideDisplay(boolean hideDisplay) {
        this.hideDisplay = hideDisplay;
    }

    public boolean isSerial() {
        return serial;
    }

    public void setSerial(boolean serial) {
        this.serial = serial;
    }

    /**
     * Checks the settings against each other.
     *
     * <p>Two settings name something that belongs to one mode. Naming it under
     * another mode is a request nobody can carry out, so it is refused here with
     * the mode that would have carried it, rather than ignored at run time.
     *
     * @throws IllegalArgumentException naming the setting and both modes
     */
    public void validate() {
        if (!compareWith.isEmpty() && mode != Mode.SCORE) {
            throw new IllegalArgumentException("Macro option '" + COMPARE_WITH + "' was given with '"
                    + MODE + "=" + mode.macroValue() + "'. A second stack is scored with '"
                    + MODE + "=" + Mode.SCORE.macroValue() + "'; either set that mode, or remove '"
                    + COMPARE_WITH + "'.");
        }
        if (!applyEngine.isEmpty() && mode != Mode.APPLY) {
            throw new IllegalArgumentException("Macro option '" + APPLY_ENGINE + "' was given with '"
                    + MODE + "=" + mode.macroValue() + "'. Naming one engine to run belongs to '"
                    + MODE + "=" + Mode.APPLY.macroValue() + "'; either set that mode, or use '"
                    + ENGINES + "' to choose which engines are considered.");
        }
    }

    /**
     * Reads the settings back out of a finished request.
     *
     * <p>The reverse of {@link #toParameters}, and the one place that mapping is
     * written down. Two callers need it: the record of what a run was given,
     * which is written beside the results, and the macro line a dialog offers
     * for copying. Neither should carry its own copy of which field is spelled
     * which way.
     *
     * <p>{@code compare_with} becomes the second stack's window title, which is
     * how a macro names an open image.
     */
    public static RegDriftMacroOptions from(RegDriftParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("Settings can be read back from a request, and none"
                    + " was given.");
        }
        RegDriftMacroOptions options = new RegDriftMacroOptions();
        options.setMode(parameters.mode());
        options.setChannel(parameters.channel());
        options.setSlice(parameters.slice());
        options.setUseRoi(parameters.useRoi());
        options.setEngines(parameters.engines());
        options.setApplyEngine(parameters.applyEngine());
        options.setWindows(parameters.windows());
        options.setWindowFrames(parameters.windowFrames());
        options.setArbiter(parameters.arbiter());
        options.setFlagMotionLoss(parameters.flagMotionLoss());
        options.setAdviseCeiling(parameters.adviseCeiling());
        options.setCompareWith(parameters.compareWith() == null
                ? "" : parameters.compareWith().getTitle());
        options.setSaveRoot(parameters.saveRoot());
        options.setHideDisplay(parameters.hideDisplay());
        options.setSerial(parameters.serial());
        return options;
    }

    /**
     * Turns these settings into a run over one recording.
     *
     * @param image           the recording to measure
     * @param compareWithImage the second stack, resolved from
     *                        {@link #getCompareWith()} by the caller, or null
     */
    public RegDriftParameters toParameters(ImagePlus image, ImagePlus compareWithImage) {
        validate();
        return RegDriftParameters.builder(image)
                .mode(mode)
                .channel(channel)
                .slice(slice)
                .useRoi(useRoi)
                .engines(engines)
                .applyEngine(applyEngine)
                .windows(windows)
                .windowFrames(windowFrames)
                .arbiter(arbiter)
                .flagMotionLoss(flagMotionLoss)
                .adviseCeiling(adviseCeiling)
                .compareWith(compareWithImage)
                .saveRoot(saveRoot)
                .hideDisplay(hideDisplay)
                .serial(serial)
                .build();
    }

    /**
     * The option string a macro recorder writes and
     * {@link RegDriftMacroOptionsParser} reads back.
     *
     * <p>Every setting is written out, including the ones left at their default,
     * and always in the order the contract lists them. A recorded line therefore
     * says what the run actually did rather than which boxes happened to be
     * ticked, and two identical runs record byte-identical lines. The three text
     * settings are left out when empty, since empty is what their absence means.
     */
    public String toMacroOptions() {
        List<String> tokens = new ArrayList<String>();
        appendValue(tokens, MODE, mode.macroValue());
        appendValue(tokens, CHANNEL, channel.toMacroValue());
        appendValue(tokens, SLICE, slice.toMacroValue());
        appendValue(tokens, USE_ROI, Boolean.toString(useRoi));
        appendValue(tokens, ENGINES, engines.toMacroValue());
        appendText(tokens, APPLY_ENGINE, applyEngine);
        appendValue(tokens, WINDOWS, windows.toMacroValue());
        appendValue(tokens, WINDOW_FRAMES, windowFrames.toMacroValue());
        appendValue(tokens, ARBITER, arbiter.macroValue());
        appendValue(tokens, FLAG_MOTION_LOSS, Boolean.toString(flagMotionLoss));
        appendValue(tokens, ADVISE_CEILING, Boolean.toString(adviseCeiling));
        appendText(tokens, COMPARE_WITH, compareWith);
        appendText(tokens, SAVE_ROOT, saveRoot);
        appendValue(tokens, HIDE_DISPLAY, Boolean.toString(hideDisplay));
        appendValue(tokens, SERIAL, Boolean.toString(serial));
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(token);
        }
        return sb.toString();
    }

    /**
     * Refuses a value that cannot survive being written and read back.
     *
     * <p>Checked when the line is <em>written</em>, so the complaint arrives
     * while the user is still looking at the setting that caused it, rather than
     * on replay as a path that silently lost its second half. There is no escape
     * syntax to fall back on: an unmatched bracket in a window title genuinely
     * cannot be written down in this grammar.
     *
     * @return the value, unchanged, when it is safe
     * @throws IllegalArgumentException naming the option and the value
     */
    public static String requireWritable(String optionName, String value) {
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == ']' || c == '"' || c == '\\' || Character.isISOControl(c)) {
                throw new IllegalArgumentException("Macro option '" + optionName + "' cannot contain"
                        + " a bracket, a quotation mark, a backslash or a line break (" + optionName
                        + "='" + text + "'). Write paths with forward slashes, and rename the image"
                        + " if its title carries a bracket.");
            }
        }
        return text;
    }

    private static void appendText(List<String> tokens, String key, String value) {
        if (value == null || value.isEmpty()) return;
        appendValue(tokens, key, value);
    }

    private static void appendValue(List<String> tokens, String key, String value) {
        String safe = requireWritable(key, value);
        tokens.add(key + "=" + (needsBrackets(safe) ? "[" + safe + "]" : safe));
    }

    private static boolean needsBrackets(String value) {
        if (value.isEmpty()) return true;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return true;
        }
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RegDriftMacroOptions)) return false;
        RegDriftMacroOptions that = (RegDriftMacroOptions) other;
        return mode == that.mode
                && channel.equals(that.channel)
                && slice.equals(that.slice)
                && useRoi == that.useRoi
                && engines.equals(that.engines)
                && applyEngine.equals(that.applyEngine)
                && windows.equals(that.windows)
                && windowFrames.equals(that.windowFrames)
                && arbiter == that.arbiter
                && flagMotionLoss == that.flagMotionLoss
                && adviseCeiling == that.adviseCeiling
                && compareWith.equals(that.compareWith)
                && saveRoot.equals(that.saveRoot)
                && hideDisplay == that.hideDisplay
                && serial == that.serial;
    }

    @Override
    public int hashCode() {
        int hash = mode.hashCode();
        hash = hash * 31 + channel.hashCode();
        hash = hash * 31 + slice.hashCode();
        hash = hash * 31 + (useRoi ? 1 : 0);
        hash = hash * 31 + engines.hashCode();
        hash = hash * 31 + applyEngine.hashCode();
        hash = hash * 31 + windows.hashCode();
        hash = hash * 31 + windowFrames.hashCode();
        hash = hash * 31 + arbiter.hashCode();
        hash = hash * 31 + (flagMotionLoss ? 1 : 0);
        hash = hash * 31 + (adviseCeiling ? 1 : 0);
        hash = hash * 31 + compareWith.hashCode();
        hash = hash * 31 + saveRoot.hashCode();
        hash = hash * 31 + (hideDisplay ? 1 : 0);
        hash = hash * 31 + (serial ? 1 : 0);
        return hash;
    }

    /** The recorded option line, which is the whole state of this object. */
    @Override
    public String toString() {
        return toMacroOptions();
    }
}
