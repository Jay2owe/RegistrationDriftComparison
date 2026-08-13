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
import regdrift.Mode;
import regdrift.RegDriftMacroOptions;
import regdrift.autofix.AutofixService;
import sc.fiji.oc3d.core.ui.ToggleSwitch;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * The dialog behind {@code Plugins > Registration > Compare Registration
 * Methods...}: Input, Analysis, Engines, Output.
 *
 * <p>All five modes are offered here, so this is the entry that can rank
 * engines, run them and score what they produced. Two of those modes need one
 * more piece of information than the rest, and each appears when its mode is
 * chosen and goes away again when it is not:
 *
 * <ul>
 *   <li><b>Recommend and apply</b> may be told which engine to run, overriding
 *       the ranking.</li>
 *   <li><b>Score an existing result</b> has to be given the registered stack it
 *       is rating.</li>
 * </ul>
 *
 * <p>The Engines section reports what was found on this computer for each
 * registration engine, and offers a repair on the rows a person can repair from
 * here. Drawing it reads the machine and opens no connection; see
 * {@link EnginePanel}.
 */
public final class CompareDialog extends RegDriftDialog {

    /** The window title, which carries the plugin's display name. */
    public static final String TITLE = "Registration & Drift Comparison - Compare"
            + " Registration Methods";

    /** The second-recording dropdown's entry for "nothing chosen yet". */
    public static final String NO_SECOND_RECORDING = "(choose the registered stack)";

    private JPanel applyGroup;
    private JTextField applyEngineField;

    private JPanel scoreGroup;
    private JComboBox<String> secondRecordingCombo;

    private JComboBox<String> arbiterCombo;
    private ToggleSwitch motionLossToggle;

    private EnginePanel enginePanel;

    /**
     * Builds the dialog against this Fiji's engines. No window is created until
     * {@link #showModal()}.
     *
     * @param choices the recordings this dialog can offer
     */
    public CompareDialog(ImageChoices choices) {
        this(choices, null);
    }

    /**
     * Builds the dialog against a supplied engine service.
     *
     * <p>The seam a test uses to hand the Engines section a known set of
     * engines instead of whatever this computer happens to have. A null service
     * means the one that reads this Fiji.
     *
     * @param choices the recordings this dialog can offer
     * @param engines where the Engines section gets its rows, or null
     */
    public CompareDialog(ImageChoices choices, AutofixService engines) {
        super(TITLE, choices, engines);
    }

    @Override
    protected String[] modeLabels() {
        return new String[]{
                LABEL_DIAGNOSE,
                LABEL_DIAGNOSE_AND_RECOMMEND,
                LABEL_APPLY,
                LABEL_COMPARE,
                LABEL_SCORE,
        };
    }

    @Override
    protected Mode initialMode() {
        return Mode.DIAGNOSE_AND_RECOMMEND;
    }

    @Override
    protected void addModeExtras() {
        applyGroup = form().beginGroup();
        applyEngineField = form().addStringField("Engine to run", "", 22);
        form().addHelpText("Leave this empty to run the engine the ranking puts first. Naming one"
                + " runs that engine instead, spelled the way its own authors spell it, as in"
                + " StackReg or Correct 3D drift.");
        form().endGroup();
        applyGroup.setVisible(false);

        scoreGroup = form().beginGroup();
        secondRecordingCombo = form().addChoice("Registered stack", secondRecordingItems(),
                NO_SECOND_RECORDING);
        form().addHelpText("The stack another plugin produced from the recording above. Both have"
                + " to be the same size and the same length, since one is meant to be the other"
                + " after registration.");
        form().endGroup();
        scoreGroup.setVisible(false);
    }

    @Override
    protected void addAdvancedExtras() {
        arbiterCombo = form().addChoice("Arbiter",
                new String[]{Arbiter.SD_VS_CONTROL.macroValue()},
                Arbiter.SD_VS_CONTROL.macroValue());
        form().addHelpText("How an engine's result is rated: the temporal standard deviation of"
                + " the registered stack against a control resampled by the same fractional shift."
                + " Interpolation blurs, and blurring lowers that number for free, so an engine is"
                + " compared against something that was blurred the same way and had no drift"
                + " removed.");

        motionLossToggle = form().addToggle("Flag arms that flatten real movement", true);
        form().addHelpText("Marks a result whose recovered path is far shorter than the movement"
                + " that was measured, which is what registering away something the sample was"
                + " actually doing looks like.");
    }

    @Override
    protected void addSectionsAfterAnalysis() {
        enginePanel = new EnginePanel(engines());
        enginePanel.render(form());
    }

    /** The Engines section, so a test can read what it drew. */
    public EnginePanel enginePanel() {
        return enginePanel;
    }

    @Override
    protected void modeExtrasChanged(Mode mode) {
        applyGroup.setVisible(mode == Mode.APPLY);
        scoreGroup.setVisible(mode == Mode.SCORE);
    }

    @Override
    protected void readExtras(RegDriftMacroOptions options) {
        options.setArbiter(Arbiter.parse(text(arbiterCombo)));
        options.setFlagMotionLoss(motionLossToggle.isSelected());
        options.setApplyEngine(options.getMode() == Mode.APPLY
                ? applyEngineField.getText().trim() : "");
        options.setCompareWith(options.getMode() == Mode.SCORE ? secondRecording() : "");
    }

    @Override
    protected void applyExtras(RegDriftMacroOptions given) {
        selectOrAdd(arbiterCombo, given.getArbiter().macroValue());
        motionLossToggle.setSelected(given.isFlagMotionLoss());
        applyEngineField.setText(given.getApplyEngine());
        selectOrAdd(secondRecordingCombo, given.getCompareWith().isEmpty()
                ? NO_SECOND_RECORDING : given.getCompareWith());
    }

    @Override
    protected String whatStopsExtras() {
        if (mode() != Mode.SCORE) return null;
        String second = secondRecording();
        if (second.isEmpty()) {
            return "Scoring an existing result needs the registered stack as well as the recording"
                    + " it came from. Open it, then choose it under 'Registered stack'.";
        }
        if (second.equals(imageTitle())) {
            return "'" + second + "' is the recording being scored, so it cannot also be the"
                    + " registered stack. Choose the stack a registration produced.";
        }
        return null;
    }

    /** The registered stack somebody chose, or empty when none was. */
    public String secondRecording() {
        String selected = text(secondRecordingCombo);
        return NO_SECOND_RECORDING.equals(selected) ? "" : selected;
    }

    private String[] secondRecordingItems() {
        String[] titles = choices().titles();
        String[] items = new String[titles.length + 1];
        items[0] = NO_SECOND_RECORDING;
        System.arraycopy(titles, 0, items, 1, titles.length);
        return items;
    }
}
