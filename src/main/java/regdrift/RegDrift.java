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
import regdrift.diag.Fingerprint;
import regdrift.diag.Frames;
import regdrift.diag.MotionDescriptors;
import regdrift.diag.WindowSampler;
import regdrift.internal.PairScheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * {@link RegDriftResult} carrying a {@link Failure}. Nothing here returns null,
 * and the modes this build does not carry out yet say so in a form a caller can
 * branch on rather than throwing. That is what lets stage 04 build a dialog and
 * click it before an estimator exists.
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

    /** How the message of a {@link Failure.Kind#NOT_IMPLEMENTED} failure opens. */
    public static final String NOT_IMPLEMENTED_PREFIX = "not_implemented: stage ";

    /** Fewer frames than this and there is no movement to measure. */
    public static final int MIN_FRAMES = 2;

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
                return notImplemented(parameters, "13");
            case COMPARE:
                return notImplemented(parameters, "13");
            case SCORE:
                return notImplemented(parameters, "12");
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
        ImagePlus image = parameters.image();
        Frames.Bin bin = Fingerprint.binFor(image.getWidth(), image.getHeight());
        Cancellation cancellation = parameters.cancellation();
        int workers = parameters.serial() ? 1 : 0;
        Frames frames = null;
        try {
            ChannelRanker.Ranking ranking = ChannelRanker.rank(image, bin, 0, workers,
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
                frames = Frames.of(image, channel, slice, bin);
            } catch (IllegalArgumentException outsideTheImage) {
                return RegDriftResult.failed(parameters, Failure.of(
                        Failure.Kind.INVALID_PARAMETERS, "'" + image.getTitle()
                                + "' cannot be measured as asked: " + outsideTheImage.getMessage()
                                + "."));
            }

            WindowSampler.Plan plan = samplerFor(parameters, frames.count())
                    .plan(frames.count());
            Fingerprint fingerprint = Fingerprint.measure(frames, plan, workers,
                    PairScheduler.Progress.NONE, cancellation);
            regdrift.diag.Verdict verdict = regdrift.diag.Verdict.of(fingerprint);

            Provenance.Builder record = Provenance.builder()
                    .pluginVersion(VERSION)
                    .mode(parameters.mode())
                    .channel(channel)
                    .channelReason(channelReason)
                    .measuredAtBin(bin.factor())
                    .windowStarts(plan.everyConsecutivePair()
                            ? new int[0] : plan.windowStarts())
                    .windowFrames(plan.framesPerWindow());
            RegDriftResult.Builder result = RegDriftResult.builder(parameters)
                    .diagnosis(diagnosisTable(ranking, fingerprint, verdict))
                    .verdict(verdict.kind(), verdict.text());

            if (parameters.mode() == Mode.DIAGNOSE_AND_RECOMMEND) {
                Candidates candidates = candidatesFor(parameters);
                if (candidates.refusal != null) {
                    return RegDriftResult.failed(parameters, candidates.refusal);
                }
                Recommender.Result advice = Recommender.rank(fingerprint, verdict,
                        candidates.engines, candidates.availability);
                ResultsTable table = RegDriftTables.recommendation();
                for (Recommendation ranked : advice.ranked()) {
                    RegDriftTables.append(table, ranked);
                }
                result.recommendation(table).ranked(advice.ranked());
                record.calibrationSet(advice.calibrationSet() + " " + advice.calibrationReason()
                                + " " + candidates.reason)
                        .engineVersions(versionsOf(candidates));
            }
            return result.provenance(record.build()).build();
        } catch (CancellationException stoppedPartWay) {
            return RegDriftResult.failed(parameters, stopped());
        } finally {
            if (frames != null) frames.release();
        }
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
        EngineAvailability availability = EngineAvailability.of(AutofixService.forThisFiji());
        EngineSelection wanted = parameters.engines();
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
        List<EngineId> present = AutofixService.forThisFiji().presentEngines();
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
    private static ResultsTable diagnosisTable(ChannelRanker.Ranking ranking,
                                               Fingerprint fingerprint,
                                               regdrift.diag.Verdict verdict) {
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

    /**
     * The result a mode this build does not carry out yet hands back.
     *
     * @param stage the two-digit build stage that fills this branch in
     */
    private static RegDriftResult notImplemented(RegDriftParameters parameters, String stage) {
        return RegDriftResult.failed(parameters, Failure.of(Failure.Kind.NOT_IMPLEMENTED,
                NOT_IMPLEMENTED_PREFIX + stage + ". Mode '" + parameters.mode().macroValue()
                        + "' is not carried out by this build of Registration and Drift"
                        + " Comparison (" + VERSION + "); it arrives in build stage " + stage
                        + "."));
    }
}
