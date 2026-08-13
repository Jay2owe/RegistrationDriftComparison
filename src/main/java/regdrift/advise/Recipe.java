/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.advise;

import regdrift.CompareRegistration_;
import regdrift.Recommendation;
import regdrift.autofix.EngineId;
import regdrift.autofix.EngineRegistry;
import sc.fiji.autofix.core.DependencySpec;
import sc.fiji.autofix.core.Sizes;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * How to actually run one engine: where it sits in the menu, the line to paste
 * into a macro, and whether it is on this computer.
 *
 * <p>The everyday version: the ranking says which spanner the measurements
 * support; this is the drawer it lives in.
 *
 * <h2>Where these strings come from, and the one that is absent</h2>
 *
 * <p>A menu path invented from a plugin's name is a wrong answer with a
 * confident tone, so every entry below was read off something rather than
 * guessed, and the sources differ:
 *
 * <ul>
 *   <li><b>Read out of the engine's own {@code plugins.config}</b>, on a Fiji
 *       install on 2026-08-13: MultiStackReg, Descriptor-based registration,
 *       Register Virtual Stack Slices, Linear Stack Alignment with SIFT. Those
 *       files name the menu and the command in as many words.</li>
 *   <li><b>From ImageJ's own naming rule</b>, for engines that ship without a
 *       {@code plugins.config}: a plugin class in the {@code plugins} folder
 *       becomes a Plugins-menu entry whose command is the class name with each
 *       underscore replaced by a space. That is why StackReg's command carries a
 *       trailing space, and why a macro line that dropped it would find no
 *       command. Fast4DReg's entries are macro files inside
 *       {@code plugins/Fast4DReg}, which the same rule puts in a submenu of
 *       that folder's name.</li>
 *   <li><b>From the catalogue's own probe</b>, for Correct 3D drift and Image
 *       Stabilizer: this plugin already looks those two up <em>by menu command</em>
 *       to decide whether they are present, so the command here is the same
 *       string, and if it were wrong the engine would read as absent on a
 *       computer that has it.</li>
 *   <li><b>Not known</b>, for NanoJ-Core. It ships as a packaged class inside a
 *       jar, so ImageJ's naming rule says nothing about where it lands, and this
 *       plugin has not read its {@code plugins.config}. Rather than print a
 *       plausible path, the row says the menu entry has not been read and where
 *       to look for it. One honest gap beats ten confident lines and one wrong
 *       one.</li>
 * </ul>
 *
 * <h2>The macro lines are the recordable form and nothing cleverer</h2>
 *
 * <p>Each line is what ImageJ's own macro recorder writes when somebody runs
 * that menu entry: the command, and settings only where this plugin has seen
 * the option spelling. StackReg carries {@code transformation=Translation}
 * because that is the transform the fingerprint measures and the one the
 * benchmark drove. Everywhere else the line opens the engine's own dialog,
 * which is a truthful recipe rather than a set of options invented for it.
 */
public final class Recipe {

    /** What the {@code menu_path} column says for an engine whose entry is unread. */
    public static final String MENU_PATH_NOT_READ =
            "not read by this plugin - open the Plugins menu after installing it";

    /** What the {@code macro_line} column says for the same. */
    public static final String MACRO_LINE_NOT_READ =
            "not read by this plugin - use ImageJ's macro recorder on the engine's own menu entry";

    private static final Map<EngineId, Recipe> RECIPES = build();

    private final String menuPath;
    private final String macroLine;

    private Recipe(String menuPath, String macroLine) {
        this.menuPath = menuPath;
        this.macroLine = macroLine;
    }

    /** The recipe for one engine. Never null: every catalogue entry has one. */
    public static Recipe forEngine(EngineId engine) {
        Recipe recipe = RECIPES.get(engine);
        if (recipe == null) {
            throw new IllegalStateException("no recipe for " + engine + ", which cannot happen"
                    + " unless a constant was added to EngineId without one");
        }
        return recipe;
    }

    /** Where this engine sits in the Fiji menu. */
    public String menuPath() {
        return menuPath;
    }

    /** A macro line that runs this engine, ready to copy. */
    public String macroLine() {
        return macroLine;
    }

    /**
     * The {@code installed} column's text for a presence: {@code yes},
     * {@code no}, or a version this plugin does not drive.
     *
     * <p>This build answers {@code yes}, {@code no} and {@code unknown} and
     * never {@code wrong_version}. Telling a version apart needs the half of
     * this plugin that drives an engine, which arrives with the harness; until
     * then a version that would not drive reads as present, which is what the
     * probe honestly found.
     */
    public static String installStatus(Recommendation.Presence presence) {
        Recommendation.Presence found =
                presence == null ? Recommendation.Presence.UNKNOWN : presence;
        return found.tableValue();
    }

    /**
     * What the repair panel would do about an engine that is absent, or an empty
     * string when there is nothing to repair.
     *
     * <p>Reading this makes nothing happen. Every repair in this plugin is a
     * button somebody presses in the Engines section, and no measurement, no
     * setting and no macro line reaches one - house rule 9.
     */
    public static String installAction(EngineId engine, Recommendation.Presence presence) {
        if (presence == Recommendation.Presence.PRESENT) return "";
        DependencySpec spec = EngineRegistry.specFor(engine);
        if (!spec.isFixableInApp()) return spec.getNonFixableReason();
        List<String> sites = spec.getUpdateSites();
        String from = sites.isEmpty() ? "" : " from the " + sites.get(0) + " update site";
        String size = Sizes.formatApproxSize(spec.getApproxDownloadSizeBytes())
                .replace("(", "").replace(")", "");
        return "Install it from the Engines section of " + CompareRegistration_.COMMAND
                + ", which fetches " + size + from + " and needs a restart afterwards. Nothing is"
                + " fetched until that button is pressed.";
    }

    /** How large that repair would be, in megabytes, or 0 when there is none. */
    public static double installSizeMb(EngineId engine, Recommendation.Presence presence) {
        if (presence == Recommendation.Presence.PRESENT) return 0;
        DependencySpec spec = EngineRegistry.specFor(engine);
        if (!spec.isFixableInApp()) return 0;
        return spec.getApproxDownloadSizeBytes() / (1024.0 * 1024.0);
    }

    @Override
    public String toString() {
        return menuPath + " | " + macroLine;
    }

    // ------------------------------------------------------------- the entries

    private static Map<EngineId, Recipe> build() {
        Map<EngineId, Recipe> recipes = new EnumMap<EngineId, Recipe>(EngineId.class);

        // ImageJ's naming rule: a plugin class in plugins/ with no plugins.config becomes a
        // Plugins-menu entry whose command is the class name with underscores turned into spaces.
        // Both of these class names end in an underscore, so both commands end in a space.
        recipes.put(EngineId.TURBOREG, new Recipe(
                "Plugins > TurboReg", "run(\"TurboReg \");"));
        recipes.put(EngineId.STACKREG, new Recipe(
                "Plugins > StackReg", "run(\"StackReg \", \"transformation=Translation\");"));

        // plugins.config, read on 2026-08-13, whose three fields are the menu, the command and
        // the entry class:
        //   Plugins>Registration, "MultiStackReg", <the entry class, spelled in EngineRegistry>
        // The class is left unspelled here on purpose. It is a trailing-underscore form, which
        // belongs in the catalogue and nowhere else - a recipe carries the name the engine's own
        // authors write, which is also what somebody types into a search box.
        recipes.put(EngineId.MULTISTACKREG, new Recipe(
                "Plugins > Registration > MultiStackReg", "run(\"MultiStackReg\");"));

        // Looked up by this same command string by the catalogue's own probe.
        recipes.put(EngineId.CORRECT_3D_DRIFT, new Recipe(
                "Plugins > Registration > Correct 3D drift", "run(\"Correct 3D drift\");"));

        // plugins.config, read on 2026-08-13:
        //   Plugins>Registration, "Descriptor-based series registration (2d/3d + t)", ...
        recipes.put(EngineId.DESCRIPTOR_BASED_REGISTRATION, new Recipe(
                "Plugins > Registration > Descriptor-based series registration (2d/3d + t)",
                "run(\"Descriptor-based series registration (2d/3d + t)\");"));

        // plugins.config, read on 2026-08-13:
        //   Plugins>Registration, "Register Virtual Stack Slices", ...
        recipes.put(EngineId.REGISTER_VIRTUAL_STACK_SLICES, new Recipe(
                "Plugins > Registration > Register Virtual Stack Slices",
                "run(\"Register Virtual Stack Slices\");"));

        // plugins.config, read on 2026-08-13:
        //   Plugins>Registration, "Linear Stack Alignment with SIFT", SIFT_Align
        recipes.put(EngineId.LINEAR_STACK_ALIGNMENT_WITH_SIFT, new Recipe(
                "Plugins > Registration > Linear Stack Alignment with SIFT",
                "run(\"Linear Stack Alignment with SIFT\");"));

        // A loose class file with no trailing underscore, so ImageJ's naming rule gives a command
        // with no trailing space - and the catalogue probes for exactly this string.
        recipes.put(EngineId.IMAGE_STABILIZER, new Recipe(
                "Plugins > Image Stabilizer", "run(\"Image Stabilizer\");"));

        // Macro files inside plugins/Fast4DReg, which ImageJ puts in a submenu named after the
        // folder, with each file's underscores turned into spaces. The four files were listed on
        // an install on 2026-08-13; this is the one that estimates and applies drift over time.
        recipes.put(EngineId.FAST_4D_REG, new Recipe(
                "Plugins > Fast4DReg > time estimate+apply",
                "run(\"time estimate+apply\");"));

        // A packaged class inside a jar, so the naming rule says nothing and this plugin has not
        // read its plugins.config. Stated as unread rather than guessed at.
        recipes.put(EngineId.NANOJ_CORE, new Recipe(
                MENU_PATH_NOT_READ, MACRO_LINE_NOT_READ));

        return recipes;
    }
}
