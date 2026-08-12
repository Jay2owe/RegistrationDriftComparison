/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

/**
 * One candidate engine, ranked, with the measured row it came from and a recipe
 * a user can run.
 *
 * <p>One row of the recommendation table. The ranking says which engine scored
 * highest on movement like this one, by a named arbiter, with the number beside
 * it - it does not say which engine is right for you, because the table was
 * measured on three phase-contrast seeds from one instrument and says so in
 * {@link #calibration()}.
 *
 * <p>This stage fixes the shape. The catalogue that fills {@link #presence()}
 * and the measured table that fills the rest arrive in later stages.
 */
public final class Recommendation {

    /** Whether the fingerprint sits inside the range the table was measured over. */
    public enum Calibration {

        /** The movement resembles recordings the table was measured on. */
        IN_RANGE("in_range"),

        /** It does not, and the expected figures are an extrapolation. */
        OUTSIDE_CALIBRATED_RANGE("outside_calibrated_range");

        private final String tableValue;

        Calibration(String tableValue) {
            this.tableValue = tableValue;
        }

        /** The token written into the {@code calibration} column. */
        public String tableValue() {
            return tableValue;
        }
    }

    /** Whether this engine is available in the Fiji that is running. */
    public enum Presence {

        /** Present, at a version this plugin drives. */
        PRESENT("yes"),

        /** Absent. */
        ABSENT("no"),

        /** Present at a version this plugin does not drive. */
        VERSION_NOT_DRIVEN("wrong_version"),

        /** Nothing has looked yet. */
        UNKNOWN("unknown");

        private final String tableValue;

        Presence(String tableValue) {
            this.tableValue = tableValue;
        }

        /** The token written into the {@code installed} column. */
        public String tableValue() {
            return tableValue;
        }
    }

    private final String engine;
    private final int rank;
    private final String reason;
    private final double expectedErrorPx;
    private final double expectedSeconds;
    private final Calibration calibration;
    private final Presence presence;
    private final String foundVersion;
    private final double installSizeMb;
    private final String installAction;
    private final String menuPath;
    private final String macroLine;

    private Recommendation(Builder builder) {
        this.engine = builder.engine;
        this.rank = builder.rank;
        this.reason = builder.reason;
        this.expectedErrorPx = builder.expectedErrorPx;
        this.expectedSeconds = builder.expectedSeconds;
        this.calibration = builder.calibration;
        this.presence = builder.presence;
        this.foundVersion = builder.foundVersion;
        this.installSizeMb = builder.installSizeMb;
        this.installAction = builder.installAction;
        this.menuPath = builder.menuPath;
        this.macroLine = builder.macroLine;
    }

    /**
     * A builder for one ranked engine.
     *
     * @param engine the engine's own spelling of its name
     * @param rank   1 is the engine that scored highest
     */
    public static Builder builder(String engine, int rank) {
        return new Builder(engine, rank);
    }

    /** The engine's own spelling of its name. */
    public String engine() {
        return engine;
    }

    /** Position in the ranking; 1 is the engine that scored highest. */
    public int rank() {
        return rank;
    }

    /** The measured row this came from, in one clause. */
    public String reason() {
        return reason;
    }

    /** Expected residual mismatch after registration, in pixels. */
    public double expectedErrorPx() {
        return expectedErrorPx;
    }

    /** Expected CPU seconds, scaled by frame count. Never wall-clock. */
    public double expectedSeconds() {
        return expectedSeconds;
    }

    /** Whether the fingerprint sits inside the measured range. */
    public Calibration calibration() {
        return calibration;
    }

    /** Whether this engine is available in the Fiji that is running. */
    public Presence presence() {
        return presence;
    }

    /**
     * The engine version found, when one was found at a version this plugin does
     * not drive. Empty otherwise.
     */
    public String foundVersion() {
        return foundVersion;
    }

    /**
     * How large the repair for a missing engine would be, in megabytes, or
     * {@code 0} when there is nothing to repair.
     */
    public double installSizeMb() {
        return installSizeMb;
    }

    /**
     * What the repair panel would do about a missing engine, in one clause, or
     * the reason it cannot. Empty when there is nothing to repair. Reading this
     * makes nothing happen: repairs are a button somebody presses.
     */
    public String installAction() {
        return installAction;
    }

    /** Where this engine sits in the Fiji menu. */
    public String menuPath() {
        return menuPath;
    }

    /** A macro line that runs this engine with these settings, ready to copy. */
    public String macroLine() {
        return macroLine;
    }

    /** The {@code installed} column text, including the version when relevant. */
    public String installedColumn() {
        if (presence == Presence.VERSION_NOT_DRIVEN && !foundVersion.isEmpty()) {
            return presence.tableValue() + ": " + foundVersion;
        }
        return presence.tableValue();
    }

    @Override
    public String toString() {
        return rank + ". " + engine + " (" + calibration.tableValue() + ")";
    }

    /** Builds a {@link Recommendation}. */
    public static final class Builder {

        private final String engine;
        private final int rank;
        private String reason = "";
        private double expectedErrorPx = Double.NaN;
        private double expectedSeconds = Double.NaN;
        /**
         * Defaults to the claim that says less. A row that nobody has placed
         * inside the measured range has not earned the flag that says it is.
         */
        private Calibration calibration = Calibration.OUTSIDE_CALIBRATED_RANGE;
        private Presence presence = Presence.UNKNOWN;
        private String foundVersion = "";
        private double installSizeMb = 0;
        private String installAction = "";
        private String menuPath = "";
        private String macroLine = "";

        private Builder(String engine, int rank) {
            if (engine == null || engine.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "A recommendation needs the engine's own spelling of its name.");
            }
            if (rank < 1) {
                throw new IllegalArgumentException(
                        "A recommendation's rank starts at 1 (rank=" + rank + ").");
            }
            this.engine = engine.trim();
            this.rank = rank;
        }

        /** The measured row this came from, in one clause. */
        public Builder reason(String reason) {
            this.reason = trimOrEmpty(reason);
            return this;
        }

        /** Expected residual mismatch after registration, in pixels. */
        public Builder expectedErrorPx(double expectedErrorPx) {
            this.expectedErrorPx = expectedErrorPx;
            return this;
        }

        /** Expected CPU seconds. Never wall-clock. */
        public Builder expectedSeconds(double expectedSeconds) {
            this.expectedSeconds = expectedSeconds;
            return this;
        }

        /** Whether the fingerprint sits inside the measured range. */
        public Builder calibration(Calibration calibration) {
            this.calibration = calibration == null
                    ? Calibration.OUTSIDE_CALIBRATED_RANGE : calibration;
            return this;
        }

        /** Whether this engine is available in the Fiji that is running. */
        public Builder presence(Presence presence) {
            this.presence = presence == null ? Presence.UNKNOWN : presence;
            return this;
        }

        /** The version found, when it is one this plugin does not drive. */
        public Builder foundVersion(String foundVersion) {
            this.foundVersion = trimOrEmpty(foundVersion);
            return this;
        }

        /** How large the repair would be, in megabytes. */
        public Builder installSizeMb(double installSizeMb) {
            this.installSizeMb = installSizeMb;
            return this;
        }

        /** What the repair panel would do, or the reason it cannot. */
        public Builder installAction(String installAction) {
            this.installAction = trimOrEmpty(installAction);
            return this;
        }

        /** Where this engine sits in the Fiji menu. */
        public Builder menuPath(String menuPath) {
            this.menuPath = trimOrEmpty(menuPath);
            return this;
        }

        /** A macro line that runs this engine with these settings. */
        public Builder macroLine(String macroLine) {
            this.macroLine = trimOrEmpty(macroLine);
            return this;
        }

        /** Builds the recommendation. */
        public Recommendation build() {
            return new Recommendation(this);
        }

        private static String trimOrEmpty(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
