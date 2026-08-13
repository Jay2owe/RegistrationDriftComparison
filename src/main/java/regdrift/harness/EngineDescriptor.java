/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.harness;

import regdrift.autofix.EngineId;
import regdrift.autofix.EngineRegistry;
import sc.fiji.autofix.core.DependencySpec;
import sc.fiji.autofix.core.FijiLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * How to actually run one registration engine: which menu entry, which
 * arguments, what it hands back, and what it leaves behind when it is finished.
 *
 * <h2>One catalogue, two halves, one part number</h2>
 *
 * <p>The parts catalogue and the fitting instructions are separate documents,
 * and they agree because they quote the same part number. {@code EngineId} is
 * that number. {@code EngineRegistry} answers <b>is it there?</b> - a probe, a
 * download size, a repair button. This answers <b>how do I drive it?</b> Nothing
 * here re-states what the catalogue already knows: the display name, the version
 * the plugin's figures were measured against and the files on disk are all read
 * back out of the catalogue entry keyed by the same constant. There is no second
 * list of engines in this plugin and there must never be one.
 *
 * <h2>Whole recordings, through ImageJ's own command table</h2>
 *
 * <p>Every arm here is driven the way a person would drive it: the menu entry,
 * with the arguments ImageJ's macro recorder writes down. That covers engines
 * with no programmable interface at all, which is most of them, and it means an
 * arm is doing the same thing the recipe on the recommendation row tells
 * somebody to do by hand. Driving an engine one frame pair at a time - which
 * TurboReg does support, and which is how the benchmark measured it - is a
 * later build's work and is deliberately absent here.
 *
 * <p>That choice is what decides which engines have an arm in this build:
 *
 * <ul>
 *   <li><b>TurboReg</b> aligns one image onto one other image. It is the engine
 *       underneath StackReg rather than a whole-recording tool of its own, and
 *       its own menu entry opens an interactive window that waits for somebody.
 *       The whole-recording form of the same engine is StackReg, which does have
 *       an arm, and which calls TurboReg for every frame while it runs.</li>
 *   <li><b>Register Virtual Stack Slices</b> reads a folder of image files and
 *       writes another folder of image files. It is not given an open recording
 *       and it does not hand one back, so driving it as an arm would mean
 *       writing somebody's recording to disk without being asked.</li>
 *   <li><b>Descriptor-based registration</b> aligns a series and fuses it into
 *       a single image. Driven on a twelve-frame recording it came back with one
 *       image rather than twelve, which is the tool working as built and leaves
 *       a comparison nothing to score against the recording that went in.</li>
 *   <li><b>NanoJ-Core</b> ships as a packaged class, so ImageJ's naming rule says
 *       nothing about which menu entry it lands under, and this plugin has not
 *       read its configuration file. A guessed entry is a confident wrong
 *       answer; an unread one is a fact.</li>
 *   <li><b>Fast4DReg</b> drives itself from macro files that ask their own
 *       questions and write their results to a folder. The same objection as
 *       Register Virtual Stack Slices applies, and the arguments those macros
 *       take have not been read off a working install.</li>
 * </ul>
 *
 * <p>Those five report {@link ArmStatus#COULD_NOT_DRIVE} with the sentence
 * above, and the comparison carries on to the next engine.
 *
 * <h2>Where an engine puts its answer</h2>
 *
 * <p>Two habits, and an arm has to know which one it is about to meet.
 * {@link Output#IN_PLACE} means the engine rewrites the recording it was handed;
 * {@link Output#NEW_IMAGE} means it leaves that one alone and makes another. An
 * arm expecting the wrong one would either score an unregistered recording or
 * lose the registered one, and both look like a working arm from the outside.
 *
 * <h2>Arguments are never empty</h2>
 *
 * <p>Every entry below carries arguments, even where there is nothing to
 * configure. ImageJ decides whether a plugin's question window appears by
 * looking at whether arguments were supplied at all: hand it none and the engine
 * stops and waits for somebody who is not there, which inside a comparison is a
 * frozen session. {@link #options(String)} therefore never returns an empty
 * string, and {@code EngineDescriptorTest} fails the build if one is written.
 *
 * <h2>Driving twice in one session, and what that turned out to be</h2>
 *
 * <p>Nobody knew the answer to this before, because the research harness this
 * code comes from sidestepped the question by ending the whole Java process when
 * it had finished driving TurboReg - with a comment saying TurboReg left
 * window-system threads behind. So it was measured.
 *
 * <p><b>Measured on 2026-08-13</b>, in one Fiji session with the SciJava layer
 * running, on a twelve-frame recording, each engine driven three times: twice
 * through one runner and once more through a second runner that carried no
 * memory of the first. Each arm ran in a thread group of its own, so a thread
 * the engine started landed in that group and was counted on the way out.
 * <b>Every arm left an empty group.</b> StackReg, MultiStackReg, Correct 3D
 * drift and Linear Stack Alignment with SIFT each left nothing running, on every
 * drive, and no image window opened at any point.
 *
 * <p>The threads the research harness saw are real, and the difference is where
 * they came from: it drove TurboReg's own window-opening path with no ImageJ
 * running, so the window system started up inside the arm and stayed. Driven
 * through the command table in batch mode, inside a session whose window system
 * is already running, none of these engines starts one.
 *
 * <p>So {@link #driveOncePerSession()} is false for every engine measured, and
 * it is <em>not</em> a claim about the three nobody has an install of. Those are
 * marked as not yet measured rather than as safe, and the arm reports what it
 * finds. The runner marks an engine drive-once for the rest of the session the
 * moment an arm really does leave a thread behind, whatever this table says.
 */
public final class EngineDescriptor {

    /** Where an engine puts the registered recording. */
    public enum Output {

        /** The engine rewrites the recording it was handed. */
        IN_PLACE,

        /** The engine leaves that one alone and makes another. */
        NEW_IMAGE
    }

    /**
     * What a caller writes into the arguments where an engine names its input.
     *
     * <p>An engine that finds its input by title has to be told the title of the
     * copy this plugin made, which is different every time. Spelled as a token
     * rather than assembled with string addition so that a descriptor stays a
     * constant and a test can read it.
     */
    public static final String TITLE_TOKEN = "{title}";

    /**
     * The menu entries this half declares for itself.
     *
     * <p>StackReg and MultiStackReg are found on disk by the catalogue rather
     * than through ImageJ's command table, so the catalogue has no command
     * string for either and these two are the driving half's own. Both come from
     * ImageJ's naming rule and from the configuration file inside the engine's
     * own archive, read on a Fiji install on 2026-08-13, and both are the same
     * strings the recommendation's copyable macro line carries -
     * {@code RecipeMatchesTheHarnessTest} fails the build if they drift apart.
     *
     * <p>StackReg's ends in a space and that is not a typing slip. Its entry
     * class ends in an underscore, ImageJ turns each underscore in a class name
     * into a space when it builds the menu, and a command missing that trailing
     * space finds no entry at all.
     */
    private static final String STACKREG_COMMAND = "StackReg ";

    /** MultiStackReg's entry, from the configuration file in its own archive. */
    private static final String MULTISTACKREG_COMMAND = "MultiStackReg";

    private static final Map<EngineId, EngineDescriptor> DESCRIPTORS = build();

    private final EngineId id;
    private final String command;
    private final String optionsTemplate;
    private final Output output;
    private final String notDrivableReason;
    private final List<EngineId> requires;
    private final boolean driveOncePerSession;
    private final String driveNote;

    private EngineDescriptor(EngineId id, String command, String optionsTemplate, Output output,
                             String notDrivableReason, List<EngineId> requires,
                             boolean driveOncePerSession, String driveNote) {
        this.id = id;
        this.command = command;
        this.optionsTemplate = optionsTemplate;
        this.output = output;
        this.notDrivableReason = notDrivableReason;
        this.requires = Collections.unmodifiableList(requires);
        this.driveOncePerSession = driveOncePerSession;
        this.driveNote = driveNote;
    }

    // -------------------------------------------------------------- the list

    /** How to drive one engine. Never null: every catalogue constant has an entry. */
    public static EngineDescriptor forEngine(EngineId engine) {
        EngineDescriptor descriptor = DESCRIPTORS.get(engine);
        if (descriptor == null) {
            throw new IllegalStateException("no way to drive " + engine + " is recorded, which"
                    + " cannot happen unless a constant was added to EngineId without one");
        }
        return descriptor;
    }

    /** Every engine, in the order the catalogue lists them. */
    public static List<EngineDescriptor> all() {
        List<EngineDescriptor> found = new ArrayList<EngineDescriptor>();
        for (DependencySpec spec : EngineRegistry.specs()) {
            found.add(forEngine((EngineId) spec.getId()));
        }
        return Collections.unmodifiableList(found);
    }

    /** The engines this build can drive over a whole recording, in catalogue order. */
    public static List<EngineDescriptor> drivable() {
        List<EngineDescriptor> found = new ArrayList<EngineDescriptor>();
        for (EngineDescriptor descriptor : all()) {
            if (descriptor.isDrivable()) found.add(descriptor);
        }
        return Collections.unmodifiableList(found);
    }

    // ------------------------------------------------------------- one engine

    /** The catalogue constant this describes, which is the join between the halves. */
    public EngineId id() {
        return id;
    }

    /** How this engine's own authors spell its name, read from the catalogue. */
    public String displayName() {
        return EngineRegistry.displayName(id);
    }

    /** The menu entry to run, or an empty string when this build drives none. */
    public String command() {
        return command;
    }

    /**
     * The arguments to run it with, with the working copy's title written in.
     *
     * <p>Never empty for an engine this build drives: see the class note on why
     * an engine handed no arguments stops and waits for somebody.
     */
    public String options(String workingTitle) {
        String title = workingTitle == null ? "" : workingTitle;
        return optionsTemplate.replace(TITLE_TOKEN, title);
    }

    /** The arguments as written, with the title left as a token. For a table. */
    public String optionsTemplate() {
        return optionsTemplate;
    }

    /** Whether this engine rewrites the recording or makes another one. */
    public Output output() {
        return output;
    }

    /** True when this build can drive this engine over a whole recording. */
    public boolean isDrivable() {
        return notDrivableReason.isEmpty();
    }

    /** Why this build drives no arm for this engine, or an empty string. */
    public String notDrivableReason() {
        return notDrivableReason;
    }

    /**
     * The engines that have to be present as well, whether or not this one is.
     *
     * <p>StackReg and MultiStackReg both call TurboReg while they run. An arm
     * dispatched without it fails part-way through with a message naming the
     * wrong tool, which sends somebody to install something they already have.
     */
    public List<EngineId> requires() {
        return requires;
    }

    /** The version this plugin's figures were measured against, from the catalogue. */
    public String measuredVersion() {
        Object version = EngineRegistry.specFor(id).getAttributes().get("version");
        return version == null ? "" : version.toString();
    }

    /**
     * The version of this engine on this computer, or an empty string when there
     * is no version to read.
     *
     * <p>Read off the file name of whichever archive the catalogue says carries
     * the engine, which is where a Fiji install records it. Two engines have
     * nothing to read: Image Stabilizer ships as a loose compiled file with no
     * version in its name, and an engine nobody has installed has no file at
     * all. An empty answer is never reported as a mismatch.
     */
    public String installedVersion() {
        try {
            File fiji = FijiLayout.resolveFijiDir();
            if (fiji == null) return "";
            for (DependencySpec.Artifact artifact : EngineRegistry.specFor(id).getArtifacts()) {
                String found = versionIn(fiji, artifact);
                if (!found.isEmpty()) return found;
            }
            return "";
        } catch (RuntimeException noFiji) {
            return "";
        }
    }

    /**
     * True when this engine leaves something running behind it, so the plugin
     * drives it once in a session and no more.
     *
     * <p>Measured, not assumed. See the class note.
     */
    public boolean driveOncePerSession() {
        return driveOncePerSession;
    }

    /**
     * What a person is told about driving this engine, or an empty string when
     * there is nothing to say.
     *
     * <p>Finished text. The Engines section shows it under the row and adds no
     * words of its own.
     */
    public String driveNote() {
        return driveNote;
    }

    @Override
    public String toString() {
        return isDrivable() ? displayName() + " -> " + command : displayName() + " (no arm)";
    }

    // ------------------------------------------------------------ the entries

    private static Map<EngineId, EngineDescriptor> build() {
        Map<EngineId, EngineDescriptor> descriptors =
                new EnumMap<EngineId, EngineDescriptor>(EngineId.class);

        /*
         * TurboReg aligns one image onto one other image; the whole-recording
         * form of the same engine is StackReg, below, which calls it for every
         * frame. Its own menu entry opens a window and waits for somebody.
         */
        descriptors.put(EngineId.TURBOREG, cannotDrive(EngineId.TURBOREG,
                "TurboReg aligns one image onto one other image, and its own menu entry opens a"
                        + " window that waits for somebody to work in it. The whole-recording form"
                        + " of the same engine is StackReg, which calls TurboReg for every frame"
                        + " and which this plugin does drive. Driving TurboReg one frame pair at a"
                        + " time is a later build's work.",
                measuredLeak(false, "")));

        descriptors.put(EngineId.STACKREG, drive(EngineId.STACKREG,
                STACKREG_COMMAND, "transformation=Translation", Output.IN_PLACE,
                Arrays.asList(EngineId.TURBOREG),
                measuredLeak(false, "")));

        descriptors.put(EngineId.MULTISTACKREG, drive(EngineId.MULTISTACKREG,
                MULTISTACKREG_COMMAND,
                "stack_1=[" + TITLE_TOKEN + "] action_1=Align file_1=[] stack_2=None"
                        + " action_2=Ignore file_2=[] transformation=Translation",
                Output.IN_PLACE, Arrays.asList(EngineId.TURBOREG),
                measuredLeak(false, "")));

        /*
         * A script rather than a compiled plugin, so it is driven through the
         * same menu entry the catalogue detects it by. Its arguments were read
         * off the script's own question window on 2026-08-13. The z arguments
         * are there because it handles 3D recordings as well and asks for them
         * whatever it is given.
         */
        descriptors.put(EngineId.CORRECT_3D_DRIFT, drive(EngineId.CORRECT_3D_DRIFT,
                EngineRegistry.CORRECT_3D_DRIFT_COMMAND,
                "channel=1 only=0 lowest=1 highest=1 max_shift_x=200 max_shift_y=200"
                        + " max_shift_z=1",
                Output.NEW_IMAGE, Collections.<EngineId>emptyList(),
                measuredLeak(false, "")));

        /*
         * Driven for real, on a twelve-frame recording, in a Fiji session on
         * 2026-08-13, and it came back with one image rather than twelve: a
         * series registration fuses what it aligned into a single image, which
         * is the tool working as its authors built it and not a fault. There is
         * nothing there for the arbiter to score against the recording it was
         * handed, so this build drives no arm for it and says why.
         */
        descriptors.put(EngineId.DESCRIPTOR_BASED_REGISTRATION,
                cannotDrive(EngineId.DESCRIPTOR_BASED_REGISTRATION,
                        "Descriptor-based registration aligns a series and fuses it into one"
                                + " image. Driven on a twelve-frame recording it came back with a"
                                + " single image rather than twelve registered frames, which is"
                                + " the tool doing what it was built to do and leaves a comparison"
                                + " nothing to score. Run it yourself from its menu entry, where"
                                + " its own settings window lets you choose what it produces.",
                        measuredLeak(false, "")));

        descriptors.put(EngineId.REGISTER_VIRTUAL_STACK_SLICES,
                cannotDrive(EngineId.REGISTER_VIRTUAL_STACK_SLICES,
                        "Register Virtual Stack Slices reads a folder of image files and writes"
                                + " another folder of image files. It is not handed an open"
                                + " recording and it does not hand one back, so a comparison arm"
                                + " would mean writing your recording to disk without asking. Run"
                                + " it yourself from its menu entry on a folder you chose.",
                        measuredLeak(false, "")));

        /*
         * Arguments as ImageJ's own recorder writes them, with the transform set
         * to translation because that is what the fingerprint measures and what
         * the benchmark drove. It leaves the recording it was handed alone and
         * makes another one.
         */
        descriptors.put(EngineId.LINEAR_STACK_ALIGNMENT_WITH_SIFT,
                drive(EngineId.LINEAR_STACK_ALIGNMENT_WITH_SIFT, EngineRegistry.SIFT_COMMAND,
                        "initial_gaussian_blur=1.60 steps_per_scale_octave=3"
                                + " minimum_image_size=64 maximum_image_size=1024"
                                + " feature_descriptor_size=4"
                                + " feature_descriptor_orientation_bins=8"
                                + " closest/next_closest_ratio=0.92 maximal_alignment_error=25"
                                + " inlier_ratio=0.05 expected_transformation=Translation"
                                + " interpolate",
                        Output.NEW_IMAGE, Collections.<EngineId>emptyList(),
                        measuredLeak(false, "")));

        descriptors.put(EngineId.IMAGE_STABILIZER, drive(EngineId.IMAGE_STABILIZER,
                EngineRegistry.IMAGE_STABILIZER_COMMAND,
                "transformation=Translation maximum_pyramid_levels=1"
                        + " template_update_coefficient=0.90 maximum_iterations=200"
                        + " error_tolerance=0.0000001",
                Output.IN_PLACE, Collections.<EngineId>emptyList(),
                notMeasuredYet()));

        descriptors.put(EngineId.FAST_4D_REG, cannotDrive(EngineId.FAST_4D_REG,
                "Fast4DReg runs from macro files that ask their own questions and write their"
                        + " results into a folder. This plugin has not read what those macros"
                        + " take, and a comparison arm would mean writing your recording to disk"
                        + " without asking. Run it yourself from its menu entry.",
                notMeasuredYet()));

        descriptors.put(EngineId.NANOJ_CORE, cannotDrive(EngineId.NANOJ_CORE,
                "NanoJ-Core ships as a packaged class, so ImageJ's naming rule says nothing about"
                        + " which menu entry it lands under and this plugin has not read its"
                        + " configuration file. Rather than guess an entry, this plugin says it"
                        + " has not read one. Open the Plugins menu after installing it.",
                notMeasuredYet()));

        return Collections.unmodifiableMap(descriptors);
    }

    /** An engine with an arm. */
    private static EngineDescriptor drive(EngineId id, String command, String options,
                                          Output output, List<EngineId> requires, String[] leak) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalStateException(id + " is declared drivable with no menu entry.");
        }
        if (options == null || options.trim().isEmpty()) {
            throw new IllegalStateException(id + " is declared drivable with no arguments, and an"
                    + " engine handed no arguments stops and waits for somebody. See the class"
                    + " note in EngineDescriptor.");
        }
        return new EngineDescriptor(id, command, options, output, "",
                new ArrayList<EngineId>(requires), "1".equals(leak[0]), leak[1]);
    }

    /** An engine this build drives no arm for, and the sentence saying why. */
    private static EngineDescriptor cannotDrive(EngineId id, String reason, String[] leak) {
        return new EngineDescriptor(id, "", "", Output.IN_PLACE, reason,
                new ArrayList<EngineId>(), "1".equals(leak[0]), leak[1]);
    }

    /** A measured answer to whether this engine can be driven twice in one session. */
    private static String[] measuredLeak(boolean once, String note) {
        return new String[]{once ? "1" : "0", note};
    }

    /**
     * An engine nothing has measured, which is not the same as one measured to
     * be safe.
     *
     * <p>These three are not installed on any computer this plugin has been
     * measured on, so nobody has driven them twice in a session to find out.
     * They are dispatched normally and the arm reports what it finds; the note
     * says the question is open rather than answered.
     */
    private static String[] notMeasuredYet() {
        return new String[]{"0", "Nobody has yet driven this engine twice in one Fiji session to"
                + " find out whether it leaves anything running behind it. The comparison reports"
                + " what it finds."};
    }

    /**
     * The version in an installed file name, or an empty string.
     *
     * <p>An artifact records the name it expects and the piece of that name that
     * comes before the version, so the version is whatever sits between that
     * piece and the file extension. A file with nothing between them - a loose
     * compiled class, say - reads as no version rather than as an empty one.
     */
    private static String versionIn(File fiji, DependencySpec.Artifact artifact) {
        String prefix = artifact.getMatchPrefix();
        if (prefix == null || prefix.isEmpty()) return "";
        File folder = new File(fiji, artifact.getFolder());
        File[] files = folder.listFiles();
        if (files == null) return "";
        for (File file : files) {
            String name = file.getName();
            if (!name.startsWith(prefix)) continue;
            String rest = name.substring(prefix.length());
            int dot = rest.lastIndexOf('.');
            if (dot > 0) rest = rest.substring(0, dot);
            if (!rest.isEmpty()) return rest;
        }
        return "";
    }
}
