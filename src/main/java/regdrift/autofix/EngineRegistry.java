/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.autofix;

import sc.fiji.autofix.core.DependencySpec;
import sc.fiji.autofix.core.DependencyStatus;
import sc.fiji.autofix.core.Probe;
import sc.fiji.autofix.core.ProbeContext;
import sc.fiji.autofix.core.Probes;
import sc.fiji.autofix.core.SpecCatalogue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * This plugin's own list of registration engines: how to tell whether each one
 * is on this computer, where it comes from, under what terms, what installing
 * it would cost, and whether this plugin may install it at all.
 *
 * <p>The machinery around it is shared with two sibling plugins. This list is
 * not, and never will be: FLASH needs a deep-learning runtime, PULSE needs a
 * tracking library, and putting all three lists in one place would put every
 * plugin's dependencies in every other plugin's window.
 *
 * <h2>What a person may and may not be offered</h2>
 *
 * <p>Three separate reasons an engine gets no install button, and the row says
 * which one applies:
 *
 * <ul>
 *   <li><b>It came with Fiji.</b> Correct 3D drift, Descriptor-based
 *       registration, Register Virtual Stack Slices and Linear Stack Alignment
 *       with SIFT are part of the Fiji download. A button that fetched them
 *       would be fetching something the user already has, and a button that did
 *       nothing would be worse than no button at all. If one of them is absent,
 *       the Fiji install is incomplete and the repair is Fiji's own updater.</li>
 *   <li><b>The terms are not clear enough.</b> An engine whose authors have not
 *       plainly said that anyone may fetch and install it gets no button and a
 *       sentence naming the page to visit instead. Not having checked is not a
 *       reason to try; it is a reason to send somebody to the page.</li>
 *   <li><b>Nothing measured.</b> A download with no measured size and no
 *       measured digest cannot be verified after it arrives, so it is not
 *       offered. Every size and digest below was measured from the file the
 *       stated address actually served, on 2026-08-13, and never estimated.</li>
 * </ul>
 *
 * <h2>The terms, as checked on 2026-08-13</h2>
 *
 * <p>TurboReg and StackReg were the ones to check first, because they are the
 * engines the benchmark measured most and the ones the harness drives most
 * directly. Their terms changed in April 2023: both pages now carry the GNU
 * General Public License v3 under "Conditions of Use", and the ImageJ project's
 * own licensing page for that group records the same, together with the message
 * from the group's head confirming the move. Before that they carried a
 * research-use restriction with a bar on passing the software on. The current
 * terms plainly permit a fetch from the group's own update site, so both carry a
 * button.
 *
 * <p>MultiStackReg does not: the file that declares its terms names none at all,
 * with a public-license line commented out beside the empty one. That is exactly
 * the case the second rule above is written for.
 *
 * <p>Image Stabilizer is free to use, copy and pass on, but for-profit use needs
 * the author's written consent, and it is distributed as loose compiled files
 * rather than as a versioned archive this plugin could check a digest against.
 * Either fact on its own would settle it.
 *
 * <h2>Third-party names are strings and must stay readable</h2>
 *
 * <p>The probes below look classes up by name at run time. Shading rewrites
 * compiled references, and it also rewrites any piece of text that looks like a
 * package being moved - so a third-party class name must never be allowed to
 * resemble one. None of the names here does: they are other people's classes,
 * in other people's packages, and this plugin moves neither.
 *
 * <h2>Pinned addresses go stale</h2>
 *
 * <p>An update site address carries the moment the author last uploaded, so
 * every address below stops working the day that file is replaced. That is the
 * intended failure: a changed file would no longer match its recorded digest,
 * and installing something whose contents nobody checked is worse than an
 * address that has expired. The version each address pins is recorded on the
 * spec, so the half of this plugin that drives an engine can say which version
 * the measurements were taken against.
 */
public final class EngineRegistry implements SpecCatalogue {

    /** The two things a run does with an engine, named as the dialog names them. */
    static final String FEATURE_COMPARE = "Compare installed engines";

    /** The mode that runs the engine the ranking puts first. */
    static final String FEATURE_APPLY = "Recommend and apply";

    /*
     * Third-party entry classes, exactly as their own authors named them. Two of
     * them end in an underscore because that is how ImageJ 1.x marks a class as
     * a plugin, and it is part of the class name, not a decoration: a probe that
     * dropped it would look up a class that does not exist. These are the one
     * place in this plugin where those trailing-underscore forms are correct;
     * everywhere a person reads a name, it is spelled the way the authors spell
     * it in their own documentation.
     */
    static final String TURBOREG_CLASS = "TurboReg_";
    static final String STACKREG_CLASS = "StackReg_";
    static final String MULTISTACKREG_CLASS = "de.embl.cmci.registration.MultiStackReg_";
    static final String FAST_4D_REG_CLASS = "gui.DriftCorrection_";
    static final String NANOJ_CORE_CLASS = "nanoj.core.java.gui.DriftCorrection_";

    /**
     * Menu commands, as ImageJ registers them. Text, so shading cannot touch
     * them either.
     *
     * <p>An engine that came with Fiji is found by its command rather than by
     * its class. The command table is what decides whether the plugin can
     * actually be run, so it is the more truthful answer of the two, and it is
     * the faster one as well.
     */
    static final String CORRECT_3D_DRIFT_COMMAND = "Correct 3D drift";
    static final String DESCRIPTOR_COMMAND = "Descriptor-based series registration (2d/3d + t)";
    static final String RVSS_COMMAND = "Register Virtual Stack Slices";
    static final String SIFT_COMMAND = "Linear Stack Alignment with SIFT";
    static final String IMAGE_STABILIZER_COMMAND = "Image Stabilizer";

    /** The two folders inside Fiji.app that an engine's files land in. */
    private static final String PLUGINS = "plugins";
    private static final String JARS = "jars";
    private static final String FAST_4D_REG_FOLDER = "plugins/Fast4DReg";

    /** Where the files come from, and where a person goes to read the terms. */
    private static final String BIG_EPFL_SITE = "BIG-EPFL";
    private static final String BIG_EPFL_URL = "https://sites.imagej.net/BIG-EPFL/";
    private static final String TURBOREG_PAGE = "https://bigwww.epfl.ch/thevenaz/turboreg/";
    private static final String STACKREG_PAGE = "https://bigwww.epfl.ch/thevenaz/stackreg/";
    private static final String BIG_LICENSE_PAGE = "https://imagej.net/licensing/big";
    private static final String MULTISTACKREG_SITE = "MultiStackReg";
    private static final String MULTISTACKREG_PAGE = "https://imagej.net/plugins/multistackreg";
    private static final String FAST_4D_REG_SITE = "Fast4DReg";
    private static final String FAST_4D_REG_URL = "https://sites.imagej.net/Fast4DReg/";
    private static final String FAST_4D_REG_PAGE = "https://imagej.net/plugins/fast4dreg";
    private static final String NANOJ_SITE = "NanoJ-Core";
    private static final String NANOJ_URL = "https://sites.imagej.net/NanoJ/";
    private static final String NANOJ_PAGE = "https://github.com/HenriquesLab/NanoJ-Core";
    private static final String IMAGE_STABILIZER_PAGE =
            "https://www.cs.cmu.edu/~kangli/code/Image_Stabilizer.html";
    private static final String FIJI_UPDATER = "Help > Update...";

    /** The day every licence statement, size and digest below was read off its source. */
    static final String CHECKED_ON = "2026-08-13";

    // ------------------------------------------------------------- the files

    /**
     * One file to fetch, with the two numbers that make fetching it safe.
     *
     * <p>The size and the digest are kept beside the address they were measured
     * from, and the download size a button shows is added up from these rather
     * than typed in separately. A spec whose stated cost disagreed with what it
     * would actually fetch is a spec that lies to somebody about to click.
     */
    private static final class Fetchable {

        private final DependencySpec.Artifact artifact;
        private final long bytes;

        Fetchable(String label, String file, String prefix, String folder,
                  String url, String sha1, long bytes, boolean acceptAnyExisting) {
            this.artifact = new DependencySpec.Artifact(
                    label, file, prefix, folder, url, sha1, acceptAnyExisting);
            this.bytes = bytes;
        }

        /** A file this plugin checks for but never fetches: no address, no digest. */
        static Fetchable present(String label, String file, String prefix, String folder) {
            return new Fetchable(label, file, prefix, folder, "", "", 0L, true);
        }
    }

    /**
     * The files first, then the classes, and stop at the first bad answer.
     *
     * <p>Both questions have to be answered for an engine this plugin can
     * repair: the right files have to be on disk, and the class in them has to
     * load. Asking them in this order is a decision about speed and it is worth
     * spelling out. A Fiji install carries several hundred archives, and asking
     * Java for a class that is <em>not</em> in any of them means opening every
     * one before the answer comes back - about a second and a half per absent
     * engine on the machine this was measured on. Asking for a class that is
     * there costs nothing, because the search stops at the first hit.
     *
     * <p>So the file check goes first. When an engine is absent, the files
     * answer immediately and the slow question is never asked; when it is here,
     * the class question is the fast kind. Measured on a real install, that is
     * the difference between a window that opens in five seconds and one that
     * opens in a tenth of one.
     *
     * <p>It also reads better. "The files are here but the class will not load"
     * is a sentence worth showing somebody - it means a restart is pending - and
     * it is a sentence that makes no sense before the files are there.
     */
    private static Probe filesThenClasses(final List<DependencySpec.Artifact> files,
                                          final String... classNames) {
        final Probe onDisk = Probes.artifactProbe(files, Collections.<String>emptyList());
        final Probe loadable = Probes.classProbe(classNames);
        return new Probe() {
            @Override
            public DependencyStatus probe(ProbeContext context) {
                DependencyStatus found = onDisk.probe(context);
                if (found == null || !found.isPresent()) return found;
                return loadable.probe(context);
            }
        };
    }

    private static List<DependencySpec.Artifact> artifactsOf(Fetchable... files) {
        List<DependencySpec.Artifact> artifacts = new ArrayList<DependencySpec.Artifact>();
        for (Fetchable file : files) artifacts.add(file.artifact);
        return Collections.unmodifiableList(artifacts);
    }

    private static long bytesOf(Fetchable... files) {
        long total = 0L;
        for (Fetchable file : files) total += file.bytes;
        return total;
    }

    private static final Fetchable TURBOREG_JAR = new Fetchable(
            "TurboReg", "TurboReg_-2.0.1.jar", "TurboReg_-", PLUGINS,
            BIG_EPFL_URL + "plugins/TurboReg_-2.0.1.jar-20241012175606",
            "b567a28723d0600b1879105bd2616c856dd1c4de", 96387L, false);

    private static final Fetchable STACKREG_JAR = new Fetchable(
            "StackReg", "StackReg_-2.0.1.jar", "StackReg_-", PLUGINS,
            BIG_EPFL_URL + "plugins/StackReg_-2.0.1.jar-20241012175606",
            "4e44bf5fffb08be257a1558240ff77c63064013a", 20947L, false);

    private static final Fetchable MULTISTACKREG_JAR = Fetchable.present(
            "MultiStackReg", "MultiStackRegistration_-1.46.5.jar",
            "MultiStackRegistration_-", PLUGINS);

    private static final Fetchable CORRECT_3D_DRIFT_JAR = Fetchable.present(
            "Correct 3D drift", "Correct_3D_Drift-1.0.7.jar", "Correct_3D_Drift-", JARS);

    private static final Fetchable DESCRIPTOR_JAR = Fetchable.present(
            "Descriptor-based registration", "Descriptor_based_registration-2.1.8.jar",
            "Descriptor_based_registration-", PLUGINS);

    private static final Fetchable RVSS_JAR = Fetchable.present(
            "Register Virtual Stack Slices", "register_virtual_stack_slices-3.0.8.jar",
            "register_virtual_stack_slices-", PLUGINS);

    private static final Fetchable SIFT_JAR = Fetchable.present(
            "Linear Stack Alignment with SIFT", "mpicbg_-1.6.0.jar", "mpicbg_-", PLUGINS);

    /*
     * A loose compiled class rather than an archive, because that is how its
     * author publishes it: no version in the name, so no version to check and
     * nothing for this plugin to verify a download against. Its author also
     * says it may be put in a folder inside plugins/, which this check would
     * not see - hence the menu command in front of it, which is the answer that
     * holds wherever the file was put.
     */
    private static final Fetchable IMAGE_STABILIZER_FILE = Fetchable.present(
            "Image Stabilizer", "Image_Stabilizer.class", "Image_Stabilizer", PLUGINS);

    private static final Fetchable FAST_4D_REG_JAR = new Fetchable(
            "Fast4DReg", "Fast4DReg_-2.4.0-jar-with-dependencies.jar", "Fast4DReg_-",
            FAST_4D_REG_FOLDER,
            FAST_4D_REG_URL
                    + "plugins/Fast4DReg/Fast4DReg_-2.4.0-jar-with-dependencies.jar-20260604184427",
            "67b9e10c68a7cecb3d8598f6fc64250b01c1bc01", 7096836L, false);

    /*
     * Fast4DReg's menu entries are macro files, not classes: without these four
     * the archive above is a library with nothing to start it from. They are
     * listed as files to fetch for that reason, and their digests were measured
     * the same way the archive's was.
     */
    private static final Fetchable FAST_4D_REG_CHANNEL_APPLY = new Fetchable(
            "Fast4DReg channel macro", "channel_apply.ijm", "channel_apply", FAST_4D_REG_FOLDER,
            FAST_4D_REG_URL + "plugins/Fast4DReg/channel_apply.ijm-20231006094047",
            "4b3a023ecb0f710f7467226de0291658f9524493", 11750L, false);

    private static final Fetchable FAST_4D_REG_TIME_APPLY = new Fetchable(
            "Fast4DReg time macro", "time_apply.ijm", "time_apply", FAST_4D_REG_FOLDER,
            FAST_4D_REG_URL + "plugins/Fast4DReg/time_apply.ijm-20231006094047",
            "a2ed116c11788461ab18661cc501b33a5faf5534", 10599L, false);

    private static final Fetchable FAST_4D_REG_TIME_ESTIMATE = new Fetchable(
            "Fast4DReg time estimate macro", "time_estimate+apply.ijm", "time_estimate",
            FAST_4D_REG_FOLDER,
            FAST_4D_REG_URL + "plugins/Fast4DReg/time_estimate+apply.ijm-20231006094047",
            "978c6e090d42af548b3d0275f113ce27d19df733", 19157L, false);

    private static final Fetchable FAST_4D_REG_CHANNEL_ESTIMATE = new Fetchable(
            "Fast4DReg channel estimate macro", "channel_estimate+apply.ijm", "channel_estimate",
            FAST_4D_REG_FOLDER,
            FAST_4D_REG_URL + "plugins/Fast4DReg/channel_estimate+apply.ijm-20231006094047",
            "270da2f8fc6b8b8d81c3c86f50d22aa5d5540b6b", 19515L, false);

    private static final Fetchable NANOJ_CORE_JAR = new Fetchable(
            "NanoJ-Core", "NanoJ_Core.jar", "NanoJ_Core", PLUGINS,
            NANOJ_URL + "plugins/NanoJ_Core.jar-20181105111906",
            "18afcec363076050ed0c1abb09800a9f9749182f", 721881L, false);

    private static final Fetchable NANOJ_APARAPI_JAR = new Fetchable(
            "NanoJ-Core parallel runtime", "aparapi.jar", "aparapi", JARS,
            NANOJ_URL + "jars/aparapi.jar-20150326174636",
            "eded340e651a573fc8f931ed7ffcb284eaecafd0", 556811L, false);

    private static final Fetchable NANOJ_TRANSFORMS_JAR = new Fetchable(
            "NanoJ-Core transform library", "JTransforms-3.1-with-dependencies.jar",
            "JTransforms-", JARS,
            NANOJ_URL + "jars/JTransforms-3.1-with-dependencies.jar-20160227165336",
            "82f06f64b95f4d1da1dfc7dec02628a157ce8692", 1501730L, false);

    // -------------------------------------------------------------- the list

    private static final List<DependencySpec> SPECS = Collections.unmodifiableList(build());

    private static final EngineRegistry INSTANCE = new EngineRegistry();

    private EngineRegistry() {
    }

    /** The catalogue, as the shared chassis consumes it. */
    public static EngineRegistry catalogue() {
        return INSTANCE;
    }

    @Override
    public List<DependencySpec> all() {
        return SPECS;
    }

    /** Every engine, in the order the panel shows them. */
    public static List<DependencySpec> specs() {
        return SPECS;
    }

    /** The entry for one engine. Never null: every constant has a spec. */
    public static DependencySpec specFor(EngineId id) {
        for (DependencySpec spec : SPECS) {
            if (spec.getId() == id) return spec;
        }
        throw new IllegalStateException("no catalogue entry for " + id
                + ", which cannot happen unless a constant was added to EngineId without one");
    }

    /** How an engine's own authors spell its name. */
    public static String displayName(EngineId id) {
        return specFor(id).getDisplayName();
    }

    /**
     * The engine a piece of text names, or null when it names none.
     *
     * <p>Matched without regard to capitalization, because a name typed into a
     * macro line comes from a person's memory rather than from this list, and
     * refusing {@code turboreg} would be pedantry with a stack trace attached.
     * The spelling this plugin then shows is the authors' own.
     */
    public static EngineId byDisplayName(String name) {
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) return null;
        for (DependencySpec spec : SPECS) {
            if (spec.getDisplayName().equalsIgnoreCase(wanted)) return (EngineId) spec.getId();
        }
        return null;
    }

    /** Every engine name, spelled the way its authors spell it, in catalogue order. */
    public static List<String> displayNames() {
        List<String> names = new ArrayList<String>(SPECS.size());
        for (DependencySpec spec : SPECS) names.add(spec.getDisplayName());
        return Collections.unmodifiableList(names);
    }

    // ------------------------------------------------------------ the specs

    private static List<DependencySpec> build() {
        List<DependencySpec> specs = new ArrayList<DependencySpec>();

        specs.add(DependencySpec.builder(EngineId.TURBOREG, "TurboReg")
                .description("Pyramidal intensity-based registration from the Biomedical Imaging"
                        + " Group at EPFL. Translation, rigid body, scaled rotation, affine and"
                        + " bilinear alignment of one image onto another.")
                .affectedFeatures(FEATURE_COMPARE, FEATURE_APPLY)
                .detectionStrategyLabel("Pinned jar in Fiji's plugins folder, then its entry class")
                .probe(filesThenClasses(artifactsOf(TURBOREG_JAR), TURBOREG_CLASS))
                .artifacts(artifactsOf(TURBOREG_JAR))
                .approxDownloadSizeBytes(bytesOf(TURBOREG_JAR))
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install TurboReg%s")
                .presentButtonLabel("Verify")
                .updateSites(BIG_EPFL_SITE)
                .attribute("version", "2.0.1")
                .attribute("source", BIG_EPFL_URL)
                .attribute("page", TURBOREG_PAGE)
                .attribute("license", "GNU General Public License v3 or later")
                .attribute("licenseSource", BIG_LICENSE_PAGE)
                .attribute("licenseCheckedOn", CHECKED_ON)
                .build());

        specs.add(DependencySpec.builder(EngineId.STACKREG, "StackReg")
                .description("Whole-stack registration from the Biomedical Imaging Group at EPFL,"
                        + " which drives TurboReg frame by frame. Installing it installs TurboReg"
                        + " as well, because it calls TurboReg while it runs.")
                .affectedFeatures(FEATURE_COMPARE, FEATURE_APPLY)
                .detectionStrategyLabel("Both pinned jars and both entry classes, checked together")
                .probe(filesThenClasses(artifactsOf(STACKREG_JAR, TURBOREG_JAR),
                        STACKREG_CLASS, TURBOREG_CLASS))
                .artifacts(artifactsOf(STACKREG_JAR, TURBOREG_JAR))
                .approxDownloadSizeBytes(bytesOf(STACKREG_JAR, TURBOREG_JAR))
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install StackReg%s")
                .presentButtonLabel("Verify")
                .updateSites(BIG_EPFL_SITE)
                .attribute("version", "2.0.1")
                .attribute("source", BIG_EPFL_URL)
                .attribute("page", STACKREG_PAGE)
                .attribute("license", "GNU General Public License v3 or later")
                .attribute("licenseSource", BIG_LICENSE_PAGE)
                .attribute("licenseCheckedOn", CHECKED_ON)
                .attribute("requires", EngineId.TURBOREG.name())
                .build());

        specs.add(DependencySpec.builder(EngineId.MULTISTACKREG, "MultiStackReg")
                .description("Brad Busse's extension of StackReg, in the form Kota Miura made"
                        + " usable from a script. It drives TurboReg, so both have to be present"
                        + " for it to run.")
                .affectedFeatures(FEATURE_COMPARE, FEATURE_APPLY)
                .detectionStrategyLabel("Its jar and entry class, checked together with TurboReg")
                .probe(filesThenClasses(artifactsOf(MULTISTACKREG_JAR, TURBOREG_JAR),
                        MULTISTACKREG_CLASS, TURBOREG_CLASS))
                .artifacts(artifactsOf(MULTISTACKREG_JAR, TURBOREG_JAR))
                .restartRequired(true)
                .fixableInApp(false)
                .nonFixableReason("The file that states MultiStackReg's terms of use names no"
                        + " terms at all, so this plugin does not fetch it for you. Read"
                        + " " + MULTISTACKREG_PAGE + ", then turn on the " + MULTISTACKREG_SITE
                        + " update site under " + FIJI_UPDATER + " and restart Fiji. It also"
                        + " needs TurboReg, which the row above can install.")
                .updateSites(MULTISTACKREG_SITE)
                .attribute("version", "1.46.5")
                .attribute("page", MULTISTACKREG_PAGE)
                .attribute("license", "not stated by its authors")
                .attribute("licenseSource", "META-INF/maven/de.embl.cmci/MultiStackRegistration_")
                .attribute("licenseCheckedOn", CHECKED_ON)
                .attribute("requires", EngineId.TURBOREG.name())
                .build());

        specs.add(DependencySpec.builder(EngineId.CORRECT_3D_DRIFT, "Correct 3D drift")
                .description("Phase-correlation drift correction for 2D and 3D time-lapse data."
                        + " It is a script rather than a compiled plugin, so it is found by its"
                        + " menu command and by the archive that carries it.")
                .affectedFeatures(FEATURE_COMPARE, FEATURE_APPLY)
                .detectionStrategyLabel("Menu command, or the archive in Fiji's jars folder")
                .probe(Probes.anyOf(
                        Probes.commandProbe(CORRECT_3D_DRIFT_COMMAND),
                        Probes.artifactProbe(artifactsOf(CORRECT_3D_DRIFT_JAR),
                                Collections.<String>emptyList())))
                .artifacts(artifactsOf(CORRECT_3D_DRIFT_JAR))
                .restartRequired(true)
                .fixableInApp(false)
                .nonFixableReason(shipsWithFiji("Correct 3D drift"))
                .attribute("version", "1.0.7")
                .attribute("page", "https://imagej.net/plugins/correct-3d-drift")
                .attribute("license", "GNU General Public License v3")
                .attribute("licenseCheckedOn", CHECKED_ON)
                .attribute("shipsWithFiji", Boolean.TRUE)
                .build());

        specs.add(DependencySpec.builder(
                        EngineId.DESCRIPTOR_BASED_REGISTRATION, "Descriptor-based registration")
                .description("Registration by matching interest points between frames, for 2D and"
                        + " 3D series.")
                .affectedFeatures(FEATURE_COMPARE, FEATURE_APPLY)
                .detectionStrategyLabel("Menu command, or the jar in Fiji's plugins folder")
                .probe(Probes.anyOf(
                        Probes.commandProbe(DESCRIPTOR_COMMAND),
                        Probes.artifactProbe(artifactsOf(DESCRIPTOR_JAR),
                                Collections.<String>emptyList())))
                .artifacts(artifactsOf(DESCRIPTOR_JAR))
                .restartRequired(true)
                .fixableInApp(false)
                .nonFixableReason(shipsWithFiji("Descriptor-based registration"))
                .attribute("version", "2.1.8")
                .attribute("page", "https://imagej.net/plugins/descriptor-based-registration")
                .attribute("license", "GNU General Public License v3")
                .attribute("licenseCheckedOn", CHECKED_ON)
                .attribute("shipsWithFiji", Boolean.TRUE)
                .build());

        specs.add(DependencySpec.builder(
                        EngineId.REGISTER_VIRTUAL_STACK_SLICES, "Register Virtual Stack Slices")
                .description("Registers a folder of images as a virtual stack, writing the"
                        + " registered slices out rather than holding them in memory.")
                .affectedFeatures(FEATURE_COMPARE, FEATURE_APPLY)
                .detectionStrategyLabel("Menu command, or the jar in Fiji's plugins folder")
                .probe(Probes.anyOf(
                        Probes.commandProbe(RVSS_COMMAND),
                        Probes.artifactProbe(artifactsOf(RVSS_JAR),
                                Collections.<String>emptyList())))
                .artifacts(artifactsOf(RVSS_JAR))
                .restartRequired(true)
                .fixableInApp(false)
                .nonFixableReason(shipsWithFiji("Register Virtual Stack Slices"))
                .attribute("version", "3.0.8")
                .attribute("page", "https://imagej.net/plugins/register-virtual-stack-slices")
                .attribute("license", "GNU General Public License v3")
                .attribute("licenseCheckedOn", CHECKED_ON)
                .attribute("shipsWithFiji", Boolean.TRUE)
                .build());

        specs.add(DependencySpec.builder(EngineId.LINEAR_STACK_ALIGNMENT_WITH_SIFT,
                        "Linear Stack Alignment with SIFT")
                .description("Feature-based stack alignment using scale-invariant feature"
                        + " correspondences, from the mpicbg library.")
                .affectedFeatures(FEATURE_COMPARE, FEATURE_APPLY)
                .detectionStrategyLabel("Menu command, or the mpicbg jar in Fiji's plugins folder")
                .probe(Probes.anyOf(
                        Probes.commandProbe(SIFT_COMMAND),
                        Probes.artifactProbe(artifactsOf(SIFT_JAR),
                                Collections.<String>emptyList())))
                .artifacts(artifactsOf(SIFT_JAR))
                .restartRequired(true)
                .fixableInApp(false)
                .nonFixableReason(shipsWithFiji("Linear Stack Alignment with SIFT"))
                .attribute("version", "1.6.0")
                .attribute("page",
                        "https://imagej.net/plugins/linear-stack-alignment-with-sift")
                .attribute("license", "GNU General Public License v3")
                .attribute("licenseCheckedOn", CHECKED_ON)
                .attribute("shipsWithFiji", Boolean.TRUE)
                .build());

        specs.add(DependencySpec.builder(EngineId.IMAGE_STABILIZER, "Image Stabilizer")
                .description("Kang Li's Lucas-Kanade stabilizer, which tracks a running template"
                        + " through the stack. Last updated in June 2009.")
                .affectedFeatures(FEATURE_COMPARE, FEATURE_APPLY)
                .detectionStrategyLabel("Menu command, or its class file in Fiji's plugins folder")
                .probe(Probes.anyOf(
                        Probes.commandProbe(IMAGE_STABILIZER_COMMAND),
                        Probes.artifactProbe(artifactsOf(IMAGE_STABILIZER_FILE),
                                Collections.<String>emptyList())))
                .artifacts(artifactsOf(IMAGE_STABILIZER_FILE))
                .restartRequired(true)
                .fixableInApp(false)
                .nonFixableReason("Image Stabilizer is free to use and pass on, but for-profit use"
                        + " needs its author's written consent, and it is published as loose"
                        + " compiled files with no version and no digest this plugin could check"
                        + " what it fetched against. Download it from "
                        + IMAGE_STABILIZER_PAGE + ", put both class files in Fiji's plugins"
                        + " folder, and restart Fiji.")
                .attribute("page", IMAGE_STABILIZER_PAGE)
                .attribute("license", "free of charge for any purpose; for-profit use needs the"
                        + " author's consent")
                .attribute("licenseSource", IMAGE_STABILIZER_PAGE)
                .attribute("licenseCheckedOn", CHECKED_ON)
                .build());

        specs.add(DependencySpec.builder(EngineId.FAST_4D_REG, "Fast4DReg")
                .description("Drift correction for 2D and 3D time-lapse data and channel"
                        + " alignment for multichannel stacks, from CellMigrationLab. Its menu"
                        + " entries are macro files, so they are installed beside the library.")
                .affectedFeatures(FEATURE_COMPARE, FEATURE_APPLY)
                .detectionStrategyLabel("Every pinned file under plugins/Fast4DReg, then its entry"
                        + " class")
                .probe(filesThenClasses(fast4DRegFiles(), FAST_4D_REG_CLASS))
                .artifacts(fast4DRegFiles())
                .approxDownloadSizeBytes(bytesOf(FAST_4D_REG_JAR, FAST_4D_REG_CHANNEL_APPLY,
                        FAST_4D_REG_TIME_APPLY, FAST_4D_REG_TIME_ESTIMATE,
                        FAST_4D_REG_CHANNEL_ESTIMATE))
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install Fast4DReg%s")
                .presentButtonLabel("Verify")
                .updateSites(FAST_4D_REG_SITE)
                .attribute("version", "2.4.0")
                .attribute("source", FAST_4D_REG_URL)
                .attribute("page", FAST_4D_REG_PAGE)
                .attribute("license", "MIT License")
                .attribute("licenseSource", "https://github.com/CellMigrationLab/Fast4DReg")
                .attribute("licenseCheckedOn", CHECKED_ON)
                .build());

        specs.add(DependencySpec.builder(EngineId.NANOJ_CORE, "NanoJ-Core")
                .description("Drift estimation and correction from the Henriques lab's NanoJ"
                        + " toolset. It brings two libraries of its own, which is most of what"
                        + " the download weighs.")
                .affectedFeatures(FEATURE_COMPARE, FEATURE_APPLY)
                .detectionStrategyLabel("All three pinned jars, then its entry class")
                .probe(filesThenClasses(nanoJFiles(), NANOJ_CORE_CLASS))
                .artifacts(nanoJFiles())
                .approxDownloadSizeBytes(
                        bytesOf(NANOJ_CORE_JAR, NANOJ_APARAPI_JAR, NANOJ_TRANSFORMS_JAR))
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install NanoJ-Core%s")
                .presentButtonLabel("Verify")
                .updateSites(NANOJ_SITE)
                .attribute("version", "2018-11-05")
                .attribute("source", NANOJ_URL)
                .attribute("page", NANOJ_PAGE)
                .attribute("license", "GNU General Public License v3.0")
                .attribute("licenseSource", NANOJ_PAGE)
                .attribute("licenseCheckedOn", CHECKED_ON)
                .build());

        return specs;
    }

    private static List<DependencySpec.Artifact> fast4DRegFiles() {
        return artifactsOf(FAST_4D_REG_JAR, FAST_4D_REG_CHANNEL_APPLY, FAST_4D_REG_TIME_APPLY,
                FAST_4D_REG_TIME_ESTIMATE, FAST_4D_REG_CHANNEL_ESTIMATE);
    }

    private static List<DependencySpec.Artifact> nanoJFiles() {
        return artifactsOf(NANOJ_CORE_JAR, NANOJ_APARAPI_JAR, NANOJ_TRANSFORMS_JAR);
    }

    /**
     * What a row says about an engine that arrived with Fiji and has gone
     * missing. Fetching it here would be fetching a piece of Fiji, which is
     * Fiji's own updater's job and nobody else's.
     */
    private static String shipsWithFiji(String engine) {
        return engine + " is part of the Fiji download, so this plugin does not fetch it"
                + " separately. If it is absent, this Fiji install is incomplete: run "
                + FIJI_UPDATER + " and restart Fiji.";
    }

    /** Every address this catalogue would ever fetch from, for a test to inspect. */
    static List<String> allDownloadUrls() {
        List<String> urls = new ArrayList<String>();
        for (DependencySpec spec : SPECS) {
            for (DependencySpec.Artifact artifact : spec.getArtifacts()) {
                String url = artifact.getDownloadUrl();
                if (url != null && !url.trim().isEmpty()) urls.add(url.trim());
            }
        }
        return urls;
    }

    /** The names of the update sites this catalogue mentions, in first-mention order. */
    static List<String> allUpdateSites() {
        List<String> sites = new ArrayList<String>();
        for (DependencySpec spec : SPECS) {
            for (String site : spec.getUpdateSites()) {
                if (!sites.contains(site)) sites.add(site);
            }
        }
        return sites;
    }

    /** Every third-party class name a probe looks up, for a test to inspect. */
    static List<String> allProbedClassNames() {
        return Collections.unmodifiableList(Arrays.asList(
                TURBOREG_CLASS, STACKREG_CLASS, MULTISTACKREG_CLASS,
                FAST_4D_REG_CLASS, NANOJ_CORE_CLASS));
    }

    /** Every menu command a probe looks up, for a test to inspect. */
    static List<String> allProbedCommands() {
        return Collections.unmodifiableList(Arrays.asList(
                CORRECT_3D_DRIFT_COMMAND, DESCRIPTOR_COMMAND, RVSS_COMMAND, SIFT_COMMAND,
                IMAGE_STABILIZER_COMMAND));
    }
}
