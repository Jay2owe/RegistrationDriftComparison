/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import regdrift.ui.DiagnosticsDialog;
import regdrift.ui.ImageChoices;
import regdrift.ui.RegDriftDialog;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code Plugins > Registration > Registration Diagnostics...}
 *
 * <p>The entry that measures and stops: what kind of movement the recording
 * holds, whether it can be registered at all, and which engines the measurement
 * supports. Everything it does works on a Fiji with no registration engine
 * installed, which is why it is its own menu item - somebody whose recording may
 * not be registrable should be able to find that out without first being shown a
 * list of software.
 *
 * <p>It therefore offers the two measuring modes and turns the other three away
 * with a sentence naming the menu item that runs them. It does not quietly run
 * something else instead: a line that asked to apply an engine and got a
 * diagnosis would look like it worked.
 */
public class RegistrationDiagnostics_ extends RegDriftEntry {

    /** The menu wording, spelled as {@code plugins.config} spells it. */
    public static final String COMMAND = "Registration Diagnostics...";

    /** The sibling menu item, which runs the three modes this entry turns away. */
    public static final String SIBLING_COMMAND = "Compare Registration Methods...";

    private static final List<Mode> MODES = Collections.unmodifiableList(Arrays.asList(
            Mode.DIAGNOSE, Mode.DIAGNOSE_AND_RECOMMEND));

    @Override
    public String command() {
        return COMMAND;
    }

    @Override
    public String otherCommand() {
        return SIBLING_COMMAND;
    }

    /** The two modes that measure. */
    @Override
    public List<Mode> modesRunHere() {
        return MODES;
    }

    @Override
    protected RegDriftDialog newDialog(ImageChoices choices) {
        return new DiagnosticsDialog(choices);
    }
}
