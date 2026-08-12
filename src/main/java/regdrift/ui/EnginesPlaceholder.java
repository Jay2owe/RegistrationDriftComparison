/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Engines section as it stands before engine detection exists.
 *
 * <p>It lists the registration engines this plugin will look for, and says
 * plainly that this build has not looked. That distinction is the whole reason
 * this class is written out rather than left blank: a section that renders
 * "missing" beside six engine names would be reporting a measurement nobody
 * made, and somebody would go and reinstall software they already have. Saying
 * "this build has not checked" costs the reader one sentence and misleads
 * nobody.
 *
 * <p>Replaced by the real panel when the engine catalogue lands: probes, install
 * sizes, versions found, and a repair button on the rows a person can repair
 * from inside the plugin. The names below are the strings that panel will use,
 * and each is spelled the way its own authors spell it, because that spelling is
 * also what somebody types into a search box.
 */
public final class EnginesPlaceholder {

    /** The engines this plugin will look for, spelled as their authors spell them. */
    public static final List<String> CANDIDATE_ENGINES = Collections.unmodifiableList(
            Arrays.asList(
                    "StackReg",
                    "TurboReg",
                    "Correct 3D drift",
                    "Fast4DReg",
                    "Linear Stack Alignment with SIFT",
                    "Image Stabilizer"));

    /** The heading over the engine names. */
    public static final String NAME_HEADING = "Engine";

    /** The heading over the status column. */
    public static final String STATUS_HEADING = "Found in this Fiji?";

    /** What each row says while there is nothing that could have looked. */
    public static final String NOT_CHECKED = "not checked by this build";

    /** The sentence that keeps the section from claiming a measurement. */
    public static final String NOTICE =
            "Engine detection arrives in a later build. The list above is the set of engines this"
                    + " plugin will look for; this build has not looked, so nothing here says"
                    + " whether any of them is present or absent on this computer.";

    /** The promise that a diagnosis needs none of this. */
    public static final String NOTHING_RUNS_HERE =
            "Nothing in this section runs while a recording is being measured, and nothing here is"
                    + " ever fetched or installed without somebody pressing a button to ask for it.";

    private EnginesPlaceholder() {
    }

    /** Draws the section onto a form. */
    public static void render(DialogForm form) {
        form.addHeader("Engines");
        form.addTableRow(NAME_HEADING, STATUS_HEADING, true);
        for (String engine : CANDIDATE_ENGINES) {
            form.addTableRow(engine, NOT_CHECKED, false);
        }
        form.addSpacer(4);
        form.addNote(NOTICE);
        form.addHelpText(NOTHING_RUNS_HERE);
    }
}
