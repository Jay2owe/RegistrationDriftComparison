/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import regdrift.Mode;

/**
 * The dialog behind {@code Plugins > Registration > Registration
 * Diagnostics...}: Input, Analysis, Output.
 *
 * <p>The same dialog as {@link CompareDialog} with the Engines section left out
 * and the mode control cut to the two modes that measure. Everything this entry
 * does works on a Fiji with no registration engine installed at all, which is
 * why it is a separate menu item rather than a setting inside the other one:
 * somebody whose recording may not be registrable at all should be able to find
 * that out without meeting a list of software they might have to install.
 *
 * <p>The settings it records are the same grammar the other entry records, so a
 * line copied from one and run in the other is read the same way. Running a line
 * that asks for something this entry does not do - applying an engine, say -
 * says which menu item does do it, rather than quietly doing something else.
 */
public final class DiagnosticsDialog extends RegDriftDialog {

    /** The window title, which carries the plugin's display name. */
    public static final String TITLE = "Registration & Drift Comparison - Registration"
            + " Diagnostics";

    /** Where to go for the modes this entry leaves out. */
    public static final String WHERE_THE_ENGINES_ARE =
            "This entry measures the recording and ranks engines against what it measured. To run"
                    + " an engine, or to score a stack that has already been registered, use"
                    + " Compare Registration Methods... in the same menu.";

    /**
     * Builds the dialog. No window is created until {@link #showModal()}.
     *
     * @param choices the recordings this dialog can offer
     */
    public DiagnosticsDialog(ImageChoices choices) {
        super(TITLE, choices);
    }

    @Override
    protected String[] modeLabels() {
        return new String[]{LABEL_DIAGNOSE, LABEL_DIAGNOSE_AND_RECOMMEND};
    }

    @Override
    protected Mode initialMode() {
        return Mode.DIAGNOSE_AND_RECOMMEND;
    }

    @Override
    protected void addModeExtras() {
        form().addNote(WHERE_THE_ENGINES_ARE);
    }
}
