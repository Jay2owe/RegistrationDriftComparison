/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.measure.ResultsTable;
import regdrift.autofix.EngineId;
import regdrift.harness.ArmStatus;
import regdrift.score.Arbiter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One engine's place in a comparison: what happened to it, what it scored, and
 * where that puts it.
 *
 * <p>One row of the {@code Comparison} table, and one line of the results view.
 * <b>Every engine the comparison considered gets one</b>, including the ones this
 * computer does not have and the ones that could not be driven - a comparison
 * that quietly leaves out the engines somebody has not installed looks like a
 * complete answer and is not.
 *
 * <h2>The ranking rule, which is where this could go wrong</h2>
 *
 * <p>{@link #rank(List)} sorts the arms that produced a figure and then
 * <b>collapses the ones it cannot tell apart</b>. Two arms whose
 * {@code sd_vs_control} differ by less than
 * {@value regdrift.score.Arbiter#CANNOT_SEPARATE_PERCENT} percentage points -
 * the gap the twelve library entries left, measured in stage 12 - carry the
 * <em>same</em> rank number, and the later one carries the sentence saying which
 * rank it could not be separated from.
 *
 * <p><b>Rank 1 appearing twice is the correct output, not a defect to tidy
 * away.</b> The alternative is to print 1 and 2 with four tenths of a percentage
 * point between them, which reads as a finding and is not one. The rule is the
 * arbiter's own threshold applied to a pair of arms rather than to an arm and its
 * control, and each arm is compared against the arm that leads its group rather
 * than against the one immediately above it, so three arms straddling six
 * percentage points do not chain into one group.
 *
 * <h2>Two different "cannot separate" statements</h2>
 *
 * <p>They are easy to confuse and mean different things, so both are carried:
 *
 * <ul>
 *   <li>{@link #separation()} is about this arm and <em>the control</em>: whether
 *       registering it did anything the resampling did not do for free.</li>
 *   <li>{@link #cannotSeparateFromRank()} is about this arm and <em>another
 *       arm</em>: whether the two can be put in an order at all.</li>
 * </ul>
 *
 * <p>An arm can be plainly better than its control and still share a rank with
 * the arm above it, and an arm can hold a rank of its own while saying it did
 * nothing the control did not. Both sentences appear.
 */
public final class ArmOutcome {

    /** The rank of an arm that produced no figure to rank. */
    public static final int UNRANKED = 0;

    private final EngineId engine;
    private final String engineName;
    private final ArmStatus status;
    private final String statusText;
    private final String detail;
    private final String settings;
    private final int rank;
    private final int cannotSeparateFromRank;
    private final Arbiter.Separation separation;
    private final String separationText;
    private final double sdVsControlPercent;
    private final double cpuSeconds;
    private final boolean cpuIsAFloor;
    private final double residualBefore;
    private final double residualAfter;
    private final double residualRemoved;
    private final double pathPx;
    private final double netPx;
    private final int framesFlagged;
    private final Recommendation.Presence presence;
    private final String installAction;
    private final double installSizeMb;
    private final String motionCaveat;

    private ArmOutcome(Builder b) {
        this.engine = b.engine;
        this.engineName = b.engineName;
        this.status = b.status;
        this.statusText = b.statusText.isEmpty() ? b.status.tableValue() : b.statusText;
        this.detail = b.detail;
        this.settings = b.settings;
        this.rank = b.rank;
        this.cannotSeparateFromRank = b.cannotSeparateFromRank;
        this.separation = b.separation;
        this.separationText = b.separationText;
        this.sdVsControlPercent = b.sdVsControlPercent;
        this.cpuSeconds = b.cpuSeconds;
        this.cpuIsAFloor = b.cpuIsAFloor;
        this.residualBefore = b.residualBefore;
        this.residualAfter = b.residualAfter;
        this.residualRemoved = b.residualRemoved;
        this.pathPx = b.pathPx;
        this.netPx = b.netPx;
        this.framesFlagged = b.framesFlagged;
        this.presence = b.presence;
        this.installAction = b.installAction;
        this.installSizeMb = b.installSizeMb;
        this.motionCaveat = b.motionCaveat;
    }

    /**
     * A builder for one arm.
     *
     * @param engine     which engine, or null when the row is about something the
     *                   catalogue does not name
     * @param engineName the engine's own spelling of its name
     * @param status     how the arm ended
     */
    public static Builder builder(EngineId engine, String engineName, ArmStatus status) {
        return new Builder(engine, engineName, status);
    }

    // ------------------------------------------------------------- the ranking

    /**
     * Puts the arms in order, sharing a rank wherever two of them cannot be told
     * apart.
     *
     * <p>Arms that produced a figure come first, stillest against the control
     * first, then the ones that produced none in the order they were given. The
     * arms with no figure are not ranked at all: a rank is a claim about a
     * measurement, and there is no measurement on a row that says the engine is
     * not installed.
     *
     * @param arms one builder per engine considered, in the order they were
     *             dispatched
     * @return a fresh list, ranked, first ranked first. Never null
     */
    public static List<ArmOutcome> rank(List<Builder> arms) {
        if (arms == null) {
            throw new IllegalArgumentException("ranking needs the arms a comparison produced");
        }
        List<Builder> scored = new ArrayList<Builder>();
        List<Builder> unscored = new ArrayList<Builder>();
        for (Builder arm : arms) {
            if (arm == null) continue;
            if (arm.hasFigure()) scored.add(arm); else unscored.add(arm);
        }
        // A stable sort, so two arms that really did score the same keep the order they were run
        // in rather than an order that depends on the sort's internals.
        Collections.sort(scored, new java.util.Comparator<Builder>() {
            @Override
            public int compare(Builder a, Builder b) {
                return Double.compare(a.sdVsControlPercent, b.sdVsControlPercent);
            }
        });

        List<ArmOutcome> ranked = new ArrayList<ArmOutcome>(scored.size() + unscored.size());
        int leaderRank = 0;
        double leaderFigure = Double.NaN;
        for (int i = 0; i < scored.size(); i++) {
            Builder arm = scored.get(i);
            boolean sameAsLeader = leaderRank > 0
                    && Math.abs(arm.sdVsControlPercent - leaderFigure)
                            < Arbiter.CANNOT_SEPARATE_PERCENT;
            if (sameAsLeader) {
                arm.rank = leaderRank;
                arm.cannotSeparateFromRank = leaderRank;
            } else {
                arm.rank = i + 1;
                arm.cannotSeparateFromRank = UNRANKED;
                leaderRank = arm.rank;
                leaderFigure = arm.sdVsControlPercent;
            }
            ranked.add(arm.build());
        }
        for (Builder arm : unscored) {
            arm.rank = UNRANKED;
            arm.cannotSeparateFromRank = UNRANKED;
            ranked.add(arm.build());
        }
        return Collections.unmodifiableList(ranked);
    }

    // ------------------------------------------------------------ what it says

    /** Which engine this row is about. Null when the catalogue does not name it. */
    public EngineId engine() {
        return engine;
    }

    /** The engine's own spelling of its name. */
    public String engineName() {
        return engineName;
    }

    /** How the arm ended, in a form a caller can branch on. */
    public ArmStatus status() {
        return status;
    }

    /** The finished sentence a person reads about how it ended. */
    public String detail() {
        return detail;
    }

    /** The arguments the engine was driven with. */
    public String settings() {
        return settings;
    }

    /** Where this arm sits, or {@link #UNRANKED} when it produced no figure. */
    public int rank() {
        return rank;
    }

    /** True when this arm produced a figure and therefore holds a rank. */
    public boolean isRanked() {
        return rank != UNRANKED;
    }

    /**
     * The rank this arm could not be separated from, or {@link #UNRANKED} when it
     * stands on its own.
     *
     * <p>Equal to {@link #rank()} whenever it is set, because an arm that cannot
     * be separated from a rank is given that rank.
     */
    public int cannotSeparateFromRank() {
        return cannotSeparateFromRank;
    }

    /** True when another arm holds the same rank for want of a difference. */
    public boolean sharesItsRank() {
        return cannotSeparateFromRank != UNRANKED;
    }

    /** How this arm compares with the control. Null when nothing was scored. */
    public Arbiter.Separation separation() {
        return separation;
    }

    /** The arbiter's own sentence about this arm and the control. */
    public String separationText() {
        return separationText;
    }

    /**
     * Temporal standard deviation against the shared control, as a percentage.
     *
     * <p>Negative is stiller. {@link Double#NaN} when the arm produced nothing to
     * score - never zero, which would read as an engine that ran and achieved
     * exactly nothing.
     */
    public double sdVsControlPercent() {
        return sdVsControlPercent;
    }

    /** What the arm cost on the processor clock. Never wall-clock - defect D6. */
    public double cpuSeconds() {
        return cpuSeconds;
    }

    /** True when that figure is a floor rather than a total. */
    public boolean cpuIsAFloor() {
        return cpuIsAFloor;
    }

    /** Frame-to-frame mismatch before registration, in pixels. */
    public double residualBefore() {
        return residualBefore;
    }

    /** Frame-to-frame mismatch after registration, in pixels. */
    public double residualAfter() {
        return residualAfter;
    }

    /** How much mismatch the arm took out, in pixels. */
    public double residualRemoved() {
        return residualRemoved;
    }

    /** Total distance this arm's transforms walked, in pixels. */
    public double pathPx() {
        return pathPx;
    }

    /** Straight-line distance from the first frame to the last, in pixels. */
    public double netPx() {
        return netPx;
    }

    /** How many frames carried a status other than {@code ok}. */
    public int framesFlagged() {
        return framesFlagged;
    }

    /** Whether the engine is present in the Fiji that is running. */
    public Recommendation.Presence presence() {
        return presence;
    }

    /**
     * What the Engines section would do about a missing engine, or empty when
     * there is nothing to do. Reading it makes nothing happen.
     */
    public String installAction() {
        return installAction;
    }

    /** How large that repair would be, in megabytes. */
    public double installSizeMb() {
        return installSizeMb;
    }

    /** True when this arm carries the motion-preservation flag. */
    public boolean motionFlagged() {
        return !motionCaveat.isEmpty();
    }

    /** The caveat that travels with that flag, or empty. Never a verdict. */
    public String motionCaveat() {
        return motionCaveat;
    }

    /** True when a figure was produced for this arm at all. */
    public boolean hasFigure() {
        return !Double.isNaN(sdVsControlPercent);
    }

    // ------------------------------------------------------------ the sentences

    /**
     * The rank as the results view prints it: the number, or a dash.
     *
     * <p>A dash rather than a blank, because a blank cell in a column of numbers
     * reads as a value somebody forgot to fill in.
     */
    public String rankColumn() {
        return isRanked() ? Integer.toString(rank) : "-";
    }

    /**
     * The whole {@code status} cell: how the arm ended, how it compares with the
     * control, where it ranks, and which rank it could not be separated from.
     *
     * <p>Tokens rather than prose, in the shape the rest of this table already
     * uses, so a script can read them and a person can still see what happened.
     */
    public String statusColumn() {
        StringBuilder cell = new StringBuilder(statusText);
        if (separation != null) cell.append("; ").append(separation.word());
        if (isRanked()) cell.append("; rank=").append(rank);
        if (sharesItsRank()) {
            cell.append("; cannot_separate_from_rank=").append(cannotSeparateFromRank);
        }
        if (cpuIsAFloor) cell.append("; cpu_is_a_floor");
        if (motionFlagged()) cell.append("; motion_preservation_flag");
        return cell.toString();
    }

    /**
     * The sentence beside the rank in the results view, or empty when the rank
     * stands on its own.
     */
    public String rankNote() {
        if (!sharesItsRank()) return "";
        return String.format(Locale.US, "cannot separate from rank %d: the two differ by less than"
                        + " the %.1f%% the library says is noise, so they are given the same rank"
                        + " rather than an order this measurement cannot justify",
                cannotSeparateFromRank, Arbiter.CANNOT_SEPARATE_PERCENT);
    }

    /** Writes this arm into a {@code Comparison} table. */
    public void appendTo(ResultsTable table) {
        RegDriftTables.ComparisonRow row = RegDriftTables.comparisonRow()
                .engine(engineName)
                .settings(settings)
                .status(statusColumn());
        if (!Double.isNaN(cpuSeconds)) row.cpuSeconds(cpuSeconds);
        if (!Double.isNaN(residualBefore)) row.residualBefore(residualBefore);
        if (!Double.isNaN(residualAfter)) row.residualAfter(residualAfter);
        if (!Double.isNaN(residualRemoved)) row.residualRemoved(residualRemoved);
        if (!Double.isNaN(sdVsControlPercent)) row.sdVsControl(sdVsControlPercent / 100.0);
        if (!Double.isNaN(pathPx)) row.pathPx(pathPx);
        if (!Double.isNaN(netPx)) row.netPx(netPx);
        if (framesFlagged >= 0) row.framesFlagged(framesFlagged);
        row.appendTo(table);
    }

    @Override
    public String toString() {
        return rankColumn() + ". " + engineName + " " + statusColumn();
    }

    /** Builds an {@link ArmOutcome}. The rank is filled in by {@link #rank(List)}. */
    public static final class Builder {

        private final EngineId engine;
        private final String engineName;
        private final ArmStatus status;
        private String statusText = "";
        private String detail = "";
        private String settings = "";
        private int rank = UNRANKED;
        private int cannotSeparateFromRank = UNRANKED;
        private Arbiter.Separation separation;
        private String separationText = "";
        private double sdVsControlPercent = Double.NaN;
        private double cpuSeconds = Double.NaN;
        private boolean cpuIsAFloor;
        private double residualBefore = Double.NaN;
        private double residualAfter = Double.NaN;
        private double residualRemoved = Double.NaN;
        private double pathPx = Double.NaN;
        private double netPx = Double.NaN;
        private int framesFlagged = -1;
        private Recommendation.Presence presence = Recommendation.Presence.UNKNOWN;
        private String installAction = "";
        private double installSizeMb;
        private String motionCaveat = "";

        private Builder(EngineId engine, String engineName, ArmStatus status) {
            if (engineName == null || engineName.trim().isEmpty()) {
                throw new IllegalArgumentException("an arm needs the engine's own spelling of its"
                        + " name");
            }
            if (status == null) {
                throw new IllegalArgumentException("an arm needs to say how it ended, and '"
                        + engineName + "' did not");
            }
            this.engine = engine;
            this.engineName = engineName.trim();
            this.status = status;
        }

        /**
         * The harness's own status cell, which carries what the status word alone
         * does not: the version found when it differs from the measured one, and
         * how many threads an engine left running.
         *
         * <p>Left alone, the cell is the status word by itself, which is what a
         * row for an engine no arm was dispatched for should say.
         */
        public Builder statusText(String statusText) {
            this.statusText = statusText == null ? "" : statusText.trim();
            return this;
        }

        /** The finished sentence about how the arm ended. */
        public Builder detail(String detail) {
            this.detail = detail == null ? "" : detail.trim();
            return this;
        }

        /** The arguments the engine was driven with. */
        public Builder settings(String settings) {
            this.settings = settings == null ? "" : settings.trim();
            return this;
        }

        /** What the arbiter measured about this arm. */
        public Builder scoring(Arbiter.Scoring scoring, boolean flagMotionLoss) {
            if (scoring == null) return this;
            this.separation = scoring.separation();
            this.separationText = scoring.separationText();
            this.sdVsControlPercent = scoring.sdVsControlPercent();
            this.residualBefore = scoring.medianResidualBefore();
            this.residualAfter = scoring.medianResidualAfter();
            this.residualRemoved = scoring.residualRemoved();
            this.framesFlagged = scoring.framesFlagged();
            if (flagMotionLoss && scoring.motion() != null && scoring.motion().raised()) {
                this.motionCaveat = scoring.motion().caveat();
            }
            return this;
        }

        /** What this arm's own transforms walked, which the shared control does not say. */
        public Builder walked(double pathPx, double netPx) {
            this.pathPx = pathPx;
            this.netPx = netPx;
            return this;
        }

        /** What the arm cost on the processor clock, and whether that is a floor. */
        public Builder cpu(double cpuSeconds, boolean cpuIsAFloor) {
            this.cpuSeconds = cpuSeconds;
            this.cpuIsAFloor = cpuIsAFloor;
            return this;
        }

        /** Whether the engine is here, and what installing it would take. */
        public Builder availability(Recommendation.Presence presence, String installAction,
                                    double installSizeMb) {
            this.presence = presence == null ? Recommendation.Presence.UNKNOWN : presence;
            this.installAction = installAction == null ? "" : installAction.trim();
            this.installSizeMb = installSizeMb;
            return this;
        }

        /** True when this arm produced a figure that can be ranked. */
        boolean hasFigure() {
            return !Double.isNaN(sdVsControlPercent);
        }

        /** Builds the arm. */
        public ArmOutcome build() {
            return new ArmOutcome(this);
        }
    }
}
