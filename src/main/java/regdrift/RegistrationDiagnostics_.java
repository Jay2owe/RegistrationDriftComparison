/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.IJ;
import ij.plugin.PlugIn;

/**
 * Placeholder entry for {@code Plugins > Registration > Registration
 * Diagnostics...}.
 *
 * <p>It exists so that the menu wiring, the packaging and the install path can
 * be proved before there is anything to run. Stage 04 replaces it with the
 * real dialog and the macro-versus-interactive routing.
 */
public class RegistrationDiagnostics_ implements PlugIn {

    @Override
    public void run(String arg) {
        IJ.showMessage("Registration Diagnostics",
                "Under construction. See docs/regdrift-build/.");
    }
}
