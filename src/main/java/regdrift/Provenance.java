/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a run did, recorded well enough that somebody else can repeat it.
 *
 * <p>The point of this record is a question that gets asked six months later:
 * why was this movie registered that way? The answer has to be a file, not a
 * memory, so the plugin version, the mode, the channel it settled on and why,
 * the scale it measured at, where the measurement windows sat, which engine
 * versions it found, and which calibration set the recommendation came from all
 * travel with the result.
 *
 * <p><b>{@link #measuredAtBin()} has no default and cannot be left out.</b>
 * Localisability is the fall in frame-to-frame correlation under a one-pixel
 * displacement, and one pixel means something different after binning than it
 * does at native resolution - the threshold the verdict uses was calibrated on
 * binned frames and reads an order of magnitude lower on unbinned ones. A
 * verdict is therefore not comparable across two different values of this
 * field, so the field is required and every diagnosis states it. This is defect
 * D12 in the build plan's ledger and it is carried open, in the open.
 */
public final class Provenance {

    /** Channel value meaning the run had not settled on one. */
    public static final int CHANNEL_UNRESOLVED = 0;

    private final String pluginVersion;
    private final Mode mode;
    private final int channel;
    private final String channelReason;
    private final int measuredAtBin;
    private final int[] windowStarts;
    private final int windowFrames;
    private final Map<String, String> engineVersions;
    private final String calibrationSet;

    private Provenance(Builder builder) {
        this.pluginVersion = builder.pluginVersion;
        this.mode = builder.mode;
        this.channel = builder.channel;
        this.channelReason = builder.channelReason;
        this.measuredAtBin = builder.measuredAtBin;
        this.windowStarts = copyOf(builder.windowStarts);
        this.windowFrames = builder.windowFrames;
        this.engineVersions = Collections.unmodifiableMap(
                new TreeMap<String, String>(builder.engineVersions));
        this.calibrationSet = builder.calibrationSet;
    }

    /** A builder. Plugin version, mode and measurement scale are required. */
    public static Builder builder() {
        return new Builder();
    }

    /** Which build of this plugin produced the result. */
    public String pluginVersion() {
        return pluginVersion;
    }

    /** What the run was asked to do. */
    public Mode mode() {
        return mode;
    }

    /**
     * The 1-based channel the movement was measured on, or
     * {@link #CHANNEL_UNRESOLVED} when the run did not get that far.
     */
    public int channel() {
        return channel;
    }

    /** Why that channel, in one clause. Empty when no channel was settled on. */
    public String channelReason() {
        return channelReason;
    }

    /**
     * The binning factor the movement was measured at; {@code 1} is native
     * resolution. Provenance, not decoration - see the class note.
     */
    public int measuredAtBin() {
        return measuredAtBin;
    }

    /**
     * Where each measurement window started, as 0-based frame indices, in
     * ascending order. Empty when every consecutive pair was measured, or when
     * the run made no measurement.
     */
    public int[] windowStarts() {
        return copyOf(windowStarts);
    }

    /** How many consecutive frames each window held. Zero when unmeasured. */
    public int windowFrames() {
        return windowFrames;
    }

    /**
     * The engine versions found in this Fiji, keyed by the engine's own
     * spelling of its name and sorted by it, so two runs of the same setup
     * produce the same record byte for byte.
     */
    public Map<String, String> engineVersions() {
        return engineVersions;
    }

    /**
     * Which measured table the recommendation was drawn from. Empty when the run
     * made no recommendation, so no calibration table was consulted.
     */
    public String calibrationSet() {
        return calibrationSet;
    }

    private static int[] copyOf(int[] source) {
        if (source == null || source.length == 0) return new int[0];
        int[] copy = new int[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    /** Builds a {@link Provenance}. */
    public static final class Builder {

        private String pluginVersion;
        private Mode mode;
        private int channel = CHANNEL_UNRESOLVED;
        private String channelReason = "";
        private int measuredAtBin = 0;
        private int[] windowStarts = new int[0];
        private int windowFrames = 0;
        private Map<String, String> engineVersions = new TreeMap<String, String>();
        private String calibrationSet = "";

        private Builder() {
        }

        /**
         * Which build of this plugin is running. Required: the facade supplies
         * it, so the version lives in one place rather than being spelled again
         * here and drifting from the one in the build file.
         */
        public Builder pluginVersion(String pluginVersion) {
            this.pluginVersion = pluginVersion;
            return this;
        }

        /** What the run was asked to do. Required. */
        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /** The 1-based channel the movement was measured on. */
        public Builder channel(int channel) {
            this.channel = channel;
            return this;
        }

        /** Why that channel, in one clause. */
        public Builder channelReason(String channelReason) {
            this.channelReason = channelReason == null ? "" : channelReason.trim();
            return this;
        }

        /**
         * The binning factor the movement was measured at; {@code 1} is native
         * resolution. Required, and at least 1 - see the class note.
         */
        public Builder measuredAtBin(int measuredAtBin) {
            this.measuredAtBin = measuredAtBin;
            return this;
        }

        /** Where each measurement window started, as 0-based frame indices. */
        public Builder windowStarts(int[] windowStarts) {
            this.windowStarts = copyOf(windowStarts);
            return this;
        }

        /** How many consecutive frames each window held. */
        public Builder windowFrames(int windowFrames) {
            this.windowFrames = windowFrames;
            return this;
        }

        /** Engine versions found, keyed by the engine's own spelling of its name. */
        public Builder engineVersions(Map<String, String> engineVersions) {
            this.engineVersions = engineVersions == null
                    ? new TreeMap<String, String>()
                    : new TreeMap<String, String>(engineVersions);
            return this;
        }

        /** Which measured table the recommendation was drawn from. */
        public Builder calibrationSet(String calibrationSet) {
            this.calibrationSet = calibrationSet == null ? "" : calibrationSet.trim();
            return this;
        }

        /**
         * @throws IllegalArgumentException when the version, the mode or the
         *         measurement scale is missing
         */
        public Provenance build() {
            if (pluginVersion == null || pluginVersion.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "A provenance record needs the plugin version that produced it.");
            }
            if (mode == null) {
                throw new IllegalArgumentException(
                        "A provenance record needs the mode the run was asked for.");
            }
            if (measuredAtBin < 1) {
                throw new IllegalArgumentException("A provenance record needs the binning factor the"
                        + " movement was measured at, 1 or more, since the registrability verdict is"
                        + " not comparable across two scales (measured_at_bin=" + measuredAtBin + ").");
            }
            this.pluginVersion = pluginVersion.trim();
            return new Provenance(this);
        }
    }
}
