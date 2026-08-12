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

/**
 * Everything a run is given, fixed before it starts.
 *
 * <p>One recording is required and nothing else is. Every other field carries
 * the default from the published option table, so
 * {@code RegDriftParameters.builder(imp).build()} is a complete, valid request:
 * measure the movement, then name the engines the measurements support.
 *
 * <p>Every field is final and there is no setter. Twelve later stages read one
 * of these while a run is in flight, several of them from worker threads, and a
 * bundle that could be edited halfway through would make a run's settings a
 * question of timing. The builder is the one place a value is chosen, and
 * {@link Builder#build()} is where it is checked.
 *
 * <p>Validation here reads dimensions and nothing else. No pixel is touched, no
 * file is opened, no window is shown.
 */
public final class RegDriftParameters {

    private final ImagePlus image;
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
    private final ImagePlus compareWith;
    private final String saveRoot;
    private final boolean hideDisplay;
    private final boolean serial;

    private RegDriftParameters(Builder builder) {
        this.image = builder.image;
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
        this.compareWith = builder.compareWith;
        this.saveRoot = builder.saveRoot;
        this.hideDisplay = builder.hideDisplay;
        this.serial = builder.serial;
    }

    /**
     * A builder for a run over one recording.
     *
     * @param image the time-lapse stack to measure. The one required input
     */
    public static Builder builder(ImagePlus image) {
        return new Builder(image);
    }

    /** The recording being measured. */
    public ImagePlus image() {
        return image;
    }

    /** What the run was asked to do. */
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

    /** True when an ROI on the input restricts which pixels vote. */
    public boolean useRoi() {
        return useRoi;
    }

    /** Which engines the run considers. */
    public EngineSelection engines() {
        return engines;
    }

    /**
     * The engine to run in apply mode, overriding the ranking, or empty to run
     * the engine ranked first.
     */
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

    /**
     * True when the run may advise an intensity ceiling.
     *
     * <p>Advice, and advice alone. There is no setting anywhere in this plugin
     * that turns a ceiling on, because an automatic ceiling deletes the sample
     * outright on fluorescence data and the benchmark it would be justified by
     * cannot see that happen. Defect D4 in the build plan's ledger.
     */
    public boolean adviseCeiling() {
        return adviseCeiling;
    }

    /** The already-registered stack to score, in score mode. Null otherwise. */
    public ImagePlus compareWith() {
        return compareWith;
    }

    /** Where the auto-save tree is written, or empty for no auto-save. */
    public String saveRoot() {
        return saveRoot;
    }

    /** True when no window is shown. */
    public boolean hideDisplay() {
        return hideDisplay;
    }

    /** True when the run uses one worker everywhere. */
    public boolean serial() {
        return serial;
    }

    /** True when a save root was given. */
    public boolean hasSaveRoot() {
        return !saveRoot.isEmpty();
    }

    /** True when an engine was named for apply mode. */
    public boolean hasApplyEngine() {
        return !applyEngine.isEmpty();
    }

    /**
     * Builds a {@link RegDriftParameters}.
     *
     * <p>Reusing a builder after {@code build()} does not change what was
     * already built: every field is copied into the finished bundle.
     */
    public static final class Builder {

        private final ImagePlus image;
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
        private ImagePlus compareWith;
        private String saveRoot = "";
        private boolean hideDisplay = false;
        private boolean serial = false;

        private Builder(ImagePlus image) {
            if (image == null) {
                throw new IllegalArgumentException(
                        "A run needs a time-lapse stack to measure. None was given.");
            }
            this.image = image;
        }

        /** What the run should do. */
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

        /** Whether an ROI on the input restricts which pixels vote. */
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
            this.applyEngine = trimOrEmpty(applyEngine);
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

        /** Whether the run may advise an intensity ceiling. Advice, never a setting. */
        public Builder adviseCeiling(boolean adviseCeiling) {
            this.adviseCeiling = adviseCeiling;
            return this;
        }

        /** The already-registered stack to score, in score mode. */
        public Builder compareWith(ImagePlus compareWith) {
            this.compareWith = compareWith;
            return this;
        }

        /** Where to write the auto-save tree. */
        public Builder saveRoot(String saveRoot) {
            this.saveRoot = trimOrEmpty(saveRoot);
            return this;
        }

        /** Whether to show no window. */
        public Builder hideDisplay(boolean hideDisplay) {
            this.hideDisplay = hideDisplay;
            return this;
        }

        /** Whether to use one worker everywhere. */
        public Builder serial(boolean serial) {
            this.serial = serial;
            return this;
        }

        /**
         * Checks the request and builds it.
         *
         * @throws IllegalArgumentException naming the setting, the value it was
         *         given and what was expected
         */
        public RegDriftParameters build() {
            int channels = image.getNChannels();
            if (!channel.isAuto() && channel.index() > channels) {
                throw new IllegalArgumentException("Channel " + channel.index()
                        + " was asked for, but '" + image.getTitle() + "' has " + channels
                        + " channel" + (channels == 1 ? "" : "s") + ". Use a channel from 1 to "
                        + channels + ", or '" + Channel.AUTO_VALUE + "'.");
            }
            if (compareWith != null && mode != Mode.SCORE) {
                throw new IllegalArgumentException("A second stack was given for mode='"
                        + mode.macroValue() + "'. A second stack is scored in mode='"
                        + Mode.SCORE.macroValue() + "'; either set that mode, or leave the second"
                        + " stack out.");
            }
            if (!applyEngine.isEmpty() && mode != Mode.APPLY) {
                throw new IllegalArgumentException("An engine to apply ('" + applyEngine
                        + "') was named for mode='" + mode.macroValue() + "'. Naming an engine to"
                        + " run belongs to mode='" + Mode.APPLY.macroValue() + "'; either set that"
                        + " mode, or use 'engines' to choose which engines are considered.");
            }
            return new RegDriftParameters(this);
        }

        private static String trimOrEmpty(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
