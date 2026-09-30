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
import ij.ImagePlus;
import ij.Macro;
import ij.WindowManager;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import regdrift.ui.CompareDialog;
import regdrift.ui.ImageChoices;
import regdrift.ui.Plots;
import regdrift.ui.Progress;
import regdrift.ui.RegDriftDialog;
import regdrift.ui.ResultsPanel;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

/**
 * What both menu entries do: work out whether a macro is driving or a person is,
 * gather the settings either way, run once, and hand back what came out.
 *
 * <p>The two entries differ in three things - the menu wording they record, the
 * modes they run, and the dialog they open. Everything else is here, so that the
 * line one entry records is a line the other can read, which is the whole point:
 * somebody will copy a line out of the recorder and run it under the other menu
 * item, and it has to mean the same thing there.
 *
 * <h2>The order matters</h2>
 *
 * <p><b>The macro line is recorded before the run starts, not after it
 * finishes.</b> A run that gives up is exactly the run whose settings somebody
 * needs to look at, and recording afterwards loses them at the moment they
 * became interesting.
 *
 * <h2>Where the windows are</h2>
 *
 * <p>{@link RegDrift#run} opens nothing, writes nothing and installs nothing,
 * and a bytecode test holds it to that. This class is the other side of that
 * line: it is where a table is shown, where a file is written, and where an
 * error reaches a person. Cancelling the dialog leaves through none of them - no
 * message, no log line, no table, and no settings bundle built.
 *
 * <h2>The seams</h2>
 *
 * <p>Every touch of the outside world - the open windows, the recorder, the
 * status bar, the dialog, the tables - is a {@code protected} method rather than
 * an inline call. That is what lets the routing be tested: a subclass in the
 * test source replaces the seams with counters and returns a dialog's settings
 * without a window ever being made, so what this class decides can be checked on
 * a machine with no display.
 */
public abstract class RegDriftEntry implements PlugIn {

    /** How this plugin names itself on screen. The single place the ampersand appears. */
    public static final String DISPLAY_NAME = "Registration & Drift Comparison";

    /** What a macro is told when it names the plugin and says nothing else. */
    public static final String NO_OPTIONS_MESSAGE =
            "Running this from a macro needs the settings written out, as in"
                    + " run(\"<entry>\", \"mode=diagnose_recommend\"). The macro recorder writes a"
                    + " line like that for you: open Plugins > Macros > Record..., run this once"
                    + " from the menu, and copy the line it produces.";

    /** What somebody is told when there is nothing open to measure. */
    public static final String NO_RECORDING_MESSAGE =
            "There is no recording open to measure. Open a time-lapse stack and start this again.";

    /** How many named steps a run reports through the status bar. */
    private static final int STEPS = 3;

    /**
     * Routes one press of the menu item, or one macro call.
     *
     * @param arg what the menu entry was registered with, which ImageJ also uses
     *            to pass options to a plugin called through {@code IJ.runPlugIn}
     */
    @Override
    public final void run(String arg) {
        String macroOptions = Macro.getOptions();
        if (!hasText(macroOptions) && hasText(arg)) {
            macroOptions = arg;
        }
        if (hasText(macroOptions)) {
            fromMacro(macroOptions);
            return;
        }
        if (!canAsk()) {
            reportFailure(NO_OPTIONS_MESSAGE, false);
            return;
        }
        fromDialog();
    }

    /**
     * Whether there is anywhere to put a dialog.
     *
     * <p>On a machine with no display there is nobody to ask, so a call that
     * brought no settings with it is told what settings would have looked like
     * rather than being left waiting on a window that cannot appear.
     */
    protected boolean canAsk() {
        return !GraphicsEnvironment.isHeadless();
    }

    // --------------------------------------------------------- what differs

    /** The menu wording this entry records, spelled as the menu spells it. */
    public abstract String command();

    /** The other menu item, for when a line asks for something this entry leaves alone. */
    public abstract String otherCommand();

    /** The modes this entry runs. */
    public abstract List<Mode> modesRunHere();

    /** The dialog this entry opens. */
    protected abstract RegDriftDialog newDialog(ImageChoices choices);

    // -------------------------------------------------------------- routing

    /** A macro is driving: no dialog, whatever the settings say. */
    private void fromMacro(String optionsText) {
        RegDriftMacroOptions options;
        try {
            options = RegDriftMacroOptionsParser.parse(optionsText);
        } catch (IllegalArgumentException unreadable) {
            reportFailure(unreadable.getMessage(), false);
            return;
        }
        boolean quietly = options.isHideDisplay();
        String elsewhere = modeBelongsElsewhere(options.getMode());
        if (elsewhere != null) {
            reportFailure(elsewhere, quietly);
            return;
        }
        ImagePlus image = activeImage();
        if (image == null) {
            reportFailure(NO_RECORDING_MESSAGE, quietly);
            return;
        }
        ImagePlus second = null;
        if (!options.getCompareWith().isEmpty()) {
            second = imageNamed(options.getCompareWith());
            if (second == null) {
                reportFailure("No open window is called '" + options.getCompareWith() + "', so"
                        + " there is nothing to score against. Open that stack first, or correct"
                        + " the '" + RegDriftMacroOptions.COMPARE_WITH + "' setting.", quietly);
                return;
            }
        }
        start(options, image, second);
    }

    /** A person is driving: ask, record what they asked for, then run it. */
    private void fromDialog() {
        ImageChoices choices = openImages();
        if (choices.isEmpty()) {
            reportFailure(NO_RECORDING_MESSAGE, false);
            return;
        }
        RegDriftDialog dialog = newDialog(choices);
        if (!askUser(dialog)) {
            return;
        }
        String title = dialog.imageTitle();
        RegDriftMacroOptions options = dialog.options();
        ImagePlus image = imageNamed(title);
        if (image == null) {
            reportFailure("The recording '" + title + "' is no longer open.", false);
            return;
        }
        ImagePlus second = null;
        if (!options.getCompareWith().isEmpty()) {
            second = imageNamed(options.getCompareWith());
            if (second == null) {
                reportFailure("The stack '" + options.getCompareWith() + "' is no longer open.",
                        false);
                return;
            }
        }
        recordCall(title, options);
        start(options, image, second);
    }

    /**
     * Turns settings into a run and reports what came back.
     *
     * <p>The switch that stops the run comes from the progress reporter, so Esc
     * reaches the measurement the same way a Cancel button would.
     */
    private void start(RegDriftMacroOptions options, ImagePlus image, ImagePlus second) {
        Progress progress = newProgress();
        RegDriftParameters parameters;
        try {
            parameters = options.toParameters(image, second, progress.cancellation(),
                    dispatchFor(options));
        } catch (IllegalArgumentException unusable) {
            reportFailure(unusable.getMessage(), options.isHideDisplay());
            return;
        }
        progress.step("Measuring the movement");
        Runnable endWatch = progress.watchEscape();
        RegDriftResult result;
        try {
            result = RegDrift.run(parameters);
        } finally {
            endWatch.run();
        }
        if (result.isSuccess()) {
            progress.finish("Done.");
        } else {
            progress.failed(DISPLAY_NAME, result.failure().message());
        }
        finish(result);
    }

    /**
     * Writes the line a macro recorder would replay.
     *
     * <p>The chosen recording is named with its own {@code selectWindow} line
     * when it is not the one already in front, because which window a run
     * measures is not one of the settings - a macro says it by selecting the
     * window, the same way a person does by clicking on it.
     */
    private void recordCall(String title, RegDriftMacroOptions options) {
        String line;
        try {
            line = options.toMacroOptions();
        } catch (IllegalArgumentException unwritable) {
            showStatus(DISPLAY_NAME + ": these settings could not be written as a macro line: "
                    + unwritable.getMessage());
            return;
        }
        record(title, line);
    }

    /** Why a mode is not run here, or null when it is. */
    public String modeBelongsElsewhere(Mode mode) {
        if (modesRunHere().contains(mode)) return null;
        StringBuilder here = new StringBuilder();
        for (Mode runs : modesRunHere()) {
            if (here.length() > 0) here.append(", ");
            here.append(runs.macroValue());
        }
        return "The menu item '" + command() + "' runs mode " + here + ". This line asked for"
                + " mode=" + mode.macroValue() + ", which '" + otherCommand() + "' in the same menu"
                + " runs. Either run this line there, or set mode to one of: " + here + ".";
    }

    // -------------------------------------------------- what a run ends with

    /**
     * What happens once a run has come back.
     *
     * <p>A run that gave up says so and stops there: nothing is saved and
     * nothing is shown, because there is nothing to save or show and a table
     * with no rows in it reads as a measurement of zero.
     *
     * <p>A run the person stopped themselves - Cancel at the cost box, or Esc -
     * is not an error, so it ends with a line in the status bar rather than an
     * error box they would then have to close. A run with no windows still
     * writes it to the Log, where its macro's author will look.
     */
    protected void finish(RegDriftResult result) {
        RegDriftParameters parameters = result.parameters();
        if (!result.isSuccess()) {
            Failure failure = result.failure();
            if (failure.kind() == Failure.Kind.CANCELED && !parameters.hideDisplay() && canAsk()) {
                showStatus(DISPLAY_NAME + ": " + failure.message());
                return;
            }
            reportFailure(failure.message(), parameters.hideDisplay());
            return;
        }
        if (parameters.hasSaveRoot()) {
            save(result);
        }
        if (!parameters.hideDisplay()) {
            display(result);
        }
    }

    /** Writes the results under the folder the settings named. */
    protected void save(RegDriftResult result) {
        RegDriftAutoSave.Report report = RegDriftAutoSave.save(result);
        if (report.isSuccess()) {
            showStatus(DISPLAY_NAME + ": results written to "
                    + report.tree().getAbsolutePath());
        } else {
            reportFailure(report.failure().message(), result.parameters().hideDisplay());
        }
    }

    /**
     * Who answers before a comparison drives anything, and what a run that asks
     * nobody assumes.
     *
     * <p>A person driving from the menu or from a macro they are watching gets
     * the box; a run that asked for no windows gets an answer of yes, because
     * there is nobody at the keyboard and a script that asked for a comparison
     * has already said it. {@code hide_display} therefore suppresses this box the
     * same way it suppresses every other one.
     */
    protected Dispatch dispatchFor(RegDriftMacroOptions options) {
        if (options.isHideDisplay() || GraphicsEnvironment.isHeadless()) {
            return Dispatch.always();
        }
        return CompareDialog.askBeforeDispatch();
    }

    /**
     * Puts the tables, the plots, the registered stack and the results view on
     * the screen.
     *
     * <p>The single method in this plugin that shows a result, which is why it is
     * here rather than in the facade: {@link RegDrift#run} hands back objects and
     * a bytecode test holds it to that. A table with no rows is left alone rather
     * than opened empty.
     *
     * <p>The results view goes up last so that it is the window in front. The
     * before-and-after panel is built but not shown - it is a picture of numbers
     * the tables already carry, it goes into the saved tree, and a second image
     * window per run is a nuisance rather than a result.
     */
    protected void display(RegDriftResult result) {
        if (GraphicsEnvironment.isHeadless()) return;
        showTable(result.diagnosis(), "Diagnosis");
        showTable(result.recommendation(), "Recommendation");
        showTable(result.comparison(), "Comparison");
        showTable(result.frames(), "Frames");
        ImagePlus registered = result.registered();
        if (registered != null) registered.show();
        Plots.showDefaults(result.traces());
        ResultsPanel.show(result);
    }

    private static void showTable(ResultsTable table, String title) {
        if (table == null || table.size() == 0) return;
        table.show(title);
    }

    // ---------------------------------------------------------- the outside

    /** Every recording currently open, and which one is in front. */
    protected ImageChoices openImages() {
        int[] ids = WindowManager.getIDList();
        List<ImageChoices.Entry> entries = new ArrayList<ImageChoices.Entry>();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus image = WindowManager.getImage(id);
                if (image != null) entries.add(describe(image));
            }
        }
        ImagePlus active = WindowManager.getCurrentImage();
        return ImageChoices.of(entries, active == null ? "" : active.getTitle());
    }

    /**
     * One open recording, described the way the facade reads it.
     *
     * <p>A plain stack that declares one frame and several slices is a time
     * series, which is what an unlabelled TIFF from a microscope usually is, so
     * it is described as frames rather than as Z slices. Describing it any other
     * way would have the dialog offer a Z choice for an axis that is really
     * time.
     */
    private static ImageChoices.Entry describe(ImagePlus image) {
        int frames = RegDrift.frameCount(image);
        int slices = frames == image.getNSlices() && image.getNFrames() <= 1
                ? 1 : image.getNSlices();
        return new ImageChoices.Entry(image.getTitle(), image.getNChannels(), slices, frames);
    }

    /** The recording in front, or null when nothing is open. */
    protected ImagePlus activeImage() {
        return WindowManager.getCurrentImage();
    }

    /** The open recording with this window title, or null when there is none. */
    protected ImagePlus imageNamed(String title) {
        return WindowManager.getImage(title);
    }

    /** Puts the dialog on the screen. False when it was cancelled or closed. */
    protected boolean askUser(RegDriftDialog dialog) {
        return dialog.showModal();
    }

    /** The reporter a run drives while it works. */
    protected Progress newProgress() {
        return Progress.forRun(STEPS);
    }

    /**
     * Writes the replayable lines into the macro recorder, when one is open.
     *
     * <p>The last line matters as much as the two above it. ImageJ writes its
     * own {@code run("<the menu item>");} when a menu command finishes without
     * having built a {@code GenericDialog}, which this plugin never does. Left
     * alone, the recorder would show the line written here <em>and</em> a second
     * one carrying no settings at all - and replaying that second line would
     * open a dialog in the middle of somebody's macro. Setting the command back
     * to nothing is how a plugin says it has already written its own line.
     *
     * <p>{@code Recorder.setCommand(null)} rather than the later
     * {@code disableCommandRecording()}, which does the same thing but arrived
     * in ImageJ 1.54f. This plugin should keep working on the older ImageJ
     * inside somebody's unattended Fiji.
     */
    protected void record(String selectTitle, String optionsLine) {
        if (!Recorder.record) return;
        ImagePlus active = activeImage();
        String front = active == null ? "" : active.getTitle();
        String select = selectWindowLine(selectTitle, front);
        if (select != null) Recorder.recordString(select);
        Recorder.recordString(runLine(optionsLine));
        Recorder.setCommand(null);
    }

    /**
     * The line a macro needs in order to be measuring the same recording, or
     * null when the chosen one is already the window in front.
     *
     * <p>Which recording a run measures is not one of the settings and never
     * will be: a macro says it by selecting a window, exactly as a person says
     * it by clicking on one.
     */
    public static String selectWindowLine(String chosenTitle, String frontTitle) {
        if (!hasText(chosenTitle) || chosenTitle.equals(frontTitle)) return null;
        return "selectWindow(\"" + chosenTitle + "\");\n";
    }

    /** The line that replays a run of this menu entry with these settings. */
    public String runLine(String optionsLine) {
        return "run(\"" + command() + "\", \"" + optionsLine + "\");\n";
    }

    /**
     * Says why a run could not do what it was asked.
     *
     * @param message  the finished sentence, which came from a typed reason
     * @param quietly  true when the settings asked for no windows, in which case
     *                 it goes to the Log rather than into a dialog. Said once
     *                 either way
     */
    protected void reportFailure(String message, boolean quietly) {
        String text = hasText(message) ? message : "This run could not finish.";
        if (quietly || GraphicsEnvironment.isHeadless()) {
            IJ.log(DISPLAY_NAME + ": " + text);
        } else {
            IJ.error(DISPLAY_NAME, text);
        }
    }

    /** A line in the status bar. Elapsed news, never a measurement. */
    protected void showStatus(String text) {
        IJ.showStatus(text);
    }

    static boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
