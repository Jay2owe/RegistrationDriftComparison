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
import ij.measure.ResultsTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything a run produced.
 *
 * <p>Four tables, a verdict with the sentence that explains it, the ranked
 * engines, the registered stack when one was made, and the record of what was
 * done. A run that could not finish carries a {@link Failure} instead: a kind
 * and a sentence, never an empty bundle that a macro or a batch loop would read
 * as success.
 *
 * <p>The verdict travels as a value as well as a table cell, because the run
 * branches on it and the library-validation stage asserts on it. A table cell
 * that has to be parsed back into a decision is a decision waiting to be parsed
 * differently by two callers.
 *
 * <p>What is <em>not</em> here, deliberately: a method that returns the
 * registered stack or throws. In diagnose mode there is no registered stack and
 * that is the expected outcome, not an error, so asking for it returns null and
 * the mode says why.
 */
public final class RegDriftResult {

    private final RegDriftParameters parameters;
    private final ResultsTable diagnosis;
    private final ResultsTable recommendation;
    private final ResultsTable comparison;
    private final ResultsTable frames;
    private final Verdict verdict;
    private final String verdictReason;
    private final List<Recommendation> ranked;
    private final ImagePlus registered;
    private final Provenance provenance;
    private final Failure failure;

    private RegDriftResult(Builder builder) {
        this.parameters = builder.parameters;
        this.diagnosis = builder.diagnosis;
        this.recommendation = builder.recommendation;
        this.comparison = builder.comparison;
        this.frames = builder.frames;
        this.verdict = builder.verdict;
        this.verdictReason = builder.verdictReason;
        this.ranked = Collections.unmodifiableList(
                new ArrayList<Recommendation>(builder.ranked));
        this.registered = builder.registered;
        this.provenance = builder.provenance;
        this.failure = builder.failure;
    }

    /** A builder for the result of a run over these settings. */
    public static Builder builder(RegDriftParameters parameters) {
        return new Builder(parameters);
    }

    /**
     * A result that says why the run could not finish.
     *
     * @param parameters what was asked for
     * @param failure    the typed reason, which is never null
     */
    public static RegDriftResult failed(RegDriftParameters parameters, Failure failure) {
        if (failure == null) {
            throw new IllegalArgumentException(
                    "A failed run needs a typed reason a caller can branch on.");
        }
        return builder(parameters).failure(failure).build();
    }

    /** The settings this run was given. */
    public RegDriftParameters parameters() {
        return parameters;
    }

    /** One row per channel. Null when the run produced no diagnosis. */
    public ResultsTable diagnosis() {
        return diagnosis;
    }

    /** One row per candidate engine. Null when the mode made no recommendation. */
    public ResultsTable recommendation() {
        return recommendation;
    }

    /** One row per arm actually run. Null when no arm ran. */
    public ResultsTable comparison() {
        return comparison;
    }

    /** One row per frame of the scored arm. Null when nothing was scored. */
    public ResultsTable frames() {
        return frames;
    }

    /**
     * Whether the movement can be registered. Null when the run stopped before
     * reaching a verdict, in which case {@link #failure()} says why.
     */
    public Verdict verdict() {
        return verdict;
    }

    /** The finished sentence behind the verdict. Empty when there is no verdict. */
    public String verdictReason() {
        return verdictReason;
    }

    /** The ranked engines, first ranked first. Empty when none were ranked. */
    public List<Recommendation> ranked() {
        return ranked;
    }

    /** The registered stack. Null unless a mode produced one. */
    public ImagePlus registered() {
        return registered;
    }

    /**
     * What the run did, in enough detail to repeat it. Null when the run stopped
     * before it measured anything.
     */
    public Provenance provenance() {
        return provenance;
    }

    /** Why the run could not finish. Null when it did. */
    public Failure failure() {
        return failure;
    }

    /** True when the run finished. */
    public boolean isSuccess() {
        return failure == null;
    }

    /** Builds a {@link RegDriftResult}. */
    public static final class Builder {

        private final RegDriftParameters parameters;
        private ResultsTable diagnosis;
        private ResultsTable recommendation;
        private ResultsTable comparison;
        private ResultsTable frames;
        private Verdict verdict;
        private String verdictReason = "";
        private List<Recommendation> ranked = new ArrayList<Recommendation>();
        private ImagePlus registered;
        private Provenance provenance;
        private Failure failure;

        private Builder(RegDriftParameters parameters) {
            if (parameters == null) {
                throw new IllegalArgumentException(
                        "A result needs the settings the run was given.");
            }
            this.parameters = parameters;
        }

        /** One row per channel. */
        public Builder diagnosis(ResultsTable diagnosis) {
            this.diagnosis = diagnosis;
            return this;
        }

        /** One row per candidate engine. */
        public Builder recommendation(ResultsTable recommendation) {
            this.recommendation = recommendation;
            return this;
        }

        /** One row per arm actually run. */
        public Builder comparison(ResultsTable comparison) {
            this.comparison = comparison;
            return this;
        }

        /** One row per frame of the scored arm. */
        public Builder frames(ResultsTable frames) {
            this.frames = frames;
            return this;
        }

        /** The verdict, and the finished sentence behind it. */
        public Builder verdict(Verdict verdict, String reason) {
            this.verdict = verdict;
            this.verdictReason = reason == null ? "" : reason.trim();
            return this;
        }

        /** The ranked engines, first ranked first. */
        public Builder ranked(List<Recommendation> ranked) {
            this.ranked = ranked == null
                    ? new ArrayList<Recommendation>()
                    : new ArrayList<Recommendation>(ranked);
            return this;
        }

        /** The registered stack, when a mode produced one. */
        public Builder registered(ImagePlus registered) {
            this.registered = registered;
            return this;
        }

        /** What the run did, in enough detail to repeat it. */
        public Builder provenance(Provenance provenance) {
            this.provenance = provenance;
            return this;
        }

        /** Why the run could not finish. */
        public Builder failure(Failure failure) {
            this.failure = failure;
            return this;
        }

        /** Builds the result. */
        public RegDriftResult build() {
            return new RegDriftResult(this);
        }
    }
}
