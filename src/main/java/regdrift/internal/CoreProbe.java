/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.internal;

import sc.fiji.autofix.core.Product;
import sc.fiji.oc3d.core.macro.MacroOptions;
import sc.fiji.oc3d.core.ui.ToggleSwitch;

/**
 * One real call into each shaded core, so the exit gate can prove the shading
 * and the relocation both happened.
 *
 * <p>A wrong Maven coordinate in {@code <artifactSet>} matches nothing, the
 * build succeeds, and the jar ships without the core classes; the first anyone
 * hears about it is a {@code NoClassDefFoundError} the moment a user clicks
 * Run. These three calls turn that into a build failure instead.
 *
 * <p>Every name here is rewritten by the shade plugin, so at test time
 * (unshaded classes) the packages read {@code sc.fiji.*} and inside the
 * packaged jar they read {@code regdrift.internal.*}. Both are correct; the
 * two constants below say which is which.
 *
 * <p>Delete when stages 02 and 05 use the cores for real.
 */
public final class CoreProbe {

    /** Package prefix the core classes carry inside the packaged jar. */
    public static final String SHADED_PREFIX = "regdrift.internal.";

    /** Package prefix they carry before packaging, i.e. during {@code mvn test}. */
    public static final String UNSHADED_PREFIX = "sc.fiji.";

    private CoreProbe() { }

    /**
     * A real call through {@code oc3d-core}: pull a value out of an ImageJ
     * macro option string. Returns {@code "yes"} for the fixed input below.
     */
    public static String oc3dCoreCall() {
        return MacroOptions.value("shaded=yes", "shaded", "no");
    }

    /**
     * A real call through {@code autofix-core}: turn a product display name
     * into the lowercase slug that ends up in filenames. Returns
     * {@code "registrationdriftcomparison"}.
     *
     * <p>Stage 05 owns the product name this plugin actually ships with, since
     * it is what the restart log and the deferred-disable script are named
     * after. This is a probe, not that decision.
     */
    public static String autofixCoreCall() {
        return Product.named("RegistrationDriftComparison").slug();
    }

    /** Fully-qualified name of the {@code oc3d-core} class used above. */
    public static String oc3dCoreClassName() {
        return MacroOptions.class.getName();
    }

    /** Fully-qualified name of the {@code autofix-core} class used above. */
    public static String autofixCoreClassName() {
        return Product.class.getName();
    }

    /**
     * Fully-qualified name of the shared toggle widget.
     *
     * <p>Recorded here because stage 04 needs to know which of the two routes
     * this repo took: the relocated widget out of {@code oc3d-core}, or a
     * verbatim copy of CPC's. It is the relocated one, and this is the check
     * that it survives packaging.
     */
    public static String toggleSwitchClassName() {
        return ToggleSwitch.class.getName();
    }
}
