/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.IJ;
import ij.Macro;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import regdrift.ui.BatchDialog;
import regdrift.ui.Progress;

import java.awt.GraphicsEnvironment;
import java.io.File;

/**
 * {@code Plugins > Registration > Registration Batch...}
 *
 * <p>A folder of recordings, each opened, measured and closed again without
 * being shown. The dialog is {@link BatchDialog}, the run is
 * {@link RegDriftBatch}, and the line it records is
 * {@link RegDriftBatchMacro}'s; this class only joins them, the way
 * {@link RegDriftEntry} joins the single-recording pieces.
 *
 * <p>What a person is told at the end is one sentence: how many recordings were
 * worked through, how many could not be measured, and where the rows went. From
 * a macro that sentence goes to the Log rather than into a box, so a script is
 * never left waiting on a click.
 */
public class RegistrationBatch_ implements PlugIn {

    /** The menu wording, spelled as {@code plugins.config} spells it. */
    public static final String COMMAND = "Registration Batch...";

    @Override
    public void run(String arg) {
        String options = Macro.getOptions();
        if (!RegDriftEntry.hasText(options) && RegDriftEntry.hasText(arg)) options = arg;
        if (RegDriftEntry.hasText(options)) {
            RegDriftBatchParameters.Builder builder;
            try {
                builder = RegDriftBatchMacro.parse(options);
            } catch (IllegalArgumentException unreadable) {
                say(unreadable.getMessage(), true, true);
                return;
            }
            start(builder, true);
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            say("Running this from a macro needs the settings written out, as in run(\"" + COMMAND
                    + "\", \"folder=[C:/recordings] mode=diagnose_recommend\"). The macro recorder"
                    + " writes the whole line for you.", true, true);
            return;
        }
        BatchDialog dialog = new BatchDialog();
        if (!dialog.showModal()) return;
        RegDriftBatchParameters.Builder builder;
        try {
            builder = dialog.builder();
        } catch (IllegalArgumentException unusable) {
            say(unusable.getMessage(), false, true);
            return;
        }
        record(builder.build());
        start(builder, false);
    }

    /** Writes the replayable line, when the recorder is open and the settings can be written. */
    private static void record(RegDriftBatchParameters parameters) {
        if (!Recorder.record) return;
        String line;
        try {
            line = RegDriftBatchMacro.toMacroOptions(parameters);
        } catch (IllegalArgumentException unwritable) {
            IJ.showStatus(RegDriftEntry.DISPLAY_NAME + ": this folder run could not be written as a"
                    + " macro line: " + unwritable.getMessage());
            return;
        }
        Recorder.recordString("run(\"" + COMMAND + "\", \""
                + RegDriftBatchMacro.inMacroString(line) + "\");\n");
        Recorder.setCommand(null);
    }

    private static void start(RegDriftBatchParameters.Builder builder, boolean fromMacro) {
        Progress progress = Progress.forRun(1);
        RegDriftBatchParameters parameters;
        try {
            parameters = builder.cancellation(progress.cancellation()).build();
        } catch (IllegalArgumentException unusable) {
            say(unusable.getMessage(), fromMacro, true);
            return;
        }
        progress.step("Working through the folder");
        Runnable endWatch = progress.watchEscape();
        RegDriftBatchResult result;
        try {
            result = RegDriftBatch.run(parameters);
        } finally {
            endWatch.run();
        }
        if (!result.isSuccess()) {
            progress.failed(RegDriftEntry.DISPLAY_NAME, result.failure().message());
            say(result.failure().message(), fromMacro, true);
            return;
        }
        String summary = summaryOf(result);
        progress.finish(result.stopped() ? "Stopped." : "Done.");
        IJ.showStatus(RegDriftEntry.DISPLAY_NAME + ": " + summary);
        say(summary, fromMacro, false);
    }

    /** One sentence: how many, how many could not be measured, and where the rows went. */
    static String summaryOf(RegDriftBatchResult result) {
        int rows = result.rows().size();
        int notStarted = 0;
        for (RegDriftBatchResult.MovieRow row : result.rows()) {
            if (!row.isSuccess() && row.failure() != null
                    && row.failure().kind() == Failure.Kind.CANCELED) {
                notStarted++;
            }
        }
        int failed = result.failedCount() - notStarted;
        int reached = rows - notStarted;
        StringBuilder said = new StringBuilder();
        if (result.stopped()) {
            said.append("Stopped when Esc was pressed, after ").append(reached)
                    .append(reached == 1 ? " recording" : " recordings").append(" of ").append(rows)
                    .append("; the other ").append(notStarted)
                    .append(notStarted == 1 ? " was" : " were").append(" not measured.");
        } else {
            said.append("Worked through ").append(rows).append(rows == 1 ? " recording" : " recordings")
                    .append('.');
        }
        if (failed > 0) {
            said.append(' ').append(failed).append(" could not be measured; each row says why.");
        }
        File saved = result.savedUnder();
        if (saved != null) {
            said.append(" Results written to ").append(saved.getAbsolutePath()).append('.');
        } else {
            said.append(" Nothing was written: no auto-save folder was given.");
        }
        return said.toString();
    }

    /**
     * Tells a person or a macro. A person gets a box; a macro gets the Log, so it
     * is never left waiting on a click.
     */
    private static void say(String text, boolean fromMacro, boolean problem) {
        if (fromMacro || GraphicsEnvironment.isHeadless()) {
            IJ.log(RegDriftEntry.DISPLAY_NAME + ": " + text);
        } else if (problem) {
            IJ.error(RegDriftEntry.DISPLAY_NAME, text);
        } else {
            IJ.showMessage(RegDriftEntry.DISPLAY_NAME, text);
        }
    }
}
