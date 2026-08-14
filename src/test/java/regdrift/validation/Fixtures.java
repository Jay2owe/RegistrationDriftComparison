/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.validation;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.Duplicator;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineId;
import regdrift.autofix.EngineRegistry;
import regdrift.diag.Frames;
import regdrift.diag.MotionLabel;
import regdrift.harness.EngineDescriptor;
import regdrift.internal.Transform;
import regdrift.score.ControlWarp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * What the validation stage measures against: the twelve library recordings, and
 * the four made-up recordings whose answer is known because they were built to
 * have it.
 *
 * <p>Nothing here asserts anything. It finds the library if this machine has it,
 * says what each entry was recorded as containing, and builds the synthetic
 * stacks the test plan names - a controllable trace, a flat featureless stack,
 * and a stack whose brightest decile <em>is</em> the sample.
 *
 * <h2>The library is not in this repository, and that is deliberate</h2>
 *
 * <p>Twelve recordings of 38 to 132 MB each live in
 * {@code Experiments\Log-Ratio Registration\library\}, which is a Dropbox-backed
 * folder beside this one and is read-only to this build. {@link #library()}
 * returns null on any machine that does not have it, every test that needs it
 * says so and skips, and {@code mvn test} stays green and fast on a machine that
 * has only checked this repository out. The runs that produced
 * {@code VALIDATION.md} were made on a machine that has it.
 *
 * <h2>Why the recorded label is not compared to directly</h2>
 *
 * <p>Each entry's {@code entry.properties} carries a {@code motion=} label, and
 * that label was measured on the <b>full uncropped frame, binned, on the
 * best-ranked plane</b>, over every consecutive pair. The entry beside it is an
 * <b>unbinned crop of channel 1</b>. Comparing a measurement of the crop against
 * a label measured on the whole frame measures the crop, which is the mistake the
 * first sampler run made. So the recorded label appears here as
 * {@link Entry#recordedLabel()} for the record, {@link Entry#headline()} carries
 * the one thing the entry was cut to demonstrate, and the sampler's own reference
 * is a full-consecutive-pair run on the same pixels - see {@code T4Rerun}.
 */
public final class Fixtures {

    /** Where the twelve recordings are, when they are not beside this repository. */
    public static final String LIBRARY_PROPERTY = "regdrift.library";

    /** Where they are on a machine that has the whole workspace checked out. */
    private static final String LIBRARY_BESIDE_THIS_REPO = "../Log-Ratio Registration/library";

    /** The recording every entry directory holds. */
    public static final String ORIGINAL = "original.tif";

    private static File found;
    private static boolean looked;

    private Fixtures() {
    }

    // ------------------------------------------------------------- the library

    /**
     * The library folder, or null when this machine does not have it.
     *
     * <p>Looked for once. A folder that exists but whose twelve entries are not
     * there - a Dropbox placeholder tree that has never been hydrated - counts as
     * not having it, because the failure it would otherwise produce is a slow one
     * that looks like a defect in this plugin.
     */
    public static synchronized File library() {
        if (looked) return found;
        looked = true;
        String named = System.getProperty(LIBRARY_PROPERTY);
        File candidate = named != null && !named.trim().isEmpty()
                ? new File(named.trim())
                : new File(LIBRARY_BESIDE_THIS_REPO);
        if (!complete(candidate)) {
            candidate = new File(System.getProperty("user.dir"), LIBRARY_BESIDE_THIS_REPO);
        }
        found = complete(candidate) ? candidate : null;
        return found;
    }

    /** True when every one of the twelve recordings is really on this disk. */
    private static boolean complete(File folder) {
        if (folder == null || !folder.isDirectory()) return false;
        for (Entry entry : ENTRIES) {
            File original = new File(new File(folder, entry.name()), ORIGINAL);
            if (!original.isFile() || original.length() <= 0) return false;
        }
        return true;
    }

    /**
     * How much heap this JVM was given, in megabytes.
     *
     * <p>The build's own test runs are held to {@code -Xmx512m} by the SciJava
     * parent, and every measurement that fits inside that is made there
     * deliberately - it is a fact worth having that a real 768x768 recording can
     * be diagnosed in half a gigabyte. The runs that hold two whole recordings at
     * once do not fit, say so by name, and are made in a JVM that was told how much
     * it may have.
     */
    public static long heapMb() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    /** The sentence a test that needs more heap than this JVM has prints. */
    public static String needsHeap(long mb) {
        return "this measurement holds two whole recordings at once and needs about " + mb
                + " MB of heap; this JVM was given " + heapMb() + " MB. Run it with"
                + " java -Xmx6g -cp target/classes;target/test-classes;<ij.jar>"
                + " org.junit.runner.JUnitCore <this class>, which is what VALIDATION.md's"
                + " figures were measured with.";
    }

    /** The sentence a skipped test prints, naming what is missing and how to point at it. */
    public static String whyItWasSkipped() {
        return "the twelve library recordings are not on this machine. They live in"
                + " Experiments\\Log-Ratio Registration\\library\\ beside this repository; point at"
                + " them with -D" + LIBRARY_PROPERTY + "=<folder> if they are somewhere else."
                + " VALIDATION.md holds the figures from the machine that has them.";
    }

    // ------------------------------------------------------------- a Fiji with engines

    private static Boolean fijiBooted;

    /**
     * Boots the Fiji named by {@code -Dplugins.dir} once, and says whether it holds
     * any engine this plugin drives.
     *
     * <p>Comparison mode drives other people's plugins through ImageJ's own command
     * table, so the table has to exist: a plain build JVM has {@code ij.jar} and
     * nothing else, and every arm would come back {@code not_installed}. Booting
     * ImageJ with {@code NO_SHOW} builds the table without a window.
     *
     * <p>Error messages are redirected into the log first and left redirected. A
     * Fiji with two plugins claiming one command name reports that through
     * {@code IJ.error}, which is a modal dialog, and a modal dialog inside an
     * unattended run is a session that never finishes. The harness Fiji has two such
     * clashes and neither has anything to do with registration.
     */
    public static synchronized boolean fijiWithEngines() {
        if (fijiBooted != null) return fijiBooted.booleanValue();
        fijiBooted = Boolean.FALSE;
        String fiji = System.getProperty("plugins.dir");
        if (fiji == null || fiji.trim().isEmpty() || !new File(fiji.trim()).isDirectory()) {
            return false;
        }
        IJ.redirectErrorMessages(true);
        new ImageJ(ImageJ.NO_SHOW);
        IJ.redirectErrorMessages(true);
        String startup = IJ.getLog();
        if (startup != null && !startup.trim().isEmpty()) {
            System.out.println("what this Fiji said on the way up, redirected out of a modal box:");
            System.out.println(startup.trim());
        }
        List<EngineId> present = AutofixService.forThisFiji().presentEngines();
        List<String> drivable = new ArrayList<String>();
        for (EngineId engine : present) {
            if (EngineDescriptor.forEngine(engine).isDrivable()) {
                drivable.add(EngineRegistry.displayName(engine));
            }
        }
        System.out.println("this Fiji has " + present.size() + " of the catalogue's engines, "
                + drivable.size() + " of which this plugin drives an arm for: " + drivable);
        fijiBooted = Boolean.valueOf(!drivable.isEmpty());
        return fijiBooted.booleanValue();
    }

    /** The sentence a test that needs engines prints when this machine has none. */
    public static String whyThereIsNoFiji() {
        String fiji = System.getProperty("plugins.dir");
        return "comparison mode drives other people's plugins, and this JVM was not pointed at a"
                + " Fiji that has any of them: plugins.dir = " + fiji + ". Run it with"
                + " -Dplugins.dir=<Fiji.app>. VALIDATION.md records which Fiji its figures came"
                + " from and which engines were in it.";
    }

    /** One library recording, and what it was recorded as containing. */
    public static final class Entry {

        private final String name;
        private final String recordedLabel;
        private final String recordedSeverity;
        private final EnumSet<MotionLabel.Component> headline;
        private final boolean noTrustworthyAnswer;
        private final double recordedSdVsControlPercent;

        private Entry(String name, String recordedLabel, String recordedSeverity,
                      EnumSet<MotionLabel.Component> headline, boolean noTrustworthyAnswer,
                      double recordedSdVsControlPercent) {
            this.name = name;
            this.recordedLabel = recordedLabel;
            this.recordedSeverity = recordedSeverity;
            this.headline = headline;
            this.noTrustworthyAnswer = noTrustworthyAnswer;
            this.recordedSdVsControlPercent = recordedSdVsControlPercent;
        }

        /** The entry directory's name. */
        public String name() {
            return name;
        }

        /**
         * The {@code motion=} line of {@code entry.properties}, verbatim.
         *
         * <p>Measured on different pixels at a different scale - see this class's
         * note. Carried so the table can print it, not so a test can assert on it.
         */
        public String recordedLabel() {
            return recordedLabel;
        }

        /** The {@code severity=} line of {@code entry.properties}, verbatim. */
        public String recordedSeverity() {
            return recordedSeverity;
        }

        /**
         * The recorded label with the knock <em>count</em> taken off and the
         * components read as a set.
         *
         * <p>D13 withdrew the count: it is a statistic of whatever pairs were
         * sampled, and one recording read KNOCK1, KNOCK2, KNOCK4 and KNOCK7 across
         * six samplings of itself. {@code ARTEFACT} is not a movement and does not
         * appear here; the entry that carries it has no trustworthy answer at all.
         */
        public Set<MotionLabel.Component> recordedComponents() {
            EnumSet<MotionLabel.Component> set =
                    EnumSet.noneOf(MotionLabel.Component.class);
            for (String piece : recordedLabel.split("\\+")) {
                String word = piece.trim().toUpperCase(Locale.ROOT).replaceAll("[0-9]+$", "");
                for (MotionLabel.Component component : MotionLabel.Component.values()) {
                    if (component.word().equals(word)) set.add(component);
                }
            }
            return set;
        }

        /**
         * What this entry was cut to demonstrate, and what a diagnosis of it has to
         * find. Empty for the two entries that have no trustworthy answer.
         */
        public Set<MotionLabel.Component> headline() {
            return Collections.unmodifiableSet(headline);
        }

        /** True for the two entries the library itself records as unresolved. */
        public boolean noTrustworthyAnswer() {
            return noTrustworthyAnswer;
        }

        /** What registering it achieved against an interpolation-matched control, per RESULTS.md. */
        public double recordedSdVsControlPercent() {
            return recordedSdVsControlPercent;
        }

        /** This entry's directory inside the library. */
        public File folder() {
            File library = library();
            return library == null ? null : new File(library, name);
        }

        /** The {@code entry.properties} beside the recording, read fresh. */
        public Properties properties() throws IOException {
            Properties properties = new Properties();
            File file = new File(folder(), "entry.properties");
            InputStream in = new FileInputStream(file);
            try {
                properties.load(in);
            } finally {
                in.close();
            }
            return properties;
        }

        /**
         * Opens the recording, with its axes set. The caller closes it.
         *
         * <p><b>Every entry's {@code original.tif} holds three channels interleaved
         * with no hyperstack metadata</b>, so ImageJ opens it as one channel of 144
         * slices and anything that reads it sees a 144-frame recording of the wrong
         * pixels. The frame count is in {@code entry.properties} and the axes are
         * set from it here. A person opening these files through Bio-Formats gets
         * the same axes; a person double-clicking the TIFF does not, and that is a
         * property of the library rather than of this plugin.
         *
         * @throws IllegalStateException naming the file, rather than handing back a
         *         null nothing can say anything about
         */
        public ImagePlus open() {
            File file = new File(folder(), ORIGINAL);
            ImagePlus imp = IJ.openImage(file.getAbsolutePath());
            if (imp == null) {
                throw new IllegalStateException("could not open " + file.getAbsolutePath()
                        + "; the library entry may be a Dropbox placeholder rather than a file");
            }
            int frames;
            try {
                frames = Integer.parseInt(properties().getProperty("frames", "0").trim());
            } catch (IOException noProperties) {
                throw new IllegalStateException("could not read entry.properties beside "
                        + file.getAbsolutePath() + ": " + noProperties.getMessage());
            }
            int planes = imp.getStack().getSize();
            if (frames <= 0 || planes % frames != 0) {
                throw new IllegalStateException(file.getAbsolutePath() + " holds " + planes
                        + " planes, which is not a whole number of channels over the " + frames
                        + " frames entry.properties records");
            }
            imp.setDimensions(planes / frames, 1, frames);
            imp.setTitle(name);
            return imp;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static Entry entry(String name, String label, String severity, String headline,
                               boolean unresolved, double sd) {
        EnumSet<MotionLabel.Component> wanted = EnumSet.noneOf(MotionLabel.Component.class);
        if (!headline.isEmpty()) {
            for (String word : headline.split("\\+")) {
                wanted.add(MotionLabel.Component.valueOf(word.trim()));
            }
        }
        return new Entry(name, label, severity, wanted, unresolved, sd);
    }

    /**
     * The twelve entries, in the order the library grades them.
     *
     * <p>{@code recordedLabel} and {@code severity} are the two lines of each
     * {@code entry.properties} - checked against the file itself by
     * {@code LibraryValidationRun}, so this table cannot drift away from the
     * library. The {@code SD vs control} figures are {@code library/RESULTS.md}.
     * The headline is this stage's pre-registered criterion and is written down in
     * {@code VALIDATION.md} before any of it was run.
     */
    public static final Entry[] ENTRIES = {
            entry("01_jitter_mild", "JITTER", "moderate", "JITTER", false, -12.1),
            entry("02_jitter", "JITTER", "severe", "JITTER", false, -37.5),
            entry("03_jitter_drift", "JITTER+DRIFT", "moderate", "JITTER+DRIFT", false, -29.6),
            entry("04_drift", "JITTER+DRIFT", "moderate", "DRIFT", false, -50.6),
            entry("05_drift_dominant", "DRIFT+JITTER", "moderate", "DRIFT", false, -5.3),
            entry("06_knock", "KNOCK2+JITTER+DRIFT", "severe", "KNOCK", false, -40.3),
            entry("07_knock_drift", "KNOCK2+JITTER+DRIFT", "severe", "KNOCK+DRIFT", false, -37.1),
            entry("08_knock_severe", "KNOCK4+JITTER+DRIFT", "extreme", "KNOCK", false, -4.2),
            entry("09_knock_extreme", "KNOCK4+JITTER+DRIFT", "extreme", "KNOCK", false, -14.4),
            entry("10_unresolved_methods_disagree", "KNOCK2+WALK+DRIFT", "extreme", "", true, 0.7),
            entry("11_unresolved_moving_artefact", "ARTEFACT", "unknown", "", true, -2.1),
            entry("12_long_baseline_9d", "KNOCK4+DRIFT+WALK", "extreme", "DRIFT", false, -0.2),
    };

    /** The entry of that name. */
    public static Entry entry(String name) {
        for (Entry entry : ENTRIES) {
            if (entry.name().equals(name)) return entry;
        }
        throw new IllegalArgumentException("no library entry called '" + name + "'. They are: "
                + Arrays.toString(ENTRIES));
    }

    /**
     * The transforms the library itself recovered for one entry, from its own
     * {@code shifts.csv}.
     *
     * <p>Cumulative, one per frame, the first the identity. Checked against
     * {@code RESULTS.md}: read this way {@code 11_unresolved_moving_artefact}
     * walks 4547.0 px and ends 101.53 px from where it started, which are that
     * file's two figures to the digit.
     */
    public static Transform[] recordedTransforms(Entry entry) throws IOException {
        List<Transform> found = new ArrayList<Transform>();
        BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(entry.folder(), "shifts.csv")), "UTF-8"));
        try {
            String header = in.readLine();
            if (header == null) throw new IOException("shifts.csv for " + entry + " is empty");
            List<String> columns = Arrays.asList(header.split(","));
            int dx = columns.indexOf("cum_dx");
            int dy = columns.indexOf("cum_dy");
            if (dx < 0 || dy < 0) {
                throw new IOException("shifts.csv for " + entry + " has no cum_dx/cum_dy columns,"
                        + " it has " + columns);
            }
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] cells = line.split(",");
                found.add(Transform.translation(Double.parseDouble(cells[dx].trim()),
                        Double.parseDouble(cells[dy].trim())));
            }
        } finally {
            in.close();
        }
        return found.toArray(new Transform[0]);
    }

    /**
     * The registered recording the library itself produced for one entry, rebuilt
     * from that entry's own {@code original.tif} and {@code shifts.csv}.
     *
     * <p>One channel, one slice, the same number of frames, warped through the
     * plugin's own resampling at the plugin's own sign convention - a transform
     * describes the motion of the content, so sampling frame {@code t} through its
     * own transform is what holds the field still. This is the same reconstruction
     * stage 12 scored the twelve library figures from.
     */
    public static ImagePlus registeredByTheLibrary(Entry entry, ImagePlus raw, int channel)
            throws IOException {
        Transform[] cumulative = recordedTransforms(entry);
        Frames frames = Frames.of(raw, channel, Frames.PROJECT_Z, Frames.Bin.none());
        try {
            int width = frames.width();
            int height = frames.height();
            if (cumulative.length != frames.count()) {
                throw new IOException(entry + " has " + cumulative.length + " recorded transforms"
                        + " and " + frames.count() + " frames");
            }
            ImageStack stack = new ImageStack(width, height);
            for (int t = 0; t < frames.count(); t++) {
                float[] warped = ControlWarp.warp(frames.plane(t), width, height, cumulative[t],
                        ControlWarp.Interpolation.BILINEAR, 0f);
                stack.addSlice("t" + (t + 1), new FloatProcessor(width, height, warped, null));
            }
            ImagePlus imp = new ImagePlus(entry.name() + " - registered by the library", stack);
            imp.setDimensions(1, 1, frames.count());
            return imp;
        } finally {
            frames.release();
        }
    }

    /**
     * One channel of one entry, as a single-channel recording of the same frames.
     *
     * <p>Why comparison mode is measured on this and not on the entry as it sits on
     * disk: every library recording is three channels interleaved, and the engines
     * this plugin drives align a stack <b>plane by plane in stack order</b>. Handed
     * a three-channel hyperstack they align channel 3 of one frame onto channel 1 of
     * the next, which is not a registration of anything. That is measured in
     * {@code VALIDATION.md} rather than assumed, and it is a property of those
     * engines rather than of this library.
     */
    public static ImagePlus openChannel(Entry entry, int channel) {
        ImagePlus whole = entry.open();
        try {
            ImagePlus one = new Duplicator().run(whole, channel, channel, 1, whole.getNSlices(),
                    1, whole.getNFrames());
            one.setTitle(entry.name() + " channel " + channel);
            return one;
        } finally {
            whole.close();
        }
    }

    /** The three benchmark seed frames, when the library is here. Empty when it is not. */
    public static List<File> seeds() {
        List<File> seeds = new ArrayList<File>();
        File library = library();
        if (library == null) return seeds;
        File folder = new File(new File(library, "benchmark"), "seeds");
        File[] files = folder.listFiles();
        if (files == null) return seeds;
        Arrays.sort(files);
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".tif")) {
                seeds.add(file);
            }
        }
        return seeds;
    }

    // ---------------------------------------------------- the made-up recordings

    /**
     * A recording built to contain a stated movement and nothing else.
     *
     * <p>One textured frame, translated by a trajectory this method builds, and
     * resampled bilinearly by arithmetic written here rather than by anything in
     * the plugin - a fixture that warped with the code under test would agree with
     * it by construction.
     *
     * @param frames        how many time points
     * @param size          the square side, in pixels
     * @param driftPxPerFrame a straight ramp, in pixels per frame
     * @param jitterPx      an independent shake about the current position, RMS px
     * @param walkPx        a step added to the position and kept, RMS px - the
     *                      difference between jitter and a random walk
     * @param jumpAt        frames a jump is injected before, 1-based, or null
     * @param jumpPx        how far each of those jumps goes, in pixels
     * @param seed          fixes every random draw, so two calls are identical
     */
    public static ImagePlus syntheticTrace(int frames, int size, double driftPxPerFrame,
                                           double jitterPx, double walkPx, int[] jumpAt,
                                           double[] jumpPx, long seed) {
        float[] texture = texture(size, seed);
        double[][] path = trajectory(frames, driftPxPerFrame, jitterPx, walkPx, jumpAt, jumpPx,
                seed);
        ImageStack stack = new ImageStack(size, size);
        for (int t = 0; t < frames; t++) {
            stack.addSlice("t" + (t + 1), shifted(texture, size, path[t][0], path[t][1]));
        }
        ImagePlus imp = new ImagePlus(String.format(Locale.US,
                "synthetic_%dx%d_t%d_drift%.2f_jitter%.2f_walk%.2f",
                size, size, frames, driftPxPerFrame, jitterPx, walkPx), stack);
        imp.setDimensions(1, 1, frames);
        return imp;
    }

    /** The trajectory {@link #syntheticTrace} moves the texture along, in pixels. */
    public static double[][] trajectory(int frames, double driftPxPerFrame, double jitterPx,
                                        double walkPx, int[] jumpAt, double[] jumpPx, long seed) {
        double[][] path = new double[frames][2];
        long state = seed * 6364136223846793005L + 1442695040888963407L;
        double walkX = 0;
        double walkY = 0;
        double jumpedX = 0;
        double jumpedY = 0;
        for (int t = 0; t < frames; t++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            double a = gaussian(state);
            state = state * 6364136223846793005L + 1442695040888963407L;
            double b = gaussian(state);
            state = state * 6364136223846793005L + 1442695040888963407L;
            double c = gaussian(state);
            state = state * 6364136223846793005L + 1442695040888963407L;
            double d = gaussian(state);
            walkX += walkPx * c;
            walkY += walkPx * d;
            if (jumpAt != null) {
                for (int j = 0; j < jumpAt.length; j++) {
                    if (jumpAt[j] == t) {
                        // Along the diagonal, so a jump moves both axes and cannot be
                        // mistaken for one axis of the drift.
                        double step = jumpPx[j] / Math.sqrt(2.0);
                        jumpedX += step;
                        jumpedY += step;
                    }
                }
            }
            path[t][0] = driftPxPerFrame * t + jitterPx * a + walkX + jumpedX;
            path[t][1] = 0.5 * driftPxPerFrame * t + jitterPx * b + walkY + jumpedY;
        }
        return path;
    }

    /** A flat stack with no structure at all: every pixel of every frame the same value. */
    public static ImagePlus flat(int frames, int size, int value) {
        ImageStack stack = new ImageStack(size, size);
        for (int t = 0; t < frames; t++) {
            ByteProcessor plane = new ByteProcessor(size, size);
            Arrays.fill((byte[]) plane.getPixels(), (byte) value);
            stack.addSlice("t" + (t + 1), plane);
        }
        ImagePlus imp = new ImagePlus("flat_" + size + "x" + size + "_t" + frames, stack);
        imp.setDimensions(1, 1, frames);
        return imp;
    }

    /**
     * A recording whose brightest decile <b>is</b> the sample: a dim, structureless
     * background with a bright textured patch on it that carries all the movement.
     *
     * <p>Defect D4's case. An automatic intensity ceiling would delete exactly the
     * pixels the movement can be read from, and the benchmark cannot see that
     * failure because its own seeds are phase contrast, where the bright pixels are
     * debris.
     */
    public static ImagePlus brightestDecileIsTheSample(int frames, int size,
                                                       double driftPxPerFrame, long seed) {
        return brightSample(frames, size, driftPxPerFrame, seed, false);
    }

    /**
     * The same recording with its brightest decile clipped down to the background -
     * what an automatic intensity ceiling would leave to measure from.
     *
     * <p>D4 in one pair of stacks. On phase contrast the brightest pixels are debris
     * and removing them helps; here they are the sample, and this is the stack that
     * says what removing them costs.
     */
    public static ImagePlus brightestDecileClipped(int frames, int size,
                                                   double driftPxPerFrame, long seed) {
        return brightSample(frames, size, driftPxPerFrame, seed, true);
    }

    private static ImagePlus brightSample(int frames, int size, double driftPxPerFrame, long seed,
                                          boolean clipTheBrightest) {
        float[] texture = texture(size, seed);
        // About a ninth of the frame, so the patch really is roughly the top decile.
        int patch = Math.max(8, size / 3);
        int origin = (size - patch) / 2;
        float[] plane = new float[size * size];
        // A dim floor with a little grain in it - enough that a robust spread exists, and not
        // enough to localize a shift from. A perfectly uniform floor has no spread at all, and
        // bright_fraction states that rather than reporting a share of nothing.
        for (int i = 0; i < plane.length; i++) {
            plane[i] = 20f + 0.06f * (texture[i] - 128f);
        }
        for (int y = 0; y < patch; y++) {
            for (int x = 0; x < patch; x++) {
                int at = (origin + y) * size + origin + x;
                plane[at] = 150f + 0.5f * (texture[at] - 128f);
            }
        }
        if (clipTheBrightest) {
            float[] sorted = plane.clone();
            Arrays.sort(sorted);
            float ceiling = sorted[(int) (0.90 * (sorted.length - 1))];
            for (int i = 0; i < plane.length; i++) {
                if (plane[i] > ceiling) plane[i] = ceiling;
            }
        }
        ImageStack stack = new ImageStack(size, size);
        for (int t = 0; t < frames; t++) {
            stack.addSlice("t" + (t + 1), shifted(plane, size, driftPxPerFrame * t,
                    0.5 * driftPxPerFrame * t));
        }
        ImagePlus imp = new ImagePlus((clipTheBrightest ? "bright_sample_clipped_" : "bright_sample_")
                + size + "x" + size + "_t" + frames, stack);
        imp.setDimensions(1, 1, frames);
        return imp;
    }

    // ------------------------------------------------------------- the arithmetic

    /**
     * A deterministic texture with structure at the pixel scale: white noise with
     * a little smoothing, which is what a real recording's localisability comes
     * from and what pure noise does not have.
     */
    private static float[] texture(int size, long seed) {
        float[] noise = new float[size * size];
        long state = seed * 2862933555777941757L + 3037000493L;
        for (int i = 0; i < noise.length; i++) {
            state = state * 2862933555777941757L + 3037000493L;
            noise[i] = (float) (128 + 45 * gaussian(state));
        }
        float[] smoothed = new float[noise.length];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double sum = 0;
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int yy = y + dy;
                        int xx = x + dx;
                        if (yy < 0 || yy >= size || xx < 0 || xx >= size) continue;
                        sum += noise[yy * size + xx];
                        count++;
                    }
                }
                smoothed[y * size + x] = (float) (sum / count);
            }
        }
        return smoothed;
    }

    /** One plane, translated by a sub-pixel amount, bilinearly, edges held. */
    private static ImageProcessor shifted(float[] plane, int size, double dx, double dy) {
        ByteProcessor out = new ByteProcessor(size, size);
        byte[] pixels = (byte[]) out.getPixels();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double sx = x - dx;
                double sy = y - dy;
                int x0 = (int) Math.floor(sx);
                int y0 = (int) Math.floor(sy);
                double fx = sx - x0;
                double fy = sy - y0;
                double v = (1 - fx) * (1 - fy) * at(plane, size, x0, y0)
                        + fx * (1 - fy) * at(plane, size, x0 + 1, y0)
                        + (1 - fx) * fy * at(plane, size, x0, y0 + 1)
                        + fx * fy * at(plane, size, x0 + 1, y0 + 1);
                int value = (int) Math.round(v);
                pixels[y * size + x] = (byte) (value < 0 ? 0 : value > 255 ? 255 : value);
            }
        }
        return out;
    }

    private static double at(float[] plane, int size, int x, int y) {
        int cx = x < 0 ? 0 : x >= size ? size - 1 : x;
        int cy = y < 0 ? 0 : y >= size ? size - 1 : y;
        return plane[cy * size + cx];
    }

    /**
     * A standard normal draw from one 64-bit state, by the polar-free route: two
     * halves of the state turned into uniforms and put through the inverse of a
     * logistic-ish approximation is not accurate enough here, so this uses the
     * Box-Muller pair from the two halves, which is exact and needs no rejection.
     */
    private static double gaussian(long state) {
        double u1 = ((state >>> 11) & 0x1FFFFF) / (double) 0x200000;
        double u2 = ((state >>> 32) & 0x1FFFFF) / (double) 0x200000;
        if (u1 < 1e-12) u1 = 1e-12;
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }
}
