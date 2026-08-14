/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import java.io.File;

/**
 * The public Java entry point for a folder: measure every recording in it and
 * hand back one row each.
 *
 * <pre>
 * RegDriftBatchParameters p = RegDriftBatchParameters.builder(folder)
 *         .pattern("^([A-Z]\\d+)_.*\\.tif$")
 *         .saveRoot(results)
 *         .build();
 * RegDriftBatchResult r = RegDriftBatch.run(p);
 * </pre>
 *
 * <p>The bracketed part of that pattern is the label: {@code A1_t0.tif} and
 * {@code A1_t1.tif} are two recordings, two rows, both labelled {@code A1}.
 *
 * <p>The mirror of {@link RegDrift}, and different from it in exactly one way
 * that is worth stating plainly. {@code RegDrift.run} shows nothing and writes
 * nothing, and a bytecode test holds it to that. <b>A batch writes</b>, when it
 * is given a folder to write into, because there is nothing else it could
 * sensibly do: two hundred recordings cannot each hand back a registered stack
 * and a panel and expect somebody to catch them. What it still does not do is
 * <em>show</em> anything - no window, no table, no plot, on any path.
 *
 * <p>It is a thin front on {@link RegDriftBatchRunner}: this is the name a
 * script calls and the shape it calls it in; the reading of the folder, the
 * order the recordings are worked through and the writing all live there.
 *
 * <p>Every branch that cannot produce what it was asked for hands back a
 * {@link RegDriftBatchResult} carrying a {@link Failure}. A single recording
 * that could not be measured is a row with its own typed reason and the folder
 * carries on; the whole batch failing is reserved for something that stops it
 * starting.
 */
public final class RegDriftBatch {

    private RegDriftBatch() {
    }

    /**
     * Runs the default request over a folder: every TIFF directly inside it,
     * measured and ranked, nothing written.
     *
     * @param folder the folder of time-lapse recordings
     */
    public static RegDriftBatchResult run(File folder) {
        return run(RegDriftBatchParameters.builder(folder).build());
    }

    /**
     * Runs one batch.
     *
     * @param parameters what to do, built by {@link RegDriftBatchParameters#builder}
     * @return one row per recording plus the folder's own line, or a typed reason
     *         the batch could not be started. Never null
     * @throws IllegalArgumentException when no settings bundle was given, which
     *         is a mistake in the calling code rather than something a batch can
     *         report about a folder
     */
    public static RegDriftBatchResult run(RegDriftBatchParameters parameters) {
        return RegDriftBatchRunner.run(parameters);
    }

    /**
     * What this pattern will do to this folder, before anything is opened.
     *
     * <p>The same reading of the folder the run itself uses, so what somebody is
     * shown and what is then done cannot be two different things.
     */
    public static String preview(RegDriftBatchParameters parameters) {
        return RegDriftBatchRunner.preview(parameters);
    }
}
