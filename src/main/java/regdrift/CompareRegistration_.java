/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import regdrift.ui.CompareDialog;
import regdrift.ui.ImageChoices;
import regdrift.ui.RegDriftDialog;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code Plugins > Registration > Compare Registration Methods...}
 *
 * <p>The entry that can do everything: measure the movement, rank the engines
 * against what it measured, run one of them, run several and score each, or rate
 * a stack somebody else already registered. Its dialog carries the Engines
 * section, since this is the entry whose modes need engines to be there.
 *
 * <p>The routing, the recording and the reporting are all in
 * {@link RegDriftEntry}, which the sibling entry shares, so a line recorded here
 * is a line that means the same thing there.
 */
public class CompareRegistration_ extends RegDriftEntry {

    /** The menu wording, spelled as {@code plugins.config} spells it. */
    public static final String COMMAND = "Compare Registration Methods...";

    /** The sibling menu item, for a line that asks for less than this entry does. */
    public static final String SIBLING_COMMAND = "Registration Diagnostics...";

    private static final List<Mode> MODES = Collections.unmodifiableList(Arrays.asList(
            Mode.DIAGNOSE, Mode.DIAGNOSE_AND_RECOMMEND, Mode.APPLY, Mode.COMPARE, Mode.SCORE));

    @Override
    public String command() {
        return COMMAND;
    }

    @Override
    public String otherCommand() {
        return SIBLING_COMMAND;
    }

    /** Every mode. This entry turns none of them away. */
    @Override
    public List<Mode> modesRunHere() {
        return MODES;
    }

    @Override
    protected RegDriftDialog newDialog(ImageChoices choices) {
        return new CompareDialog(choices);
    }
}
