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
                return notImplemented(parameters, "09");
            case DIAGNOSE_AND_RECOMMEND:
                return notImplemented(parameters, "10");
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
