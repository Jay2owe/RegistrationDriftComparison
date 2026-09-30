/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import regdrift.Arbiter;
import regdrift.Channel;
import regdrift.EngineSelection;
import regdrift.Mode;
import regdrift.RegDrift;
import regdrift.RegDriftMacroOptions;
import regdrift.Slice;
import regdrift.WindowFrames;
import regdrift.Windows;
import regdrift.advise.CalibrationTable;
import regdrift.autofix.AutofixService;
import sc.fiji.oc3d.core.ui.CollapsiblePane;
import sc.fiji.oc3d.core.ui.ToggleSwitch;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.util.ArrayList;
import java.util.List;

/**
 * What the two dialogs share: the Input section, the mode control with its
 * folded-away Advanced pane, the Output section, and the reading of every
 * control back into the published settings.
 *
 * <h2>One visible control in Analysis</h2>
 *
 * <p>The mode is the decision somebody came here to make, and it is the single
 * control the Analysis section shows. Everything else folds away behind
 * Advanced, closed when the dialog opens, and the plugin is usable without it
 * ever being opened. That is not tidiness: the case for building this plugin at
 * all rests on a hard filter that a tool needing twenty visible settings
 * recreates the adoption problem it set out to fix.
 *
 * <h2>One reader, one writer</h2>
 *
 * <p>{@link #options()} produces a {@link RegDriftMacroOptions}, which is the
 * same object the macro parser produces from a recorded line. Both menu entries
 * therefore write lines the other can read, because there is one grammar and one
 * mapping into it, rather than a dialog that assembles its own string.
 *
 * <h2>A note for anybody adding a section</h2>
 *
 * <p>The controls are built during construction, through the hooks below. A
 * subclass must therefore assign its widgets inside those hooks and give its
 * fields no initializers, since a field initializer in a subclass runs
 * <em>after</em> this constructor and would overwrite a widget that was already
 * built and wired up.
 */
public abstract class RegDriftDialog {

    /** The mode control's wording, which is what a person reads and chooses. */
    public static final String LABEL_DIAGNOSE = "Diagnose";

    /** Measure, then rank the engines. The mode a dialog opens on. */
    public static final String LABEL_DIAGNOSE_AND_RECOMMEND = "Diagnose and recommend";

    /** Measure, rank, then run the engine ranked first. */
    public static final String LABEL_APPLY = "Recommend and apply";

    /** Run several engines on this recording and score each one. */
    public static final String LABEL_COMPARE = "Compare installed engines";

    /** Rate a stack another plugin already registered. */
    public static final String LABEL_SCORE = "Score an existing result";

    /** The Advanced disclosure's title. */
    public static final String ADVANCED = "Advanced";

    /** What the channel dropdown says for "let the ranking choose". */
    public static final String CHANNEL_AUTO = "auto";

    /** What the Z dropdown says for a projection. */
    public static final String SLICE_PROJECT = "project (maximum through Z)";

    /** What the windows dropdown says for the measured default. */
    public static final String WINDOWS_AUTO = "auto (3 windows)";

    /** The explicit "measure the whole recording" choice, which is windows=0. */
    public static final String WINDOWS_EVERY_PAIR = "measure every consecutive frame pair";

    /** What the frames-per-window dropdown says for the measured default. */
    public static final String FRAMES_AUTO = "auto (12 frames)";

    /** The engines dropdown entry for the engines this Fiji already has. */
    public static final String ENGINES_PRESENT = "engines installed in this Fiji";

    /** The engines dropdown entry for every engine the plugin knows about. */
    public static final String ENGINES_ALL = "every engine this plugin knows about";

    private static final String[] WINDOW_ITEMS = {
            WINDOWS_AUTO, "2 windows", "3 windows", "4 windows", "5 windows", "6 windows",
            WINDOWS_EVERY_PAIR,
    };

    private static final String[] FRAME_ITEMS = {
            FRAMES_AUTO, "6 frames", "8 frames", "12 frames", "16 frames", "24 frames",
            "32 frames",
    };

    private final DialogForm form;
    private final ImageChoices choices;
    private final AutofixService engines;

    private JComboBox<String> imageCombo;
    private JLabel shapeLine;
    private JComboBox<String> channelCombo;
    private JComboBox<String> sliceCombo;
    private ToggleSwitch roiToggle;

    private DialogForm.Radios modeRadios;
    private JLabel modeHelp;
    private JLabel calibrationNote;
    private CollapsiblePane advanced;

    private JComboBox<String> enginesCombo;
    private JComboBox<String> windowsCombo;
    private JComboBox<String> framesCombo;
    private ToggleSwitch ceilingToggle;
    private ToggleSwitch serialToggle;

    private JTextField saveRootField;
    private ToggleSwitch hideDisplayToggle;

    /**
     * Builds the dialog's controls. No window is created here.
     *
     * @param title   the window title, which carries the display name
     * @param choices the recordings this dialog can offer
     */
    protected RegDriftDialog(String title, ImageChoices choices) {
        this(title, choices, null);
    }

    /**
     * As above, with the engine service the Engines section reads from.
     *
     * <p>Assigned before the section hooks run, which is what lets a subclass
     * that has an Engines section reach it from {@link #addSectionsAfterAnalysis()}
     * - a subclass field would still be unassigned at that point. A dialog
     * without that section never asks for it, and asking is what triggers the
     * reading of this computer, so the dialog that has no Engines section reads
     * nothing.
     *
     * @param title   the window title, which carries the display name
     * @param choices the recordings this dialog can offer
     * @param engines where the Engines section gets its rows, or null for this Fiji
     */
    protected RegDriftDialog(String title, ImageChoices choices, AutofixService engines) {
        this.form = new DialogForm(title);
        this.choices = choices == null ? ImageChoices.none() : choices;
        this.engines = engines;
        buildInput();
        buildAnalysis();
        addSectionsAfterAnalysis();
        buildOutput();
        form.setCheck(new DialogForm.Check() {
            @Override public String problem() {
                return whatStopsThisRun();
            }
        });
        modeChanged();
    }

    // -------------------------------------------------------------- the shape

    /** The mode entries this dialog offers, in the order they are shown. */
    protected abstract String[] modeLabels();

    /** The mode this dialog opens on. */
    protected abstract Mode initialMode();

    /** Adds anything this dialog wants between Analysis and Output. */
    protected void addSectionsAfterAnalysis() {
    }

    /** Adds anything this dialog wants inside the Advanced pane. */
    protected void addAdvancedExtras() {
    }

    /** Adds anything this dialog wants under the mode control. */
    protected void addModeExtras() {
    }

    /** Called whenever the chosen mode changes, after the shared handling. */
    protected void modeExtrasChanged(Mode mode) {
    }

    /** Reads this dialog's own controls into the settings. */
    protected void readExtras(RegDriftMacroOptions options) {
    }

    /** What has to change before this dialog can be accepted, or null. */
    protected String whatStopsExtras() {
        return null;
    }

    /** The form every control was added to. */
    protected DialogForm form() {
        return form;
    }

    /** The recordings this dialog was offered. */
    protected ImageChoices choices() {
        return choices;
    }

    /**
     * The engine service this dialog reads from.
     *
     * <p>Falls back to the one that reads this Fiji, built once and shared, so
     * that opening the dialog twice does not read every class and every file
     * twice.
     */
    protected AutofixService engines() {
        return engines == null ? AutofixService.forThisFiji() : engines;
    }

    // ------------------------------------------------------------- the result

    /** The window title of the recording somebody chose. */
    public String imageTitle() {
        Object selected = imageCombo.getSelectedItem();
        return selected == null ? "" : selected.toString();
    }

    /** The chosen mode. */
    public Mode mode() {
        return modeOf(modeRadios.selected());
    }

    /** The Advanced disclosure, so a test can open and close it. */
    public CollapsiblePane advanced() {
        return advanced;
    }

    /** The section headers, in the order they are shown. */
    public List<String> sections() {
        return form.headers();
    }

    /** What the channel dropdown currently offers. */
    public List<String> channelChoices() {
        return itemsOf(channelCombo);
    }

    /** What the Z dropdown currently offers. */
    public List<String> sliceChoices() {
        return itemsOf(sliceCombo);
    }

    /** The mode wording this dialog offers, in the order it is shown. */
    public List<String> modeChoices() {
        return modeRadios.labels();
    }

    /**
     * Everything the controls say, in the published settings grammar.
     *
     * <p>The same shape the macro parser produces, so what a dialog records and
     * what a macro replays are the same object built two ways.
     */
    public RegDriftMacroOptions options() {
        RegDriftMacroOptions options = new RegDriftMacroOptions();
        options.setMode(mode());
        options.setChannel(channelOf(text(channelCombo)));
        options.setSlice(sliceOf(text(sliceCombo)));
        options.setUseRoi(roiToggle.isSelected());
        options.setEngines(enginesOf(text(enginesCombo)));
        options.setWindows(windowsOf(text(windowsCombo)));
        options.setWindowFrames(framesOf(text(framesCombo)));
        options.setArbiter(Arbiter.SD_VS_CONTROL);
        options.setAdviseCeiling(ceilingToggle.isSelected());
        options.setSerial(serialToggle.isSelected());
        options.setSaveRoot(saveRootField.getText().trim());
        options.setHideDisplay(hideDisplayToggle.isSelected());
        readExtras(options);
        return options;
    }

    /**
     * Sets every control to what a settings bundle says.
     *
     * <p>The reverse of {@link #options()}, and the reason both directions can
     * be checked against each other: settings in, settings out, and anything the
     * controls cannot represent shows up as a difference rather than as a value
     * quietly reverting to its default. A mode this dialog does not offer is
     * left alone, since a dialog with two modes cannot be set to a third.
     */
    public void applyOptions(RegDriftMacroOptions given) {
        if (given == null) return;
        modeRadios.select(labelOf(given.getMode()));
        selectOrAdd(channelCombo, given.getChannel().isAuto()
                ? CHANNEL_AUTO : Integer.toString(given.getChannel().index()));
        selectOrAdd(sliceCombo, given.getSlice().isProject()
                ? SLICE_PROJECT : Integer.toString(given.getSlice().index()));
        roiToggle.setSelected(given.isUseRoi());
        selectOrAdd(enginesCombo, labelForEngines(given.getEngines()));
        selectOrAdd(windowsCombo, labelForWindows(given.getWindows()));
        selectOrAdd(framesCombo, labelForFrames(given.getWindowFrames()));
        ceilingToggle.setSelected(given.isAdviseCeiling());
        serialToggle.setSelected(given.isSerial());
        saveRootField.setText(given.getSaveRoot());
        hideDisplayToggle.setSelected(given.isHideDisplay());
        applyExtras(given);
        modeChanged();
    }

    /** Sets this dialog's own controls from a settings bundle. */
    protected void applyExtras(RegDriftMacroOptions given) {
    }

    /** Puts the dialog on the screen. True when OK was pressed. */
    public boolean showModal() {
        return form.showModal();
    }

    // -------------------------------------------------------------- the input

    private void buildInput() {
        form.addHeader("Input");
        String[] titles = choices.titles();
        imageCombo = form.addChoice("Recording", titles.length == 0
                ? new String[]{""} : titles, choices.activeTitle());
        shapeLine = form.addHelpText(shapeTextFor(choices.activeTitle()));

        ImageChoices.Entry active = choices.active();
        channelCombo = form.addChoice("Measure movement on channel",
                channelItems(active), CHANNEL_AUTO);
        form.addHelpText("With 'auto' the channels are ranked and the movement is measured on the"
                + " one the ranking puts first. Which channel that was, and why, is written into"
                + " the record of the run.");

        sliceCombo = form.addChoice("Z", sliceItems(active), SLICE_PROJECT);
        form.addHelpText("A projection through Z is the default: one plane of a thick sample can"
                + " drift through focus while the field itself sits still.");

        roiToggle = form.addToggle("Restrict to the ROI on the recording", false);
        form.addHelpText("Measures the movement inside the rectangle enclosing the selection on"
                + " the recording, at least " + RegDrift.MIN_ROI_SIDE_PX + " pixels a side. For"
                + " measuring on a static background while a large object crosses the field."
                + " Engines and scoring still use the whole frame.");

        imageCombo.addActionListener(event -> imageChanged());
    }

    private void imageChanged() {
        String title = imageTitle();
        shapeLine.setText(DialogForm.wrapped(shapeTextFor(title)));
        ImageChoices.Entry entry = choices.find(title);
        replaceItems(channelCombo, channelItems(entry), CHANNEL_AUTO);
        replaceItems(sliceCombo, sliceItems(entry), SLICE_PROJECT);
        form.repack();
    }

    private String shapeTextFor(String title) {
        ImageChoices.Entry entry = choices.find(title);
        if (entry == null) {
            return "No recording is open. Open a time-lapse stack, then start this again.";
        }
        return entry.shape() + ".";
    }

    private static String[] channelItems(ImageChoices.Entry entry) {
        int channels = entry == null ? 1 : entry.channels();
        List<String> items = new ArrayList<String>();
        items.add(CHANNEL_AUTO);
        for (int c = 1; c <= channels; c++) items.add(Integer.toString(c));
        return items.toArray(new String[0]);
    }

    private static String[] sliceItems(ImageChoices.Entry entry) {
        int slices = entry == null ? 1 : entry.slices();
        List<String> items = new ArrayList<String>();
        items.add(SLICE_PROJECT);
        for (int z = 1; z <= slices; z++) items.add(Integer.toString(z));
        return items.toArray(new String[0]);
    }

    // ----------------------------------------------------------- the analysis

    private void buildAnalysis() {
        form.addHeader("Analysis");
        modeRadios = form.addRadios(modeLabels(), labelOf(initialMode()));
        modeHelp = form.addHelpText(helpForMode(initialMode()));
        calibrationNote = form.addNote(calibrationNoteFor(initialMode()));
        modeRadios.onChange(new Runnable() {
            @Override public void run() {
                modeChanged();
            }
        });
        addModeExtras();

        advanced = form.beginCollapsible(ADVANCED, false);
        enginesCombo = form.addChoice("Candidate engines",
                new String[]{ENGINES_PRESENT, ENGINES_ALL}, ENGINES_PRESENT);
        form.addHelpText("Which engines the ranking considers. Nothing is fetched, written or"
                + " installed by this setting; an engine this Fiji does not have is ranked and"
                + " marked as absent.");

        windowsCombo = form.addChoice("Measurement windows", WINDOW_ITEMS, WINDOWS_AUTO);
        form.addHelpText("Movement is measured over a few stretches of consecutive frames spread"
                + " through the recording, plus one pair bridging each gap, so the measurement"
                + " costs the same on a 50-frame recording and a 5,000-frame one. Three windows of"
                + " twelve frames reproduced the whole-recording movement label on all twelve"
                + " library recordings. '" + WINDOWS_EVERY_PAIR + "' measures the whole recording"
                + " instead, and takes as long as that sounds.");

        framesCombo = form.addChoice("Frames per window", FRAME_ITEMS, FRAMES_AUTO);
        form.addHelpText("Every pair inside a window is a genuinely consecutive pair, which is what"
                + " lets jitter be told apart from a slow wander.");

        addAdvancedExtras();

        ceilingToggle = form.addToggle("Advise on an intensity ceiling", true);
        form.addHelpText("Reports when a bright structure covers a share of the frame, and what a"
                + " ceiling near that percentile would exclude. It is advice and stays advice:"
                + " there is no setting in this plugin that switches a ceiling on, because on a"
                + " recording whose sample is itself the brightest thing present the same cut"
                + " deletes the sample.");

        serialToggle = form.addToggle("Use one worker", false);
        form.addHelpText("Runs every stage on a single thread. Slower, and the arrangement the"
                + " equivalence tests compare a parallel run against.");

        form.endCollapsible(advanced);
    }

    private void modeChanged() {
        Mode mode = mode();
        modeHelp.setText(DialogForm.wrapped(helpForMode(mode)));
        String note = calibrationNoteFor(mode);
        calibrationNote.setText(DialogForm.wrapped(note));
        calibrationNote.setVisible(!note.isEmpty());
        modeExtrasChanged(mode);
        form.repack();
    }

    /**
     * What a mode that ranks engines says about the table it ranks them from -
     * defect D10.
     *
     * <p>Beside the control that turns ranking on, not in a manual and not in a
     * footnote. The ranking is a lookup in measurements taken on three
     * phase-contrast frames from one instrument, and a reader who is not told
     * that will read it as a general statement about registration software. The
     * sentence itself lives with the table, in
     * {@link regdrift.advise.CalibrationTable}, so the dialog, the saved notes
     * and the recommendation table cannot end up carrying three versions of it.
     *
     * <p>Empty for a mode that ranks nothing, because a calibration note beside
     * a measurement that consults no table is noise.
     */
    public static String calibrationNoteFor(Mode mode) {
        if (mode == Mode.DIAGNOSE || mode == Mode.SCORE) return "";
        return CalibrationTable.CALIBRATION_SET
                + " A recording unlike those is marked outside_calibrated_range, with the reason,"
                + " rather than being fitted to the nearest thing that was measured.";
    }

    // ------------------------------------------------------------- the output

    private void buildOutput() {
        form.addHeader("Output");
        saveRootField = form.addFolderField("Auto-save folder", "");
        form.addHelpText("Leave this empty and nothing is written to disk. Given a folder, the"
                + " tables are written into a RegistrationDriftComparison folder inside it, beside"
                + " a README.txt that says what each file is and which settings produced it.");

        hideDisplayToggle = form.addToggle("Show no windows", false);
        form.addHelpText("Produces the results without putting anything on the screen. What a"
                + " batch script wants, and what the macro option hide_display does.");

        form.addNote("Which tables and images a run produces follows the mode above. Per-output"
                + " tick boxes arrive with the results view in a later build.");
    }

    // --------------------------------------------------------- the OK check

    /**
     * What has to change before this dialog can be accepted, or null when it
     * can be. What the OK button asks before it closes anything.
     */
    protected String whatStopsThisRun() {
        if (choices.isEmpty()) {
            return "There is no recording open to measure. Open a time-lapse stack, then start"
                    + " this again.";
        }
        if (choices.find(imageTitle()) == null) {
            return "The recording '" + imageTitle() + "' is no longer open. Choose one that is.";
        }
        String extras = whatStopsExtras();
        if (extras != null) return extras;
        try {
            RegDriftMacroOptions.requireWritable("image", imageTitle());
            options().toMacroOptions();
        } catch (IllegalArgumentException unwritable) {
            return "These settings cannot be written down as a macro line, so a run made with them"
                    + " could not be repeated: " + unwritable.getMessage();
        }
        return null;
    }

    // ------------------------------------------------- wording to value types

    /** The mode a piece of the mode control's wording means. */
    public static Mode modeOf(String label) {
        if (LABEL_DIAGNOSE.equals(label)) return Mode.DIAGNOSE;
        if (LABEL_APPLY.equals(label)) return Mode.APPLY;
        if (LABEL_COMPARE.equals(label)) return Mode.COMPARE;
        if (LABEL_SCORE.equals(label)) return Mode.SCORE;
        return Mode.DIAGNOSE_AND_RECOMMEND;
    }

    /** How a mode is worded in the mode control. */
    public static String labelOf(Mode mode) {
        switch (mode) {
            case DIAGNOSE:
                return LABEL_DIAGNOSE;
            case APPLY:
                return LABEL_APPLY;
            case COMPARE:
                return LABEL_COMPARE;
            case SCORE:
                return LABEL_SCORE;
            default:
                return LABEL_DIAGNOSE_AND_RECOMMEND;
        }
    }

    /** The sentence under the mode control for a given mode. */
    public static String helpForMode(Mode mode) {
        switch (mode) {
            case DIAGNOSE:
                return "Measures the movement in the recording and stops there.";
            case APPLY:
                return "Measures, ranks the engines, then runs the engine ranked first and hands"
                        + " back the registered stack.";
            case COMPARE:
                return "Runs several engines on this recording and scores each one against the"
                        + " same control.";
            case SCORE:
                return "Rates a stack another plugin already registered, against the recording it"
                        + " came from.";
            default:
                return "Measures the movement, then names the registration engines the"
                        + " measurements support, with the error and the CPU seconds each is"
                        + " expected to reach on a recording like this one.";
        }
    }

    private static Channel channelOf(String item) {
        return CHANNEL_AUTO.equals(item) ? Channel.AUTO : Channel.parse(item);
    }

    private static Slice sliceOf(String item) {
        return item != null && item.startsWith("project") ? Slice.PROJECT : Slice.parse(item);
    }

    private static EngineSelection enginesOf(String item) {
        if (ENGINES_ALL.equals(item)) return EngineSelection.all();
        if (ENGINES_PRESENT.equals(item)) return EngineSelection.present();
        return EngineSelection.parse(item);
    }

    private static Windows windowsOf(String item) {
        if (item == null || item.startsWith("auto")) return Windows.auto();
        if (WINDOWS_EVERY_PAIR.equals(item)) return Windows.allPairs();
        return Windows.of(leadingNumber(item, Windows.AUTO_COUNT));
    }

    private static WindowFrames framesOf(String item) {
        if (item == null || item.startsWith("auto")) return WindowFrames.auto();
        return WindowFrames.of(leadingNumber(item, WindowFrames.AUTO_FRAMES));
    }

    /** How an engine set is worded in the dropdown. */
    public static String labelForEngines(EngineSelection engines) {
        if (engines.kind() == EngineSelection.Kind.ALL) return ENGINES_ALL;
        if (engines.kind() == EngineSelection.Kind.PRESENT) return ENGINES_PRESENT;
        return engines.toMacroValue();
    }

    /** How a window count is worded in the dropdown. */
    public static String labelForWindows(Windows windows) {
        if (windows.isAuto()) return WINDOWS_AUTO;
        if (windows.isAllPairs()) return WINDOWS_EVERY_PAIR;
        return windows.resolvedCount() + " windows";
    }

    /** How a frames-per-window count is worded in the dropdown. */
    public static String labelForFrames(WindowFrames frames) {
        return frames.isAuto() ? FRAMES_AUTO : frames.resolved() + " frames";
    }

    private static int leadingNumber(String item, int fallback) {
        int end = 0;
        while (end < item.length() && Character.isDigit(item.charAt(end))) end++;
        if (end == 0) return fallback;
        return Integer.parseInt(item.substring(0, end));
    }

    // ------------------------------------------------------------- machinery

    /** The chosen entry of a dropdown, or an empty string when there is none. */
    protected static String text(JComboBox<String> combo) {
        Object selected = combo.getSelectedItem();
        return selected == null ? "" : selected.toString();
    }

    /**
     * Chooses an entry, adding it to the dropdown when it is not already there.
     *
     * <p>A macro line can name a window count or an engine list nobody put in
     * the dropdown, and a dialog seeded from one should show what it was given
     * rather than the nearest thing it happened to offer.
     */
    protected static void selectOrAdd(JComboBox<String> combo, String item) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (item.equals(combo.getItemAt(i))) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.addItem(item);
        combo.setSelectedItem(item);
    }

    /** Everything a dropdown offers, in the order it offers it. */
    private static List<String> itemsOf(JComboBox<String> combo) {
        List<String> items = new ArrayList<String>(combo.getItemCount());
        for (int i = 0; i < combo.getItemCount(); i++) items.add(combo.getItemAt(i));
        return items;
    }

    /** Replaces a dropdown's entries, keeping the chosen one where it survives. */
    private static void replaceItems(JComboBox<String> combo, String[] items, String fallback) {
        String previous = text(combo);
        combo.removeAllItems();
        boolean kept = false;
        for (String item : items) {
            combo.addItem(item);
            if (item.equals(previous)) kept = true;
        }
        combo.setSelectedItem(kept ? previous : fallback);
    }
}
