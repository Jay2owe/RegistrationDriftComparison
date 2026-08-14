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
import regdrift.advise.EngineAvailability;
import regdrift.advise.Recommender;
import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineId;
import regdrift.autofix.EngineRegistry;
import regdrift.diag.ChannelRanker;
import regdrift.diag.Estimators;
import regdrift.diag.Fingerprint;
import regdrift.diag.Frames;
import regdrift.diag.MotionDescriptors;
import regdrift.diag.WindowSampler;
import regdrift.harness.ArmResult;
import regdrift.harness.ArmStatus;
import regdrift.harness.EngineDescriptor;
import regdrift.harness.EngineRunner;
import regdrift.internal.PairScheduler;
import regdrift.internal.Transform;
import regdrift.score.ControlWarp;
import regdrift.score.Kymograph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * The public Java entry point: measure one recording and hand back what was
 * measured.
 *
 * <pre>
 * RegDriftParameters p = RegDriftParameters.builder(imp)
 *         .mode(Mode.DIAGNOSE_AND_RECOMMEND)
 *         .build();
 * RegDriftResult r = RegDrift.run(p);
 * </pre>
 *
 * <p><b>This method opens no dialog, shows no window, writes no file, installs
 * nothing, and needs no ImageJ window to be open.</b> That is not a habit, it is
 * a contract, and {@code ApiIsolationTest} asserts it against the compiled
 * bytecode of this class rather than against its imports. Writing files is
 * {@link RegDriftAutoSave}'s job, and this class does not reach it: the entry
 * classes and the batch runner decide whether anything is saved, because they
 * are the two places that know a user asked for it.
 *
 * <p>Every branch that cannot produce what it was asked for returns a
 * {@link RegDriftResult} carrying a {@link Failure}. Nothing here returns null
 * and nothing throws at a caller who handed in a recording this plugin cannot
 * do anything with: the reason is typed, so a macro, a batch loop and a dialog
 * can each say the right thing about it.
 *
 * <p>All five modes measure something. Two of them drive registration engines
 * over the recording as well - see {@link #drive}, which is also where the
 * decision about what every arm is scored against is written down.
 */
public final class RegDrift {

    /**
     * Which build produced a result.
     *
     * <p>Kept in step with the {@code version} in {@code pom.xml} by hand, and
     * read from here by everything else - the provenance record, the auto-save
     * {@code README.txt} and the summary file - so the version is spelled in one
     * place inside the source tree.
     */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    /** Fewer frames than this and there is no movement to measure. */
    public static final int MIN_FRAMES = 2;

    /**
     * How far outside the recording's own movement an arm may put the content
     * before its figure is refused rather than reported.
     *
     * <p>Two pixels. Every arm of a comparison is measured over one region - the
     * part of the frame that is real in every frame of the recording's own
     * movement, standing back a little from the edge - and an arm that put the
     * content further out than that leaves a strip of fill inside the region.
     * Fill is perfectly still, so it reads as a flawless registration, which is
     * the one direction this must not be wrong in.
     *
     * <p>The allowance exists because two pixels of it are ordinary rather than
     * suspicious: one for the rounding to whole pixels the region is worked out
     * with, and one for the last digit of the phase correlation that recovered
     * what the arm did. An arm outside <em>that</em> has genuinely put the
     * content somewhere the recording never went, and its row says so instead of
     * carrying a number.
     */
    public static final int ARM_MARGIN_ALLOWANCE_PX = 2;

    /**
     * Where a run finds out which registration engines this computer has, and
     * how it hands a recording to one.
     *
     * <p>The seam, and the reason there is one. Everything else about a
     * comparison can be checked against a recording built for the purpose, but
     * the two things a comparison is <em>about</em> - what is installed, and what
     * an engine does to a stack - are facts about somebody else's software on
     * somebody else's machine. A test that depended on those would pass or fail
     * according to whose laptop it ran on, which is the opposite of what it is
     * for.
     *
     * <p>Package-private, with no public way to change it and no macro option, so
     * it is a seam rather than a setting: every shipped path uses
     * {@link Bench#thisFiji()}, which reads the Fiji this is running inside and
     * drives engines through ImageJ's own command table.
     */
    static Bench bench = Bench.thisFiji();

    /** What this computer has of each engine, and how one is driven. */
    interface Bench {

        /** The catalogue. Reading it fetches nothing and writes nothing. */
        AutofixService catalogue();

        /** How an arm is run. Serial over arms, no window, no exit. */
        EngineRunner runner();

        /** The real one: this Fiji, and ImageJ's own command table. */
        static Bench thisFiji() {
            return new Bench() {
                @Override
                public AutofixService catalogue() {
                    return AutofixService.forThisFiji();
                }

                @Override
                public EngineRunner runner() {
                    return EngineRunner.forThisSession();
                }
            };
        }
    }

    private RegDrift() {
    }

    /**
     * Runs the default request over one recording: measure the movement, then
     * name the engines the measurements support.
     *
     * @param image the time-lapse stack to measure
     */
    public static RegDriftResult run(ImagePlus image) {
        return run(RegDriftParameters.builder(image).build());
    }

    /**
     * Runs one request.
     *
     * @param parameters what to do, built by {@link RegDriftParameters#builder}
     * @return what the run produced, or a typed reason it could not. Never null
     * @throws IllegalArgumentException when no settings bundle was given, which
     *         is a mistake in the calling code rather than something a run can
     *         report about a recording
     */
    public static RegDriftResult run(RegDriftParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("RegDrift.run needs a settings bundle. Build one"
                    + " with RegDriftParameters.builder(image).build().");
        }
        if (parameters.cancellation().canceled()) {
            return RegDriftResult.failed(parameters, stopped());
        }
        Failure unusable = checkRunnable(parameters);
        if (unusable != null) {
            return RegDriftResult.failed(parameters, unusable);
        }
        switch (parameters.mode()) {
            case DIAGNOSE:
            case DIAGNOSE_AND_RECOMMEND:
                return diagnose(parameters);
            case APPLY:
                return drive(parameters, true);
            case COMPARE:
                return drive(parameters, false);
            case SCORE:
                return score(parameters);
            default:
                break;
        }
        throw new IllegalStateException("Mode " + parameters.mode() + " has no branch in"
                + " RegDrift.run. A mode was added to the enum without one.");
    }

    /**
     * The reason a run hands back when the person who started it asked it to
     * stop.
     *
     * <p>A sentence rather than a thrown exception, so a batch loop can tell
     * "the user stopped this one" apart from "this one broke", and so nothing
     * half-finished is shown as though it were a measurement.
     */
    public static Failure stopped() {
        return Failure.of(Failure.Kind.CANCELED, "This run was stopped before it finished, so"
                + " there is nothing to report. Nothing was changed and nothing was saved.");
    }

    /**
     * How many time points a recording holds.
     *
     * <p>A plain stack - one channel, one frame declared, several slices - is
     * read as a time series, which is what the contract says and what an
     * unlabelled TIFF from a microscope usually is. A hyperstack states its own
     * frame count and is taken at its word.
     */
    public static int frameCount(ImagePlus image) {
        if (image == null) return 0;
        int frames = image.getNFrames();
        if (frames > 1) return frames;
        if (image.getNChannels() == 1 && image.getNSlices() > 1) return image.getNSlices();
        return frames;
    }

    /**
     * Whether this request can be attempted at all, before any mode branch sees
     * it. Returns null when it can.
     */
    private static Failure checkRunnable(RegDriftParameters parameters) {
        ImagePlus image = parameters.image();
        int frames = frameCount(image);
        if (frames < MIN_FRAMES) {
            return Failure.of(Failure.Kind.NO_TIME_AXIS, "'" + image.getTitle() + "' holds "
                    + frames + " frame" + (frames == 1 ? "" : "s") + ", so it carries no movement"
                    + " to measure. Open a time-lapse recording of " + MIN_FRAMES + " frames or"
                    + " more.");
        }
        if (parameters.mode() != Mode.SCORE) {
            return null;
        }
        ImagePlus second = parameters.compareWith();
        if (second == null) {
            return Failure.of(Failure.Kind.INVALID_PARAMETERS, "Mode '"
                    + Mode.SCORE.macroValue() + "' rates a recording somebody has already"
                    + " registered, and no second stack was given. Set '"
                    + RegDriftMacroOptions.COMPARE_WITH + "' to the registered stack, or choose"
                    + " another mode.");
        }
        int secondFrames = frameCount(second);
        if (second.getWidth() != image.getWidth()
                || second.getHeight() != image.getHeight()
                || secondFrames != frames) {
            return Failure.of(Failure.Kind.SECOND_STACK_MISMATCH, "The registered stack '"
                    + second.getTitle() + "' is " + second.getWidth() + "x" + second.getHeight()
                    + " over " + secondFrames + " frames, and '" + image.getTitle() + "' is "
                    + image.getWidth() + "x" + image.getHeight() + " over " + frames + " frames."
                    + " A scored pair has to be the same recording before and after, at the same"
                    + " size and the same length.");
        }
        return null;
    }

    // ------------------------------------------------------------------ diagnose

    /**
     * Measure the movement in one recording, say whether it can be registered,
     * and - in {@link Mode#DIAGNOSE_AND_RECOMMEND} - name the engines the
     * measurements support.
     *
     * <p>Both modes take the same measurement, because the recommendation is a
     * lookup in a bundled table and costs nothing beyond it. What the second mode
     * adds is a table read, a catalogue read, and the calibration flag that says
     * whether the recording resembles what the table was measured on.
     *
     * <p><b>Nothing is fetched, and nothing is installed.</b> Reading which
     * engines are here looks at classes already loaded, files already on disk and
     * ImageJ's own command table; the single method in this plugin that can reach
     * a network is a repair somebody presses a button for, and no path from here
     * reaches it.
     *
     * <p>Everything here runs on this thread except the frame pairs, which go to
     * a bounded, run-owned pool inside {@link Fingerprint}. Nothing is shown,
     * nothing is saved and nothing is installed.
     *
     * <p><b>The scale is chosen before anything is measured</b> and is written
     * into every row and into the provenance - see
     * {@link Fingerprint#binFor(int, int)} and defect D12.
     *
     * <p>One row per channel, as the contract's {@code Diagnosis} table says.
     * The channel the movement was measured on carries every column; the others
     * carry the four the channel ranking measures for all of them - the channel,
     * its localisability, the scale, and its frame correlation - and nothing
     * else, because nothing else was measured on them. The two localisability
     * figures come from different pair sets on purpose: the ranking scores every
     * channel on a strided handful of pairs to stay cheap, and the estimation
     * channel is then re-measured over the pairs the fingerprint actually used,
     * which is the number the verdict quotes.
     *
     * <p><b>Not yet acted on here:</b> {@link RegDriftParameters#useRoi()}. An
     * ROI restricting which pixels vote needs {@link Frames} to carry one, and
     * {@code Frames} does not; the setting is carried through the request and
     * the record and changes nothing about the measurement. Stated rather than
     * left to be discovered from a diagnosis that quietly measured the whole
     * frame.
     */
    private static RegDriftResult diagnose(RegDriftParameters parameters) {
        Measured measured = null;
        try {
            measured = measure(parameters);
            if (measured.refusal != null) {
                return RegDriftResult.failed(parameters, measured.refusal);
            }
            Provenance.Builder record = measured.record(parameters);
            RegDriftResult.Builder result = RegDriftResult.builder(parameters)
                    .diagnosis(diagnosisTable(measured))
                    .verdict(measured.verdict.kind(), measured.verdict.text())
                    .traces(tracesOf(measured.fingerprint));

            if (parameters.mode() == Mode.DIAGNOSE_AND_RECOMMEND) {
                Candidates candidates = candidatesFor(parameters);
                if (candidates.refusal != null) {
                    return RegDriftResult.failed(parameters, candidates.refusal);
                }
                Recommender.Result advice = Recommender.rank(measured.fingerprint,
                        measured.verdict, candidates.engines, candidates.availability);
                result.recommendation(recommendationTable(advice)).ranked(advice.ranked());
                record.calibrationSet(calibrationNote(advice, candidates))
                        .engineVersions(versionsOf(candidates));
            }
            return result.provenance(record.build()).build();
        } catch (CancellationException stoppedPartWay) {
            return RegDriftResult.failed(parameters, stopped());
        } finally {
            if (measured != null) measured.release();
        }
    }

    /**
     * The measuring half of every mode: which channel, at what scale, over which
     * frames, and what came out of them.
     *
     * <p>Shared by every mode that measures, so a recommendation, an applied
     * engine and a comparison are all keyed on the same fingerprint of the same
     * recording taken the same way. A run that cannot get this far comes back
     * with {@link Measured#refusal} set rather than throwing.
     *
     * <p>The caller lets go of what this opened, by calling
     * {@link Measured#release()} in a {@code finally}.
     */
    private static Measured measure(RegDriftParameters parameters) {
        ImagePlus image = parameters.image();
        Cancellation cancellation = parameters.cancellation();
        int workers = parameters.serial() ? 1 : 0;
        Measured measured = new Measured();
        measured.bin = Fingerprint.binFor(image.getWidth(), image.getHeight());

        measured.ranking = ChannelRanker.rank(image, measured.bin, 0, workers,
                PairScheduler.Progress.NONE, cancellation);
        measured.channel = parameters.channel().isAuto()
                ? (measured.ranking.measured() ? measured.ranking.chosen() : 1)
                : parameters.channel().index();
        measured.channelReason = parameters.channel().isAuto()
                ? measured.ranking.reason()
                : "Channel " + measured.channel + " was asked for, so no channel ranking was"
                        + " applied.";
        measured.slice = parameters.slice().isProject()
                ? Frames.PROJECT_Z : parameters.slice().index();

        try {
            measured.frames = Frames.of(image, measured.channel, measured.slice, measured.bin);
        } catch (IllegalArgumentException outsideTheImage) {
            measured.refusal = Failure.of(Failure.Kind.INVALID_PARAMETERS, "'" + image.getTitle()
                    + "' cannot be measured as asked: " + outsideTheImage.getMessage() + ".");
            return measured;
        }

        measured.plan = samplerFor(parameters, measured.frames.count())
                .plan(measured.frames.count());
        measured.fingerprint = Fingerprint.measure(measured.frames, measured.plan, workers,
                PairScheduler.Progress.NONE, cancellation);
        measured.verdict = regdrift.diag.Verdict.of(measured.fingerprint);
        return measured;
    }

    /** What the measuring half of a run produced, and what it opened to do it. */
    private static final class Measured {

        private Frames.Bin bin;
        private ChannelRanker.Ranking ranking;
        private int channel;
        private String channelReason = "";
        private int slice;
        private Frames frames;
        private WindowSampler.Plan plan;
        private Fingerprint fingerprint;
        private regdrift.diag.Verdict verdict;
        private Failure refusal;

        /** The record of what was measured, before any mode adds to it. */
        Provenance.Builder record(RegDriftParameters parameters) {
            return Provenance.builder()
                    .pluginVersion(VERSION)
                    .mode(parameters.mode())
                    .channel(channel)
                    .channelReason(channelReason)
                    .measuredAtBin(bin.factor())
                    .windowStarts(plan.everyConsecutivePair() ? new int[0] : plan.windowStarts())
                    .windowFrames(plan.framesPerWindow());
        }

        /** Lets go of the frames this opened. Doing it twice is safe. */
        void release() {
            if (frames == null) return;
            frames.release();
            frames = null;
        }
    }

    /** One row per candidate engine, in the order the ranking put them. */
    private static ResultsTable recommendationTable(Recommender.Result advice) {
        ResultsTable table = RegDriftTables.recommendation();
        for (Recommendation ranked : advice.ranked()) {
            RegDriftTables.append(table, ranked);
        }
        return table;
    }

    /** The calibration set, whether this recording sits inside it, and which engines. */
    private static String calibrationNote(Recommender.Result advice, Candidates candidates) {
        return advice.calibrationSet() + " " + advice.calibrationReason() + " "
                + candidates.reason;
    }

    // -------------------------------------------------------------------- score

    /**
     * Rate a recording somebody has already registered, against an
     * interpolation-matched control.
     *
     * <p>Two stacks arrive: the recording as it was, and the recording after some
     * plugin ran on it. Almost no registration plugin says what shifts it applied,
     * so the shifts are recovered by measuring the raw frame against the
     * registered one, and the control is then built from the <b>fractional part
     * only</b> of each of them. That control resamples exactly as the registered
     * recording did and takes out none of the drift, so what is reported -
     * {@code sd_vs_control} - is the part attributable to holding the field still.
     * <b>The raw recording's own temporal standard deviation is never computed and
     * is nowhere in the output</b> (defect D11); scored against it, a plugin that
     * did nothing but blur would look like a good one.
     *
     * <p>Where the control and the result are within noise the {@code status}
     * column says {@code cannot_separate} rather than a figure that would be read
     * as a ranking. That is a statement about the measurement, not about the
     * plugin being rated - see {@link regdrift.score.Arbiter}.
     *
     * <p><b>Scored at native resolution.</b> The fractional part of a shift
     * measured on binned pixels is not the fraction the engine resampled with, so
     * the one place in this plugin that does not measure at the fingerprint's
     * scale is this one, and it says so here rather than being discovered from a
     * control that resampled by the wrong amount (defect D12).
     *
     * <p>Nothing is shown, nothing is saved, and no engine is driven: this mode
     * judges a registration that already happened.
     */
    private static RegDriftResult score(RegDriftParameters parameters) {
        ImagePlus rawImage = parameters.image();
        ImagePlus registeredImage = parameters.compareWith();
        Cancellation cancellation = parameters.cancellation();
        int workers = parameters.serial() ? 1 : 0;
        Frames raw = null;
        Frames registered = null;
        try {
            Frames.Bin rankingBin = Fingerprint.binFor(rawImage.getWidth(), rawImage.getHeight());
            ChannelRanker.Ranking ranking = ChannelRanker.rank(rawImage, rankingBin, 0, workers,
                    PairScheduler.Progress.NONE, cancellation);
            int channel = parameters.channel().isAuto()
                    ? (ranking.measured() ? ranking.chosen() : 1)
                    : parameters.channel().index();
            String channelReason = parameters.channel().isAuto()
                    ? ranking.reason()
                    : "Channel " + channel + " was asked for, so no channel ranking was applied.";
            int slice = parameters.slice().isProject()
                    ? Frames.PROJECT_Z : parameters.slice().index();

            try {
                raw = Frames.of(rawImage, channel, slice, Frames.Bin.none());
                registered = Frames.of(registeredImage, channel, slice, Frames.Bin.none());
            } catch (IllegalArgumentException outsideTheImage) {
                return RegDriftResult.failed(parameters, Failure.of(
                        Failure.Kind.INVALID_PARAMETERS, "This pair cannot be scored as asked: "
                                + outsideTheImage.getMessage() + "."));
            }

            regdrift.score.Arbiter.Recovered recovered;
            regdrift.score.Arbiter.Scoring scoring;
            try {
                recovered = regdrift.score.Arbiter.recover(raw, registered, workers,
                        PairScheduler.Progress.NONE, cancellation);
                scoring = regdrift.score.Arbiter.score(raw, registered, recovered.cumulative(),
                        regdrift.score.ControlWarp.Interpolation.BILINEAR, workers,
                        PairScheduler.Progress.NONE, cancellation);
            } catch (IllegalArgumentException noControl) {
                return RegDriftResult.failed(parameters, Failure.of(Failure.Kind.SCORING_FAILED,
                        "'" + registeredImage.getTitle() + "' cannot be scored against '"
                                + rawImage.getTitle() + "': " + noControl.getMessage()));
            }

            Provenance record = Provenance.builder()
                    .pluginVersion(VERSION)
                    .mode(parameters.mode())
                    .channel(channel)
                    .channelReason(channelReason)
                    .measuredAtBin(Frames.Bin.none().factor())
                    .calibrationSet(recovered.provenance() + ". " + scoring.provenance())
                    .build();
            return RegDriftResult.builder(parameters)
                    .comparison(comparisonTable(registeredImage.getTitle(), scoring, parameters))
                    .frames(framesTable(scoring, recovered))
                    .provenance(record)
                    .build();
        } catch (CancellationException stoppedPartWay) {
            return RegDriftResult.failed(parameters, stopped());
        } finally {
            if (raw != null) raw.release();
            if (registered != null) registered.release();
        }
    }

    /**
     * The one-row comparison table for a scored arm.
     *
     * <p>{@code cpu_seconds} is left unmeasured on purpose: nothing was driven
     * here, and a zero in that column would read as an engine that cost nothing
     * (defect D6 in spirit - a timing shown is a timing measured).
     */
    private static ResultsTable comparisonTable(String title,
                                                regdrift.score.Arbiter.Scoring scoring,
                                                RegDriftParameters parameters) {
        ResultsTable table = RegDriftTables.comparison();
        StringBuilder status = new StringBuilder(scoring.separation().word());
        if (parameters.flagMotionLoss() && scoring.motion().raised()) {
            status.append("; motion_preservation_flag");
        }
        RegDriftTables.comparisonRow()
                .engine(title)
                .settings(parameters.arbiter().macroValue() + ", "
                        + scoring.interpolation().words() + " control")
                .residualBefore(scoring.medianResidualBefore())
                .residualAfter(scoring.medianResidualAfter())
                .residualRemoved(scoring.residualRemoved())
                .sdVsControl(scoring.sdVsControl())
                .pathPx(scoring.pathPx())
                .netPx(scoring.netPx())
                .framesFlagged(scoring.framesFlagged())
                .status(status.toString())
                .appendTo(table);
        return table;
    }

    /** One row per frame of the scored arm, in frame order. */
    private static ResultsTable framesTable(regdrift.score.Arbiter.Scoring scoring,
                                            regdrift.score.Arbiter.Recovered recovered) {
        ResultsTable table = RegDriftTables.frames();
        double[] before = scoring.residualBefore();
        double[] after = scoring.residualAfter();
        regdrift.internal.Transform[] cumulative = scoring.cumulative();
        for (int t = 0; t < cumulative.length; t++) {
            regdrift.internal.Transform now = cumulative[t];
            regdrift.internal.Transform was = t == 0 ? now : cumulative[t - 1];
            FrameStatus status = scoring.statusOf(t);
            if (recovered.statusOf(t) == regdrift.diag.Estimator.Status.AT_SHIFT_BOUND) {
                status = FrameStatus.AT_SHIFT_BOUND;
            }
            RegDriftTables.framesRow()
                    .t(t + 1)
                    .cumDx(now.dx)
                    .cumDy(now.dy)
                    .stepDx(now.dx - was.dx)
                    .stepDy(now.dy - was.dy)
                    .residualBefore(before[t])
                    .residualAfter(after[t])
                    .validFraction(scoring.validFraction())
                    .status(status)
                    .appendTo(table);
        }
        return table;
    }

    // --------------------------------------------------------------------- arms

    /**
     * Run registration engines over this recording and rate what each of them
     * produced.
     *
     * <p>Two modes arrive here. {@link Mode#APPLY} drives one engine - the one
     * the ranking put first among the engines this computer has, or the one the
     * settings named - and hands back the registered recording.
     * {@link Mode#COMPARE} drives every engine that is here, one after another,
     * and hands back a row for every engine it considered, <b>including the ones
     * that are not here</b>. They share this method because everything except how
     * many engines are dispatched is the same, and because the two modes must
     * produce the same figure for the same engine on the same recording.
     *
     * <h2>One control for every arm, and where it comes from</h2>
     *
     * <p>The control is the recording resampled by the fractional part only of a
     * transform per frame, so it carries the resampling blur every method gets for
     * free and none of the alignment (defect D11). Which transforms is a decision
     * with teeth, because changing it changes every number, and there were two
     * candidates: the reference arm's, or this plugin's own estimate.
     *
     * <p><b>This plugin's own estimate</b>, measured by
     * {@link regdrift.score.Arbiter#ownMotion} over every consecutive frame pair
     * at native resolution, <b>before any engine is dispatched</b>. Two reasons,
     * and both are about the figures being comparable:
     *
     * <ul>
     *   <li>It exists before any arm runs, so an arm that fails, times out, or is
     *       not installed on this computer cannot change every other arm's
     *       number.</li>
     *   <li>It does not depend on which engines this computer happens to have, so
     *       the same recording compared on two computers with different engines
     *       installed gives figures for the engines they share that can be put
     *       beside each other.</li>
     * </ul>
     *
     * <p>The reference arm's transforms would fail both. The cost of measuring it
     * is one estimator pass over the recording, which the pre-dispatch estimate
     * counts.
     *
     * <p>{@link Mode#SCORE} deliberately does <em>not</em> share this. It rates
     * one recording somebody else registered, there is no second arm to be
     * like-for-like with, and its control is that arm's own resampling - which is
     * what stage 12 measured the twelve library figures with. A figure from
     * {@code score} and a figure from {@code compare} are therefore about
     * different controls, and both say so in {@code provenance}.
     *
     * <h2>One region for every arm</h2>
     *
     * <p>Everything is measured inside the part of the frame that is real in every
     * frame, standing back {@value #ARM_MARGIN_ALLOWANCE_PX} pixels further - see
     * {@link #ARM_MARGIN_ALLOWANCE_PX}. The same region for every arm, so two
     * figures are about the same pixels; and an arm that put the content outside
     * it gets a row with the reason rather than a figure measured partly on fill.
     *
     * <p>Nothing here is shown, nothing is saved, and no window is opened - the
     * engines are driven through ImageJ's batch mode, which is
     * {@link EngineRunner}'s business and is asserted from its compiled form.
     */
    private static RegDriftResult drive(RegDriftParameters parameters, boolean applyOne) {
        ImagePlus image = parameters.image();
        Cancellation cancellation = parameters.cancellation();
        int workers = parameters.serial() ? 1 : 0;
        ControlWarp.Interpolation resampling = ControlWarp.Interpolation.BILINEAR;
        Measured measured = null;
        Frames raw = null;
        ImagePlus keep = null;
        try {
            measured = measure(parameters);
            if (measured.refusal != null) {
                return RegDriftResult.failed(parameters, measured.refusal);
            }
            Candidates candidates = candidatesFor(parameters, true);
            if (candidates.refusal != null) {
                return RegDriftResult.failed(parameters, candidates.refusal);
            }
            Recommender.Result advice = Recommender.rank(measured.fingerprint, measured.verdict,
                    candidates.engines, candidates.availability);
            Lineup lineup = lineupFor(parameters, applyOne, advice, candidates);
            if (lineup.refusal != null) {
                return RegDriftResult.failed(parameters, lineup.refusal);
            }

            DispatchEstimate estimate = estimateFor(lineup, image,
                    measured.fingerprint.frameCount());
            if (!lineup.driven.isEmpty() && !parameters.dispatch().proceed(estimate)) {
                return RegDriftResult.failed(parameters, Failure.of(Failure.Kind.CANCELED,
                        "Nothing was driven: this run was stopped at the estimate of what it would"
                                + " cost (" + estimate.shortText() + "). No engine was run, no"
                                + " window was opened and nothing was saved."));
            }
            if (cancellation.canceled()) return RegDriftResult.failed(parameters, stopped());

            if (lineup.driven.isEmpty()) {
                // Every engine considered is missing, or is one this plugin drives no arm for.
                // The rows are the answer - a comparison that came back empty because there was
                // nothing to run would read as a comparison that found nothing to choose between.
                // Nothing below this point is worth doing: no arm means nothing to score, and the
                // pass that measures the recording's own movement exists to build the control the
                // arms are scored against.
                return nothingDriven(parameters, measured, advice, candidates);
            }

            int channel = measured.channel;
            int slice = measured.slice;
            Fingerprint fingerprint = measured.fingerprint;
            ResultsTable diagnosis = diagnosisTable(measured);
            Provenance.Builder record = measured.record(parameters)
                    .engineVersions(versionsOf(candidates));
            // The binned frames have done their work, and the pass below opens the recording again
            // at native resolution. Two copies of a long recording at once is what this avoids.
            measured.release();

            raw = Frames.of(image, channel, slice, Frames.Bin.none());
            regdrift.score.Arbiter.Recovered own = regdrift.score.Arbiter.ownMotion(raw, workers,
                    PairScheduler.Progress.NONE, cancellation);
            Transform[] controlTransforms = own.cumulative();
            try {
                ControlWarp.requireAnInterpolatingControl(
                        ControlWarp.fractionalOf(controlTransforms), resampling);
            } catch (IllegalArgumentException noControl) {
                return RegDriftResult.failed(parameters, Failure.of(Failure.Kind.SCORING_FAILED,
                        "'" + image.getTitle() + "' cannot be compared: " + noControl.getMessage()
                                + " No engine was driven."));
            }
            int width = raw.width();
            int height = raw.height();
            ControlWarp.Margin shared = ControlWarp
                    .validMargin(controlTransforms, width, height, resampling)
                    .grownBy(ARM_MARGIN_ALLOWANCE_PX, width, height);

            EngineRunner runner = bench.runner();
            List<ArmOutcome.Builder> arms = new ArrayList<ArmOutcome.Builder>();
            regdrift.score.Arbiter.Scoring bestScoring = null;
            regdrift.score.Arbiter.Recovered bestRecovered = null;
            double bestFigure = Double.NaN;

            for (EngineId engine : lineup.rows) {
                String name = EngineRegistry.displayName(engine);
                EngineDescriptor descriptor = EngineDescriptor.forEngine(engine);
                Recommendation ranked = rankedFor(advice, name);
                Recommendation.Presence presence = candidates.availability.presenceOf(engine);

                if (!lineup.driven.contains(engine)) {
                    arms.add(notDriven(engine, name, descriptor, presence, ranked));
                    continue;
                }
                if (cancellation.canceled()) {
                    arms.add(ArmOutcome.builder(engine, name, ArmStatus.CANCELED)
                            .settings(descriptor.optionsTemplate())
                            .detail("The run was stopped before " + name + " was dispatched, so"
                                    + " this engine was not driven and nothing about it was"
                                    + " measured.")
                            .availability(presence, actionOf(ranked), sizeOf(ranked)));
                    continue;
                }

                ArmResult arm = runner.run(descriptor, image, cancellation, 0L);
                ArmOutcome.Builder row = ArmOutcome.builder(engine, name, arm.status())
                        .statusText(arm.statusColumn())
                        .settings(arm.settings())
                        .detail(arm.detail())
                        .cpu(arm.cpuSeconds(), arm.cpuIsAFloor())
                        .availability(presence, actionOf(ranked), sizeOf(ranked));
                arms.add(row);

                ImagePlus produced = arm.registered();
                if (produced == null) continue;
                Frames armFrames = null;
                try {
                    armFrames = Frames.of(produced, channel, slice, Frames.Bin.none());
                    regdrift.score.Arbiter.Recovered did = regdrift.score.Arbiter.recover(raw,
                            armFrames, workers, PairScheduler.Progress.NONE, cancellation);
                    row.walked(did.pathPx(), did.netPx());
                    ControlWarp.Margin armMargin = ControlWarp.validMargin(did.cumulative(),
                            width, height, resampling);
                    if (!armMargin.fitsInside(shared)) {
                        row.detail(arm.detail() + " " + outsideTheSharedRegion(name, armMargin,
                                shared, did.framesAtBound()));
                    } else {
                        regdrift.score.Arbiter.Scoring scored = regdrift.score.Arbiter.score(raw,
                                armFrames, did.cumulative(), controlTransforms, shared, resampling,
                                workers, PairScheduler.Progress.NONE, cancellation);
                        row.scoring(scored, parameters.flagMotionLoss());
                        if (Double.isNaN(bestFigure) || scored.sdVsControlPercent() < bestFigure) {
                            bestFigure = scored.sdVsControlPercent();
                            bestScoring = scored;
                            bestRecovered = did;
                            ImagePlus previous = keep;
                            keep = produced;
                            produced = previous;
                        }
                    }
                } catch (IllegalArgumentException couldNotScore) {
                    row.detail(arm.detail() + " What it produced could not be scored against the"
                            + " recording it came from: " + couldNotScore.getMessage() + ".");
                } finally {
                    if (armFrames != null) armFrames.release();
                }
                // Whatever is not being handed back is let go of now rather than at the end: arms
                // are serial, and holding every arm's recording at once is how a comparison of a
                // long recording runs a machine out of memory.
                if (produced != null && produced != keep) letGo(produced);
            }

            List<ArmOutcome> ranked = ArmOutcome.rank(arms);
            ResultsTable comparison = RegDriftTables.comparison();
            for (ArmOutcome one : ranked) {
                one.appendTo(comparison);
            }

            RegDriftResult.Builder result = RegDriftResult.builder(parameters)
                    .diagnosis(diagnosis)
                    .verdict(measured.verdict.kind(), measured.verdict.text())
                    .recommendation(recommendationTable(advice))
                    .ranked(advice.ranked())
                    .arms(ranked)
                    .comparison(comparison)
                    .traces(tracesOf(fingerprint, controlTransforms));
            if (bestScoring != null) {
                result.frames(framesTable(bestScoring, bestRecovered));
            }
            if (keep != null) {
                keep.setTitle(registeredTitle(image, ranked));
                result.registered(keep);
                Frames keptFrames = null;
                try {
                    keptFrames = Frames.of(keep, channel, slice, Frames.Bin.none());
                    result.qcPanel(Kymograph.beforeAndAfter(raw, keptFrames, shared,
                            image.getTitle() + " - before and after"));
                } catch (IllegalArgumentException noPanel) {
                    // A panel is a picture of measurements the tables already carry. Not being
                    // able to draw one is not a reason to lose the run, and there is nothing here
                    // for a person to act on that the rows do not already say.
                    noPanel.getMessage();
                } finally {
                    if (keptFrames != null) keptFrames.release();
                }
                keep = null;                // handed over; not this method's to let go of
            }
            record.calibrationSet(calibrationNote(advice, candidates) + " " + own.provenance()
                    + (bestScoring == null ? "" : ". " + bestScoring.provenance()));
            return result.provenance(record.build()).build();
        } catch (CancellationException stoppedPartWay) {
            return RegDriftResult.failed(parameters, stopped());
        } finally {
            if (keep != null) letGo(keep);
            if (raw != null) raw.release();
            if (measured != null) measured.release();
        }
    }

    /**
     * A comparison in which no engine could be driven: every engine considered
     * gets its row, with what to do about it.
     *
     * <p>This is a finished run rather than a failure, and the distinction is the
     * third of this stage's presentation rules. A comparison that came back with
     * a reason instead of a table would leave somebody guessing which engines it
     * had wanted; a table of rows, each naming what installing it would take,
     * says exactly what would have to change for there to be a comparison.
     */
    private static RegDriftResult nothingDriven(RegDriftParameters parameters, Measured measured,
                                                Recommender.Result advice, Candidates candidates) {
        List<ArmOutcome.Builder> arms = new ArrayList<ArmOutcome.Builder>();
        for (Recommendation ranked : advice.ranked()) {
            EngineId engine = EngineRegistry.byDisplayName(ranked.engine());
            if (engine == null) continue;
            arms.add(notDriven(engine, ranked.engine(), EngineDescriptor.forEngine(engine),
                    candidates.availability.presenceOf(engine), ranked));
        }
        List<ArmOutcome> ranked = ArmOutcome.rank(arms);
        ResultsTable comparison = RegDriftTables.comparison();
        for (ArmOutcome arm : ranked) {
            arm.appendTo(comparison);
        }
        return RegDriftResult.builder(parameters)
                .diagnosis(diagnosisTable(measured))
                .verdict(measured.verdict.kind(), measured.verdict.text())
                .recommendation(recommendationTable(advice))
                .ranked(advice.ranked())
                .arms(ranked)
                .comparison(comparison)
                .traces(tracesOf(measured.fingerprint))
                .provenance(measured.record(parameters)
                        .calibrationSet(calibrationNote(advice, candidates))
                        .engineVersions(versionsOf(candidates))
                        .build())
                .build();
    }

    /**
     * Which engines get a row, and which of those are actually dispatched.
     *
     * <p>Every engine the run considered gets a row, present or not: a comparison
     * that silently leaves out the engines somebody has not installed looks like a
     * complete answer and is not. What is <em>driven</em> is the subset this
     * computer has and this plugin knows how to drive.
     */
    private static Lineup lineupFor(RegDriftParameters parameters, boolean applyOne,
                                    Recommender.Result advice, Candidates candidates) {
        Lineup lineup = new Lineup();
        List<EngineId> ordered = new ArrayList<EngineId>();
        for (Recommendation ranked : advice.ranked()) {
            EngineId engine = EngineRegistry.byDisplayName(ranked.engine());
            if (engine != null && !ordered.contains(engine)) ordered.add(engine);
        }

        if (!applyOne) {
            lineup.rows = ordered;
            for (EngineId engine : ordered) {
                if (canBeDriven(engine, candidates)) lineup.driven.add(engine);
            }
            return lineup;
        }

        if (parameters.hasApplyEngine()) {
            EngineId named = EngineRegistry.byDisplayName(parameters.applyEngine());
            if (named == null) {
                lineup.refusal = Failure.of(Failure.Kind.INVALID_PARAMETERS, "This build does not"
                        + " know a registration engine called '" + parameters.applyEngine()
                        + "'. The engines it knows about are: "
                        + join(EngineRegistry.displayNames()) + ".");
                return lineup;
            }
            if (!canBeDriven(named, candidates)) {
                lineup.refusal = cannotDrive(named, candidates, advice);
                return lineup;
            }
            lineup.rows.add(named);
            lineup.driven.add(named);
            return lineup;
        }

        for (EngineId engine : ordered) {
            if (!canBeDriven(engine, candidates)) continue;
            lineup.rows.add(engine);
            lineup.driven.add(engine);
            return lineup;
        }
        lineup.refusal = nothingToDrive(ordered, candidates, advice);
        return lineup;
    }

    /**
     * Whether an arm would be dispatched for this engine.
     *
     * <p>Present, or present at a version this plugin's figures were not measured
     * against - which still runs, and is flagged in the row. An engine the
     * catalogue could not check is <b>not</b> dispatched: on a real Fiji the
     * catalogue answers yes or no, so an unknown means this is not a real Fiji,
     * and driving somebody else's plugin on a guess is not something to do inside
     * a live session.
     */
    private static boolean canBeDriven(EngineId engine, Candidates candidates) {
        Recommendation.Presence presence = candidates.availability.presenceOf(engine);
        if (presence != Recommendation.Presence.PRESENT
                && presence != Recommendation.Presence.VERSION_NOT_DRIVEN) {
            return false;
        }
        return EngineDescriptor.forEngine(engine).isDrivable();
    }

    /** The row for an engine no arm was dispatched for, with what to do about it. */
    private static ArmOutcome.Builder notDriven(EngineId engine, String name,
                                                EngineDescriptor descriptor,
                                                Recommendation.Presence presence,
                                                Recommendation ranked) {
        boolean absent = presence == Recommendation.Presence.ABSENT;
        ArmStatus status = absent ? ArmStatus.NOT_INSTALLED : ArmStatus.COULD_NOT_DRIVE;
        String why;
        if (absent) {
            why = name + " is not on this computer, so no arm was run for it. "
                    + (ranked == null || ranked.installAction().isEmpty()
                            ? "The Engines section says what to do about it."
                            : ranked.installAction())
                    + " Nothing was fetched to produce this row.";
        } else if (!descriptor.isDrivable()) {
            why = descriptor.notDrivableReason();
        } else {
            why = "Whether " + name + " is on this computer could not be read, so no arm was run"
                    + " for it. Driving somebody else's plugin on a guess is not something this"
                    + " does inside a live session; the Engines section reports what was found.";
        }
        return ArmOutcome.builder(engine, name, status)
                .settings(descriptor.optionsTemplate())
                .detail(why)
                .availability(presence, actionOf(ranked), sizeOf(ranked));
    }

    /**
     * Why an arm's figure is refused rather than reported.
     *
     * <p>Two sentences, and which one arrives matters. An arm whose shifts were
     * recovered cleanly and still sits outside the region really did put the
     * content somewhere the recording never went. An arm where the search for
     * those shifts ran out at its own bound is a different thing: nobody knows
     * where the content ended up, and blaming the engine for it would send
     * somebody looking at the wrong software. Both end the same way - no figure,
     * no rank - because both leave a number that would be measured partly on
     * fill, and fill does not move, so it reads as a flawless registration.
     */
    private static String outsideTheSharedRegion(String name, ControlWarp.Margin arm,
                                                 ControlWarp.Margin shared, int framesAtBound) {
        StringBuilder said = new StringBuilder();
        if (framesAtBound > 0) {
            said.append("Where ").append(name).append(" put the content could not be measured: the")
                    .append(" search for it ran out at its own bound on ").append(framesAtBound)
                    .append(framesAtBound == 1 ? " frame" : " frames").append(", so the shifts")
                    .append(" recovered from it are a floor rather than a measurement, and the")
                    .append(" region they describe (").append(arm).append(") falls outside the one")
                    .append(" every arm of this comparison is measured over (").append(shared)
                    .append(").");
        } else {
            said.append(name).append(" put the content outside the region every arm of this")
                    .append(" comparison is measured over (").append(arm).append(" against ")
                    .append(shared).append(").");
        }
        said.append(" Part of any figure for it would therefore be measured on the fill an engine")
                .append(" leaves behind rather than on the sample, and fill does not move, so it")
                .append(" reads as a flawless registration. The arm ran and what it cost is")
                .append(" reported; it is left out of the ranking rather than ranked on that.");
        return said.toString();
    }

    /** The reason a named engine cannot be run here. */
    private static Failure cannotDrive(EngineId engine, Candidates candidates,
                                       Recommender.Result advice) {
        String name = EngineRegistry.displayName(engine);
        Recommendation ranked = rankedFor(advice, name);
        EngineDescriptor descriptor = EngineDescriptor.forEngine(engine);
        if (!descriptor.isDrivable()) {
            return Failure.of(Failure.Kind.ENGINE_UNAVAILABLE, descriptor.notDrivableReason());
        }
        return Failure.of(Failure.Kind.ENGINE_UNAVAILABLE, name + " was named as the engine to run"
                + " and is not available on this computer, so nothing was driven. "
                + (ranked == null || ranked.installAction().isEmpty()
                        ? "The Engines section of the dialog says what to do about it."
                        : ranked.installAction())
                + " Nothing here fetches anything: installing an engine is a button in the Engines"
                + " section.");
    }

    /** The reason there is no engine here to apply at all. */
    private static Failure nothingToDrive(List<EngineId> ordered, Candidates candidates,
                                          Recommender.Result advice) {
        StringBuilder said = new StringBuilder("No registration engine this plugin can drive is"
                + " available on this computer, so nothing was run. The ranking was still worked"
                + " out and says which engine the measurements support");
        for (Recommendation ranked : advice.ranked()) {
            if (ranked.rank() != 1) continue;
            said.append(": ").append(ranked.engine()).append(".");
            if (!ranked.installAction().isEmpty()) {
                said.append(' ').append(ranked.installAction());
            }
            break;
        }
        said.append(" Run mode '").append(Mode.DIAGNOSE_AND_RECOMMEND.macroValue())
                .append("' for that ranking on its own, or install an engine from the Engines"
                        + " section of the dialog. Nothing was fetched to produce this.");
        return Failure.of(Failure.Kind.ENGINE_UNAVAILABLE, said.toString());
    }

    /** The ranked row for one engine, or null when the ranking has none. */
    private static Recommendation rankedFor(Recommender.Result advice, String engineName) {
        for (Recommendation ranked : advice.ranked()) {
            if (ranked.engine().equals(engineName)) return ranked;
        }
        return null;
    }

    private static String actionOf(Recommendation ranked) {
        return ranked == null ? "" : ranked.installAction();
    }

    private static double sizeOf(Recommendation ranked) {
        return ranked == null ? 0 : ranked.installSizeMb();
    }

    /**
     * What the registered recording is called when it is handed back.
     *
     * <p>The recording's own title and the engine that produced it, so a window
     * on somebody's screen says which of several attempts it is. What a saved
     * file is called is worked out where files are written, from the recording's
     * title, and not from here.
     */
    private static String registeredTitle(ImagePlus image, List<ArmOutcome> ranked) {
        String stem = image.getTitle() == null ? "registered" : image.getTitle();
        for (ArmOutcome arm : ranked) {
            if (arm.rank() == 1) return stem + " - " + arm.engineName();
        }
        return stem + " - registered";
    }

    /**
     * Lets go of a recording no longer needed.
     *
     * <p>Never a window: what an arm produced was never shown and was taken back
     * out of ImageJ's list by the harness before it was handed over. This releases
     * the pixels, which on a long recording is hundreds of megabytes an arm.
     */
    private static void letGo(ImagePlus produced) {
        try {
            produced.changes = false;
            produced.flush();
        } catch (RuntimeException stubborn) {
            // One recording that will not let go of its pixels is one object the collector will
            // take later. Losing the run over it would be worse, and there is nothing here for a
            // person to act on.
            return;
        }
    }

    /** What a comparison is expected to cost, before anything is dispatched. */
    private static DispatchEstimate estimateFor(Lineup lineup, ImagePlus image, int frames) {
        DispatchEstimate.Builder estimate = DispatchEstimate
                .builder(frames, image.getWidth(), image.getHeight());
        for (EngineId engine : lineup.driven) {
            estimate.arm(EngineRegistry.displayName(engine));
        }
        return estimate.build();
    }

    /** Which engines get a row, which of them are dispatched, and why not. */
    private static final class Lineup {

        private List<EngineId> rows = new ArrayList<EngineId>();
        private final List<EngineId> driven = new ArrayList<EngineId>();
        private Failure refusal;
    }

    // ------------------------------------------------------------------- curves

    /** The curves a measuring run produced: the sampled steps, and the intensity. */
    private static List<Trace> tracesOf(Fingerprint fingerprint) {
        List<Trace> traces = new ArrayList<Trace>();
        Trace motion = sampledMotionTrace(fingerprint);
        if (motion != null) traces.add(motion);
        Trace intensity = intensityTrace(fingerprint);
        if (intensity != null) traces.add(intensity);
        return traces;
    }

    /** The curves a driving run produced: the whole-recording chain, and the intensity. */
    private static List<Trace> tracesOf(Fingerprint fingerprint, Transform[] chain) {
        List<Trace> traces = new ArrayList<Trace>();
        Trace motion = chainTrace(chain);
        if (motion != null) traces.add(motion);
        Trace intensity = intensityTrace(fingerprint);
        if (intensity != null) traces.add(intensity);
        return traces;
    }

    /**
     * How far the recording moved at each frame pair the sampler measured.
     *
     * <p>Steps rather than a running total, because a windowed sample has no
     * running total to draw: three windows of consecutive frames with gaps between
     * them, and a line joined across a gap would claim a measurement nobody took.
     * The steps across those gaps are drawn as a curve of their own for the same
     * reason - one of them spans two frames and another spans two hundred, and
     * putting them on one line with the within-window steps would read as a spike.
     */
    private static Trace sampledMotionTrace(Fingerprint fingerprint) {
        Estimators.Result estimates = fingerprint.estimates();
        WindowSampler.Plan plan = fingerprint.plan();
        if (estimates == null || plan == null || estimates.pairs().isEmpty()) return null;
        int factor = estimates.measuredAt() == null ? 1 : estimates.measuredAt().factor();
        int windowPairs = plan.windowPairCount();
        List<Double> stepX = new ArrayList<Double>();
        List<Double> stepY = new ArrayList<Double>();
        List<Double> bridgeX = new ArrayList<Double>();
        List<Double> bridgeY = new ArrayList<Double>();
        List<Estimators.PairEstimate> pairs = estimates.pairs();
        for (int i = 0; i < pairs.size(); i++) {
            Estimators.PairEstimate pair = pairs.get(i);
            regdrift.diag.Estimator.Displacement found = pair.byPhaseCorrelation();
            if (found == null || !found.defined()) continue;
            double px = factor * Math.hypot(found.dx(), found.dy());
            if (i < windowPairs) {
                stepX.add(Double.valueOf(pair.to() + 1.0));
                stepY.add(Double.valueOf(px));
            } else {
                bridgeX.add(Double.valueOf(pair.to() + 1.0));
                bridgeY.add(Double.valueOf(px));
            }
        }
        if (stepX.isEmpty() && bridgeX.isEmpty()) return null;
        List<Trace.Series> series = new ArrayList<Trace.Series>();
        if (!stepX.isEmpty()) {
            series.add(new Trace.Series("step between consecutive frames",
                    unbox(stepX), unbox(stepY)));
        }
        if (!bridgeX.isEmpty()) {
            series.add(new Trace.Series("step across a gap between windows",
                    unbox(bridgeX), unbox(bridgeY)));
        }
        return Trace.of(Trace.Kind.MOTION, "Motion trace", "frame", "step (pixels)", series, true,
                "Measured over " + plan.provenance() + ". A step across a gap spans many frames and"
                        + " is drawn apart from the consecutive ones for that reason.");
    }

    /** How far the content has travelled from the first frame, frame by frame. */
    private static Trace chainTrace(Transform[] chain) {
        if (chain == null || chain.length < 2) return null;
        double[] frame = new double[chain.length];
        double[] dx = new double[chain.length];
        double[] dy = new double[chain.length];
        double[] distance = new double[chain.length];
        for (int t = 0; t < chain.length; t++) {
            Transform now = chain[t] == null ? Transform.IDENTITY : chain[t];
            frame[t] = t + 1.0;
            dx[t] = now.dx;
            dy[t] = now.dy;
            distance[t] = Math.hypot(now.dx, now.dy);
        }
        List<Trace.Series> series = new ArrayList<Trace.Series>();
        series.add(new Trace.Series("distance from the first frame", frame, distance));
        series.add(new Trace.Series("x", frame, dx));
        series.add(new Trace.Series("y", frame, dy));
        return Trace.of(Trace.Kind.MOTION, "Motion trace", "frame", "displacement (pixels)",
                series, true, "Measured between consecutive frames of the recording itself at"
                        + " native resolution, before any engine ran. This is what the control"
                        + " every arm was scored against was built from.");
    }

    /**
     * Mean brightness against frame, in log2 units, with the fitted trend beside
     * it.
     *
     * <p>Off by default. It is the check somebody asks for when {@code log2_trend}
     * reads oddly, and drawing a fitted line without the points it was fitted
     * through is how a fit stops being checkable.
     */
    private static Trace intensityTrace(Fingerprint fingerprint) {
        int[] frames = fingerprint.sampledFrames();
        double[] log2Mean = fingerprint.sampledLog2Mean();
        if (frames.length < 2 || log2Mean.length != frames.length) return null;
        double[] x = new double[frames.length];
        for (int i = 0; i < frames.length; i++) {
            x[i] = frames[i] + 1.0;
        }
        List<Trace.Series> series = new ArrayList<Trace.Series>();
        series.add(new Trace.Series("measured", x, log2Mean));
        double trend = fingerprint.log2Trend();
        int span = Math.max(1, fingerprint.frameCount() - 1);
        if (!Double.isNaN(trend)) {
            double slope = trend / span;
            double intercept = log2Mean[0] - slope * frames[0];
            double[] fitted = new double[frames.length];
            for (int i = 0; i < frames.length; i++) {
                fitted[i] = intercept + slope * frames[i];
            }
            series.add(new Trace.Series("fitted trend", x, fitted));
        }
        return Trace.of(Trace.Kind.INTENSITY, "Intensity trend", "frame",
                "mean brightness (log2)", series, false,
                String.format(Locale.US, "Fitted through %d of the %d frames, the ones the sampler"
                                + " read. Across the whole recording the fit changes by %.3f in"
                                + " log2.", frames.length, fingerprint.frameCount(), trend));
    }

    private static double[] unbox(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i).doubleValue();
        }
        return out;
    }

    // ---------------------------------------------------------------- recommend

    /**
     * Which engines to rank, and what this computer has of them.
     *
     * <p>Reading the catalogue looks at classes already loaded, files already on
     * disk and ImageJ's own command table. <b>It fetches nothing and writes
     * nothing</b>, which is house rule 9 and the reason a recommendation works
     * completely on a Fiji with no registration engine installed.
     *
     * <p>The awkward case is that Fiji: {@code engines=installed} on a computer
     * with none installed resolves to an empty list, and an empty recommendation
     * table is the least useful thing this plugin could hand somebody who has
     * just been told their recording can be registered. So an empty answer falls
     * back to the whole catalogue, every row marked absent with what installing
     * it would cost, and the fallback is stated in the record of the run rather
     * than being silent.
     */
    private static Candidates candidatesFor(RegDriftParameters parameters) {
        return candidatesFor(parameters, false);
    }

    /**
     * The same, for a mode that is about to drive engines.
     *
     * <p>One difference, and it is the third presentation rule of this stage.
     * {@code engines=installed} is the default, and for a ranking it means what it
     * says: rank what is here. For a <b>comparison</b> it would mean quietly
     * leaving out every engine somebody has not installed - and a comparison that
     * lists four engines on a computer with four installed reads as a comparison
     * of the field, which it is not. So a comparison considers the whole
     * catalogue, every absent engine gets a row saying what installing it would
     * take, and the arms that actually run are the ones that are here.
     *
     * <p>{@code engines=all} already meant this. A run that named its engines
     * still gets exactly those, because naming them is a choice rather than a
     * default nobody looked at.
     */
    private static Candidates candidatesFor(RegDriftParameters parameters, boolean forDriving) {
        EngineAvailability availability = EngineAvailability.of(bench.catalogue());
        EngineSelection wanted = parameters.engines();
        if (forDriving && wanted.kind() == EngineSelection.Kind.PRESENT) {
            return new Candidates(Recommender.everyEngine(), availability,
                    "Considered every engine this plugin knows about, so the ones this computer"
                            + " does not have appear with what installing them would take rather"
                            + " than being left out of a comparison that would then read as a"
                            + " comparison of the field. The arms that ran are the ones that are"
                            + " here.");
        }
        if (wanted.isNamed()) {
            List<EngineId> named = new ArrayList<EngineId>();
            for (String name : wanted.names()) {
                EngineId engine = EngineRegistry.byDisplayName(name);
                if (engine == null) {
                    return Candidates.refused(Failure.of(Failure.Kind.INVALID_PARAMETERS,
                            "This build does not know a registration engine called '" + name
                                    + "'. The engines it knows about are: "
                                    + join(EngineRegistry.displayNames()) + "."));
                }
                if (!named.contains(engine)) named.add(engine);
            }
            return new Candidates(named, availability,
                    "Ranked the engines this run named: " + join(wanted.names()) + ".");
        }
        if (wanted.kind() == EngineSelection.Kind.ALL) {
            return new Candidates(Recommender.everyEngine(), availability,
                    "Ranked every engine this plugin knows about, present or not.");
        }
        List<EngineId> present = bench.catalogue().presentEngines();
        if (!present.isEmpty()) {
            return new Candidates(present, availability,
                    "Ranked the engines already present in this Fiji.");
        }
        return new Candidates(Recommender.everyEngine(), availability,
                "No registration engine is present in this Fiji, so every engine this plugin knows"
                        + " about was ranked instead, each marked absent with what installing it"
                        + " would cost. Nothing was fetched.");
    }

    /**
     * The versions of the engines this run found, for the saved record.
     *
     * <p>The version each catalogue entry pins, reported for the engines that
     * are actually here. An engine that is absent has no version to report and
     * is left out rather than recorded as an empty string.
     */
    private static Map<String, String> versionsOf(Candidates candidates) {
        Map<String, String> versions = new LinkedHashMap<String, String>();
        for (EngineId engine : candidates.engines) {
            if (candidates.availability.presenceOf(engine) != Recommendation.Presence.PRESENT) {
                continue;
            }
            String version = EngineRegistry.specFor(engine).attribute("version", String.class);
            versions.put(EngineRegistry.displayName(engine),
                    version == null || version.trim().isEmpty() ? "present" : version.trim());
        }
        return versions;
    }

    private static String join(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) out.append(i == names.size() - 1 ? " and " : ", ");
            out.append(names.get(i));
        }
        return out.toString();
    }

    /** Which engines a run ranks, what this computer has, and why that set. */
    private static final class Candidates {

        private final List<EngineId> engines;
        private final EngineAvailability availability;
        private final String reason;
        private final Failure refusal;

        private Candidates(List<EngineId> engines, EngineAvailability availability, String reason) {
            this.engines = engines;
            this.availability = availability;
            this.reason = reason;
            this.refusal = null;
        }

        private Candidates(Failure refusal) {
            this.engines = new ArrayList<EngineId>();
            this.availability = null;
            this.reason = "";
            this.refusal = refusal;
        }

        static Candidates refused(Failure refusal) {
            return new Candidates(refusal);
        }
    }

    /**
     * The sampler this request asks for: the measured default unless the caller
     * named a shape, and every consecutive pair when the caller asked for that.
     */
    private static WindowSampler samplerFor(RegDriftParameters parameters, int frameCount) {
        Windows windows = parameters.windows();
        if (windows.isAllPairs()) return WindowSampler.everyConsecutivePair();
        int perWindow = Math.max(2, parameters.windowFrames().resolvedFor(frameCount));
        return WindowSampler.of(Math.max(1, windows.resolvedCount()), perWindow);
    }

    /**
     * One row per channel, in channel order, with the estimation channel's row
     * filled all the way.
     */
    private static ResultsTable diagnosisTable(Measured measured) {
        ChannelRanker.Ranking ranking = measured.ranking;
        Fingerprint fingerprint = measured.fingerprint;
        regdrift.diag.Verdict verdict = measured.verdict;
        ResultsTable table = RegDriftTables.diagnosis();
        ChannelRanker.ChannelQuality[] ranked = ranking.channels();
        int channels = ranked.length;
        for (int c = 1; c <= channels; c++) {
            ChannelRanker.ChannelQuality quality = null;
            for (int i = 0; i < ranked.length; i++) {
                if (ranked[i].channel() == c) quality = ranked[i];
            }
            if (c != fingerprint.channel()) {
                RegDriftTables.diagnosisRow()
                        .channel(c)
                        .localisability(quality == null
                                ? Double.NaN : quality.localisability().value())
                        .measuredAtBin(ranking.measuredAt().factor())
                        .frameCorrelation(quality == null
                                ? Double.NaN : quality.frameCorrelation())
                        .appendTo(table);
                continue;
            }
            appendMeasuredRow(table, fingerprint, verdict);
        }
        if (fingerprint.channel() > channels) {
            // A channel the ranking did not cover, which only happens if the two disagree about
            // how many there are. The measured row is the one that must not be lost.
            appendMeasuredRow(table, fingerprint, verdict);
        }
        return table;
    }

    /** The estimation channel's row: every column the fingerprint measured. */
    private static void appendMeasuredRow(ResultsTable table, Fingerprint fingerprint,
                                          regdrift.diag.Verdict verdict) {
        MotionDescriptors motion = fingerprint.motion();
        RegDriftTables.DiagnosisRow row = RegDriftTables.diagnosisRow()
                .channel(fingerprint.channel())
                .localisability(fingerprint.localisability().value())
                .measuredAtBin(fingerprint.measuredAt().factor())
                .frameCorrelation(fingerprint.frameCorrelation())
                .log2Trend(fingerprint.log2Trend())
                .brightFraction(fingerprint.brightFraction())
                .agreementPx(fingerprint.agreementPx())
                .verdict(verdict.kind());
        if (motion != null) {
            row.driftRatePx(motion.driftRatePx())
                    .bridgeMaxPx(motion.bridgeMaxPx())
                    .bridgeSpan(motion.bridgeSpan())
                    .wander(motion.wander())
                    .stepRmsPx(motion.stepRmsPx())
                    .stepMaxPx(motion.stepMaxPx())
                    .knockPresent(motion.knockPresent())
                    .motionLabel(motion.label().render())
                    .motionDominant(motion.label().dominantWord())
                    .severity(motion.severity().word());
        }
        row.appendTo(table);
    }

}
