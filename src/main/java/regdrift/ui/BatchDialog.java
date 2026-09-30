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
import regdrift.EngineSelection;
import regdrift.Mode;
import regdrift.RegDriftBatchParameters;
import regdrift.RegDriftBatchRunner;
import regdrift.WindowFrames;
import regdrift.Windows;
import sc.fiji.oc3d.core.ui.CollapsiblePane;
import sc.fiji.oc3d.core.ui.ToggleSwitch;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;

/**
 * The dialog behind a run over a folder: which folder, which filenames, what
 * each recording is labelled, and what to do with every one of them.
 *
 * <h2>The preview is the point</h2>
 *
 * <p>The filename pattern is the one setting of a batch somebody is likely to
 * get wrong, and the cost of getting it wrong is silent: a folder of two
 * hundred recordings where a hundred and ninety were quietly left out looks
 * exactly like a folder of ten. So the dialog will not let anybody past without
 * showing which files it is about to take, what each one will be labelled, and -
 * as importantly - <b>which files it is about to leave alone</b>.
 *
 * <p>The preview comes from {@link RegDriftBatchRunner#preview}, which is the
 * same reading of the folder the run itself uses. It is not a second
 * implementation that agrees most of the time.
 *
 * <h2>Why this is not a {@link RegDriftDialog}</h2>
 *
 * <p>That one opens on the recordings already on the screen and asks which of
 * them to measure. A batch has none: it is given a folder, and the recordings in
 * it are opened and closed again without ever being shown. Sharing a base class
 * would mean an Input section whose first control is a list of open windows the
 * batch will not use.
 *
 * <p>What is shared is the shape - {@link DialogForm}, the same sections in the
 * same order, the same Advanced disclosure closed on opening - and the settings
 * are the same value types, so a batch and a single recording measure the same
 * way.
 *
 * <h2>Building it needs no screen</h2>
 *
 * <p>Like every dialog here: the controls are built in the constructor and the
 * window is built in {@link #showModal()} and nowhere else, so what this offers,
 * what it says and what it reads back can all be checked on a machine with no
 * display.
 */
public final class BatchDialog {

    /** The window title, which carries the plugin's display name. */
    public static final String TITLE = "Registration & Drift Comparison - a folder of recordings";

    /** The mode control's wording. Scoring is not here; see {@link #WHY_NO_SCORE}. */
    public static final String[] MODE_LABELS = {
            RegDriftDialog.LABEL_DIAGNOSE,
            RegDriftDialog.LABEL_DIAGNOSE_AND_RECOMMEND,
            RegDriftDialog.LABEL_APPLY,
            RegDriftDialog.LABEL_COMPARE,
    };

    /** Why a folder cannot be scored, said where somebody would look for it. */
    public static final String WHY_NO_SCORE =
            "Scoring rates a recording against the registered form of it that another plugin"
                    + " produced, which is two stacks for every recording. A folder and one"
                    + " filename pattern give no way to say which registered stack belongs to which"
                    + " recording, so a folder run does not offer it. Score a pair at a time from"
                    + " Compare Registration Methods...";

    /** What the preview says before a folder has been chosen. */
    public static final String NOTHING_CHOSEN =
            "Choose a folder to see which recordings this pattern takes and which it leaves alone.";

    /** The Advanced disclosure's title, spelled as the other dialogs spell it. */
    public static final String ADVANCED = RegDriftDialog.ADVANCED;

    private static final String[] WINDOW_ITEMS = {
            "auto (3 windows)", "2 windows", "3 windows", "4 windows", "5 windows", "6 windows",
            "measure every consecutive frame pair",
    };

    private static final String[] FRAME_ITEMS = {
            "auto (12 frames)", "6 frames", "8 frames", "12 frames", "16 frames", "24 frames",
            "32 frames",
    };

    private static final String[] WORKER_ITEMS = {
            "auto", "1", "2", "3", "4", "6", "8",
    };

    /** How long the folder and pattern text has to rest before the preview reads the folder. */
    static final int SETTLE_MS = 400;

    private final DialogForm form;

    private JTextField folderField;
    private ToggleSwitch recursiveToggle;
    private JTextField patternField;
    private JComboBox<String> groupCombo;
    private JTextArea preview;

    private DialogForm.Radios modeRadios;
    private JLabel modeHelp;
    private JLabel calibrationNote;
    private JLabel workerNote;
    private CollapsiblePane advanced;

    private JComboBox<String> enginesCombo;
    private JComboBox<String> windowsCombo;
    private JComboBox<String> framesCombo;
    private JComboBox<String> workersCombo;
    private ToggleSwitch ceilingToggle;

    private JTextField saveRootField;

    /** Builds the dialog's controls. No window is created here. */
    public BatchDialog() {
        this.form = new DialogForm(TITLE);
        buildInput();
        buildAnalysis();
        buildOutput();
        form.setCheck(new DialogForm.Check() {
            @Override public String problem() {
                return whatStopsThisRun();
            }
        });
        modeChanged();
        refreshPreview();
    }

    /** The form every control was added to. */
    public DialogForm form() {
        return form;
    }

    /** The section headers, in the order they are shown. */
    public List<String> sections() {
        return form.headers();
    }

    /** The mode wording this dialog offers, in the order it is shown. */
    public List<String> modeChoices() {
        return modeRadios.labels();
    }

    /** The Advanced disclosure, so a test can open and close it. */
    public CollapsiblePane advanced() {
        return advanced;
    }

    /** What the preview currently says. */
    public String previewText() {
        return preview.getText();
    }

    /** The sentence saying how the folder will be worked through. */
    public String workerNoteText() {
        return workerNote.getText();
    }

    /** The folder field itself, so a test can fill it in the way the folder button does. */
    JTextField folderFieldForTest() {
        return folderField;
    }

    /** Puts the dialog on the screen. True when OK was pressed. */
    public boolean showModal() {
        return form.showModal();
    }

    // ------------------------------------------------------------ the result

    /** The chosen mode. */
    public Mode mode() {
        return RegDriftDialog.modeOf(modeRadios.selected());
    }

    /** The folder somebody chose, or an empty string. */
    public String folder() {
        return folderField.getText().trim();
    }

    /**
     * Chooses what each recording is asked to do, and brings the sentences
     * beside it up to date.
     *
     * <p>The three sentences under the mode control - what the mode does, what
     * the calibration behind it rests on, and how many recordings will be worked
     * through at once - are the answer to a question somebody would otherwise
     * have to run the folder to find out. Setting the mode without them is
     * setting it wrong, so they are refreshed here rather than by whoever calls
     * this.
     */
    public void mode(Mode mode) {
        modeRadios.select(RegDriftDialog.labelOf(mode));
        modeChanged();
    }

    /** Chooses the folder of recordings, and shows what this pattern takes from it. */
    public void folder(String path) {
        folderField.setText(path == null ? "" : path);
        refreshPreview();
    }

    /** Chooses which filenames count, and shows what that takes. */
    public void pattern(String pattern) {
        patternField.setText(pattern == null ? "" : pattern);
        refreshPreview();
    }

    /** Chooses whether sub-folders are read as well, and shows what that takes. */
    public void recursive(boolean recursive) {
        recursiveToggle.setSelected(recursive);
        refreshPreview();
    }

    /**
     * Everything the controls say, as a batch request.
     *
     * @throws IllegalArgumentException when the pattern cannot be read as a
     *         regular expression, naming what is wrong with it. The OK button
     *         asks {@link #whatStopsThisRun()} first, so a person never meets it
     */
    public RegDriftBatchParameters parameters() {
        return builder().build();
    }

    /**
     * Everything the controls say, not yet built, so the menu command can add
     * the switch Esc pulls before the request is fixed.
     */
    public RegDriftBatchParameters.Builder builder() {
        return RegDriftBatchParameters.builder(new File(folder()))
                        .pattern(patternField.getText())
                        .groupCapture(groupOf(text(groupCombo)))
                        .recursive(recursiveToggle.isSelected())
                        .mode(mode())
                        .engines(enginesOf(text(enginesCombo)))
                        .windows(windowsOf(text(windowsCombo)))
                        .windowFrames(framesOf(text(framesCombo)))
                        .arbiter(Arbiter.SD_VS_CONTROL)
                        .adviseCeiling(ceilingToggle.isSelected())
                        .movieWorkers(workersOf(text(workersCombo)))
                        .saveRoot(saveRootField.getText().trim());
    }

    // ------------------------------------------------------------- the input

    private void buildInput() {
        form.addHeader("Input");
        folderField = form.addFolderField("Folder of recordings", "");
        form.addHelpText("Every file in this folder whose name matches the pattern below is opened,"
                + " measured and closed again. Nothing is shown on the screen while a folder runs.");

        recursiveToggle = form.addToggle("Read sub-folders as well", false);
        form.addHelpText("Off by default: the folder you chose is the folder you meant. Turned on,"
                + " every folder under it is read too, and a recording's row says which sub-folder"
                + " it came from.");

        patternField = form.addStringField("Filename pattern",
                RegDriftBatchParameters.DEFAULT_PATTERN, 24);
        form.addHelpText("A regular expression matched against the whole filename. The default"
                + " takes every TIFF. A file that does not match is left alone and listed as"
                + " skipped below, so a pattern that takes half a folder shows as one.");

        groupCombo = form.addChoice("Label from capture group",
                new String[]{"1", "2", "3", "no label"}, "1");
        form.addHelpText("Whatever that capture group catches becomes the recording's label, and"
                + " lands in the 'group' column beside its row: ^(?<group>[A-Z]\\d+)_.*\\.tif$"
                + " labels A1_t0.tif and A1_t1.tif both A1. A pattern with no such group labels"
                + " every recording '" + RegDriftBatchParameters.UNGROUPED_LABEL + "'.");

        JButton refresh = new JButton("Show what this takes");
        refresh.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                refreshPreview();
            }
        });
        form.content().add(refresh);
        form.addSpacer(4);
        preview = form.addTextArea(NOTHING_CHOSEN, 9, 52);

        ActionListener rescan = new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                refreshPreview();
            }
        };
        folderField.addActionListener(rescan);
        patternField.addActionListener(rescan);
        // The folder button fills the field in without pressing Enter, and so does typing;
        // either way the preview follows once the text has stopped changing.
        final Timer settle = new Timer(SETTLE_MS, rescan);
        settle.setRepeats(false);
        DocumentListener typed = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) {
                settle.restart();
            }

            @Override public void removeUpdate(DocumentEvent event) {
                settle.restart();
            }

            @Override public void changedUpdate(DocumentEvent event) {
                settle.restart();
            }
        };
        folderField.getDocument().addDocumentListener(typed);
        patternField.getDocument().addDocumentListener(typed);
        groupCombo.addActionListener(rescan);
        recursiveToggle.addChangeListener(new Runnable() {
            @Override public void run() {
                refreshPreview();
            }
        });
    }

    /**
     * Reads the folder and shows what a run would do to it.
     *
     * <p>Reading a folder is the one thing this dialog does that touches the
     * disk, and it is done because the alternative is somebody finding out from
     * the rows. A pattern that cannot be read as a regular expression says so
     * here rather than at the OK button.
     */
    public void refreshPreview() {
        if (folder().isEmpty()) {
            preview.setText(NOTHING_CHOSEN);
            preview.setCaretPosition(0);
            return;
        }
        try {
            preview.setText(RegDriftBatchRunner.preview(parameters()));
        } catch (IllegalArgumentException unreadable) {
            preview.setText(unreadable.getMessage());
        }
        preview.setCaretPosition(0);
    }

    // ---------------------------------------------------------- the analysis

    private void buildAnalysis() {
        form.addHeader("Analysis");
        modeRadios = form.addRadios(MODE_LABELS, RegDriftDialog.LABEL_DIAGNOSE_AND_RECOMMEND);
        modeHelp = form.addHelpText(
                RegDriftDialog.helpForMode(Mode.DIAGNOSE_AND_RECOMMEND));
        calibrationNote = form.addNote(
                RegDriftDialog.calibrationNoteFor(Mode.DIAGNOSE_AND_RECOMMEND));
        workerNote = form.addNote(
                RegDriftBatchRunner.movieWorkerNoteFor(Mode.DIAGNOSE_AND_RECOMMEND));
        form.addNote(WHY_NO_SCORE);
        modeRadios.onChange(new Runnable() {
            @Override public void run() {
                modeChanged();
            }
        });

        advanced = form.beginCollapsible(ADVANCED, false);
        enginesCombo = form.addChoice("Candidate engines",
                new String[]{RegDriftDialog.ENGINES_PRESENT, RegDriftDialog.ENGINES_ALL},
                RegDriftDialog.ENGINES_PRESENT);
        form.addHelpText("Which engines the ranking considers. Nothing is fetched, written or"
                + " installed by this setting.");

        windowsCombo = form.addChoice("Measurement windows", WINDOW_ITEMS, WINDOW_ITEMS[0]);
        form.addHelpText("Movement is measured over a few stretches of consecutive frames spread"
                + " through each recording, so the measurement costs the same on a 50-frame"
                + " recording and a 5,000-frame one.");

        framesCombo = form.addChoice("Frames per window", FRAME_ITEMS, FRAME_ITEMS[0]);
        form.addHelpText("Every pair inside a window is a genuinely consecutive pair, which is what"
                + " lets jitter be told apart from a slow wander.");

        workersCombo = form.addChoice("Recordings at a time", WORKER_ITEMS, WORKER_ITEMS[0]);
        form.addHelpText("How many recordings are measured beside each other. 'auto' asks this"
                + " machine and drops back when the recordings are large enough that more would"
                + " not fit in memory. The frame pairs inside one recording always run on a single"
                + " worker: a folder run moves the work out one level rather than adding one"
                + " underneath.");

        ceilingToggle = form.addToggle("Advise on an intensity ceiling", true);
        form.addHelpText("Reports when a bright structure covers a share of the frame. It is advice"
                + " and stays advice: there is no setting in this plugin that switches a ceiling"
                + " on.");
        form.endCollapsible(advanced);
    }

    private void modeChanged() {
        Mode mode = mode();
        modeHelp.setText(DialogForm.wrapped(RegDriftDialog.helpForMode(mode)));
        String calibration = RegDriftDialog.calibrationNoteFor(mode);
        calibrationNote.setText(DialogForm.wrapped(calibration));
        calibrationNote.setVisible(!calibration.isEmpty());
        workerNote.setText(DialogForm.wrapped(RegDriftBatchRunner.movieWorkerNoteFor(mode)));
        form.repack();
    }

    // ------------------------------------------------------------- the output

    private void buildOutput() {
        form.addHeader("Output");
        saveRootField = form.addFolderField("Auto-save folder", "");
        form.addHelpText("Where the results go. Each recording's tables are written the same way a"
                + " single recording's are, and a batch folder beside them holds one line per"
                + " recording plus one for the folder as a whole.");
        form.addNote("Leaving this empty runs the whole folder and writes nothing, which is worth"
                + " doing once to check the pattern and the settings. A folder run shows no window,"
                + " no table and no plot either way: two hundred recordings cannot each open one.");
    }

    // ---------------------------------------------------------- the OK check

    /** What has to change before this dialog can be accepted, or null. */
    public String whatStopsThisRun() {
        if (folder().isEmpty()) {
            return "No folder was chosen. Choose the folder holding the recordings to work"
                    + " through.";
        }
        File chosen = new File(folder());
        if (!chosen.isDirectory()) {
            return "'" + chosen.getAbsolutePath() + "' is not a folder this computer can read."
                    + " Choose a folder of time-lapse recordings.";
        }
        RegDriftBatchParameters parameters;
        try {
            parameters = parameters();
        } catch (IllegalArgumentException unusable) {
            return unusable.getMessage();
        }
        if (RegDriftBatchRunner.scan(parameters).movies().isEmpty()) {
            return "No file in '" + chosen.getAbsolutePath() + "' matches the pattern '"
                    + parameters.pattern() + "', so there would be nothing to run. The default"
                    + " pattern, '" + RegDriftBatchParameters.DEFAULT_PATTERN + "', takes every"
                    + " TIFF in the folder.";
        }
        return null;
    }

    // ------------------------------------------- wording to value types

    private static String text(JComboBox<String> combo) {
        Object selected = combo.getSelectedItem();
        return selected == null ? "" : selected.toString();
    }

    private static int groupOf(String item) {
        try {
            return Integer.parseInt(item.trim());
        } catch (NumberFormatException noLabel) {
            return 0;
        }
    }

    private static int workersOf(String item) {
        try {
            return Integer.parseInt(item.trim());
        } catch (NumberFormatException auto) {
            return 0;
        }
    }

    private static EngineSelection enginesOf(String item) {
        if (RegDriftDialog.ENGINES_ALL.equals(item)) return EngineSelection.all();
        if (RegDriftDialog.ENGINES_PRESENT.equals(item)) return EngineSelection.present();
        return EngineSelection.parse(item);
    }

    private static Windows windowsOf(String item) {
        if (item == null || item.startsWith("auto")) return Windows.auto();
        if (WINDOW_ITEMS[WINDOW_ITEMS.length - 1].equals(item)) return Windows.allPairs();
        return Windows.of(leadingNumber(item, Windows.AUTO_COUNT));
    }

    private static WindowFrames framesOf(String item) {
        if (item == null || item.startsWith("auto")) return WindowFrames.auto();
        return WindowFrames.of(leadingNumber(item, WindowFrames.AUTO_FRAMES));
    }

    private static int leadingNumber(String item, int fallback) {
        int end = 0;
        while (end < item.length() && Character.isDigit(item.charAt(end))) end++;
        return end == 0 ? fallback : Integer.parseInt(item.substring(0, end));
    }
}
