/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.ImagePlus;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Everything a batch is given: a folder of recordings, which filenames count,
 * how they are labelled, and the settings every one of them is run with.
 *
 * <p>One folder is required and nothing else is.
 * {@code RegDriftBatchParameters.builder(folder).build()} is a complete request:
 * every TIFF directly inside that folder, measured and ranked, nothing written
 * and nothing shown.
 *
 * <h2>The filename pattern, and what a capture group is for</h2>
 *
 * <p>The pattern decides two things at once. A file whose name matches it is a
 * recording this batch runs; a file whose name does not is left alone and
 * reported as skipped, which is how somebody finds out their pattern is wrong
 * before two hundred rows have been produced from the wrong half of a folder.
 * The pattern's capture group is a <b>label</b>: whatever it captures becomes the
 * {@code group} column beside that movie's row, so a plate's wells, a genotype
 * or a treatment arm can be read straight out of the filenames somebody already
 * has.
 *
 * <pre>
 * ^(?&lt;group&gt;[A-Z]\\d+)_.*\\.tif$      A1_t0.tif and A1_t1.tif are two
 *                                     recordings, both labelled A1
 * </pre>
 *
 * <p>A group is a label, not a bundle: three files sharing a label are three
 * recordings and three rows, not one recording assembled from three files.
 *
 * <h2>Never nests</h2>
 *
 * <p>{@link #perMovie(ImagePlus)} sets {@code serial} on every inner run,
 * unconditionally. A batch is the outer parallel axis and it moves out one level
 * rather than adding one, so the frame pairs inside a movie run on a single
 * worker while the movies themselves run beside each other.
 *
 * <p>Unconditionally, and not only when the movies really are running in
 * parallel, because a serial batch and a parallel batch have to be the same run
 * done at two speeds. If the inner setting followed the outer worker count, the
 * two would be different code paths, and the test that says their rows are
 * identical would be asserting less than it appears to.
 *
 * <p>Every field is final and there is no setter, for the same reason
 * {@link RegDriftParameters} has none: worker threads read one of these while a
 * batch is in flight.
 */
public final class RegDriftBatchParameters {

    /**
     * Which filenames count when nobody says otherwise: any TIFF, either
     * spelling, either case.
     *
     * <p>No capture group, so every recording carries the same label - see
     * {@link #UNGROUPED_LABEL}.
     */
    public static final String DEFAULT_PATTERN = "(?i).*\\.tiff?";

    /** Which capture group carries the label, when nobody says otherwise. */
    public static final int DEFAULT_GROUP = 1;

    /**
     * The label a recording carries when the pattern names no capture group, or
     * names one that did not take part in the match.
     *
     * <p>Spelled the same as the shared discovery helper's key for the same
     * situation, so a folder read by two plugins in this family is labelled the
     * same way by both.
     */
    public static final String UNGROUPED_LABEL = "all";

    private final File folder;
    private final String pattern;
    private final Pattern compiled;
    private final int groupCapture;
    private final boolean recursive;
    private final Mode mode;
    private final Channel channel;
    private final Slice slice;
    private final boolean useRoi;
    private final EngineSelection engines;
    private final String applyEngine;
    private final Windows windows;
    private final WindowFrames windowFrames;
    private final Arbiter arbiter;
    private final boolean flagMotionLoss;
    private final boolean adviseCeiling;
    private final String saveRoot;
    private final int movieWorkers;
    private final Cancellation cancellation;
    private final Dispatch dispatch;

    private RegDriftBatchParameters(Builder builder) {
        this.folder = builder.folder;
        this.pattern = builder.pattern;
        this.compiled = builder.compiled;
        this.groupCapture = builder.groupCapture;
        this.recursive = builder.recursive;
        this.mode = builder.mode;
        this.channel = builder.channel;
        this.slice = builder.slice;
        this.useRoi = builder.useRoi;
        this.engines = builder.engines;
        this.applyEngine = builder.applyEngine;
        this.windows = builder.windows;
        this.windowFrames = builder.windowFrames;
        this.arbiter = builder.arbiter;
        this.flagMotionLoss = builder.flagMotionLoss;
        this.adviseCeiling = builder.adviseCeiling;
        this.saveRoot = builder.saveRoot;
        this.movieWorkers = builder.movieWorkers;
        this.cancellation = builder.cancellation;
        this.dispatch = builder.dispatch;
    }

    /**
     * A builder for a batch over one folder.
     *
     * @param folder the folder of recordings. The one required input
     */
    public static Builder builder(File folder) {
        return new Builder(folder);
    }

    /** The folder the recordings are read from. */
    public File folder() {
        return folder;
    }

    /** Which filenames count, as it was written. */
    public String pattern() {
        return pattern;
    }

    /** Which filenames count, compiled. */
    public Pattern compiledPattern() {
        return compiled;
    }

    /** Which capture group carries the label. */
    public int groupCapture() {
        return groupCapture;
    }

    /** True when sub-folders are read as well. */
    public boolean recursive() {
        return recursive;
    }

    /** What each recording is asked to do. */
    public Mode mode() {
        return mode;
    }

    /** Which channel the movement is measured on. */
    public Channel channel() {
        return channel;
    }

    /** Which Z slice, or a projection through Z. */
    public Slice slice() {
        return slice;
    }

    /** True when an ROI on a recording restricts which pixels vote. */
    public boolean useRoi() {
        return useRoi;
    }

    /** Which engines each run considers. */
    public EngineSelection engines() {
        return engines;
    }

    /** The engine to run in apply mode, or empty for the one ranked first. */
    public String applyEngine() {
        return applyEngine;
    }

    /** How many measurement windows. */
    public Windows windows() {
        return windows;
    }

    /** How many consecutive frames each window holds. */
    public WindowFrames windowFrames() {
        return windowFrames;
    }

    /** Which measurement decides how an arm scored. */
    public Arbiter arbiter() {
        return arbiter;
    }

    /** True when arms that flatten real movement are flagged. */
    public boolean flagMotionLoss() {
        return flagMotionLoss;
    }

    /** True when a run may advise an intensity ceiling. Advice, never a setting. */
    public boolean adviseCeiling() {
        return adviseCeiling;
    }

    /** Where the results are written, or empty for a batch that writes nothing. */
    public String saveRoot() {
        return saveRoot;
    }

    /** True when a folder to write into was given. */
    public boolean hasSaveRoot() {
        return !saveRoot.isEmpty();
    }

    /** The folder to write into, or null when none was given. */
    public File saveRootFolder() {
        return saveRoot.isEmpty() ? null : new File(saveRoot);
    }

    /**
     * How many recordings the caller asked to have in flight at once.
     *
     * <p>Zero means decide from the machine, one means one at a time. What the
     * batch actually uses is {@link RegDriftBatchRunner#movieWorkersFor}, which
     * takes this, the mode and the memory a recording needs, and can only ever
     * come back with fewer.
     */
    public int movieWorkers() {
        return movieWorkers;
    }

    /** The switch that says whether this batch has been asked to stop. */
    public Cancellation cancellation() {
        return cancellation;
    }

    /**
     * Who is asked before a comparison drives anything.
     *
     * <p>{@link Dispatch#always()} unless something else was given, which is what
     * a batch wants: the question is asked once, before the folder is started,
     * rather than once per recording in a run somebody walked away from.
     */
    public Dispatch dispatch() {
        return dispatch;
    }

    // ------------------------------------------------------------ the label

    /**
     * The label this filename carries, or null when the pattern does not match
     * it at all.
     *
     * <p>The single place a filename is turned into a label, so the preview
     * somebody reads before starting and the grouping the run uses cannot come
     * apart. A capture group that is in the pattern but did not take part in the
     * match - an optional one - leaves the file matched and labelled
     * {@link #UNGROUPED_LABEL}, because there is nothing to read out of it and
     * dropping the recording over it would be worse.
     */
    public String labelFor(String fileName) {
        if (fileName == null) return null;
        Matcher matcher = compiled.matcher(fileName);
        if (!matcher.matches()) return null;
        if (groupCapture < 1 || groupCapture > matcher.groupCount()) return UNGROUPED_LABEL;
        String captured = matcher.group(groupCapture);
        return captured == null || captured.isEmpty() ? UNGROUPED_LABEL : captured;
    }

    // ------------------------------------------------------ one recording

    /**
     * The settings one recording of this batch is run with.
     *
     * <p><b>{@code serial} is set here, always.</b> That is the never-nests rule
     * made concrete: the batch is the outer axis, and an inner pool underneath it
     * would oversubscribe the machine while making the worker budget meaningless.
     *
     * <p>{@code hide_display} is set here too, and is not a setting a batch
     * offers. Two hundred recordings cannot each open a window, a table and a
     * plot; the results are written and the rows are the answer.
     */
    public RegDriftParameters perMovie(ImagePlus image) {
        RegDriftParameters.Builder builder = RegDriftParameters.builder(image)
                .mode(mode)
                .channel(channel)
                .slice(slice)
                .useRoi(useRoi)
                .engines(engines)
                .windows(windows)
                .windowFrames(windowFrames)
                .arbiter(arbiter)
                .flagMotionLoss(flagMotionLoss)
                .adviseCeiling(adviseCeiling)
                .saveRoot(saveRoot)
                .hideDisplay(true)
                .serial(true)
                .cancellation(cancellation)
                .dispatch(dispatch);
        if (mode == Mode.APPLY) builder.applyEngine(applyEngine);
        return builder.build();
    }

    /**
     * Builds a {@link RegDriftBatchParameters}.
     *
     * <p>Reusing a builder after {@code build()} does not change what was already
     * built.
     */
    public static final class Builder {

        private final File folder;
        private String pattern = DEFAULT_PATTERN;
        private Pattern compiled = Pattern.compile(DEFAULT_PATTERN);
        private int groupCapture = DEFAULT_GROUP;
        private boolean recursive = false;
        private Mode mode = Mode.DIAGNOSE_AND_RECOMMEND;
        private Channel channel = Channel.AUTO;
        private Slice slice = Slice.PROJECT;
        private boolean useRoi = false;
        private EngineSelection engines = EngineSelection.defaultSelection();
        private String applyEngine = "";
        private Windows windows = Windows.auto();
        private WindowFrames windowFrames = WindowFrames.auto();
        private Arbiter arbiter = Arbiter.SD_VS_CONTROL;
        private boolean flagMotionLoss = true;
        private boolean adviseCeiling = true;
        private String saveRoot = "";
        private int movieWorkers = 0;
        private Cancellation cancellation = Cancellation.never();
        private Dispatch dispatch = Dispatch.always();

        private Builder(File folder) {
            if (folder == null) {
                throw new IllegalArgumentException(
                        "A batch needs a folder of recordings to read. None was given.");
            }
            this.folder = folder;
        }

        /**
         * Which filenames count, and where the label comes from.
         *
         * @throws IllegalArgumentException naming the pattern and what is wrong
         *         with it, because a pattern nobody can read is the one setting
         *         of a batch somebody is most likely to get wrong
         */
        public Builder pattern(String pattern) {
            String text = pattern == null || pattern.trim().isEmpty()
                    ? DEFAULT_PATTERN : pattern.trim();
            try {
                this.compiled = Pattern.compile(text);
            } catch (PatternSyntaxException unreadable) {
                throw new IllegalArgumentException("The filename pattern '" + text + "' cannot be"
                        + " read as a regular expression: " + unreadable.getDescription()
                        + " (at position " + unreadable.getIndex() + "). The default, '"
                        + DEFAULT_PATTERN + "', takes every TIFF in the folder.");
            }
            this.pattern = text;
            return this;
        }

        /** Which capture group carries the label. Out of range means every file shares one. */
        public Builder groupCapture(int groupCapture) {
            this.groupCapture = groupCapture;
            return this;
        }

        /** Whether sub-folders are read as well. */
        public Builder recursive(boolean recursive) {
            this.recursive = recursive;
            return this;
        }

        /** What each recording is asked to do. */
        public Builder mode(Mode mode) {
            this.mode = mode == null ? Mode.DIAGNOSE_AND_RECOMMEND : mode;
            return this;
        }

        /** Which channel to measure on. */
        public Builder channel(Channel channel) {
            this.channel = channel == null ? Channel.AUTO : channel;
            return this;
        }

        /** Which Z slice to measure on, or a projection. */
        public Builder slice(Slice slice) {
            this.slice = slice == null ? Slice.PROJECT : slice;
            return this;
        }

        /** Whether an ROI on a recording restricts which pixels vote. */
        public Builder useRoi(boolean useRoi) {
            this.useRoi = useRoi;
            return this;
        }

        /** Which engines to consider. */
        public Builder engines(EngineSelection engines) {
            this.engines = engines == null ? EngineSelection.defaultSelection() : engines;
            return this;
        }

        /** An engine to run in apply mode, overriding the ranking. */
        public Builder applyEngine(String applyEngine) {
            this.applyEngine = applyEngine == null ? "" : applyEngine.trim();
            return this;
        }

        /** How many measurement windows. */
        public Builder windows(Windows windows) {
            this.windows = windows == null ? Windows.auto() : windows;
            return this;
        }

        /** How many consecutive frames each window holds. */
        public Builder windowFrames(WindowFrames windowFrames) {
            this.windowFrames = windowFrames == null ? WindowFrames.auto() : windowFrames;
            return this;
        }

        /** Which measurement decides how an arm scored. */
        public Builder arbiter(Arbiter arbiter) {
            this.arbiter = arbiter == null ? Arbiter.SD_VS_CONTROL : arbiter;
            return this;
        }

        /** Whether arms that flatten real movement are flagged. */
        public Builder flagMotionLoss(boolean flagMotionLoss) {
            this.flagMotionLoss = flagMotionLoss;
            return this;
        }

        /** Whether a run may advise an intensity ceiling. Advice, never a setting. */
        public Builder adviseCeiling(boolean adviseCeiling) {
            this.adviseCeiling = adviseCeiling;
            return this;
        }

        /** Where to write the results. Empty writes nothing. */
        public Builder saveRoot(String saveRoot) {
            this.saveRoot = saveRoot == null ? "" : saveRoot.trim();
            return this;
        }

        /** Where to write the results. */
        public Builder saveRoot(File saveRoot) {
            return saveRoot(saveRoot == null ? "" : saveRoot.getAbsolutePath());
        }

        /** How many recordings to have in flight at once. 0 decides, 1 is one at a time. */
        public Builder movieWorkers(int movieWorkers) {
            this.movieWorkers = movieWorkers;
            return this;
        }

        /** The switch a person can pull to stop this batch. */
        public Builder cancellation(Cancellation cancellation) {
            this.cancellation = cancellation == null ? Cancellation.never() : cancellation;
            return this;
        }

        /** Who answers before a comparison drives anything. */
        public Builder dispatch(Dispatch dispatch) {
            this.dispatch = dispatch == null ? Dispatch.always() : dispatch;
            return this;
        }

        /**
         * Checks the request and builds it.
         *
         * @throws IllegalArgumentException naming the setting, the value it was
         *         given and what was expected
         */
        public RegDriftBatchParameters build() {
            if (!applyEngine.isEmpty() && mode != Mode.APPLY) {
                throw new IllegalArgumentException("An engine to apply ('" + applyEngine
                        + "') was named for mode='" + mode.macroValue() + "'. Naming an engine to"
                        + " run belongs to mode='" + Mode.APPLY.macroValue() + "'; either set that"
                        + " mode, or use 'engines' to choose which engines are considered.");
            }
            return new RegDriftBatchParameters(this);
        }
    }
}
