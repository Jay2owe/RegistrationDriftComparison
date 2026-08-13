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
import ij.ImageStack;
import ij.Macro;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import regdrift.autofix.EngineId;
import regdrift.ui.CompareDialog;
import regdrift.ui.DiagnosticsDialog;
import regdrift.ui.ImageChoices;
import regdrift.ui.Progress;
import regdrift.ui.RegDriftDialog;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Option string in, settings out, and no window anywhere.
 *
 * <p>What the two menu entries decide - macro or person, which modes belong
 * here, what gets recorded and when, what happens when somebody presses Cancel -
 * is checked here without a dialog ever being put on the screen. Every touch of
 * the outside world in {@link RegDriftEntry} is a method a subclass can replace,
 * and the probe below replaces each of them with a list, so a run leaves a
 * written record of what it would have shown, written and recorded.
 *
 * <h2>What this test cannot do, and who does it</h2>
 *
 * <p>It does not press a button, and it does not prove that the dialog appears
 * where a person can see it. The controls, their defaults and what they read
 * back as are checked by {@code DialogsTest} on the real dialog objects; that
 * the window itself opens, that Cancel closes it and that the ImageJ recorder
 * writes what is asserted here into its own window is the hands-on pass, and it
 * is the last stage of the build that does it.
 *
 * <h2>Why a thread called Run$_</h2>
 *
 * <p>{@code Macro.getOptions()} hands back a macro's settings when the thread
 * asking is the one the macro is running on, and it recognises that thread by
 * its name beginning {@code Run$_}. Running the macro tests on such a thread is
 * therefore not a trick to get past a check: it is what makes them the same code
 * path a real macro takes, rather than a call to the parser dressed up as one.
 */
public class EntryRoutingTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    // ------------------------------------------------------- the macro route

    @Test
    public void aMacroLineBecomesSettingsAndNoDialogIsBuilt() throws Exception {
        Probe probe = probeFor(new CompareRegistration_());
        probe.open("movie.tif", stack(2, 1, 6));

        asMacro("mode=diagnose channel=2 windows=0 hide_display", probe, "");

        assertEquals("a macro must never be asked a question", 0, probe.dialogsAsked);
        assertEquals("the run should have reached the facade once", 1, probe.finished.size());
        RegDriftParameters used = probe.finished.get(0).parameters();
        assertEquals(Mode.DIAGNOSE, used.mode());
        assertEquals(2, used.channel().index());
        assertTrue("windows=0 means every consecutive pair", used.windows().isAllPairs());
        assertTrue(used.hideDisplay());
        assertEquals("movie.tif", used.image().getTitle());
    }

    @Test
    public void theOptionsMayArriveAsThePluginArgumentInstead() throws Exception {
        Probe probe = probeFor(new CompareRegistration_());
        probe.open("movie.tif", stack(1, 1, 6));

        probe.run("mode=diagnose serial=true");

        assertEquals(0, probe.dialogsAsked);
        assertEquals(1, probe.finished.size());
        assertTrue(probe.finished.get(0).parameters().serial());
    }

    /**
     * The gate item that can be run without a person: a diagnose line with
     * {@code hide_display} measures the recording, opens nothing and says
     * nothing.
     *
     * <p>Stage 09 filled the diagnose branch in, so this run now comes back with
     * a measurement rather than with a not-implemented reason. The recording is
     * an 8x8 stack of zeros, which neither estimator can read, so the verdict is
     * the warning - and a warning is still a finished run: nothing refuses, and
     * nothing is reported to the user as an error. Defect D7.
     */
    @Test
    public void aHiddenMacroRunMeasuresTheRecordingAndOpensNothing() throws Exception {
        Probe probe = probeFor(new RegistrationDiagnostics_());
        probe.open("movie.tif", stack(1, 1, 8));

        asMacro("mode=diagnose hide_display", probe, "");

        assertEquals("nothing may be shown when the settings asked for no windows",
                0, probe.loud.size());
        assertEquals("a run that measured something reports no failure at all",
                0, probe.quiet.size());
        assertEquals(0, probe.shown.size());
        assertEquals(0, probe.recorded.size());

        RegDriftResult result = probe.finished.get(0);
        assertTrue(result.failure() == null ? "" : result.failure().message(),
                result.isSuccess());
        assertEquals(Verdict.WARN_LOW_STRUCTURE, result.verdict());
        assertNotNull("and the diagnosis table is filled in", result.diagnosis());
    }

    /**
     * The recommending mode measures, ranks and opens nothing, on a Fiji with no
     * registration engine installed.
     *
     * <p>Gate item 6 of stage 10, run without a person: no engine is present in
     * a test JVM, so every engine comes back absent with what installing it
     * would cost, and <b>nothing is fetched to produce that</b>. The recording is
     * an 8x8 stack of zeros, so the verdict is the warning - and a warning still
     * gets a ranking, because whether the movement could be read and which
     * engines the table supports are two different questions.
     */
    @Test
    public void aHiddenMacroRunRanksEnginesAndInstallsNothing() throws Exception {
        Probe probe = probeFor(new RegistrationDiagnostics_());
        probe.open("movie.tif", stack(1, 1, 8));

        asMacro("mode=diagnose_recommend hide_display", probe, "");

        assertEquals("nothing may be shown when the settings asked for no windows",
                0, probe.loud.size());
        assertEquals("a run that measured something reports no failure at all",
                0, probe.quiet.size());
        assertEquals(0, probe.shown.size());
        assertEquals(0, probe.recorded.size());

        RegDriftResult result = probe.finished.get(0);
        assertTrue(result.failure() == null ? "" : result.failure().message(),
                result.isSuccess());
        assertEquals(Verdict.WARN_LOW_STRUCTURE, result.verdict());
        assertFalse("and it ranked, which is what this mode adds", result.ranked().isEmpty());
        assertNotNull("the ranking reaches the table too", result.recommendation());
        assertEquals("no engine is present here, so every engine the catalogue knows about is"
                        + " ranked rather than none of them",
                EngineId.values().length, result.ranked().size());
        for (Recommendation row : result.ranked()) {
            assertNotEquals("nothing can be present in a JVM with no Fiji around it, and nothing"
                            + " was fetched to make one present - house rule 9",
                    Recommendation.Presence.PRESENT, row.presence());
        }
        assertEquals("rank 1 is rank 1, counted from one", 1, result.ranked().get(0).rank());
    }

    /**
     * A mode this build does not carry out yet still says so exactly once, with
     * no window, when a macro asked for no windows.
     *
     * <p>The half of the gate item above that outlived the two measuring
     * branches being filled in: the rule is that a reason is given once, not
     * twice, and it needs a mode that still has a reason to give. Stage 09 moved
     * this off {@code diagnose} and stage 10 moved it off
     * {@code diagnose_recommend}; {@code apply} is where it sits until stage 13.
     */
    @Test
    public void aHiddenMacroRunOnAnUnbuiltModeSaysItsOneThingOnce() throws Exception {
        Probe probe = probeFor(new CompareRegistration_());
        probe.open("movie.tif", stack(1, 1, 8));

        asMacro("mode=apply hide_display", probe, "");

        assertEquals("nothing may be shown when the settings asked for no windows",
                0, probe.loud.size());
        assertEquals("and the reason is given once, not twice", 1, probe.quiet.size());
        assertTrue(probe.quiet.get(0), probe.quiet.get(0).contains("not carried out by this build"));
        assertEquals(0, probe.shown.size());
        assertEquals(0, probe.recorded.size());

        RegDriftResult result = probe.finished.get(0);
        assertEquals(Failure.Kind.NOT_IMPLEMENTED, result.failure().kind());
    }

    @Test
    public void anUnreadableMacroLineNamesWhatItCouldNotRead() throws Exception {
        Probe probe = probeFor(new CompareRegistration_());
        probe.open("movie.tif", stack(1, 1, 6));

        asMacro("mode=diagnose nonsense=7", probe, "");

        assertEquals(0, probe.finished.size());
        assertEquals(1, probe.loud.size());
        assertTrue(probe.loud.get(0), probe.loud.get(0).contains("nonsense"));
    }

    @Test
    public void aMacroWithNothingOpenSaysSoRatherThanFailingQuietly() throws Exception {
        Probe probe = probeFor(new CompareRegistration_());

        asMacro("mode=diagnose", probe, "");

        assertEquals(0, probe.finished.size());
        assertEquals(1, probe.loud.size());
        assertEquals(RegDriftEntry.NO_RECORDING_MESSAGE, probe.loud.get(0));
    }

    // ------------------------------------------------------ the dialog route

    @Test
    public void theRecordedLineIsWrittenBeforeTheRunStarts() {
        Probe probe = probeFor(new CompareRegistration_());
        probe.open("movie.tif", stack(1, 1, 6));
        probe.dialog = dialogShowing(probe, defaults());

        probe.run("");

        assertEquals(1, probe.recorded.size());
        assertEquals("the settings must already be recorded when the run comes back",
                1, probe.recordedWhenFinished);
        assertEquals(1, probe.finished.size());
    }

    @Test
    public void cancelingTheDialogLeavesNothingBehindAtAll() {
        Probe probe = probeFor(new CompareRegistration_());
        probe.open("movie.tif", stack(1, 1, 6));
        probe.dialog = dialogShowing(probe, defaults());
        probe.accept = false;

        probe.run("");

        assertEquals("no message", 0, probe.loud.size() + probe.quiet.size());
        assertEquals("no status line", 0, probe.status.size());
        assertEquals("no recorded line", 0, probe.recorded.size());
        assertEquals("no run", 0, probe.finished.size());
        assertEquals("no table", 0, probe.shown.size());
    }

    /**
     * Three settings that differ from each other and from the defaults, each
     * read out of a real dialog, written as a macro line and read back. One of
     * them is {@code windows=0}, the explicit "measure the whole recording"
     * choice, because a value that means something other than what its number
     * says is the one a round trip is most likely to lose.
     */
    @Test
    public void settingsSurviveBeingRecordedAndReadBackAgain() {
        List<RegDriftMacroOptions> combinations = Arrays.asList(
                defaults(),
                measureEveryPair(),
                scoreASecondStack());

        for (RegDriftMacroOptions wanted : combinations) {
            Probe probe = probeFor(new CompareRegistration_());
            probe.open("movie.tif", stack(3, 4, 6));
            probe.open("registered.tif", stack(3, 4, 6));
            probe.dialog = dialogShowing(probe, wanted);

            probe.run("");

            assertEquals("one line per run: " + wanted, 1, probe.recorded.size());
            String line = probe.recorded.get(0).line;
            assertEquals("the recorded line has to read back as what was asked for",
                    wanted, RegDriftMacroOptionsParser.parse(line));
        }
    }

    @Test
    public void aRecordedLineNamesTheWindowWhenItIsNotTheOneInFront() {
        Probe probe = probeFor(new CompareRegistration_());
        probe.open("front.tif", stack(1, 1, 6));
        probe.open("behind.tif", stack(1, 1, 6));
        probe.activeTitle = "front.tif";
        CompareDialog dialog = new CompareDialog(probe.openImages());
        dialog.applyOptions(defaults());
        probe.dialog = new ChosenRecording(dialog, "behind.tif");

        probe.run("");

        assertEquals(1, probe.recorded.size());
        assertEquals("the run is recorded against the recording somebody chose",
                "behind.tif", probe.recorded.get(0).selectTitle);
    }

    /**
     * The two lines a run leaves in the recorder, checked as text, since text is
     * what somebody pastes back into a macro window.
     */
    @Test
    public void theRecordedTextIsAMacroSomebodyCouldRun() {
        assertEquals("selectWindow(\"behind.tif\");\n",
                RegDriftEntry.selectWindowLine("behind.tif", "front.tif"));
        assertEquals("a recording already in front needs no line of its own",
                null, RegDriftEntry.selectWindowLine("front.tif", "front.tif"));

        assertEquals("run(\"" + CompareRegistration_.COMMAND + "\", \"mode=diagnose\");\n",
                new CompareRegistration_().runLine("mode=diagnose"));
        assertEquals("run(\"" + RegistrationDiagnostics_.COMMAND + "\", \"mode=diagnose\");\n",
                new RegistrationDiagnostics_().runLine("mode=diagnose"));
    }

    // ---------------------------------------------------- which entry is which

    @Test
    public void theDiagnosticsEntryTurnsAwayAModeItDoesNotRun() throws Exception {
        Probe probe = probeFor(new RegistrationDiagnostics_());
        probe.open("movie.tif", stack(1, 1, 6));

        asMacro("mode=apply", probe, "");

        assertEquals("nothing may be run instead of what was asked for", 0, probe.finished.size());
        assertEquals(1, probe.loud.size());
        String said = probe.loud.get(0);
        assertTrue(said, said.contains("apply"));
        assertTrue("it has to name the menu item that does run it: " + said,
                said.contains(CompareRegistration_.COMMAND));
    }

    @Test
    public void eachEntryReadsALineTheOtherRecorded() throws Exception {
        RegDriftMacroOptions measuring = defaults();
        measuring.setMode(Mode.DIAGNOSE);
        String line = measuring.toMacroOptions();

        for (RegDriftEntry entry : Arrays.asList(
                new CompareRegistration_(), new RegistrationDiagnostics_())) {
            Probe probe = probeFor(entry);
            probe.open("movie.tif", stack(1, 1, 6));

            asMacro(line, probe, "");

            assertEquals(entry.command() + " should have run the line", 1, probe.finished.size());
            assertEquals(Mode.DIAGNOSE, probe.finished.get(0).parameters().mode());
        }
    }

    @Test
    public void theTwoEntriesCarryTheMenuWordingTheirOwnMenuFileUses() throws Exception {
        String config = pluginsConfig();
        assertTrue(config, config.contains("\"" + CompareRegistration_.COMMAND + "\""));
        assertTrue(config, config.contains("\"" + RegistrationDiagnostics_.COMMAND + "\""));
        assertTrue(config, config.contains("regdrift.CompareRegistration_"));
        assertTrue(config, config.contains("regdrift.RegistrationDiagnostics_"));

        assertEquals(RegistrationDiagnostics_.COMMAND, new CompareRegistration_().otherCommand());
        assertEquals(CompareRegistration_.COMMAND, new RegistrationDiagnostics_().otherCommand());
    }

    @Test
    public void eachEntryRunsTheModesItSaysItDoes() {
        assertEquals(Arrays.asList(Mode.values()),
                new CompareRegistration_().modesRunHere());
        assertEquals(Arrays.asList(Mode.DIAGNOSE, Mode.DIAGNOSE_AND_RECOMMEND),
                new RegistrationDiagnostics_().modesRunHere());
    }

    @Test
    public void eachEntryOpensItsOwnDialog() {
        ImageChoices choices = ImageChoices.of(
                Arrays.asList(new ImageChoices.Entry("movie.tif", 1, 1, 6)), "movie.tif");

        assertTrue(new CompareRegistration_().newDialog(choices) instanceof CompareDialog);
        assertTrue(new RegistrationDiagnostics_().newDialog(choices) instanceof DiagnosticsDialog);
    }

    // ------------------------------------------------- what a finished run does

    @Test
    public void aResultIsShownWhenWindowsAreWantedAndNotWhenTheyAreNot() {
        Probe shows = probeFor(new CompareRegistration_());
        shows.finish(succeeded(RegDriftParameters.builder(stack(1, 1, 6)).build()));
        assertEquals(1, shows.shown.size());

        Probe hides = probeFor(new CompareRegistration_());
        hides.finish(succeeded(RegDriftParameters.builder(stack(1, 1, 6))
                .hideDisplay(true).build()));
        assertEquals(0, hides.shown.size());
    }

    @Test
    public void aSaveRootMeansTheEntryWritesTheTree() throws Exception {
        File root = folder.newFolder("results");
        Probe probe = probeFor(new CompareRegistration_());

        probe.finish(succeeded(RegDriftParameters.builder(stack(1, 1, 6))
                .saveRoot(root.getAbsolutePath()).build()));

        File tree = new File(root, RegDriftAutoSave.TREE_FOLDER);
        assertTrue("the entry class is what asks for a save: " + tree.getAbsolutePath(),
                tree.isDirectory());
        assertTrue(new File(tree, RegDriftAutoSave.README_FILE).isFile());
        assertEquals("a save that worked is news, not an error", 0, probe.loud.size());
    }

    @Test
    public void aRunThatGaveUpSavesNothingAndShowsNothing() throws Exception {
        File root = folder.newFolder("untouched");
        Probe probe = probeFor(new CompareRegistration_());
        RegDriftParameters parameters = RegDriftParameters.builder(stack(1, 1, 6))
                .saveRoot(root.getAbsolutePath()).build();

        probe.finish(RegDriftResult.failed(parameters,
                Failure.of(Failure.Kind.ESTIMATOR_FAILED, "The movement could not be measured.")));

        assertFalse(new File(root, RegDriftAutoSave.TREE_FOLDER).exists());
        assertEquals(0, probe.shown.size());
        assertEquals(1, probe.loud.size());
    }

    // -------------------------------------------------------- stopping a run

    @Test
    public void aRunStoppedBeforeItStartedComesBackAsATypedReason() {
        Cancellation.Flag stop = Cancellation.flag();
        assertFalse(stop.canceled());
        stop.cancel();

        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(stack(1, 1, 6))
                .cancellation(stop).build());

        assertNotNull(result);
        assertEquals(Failure.Kind.CANCELED, result.failure().kind());
        assertTrue(result.failure().message(),
                result.failure().message().toLowerCase().contains("stopped"));
    }

    @Test
    public void aRunNobodyStoppedCarriesASwitchNobodyCanPull() {
        RegDriftParameters parameters = RegDriftParameters.builder(stack(1, 1, 6)).build();
        assertNotNull(parameters.cancellation());
        assertFalse(parameters.cancellation().canceled());
    }

    @Test
    public void theProgressReporterHandsOutTheSwitchThatStopsTheRun() {
        Progress progress = Progress.silent();
        assertFalse(progress.cancellation().canceled());

        progress.cancel();

        assertTrue(progress.canceled());
        assertTrue("the run and the button must be looking at the same switch",
                progress.cancellation().canceled());
    }

    // ----------------------------------------------------------- the fixtures

    private static RegDriftMacroOptions defaults() {
        return new RegDriftMacroOptions();
    }

    private static RegDriftMacroOptions measureEveryPair() {
        RegDriftMacroOptions options = new RegDriftMacroOptions();
        options.setMode(Mode.DIAGNOSE);
        options.setChannel(Channel.of(3));
        options.setSlice(Slice.of(2));
        options.setUseRoi(true);
        options.setWindows(Windows.allPairs());
        options.setWindowFrames(WindowFrames.of(24));
        options.setEngines(EngineSelection.all());
        options.setAdviseCeiling(false);
        options.setSerial(true);
        return options;
    }

    private static RegDriftMacroOptions scoreASecondStack() {
        RegDriftMacroOptions options = new RegDriftMacroOptions();
        options.setMode(Mode.SCORE);
        options.setCompareWith("registered.tif");
        options.setFlagMotionLoss(false);
        options.setWindows(Windows.of(4));
        options.setHideDisplay(true);
        return options;
    }

    /** A real dialog, seeded with settings, standing in for somebody's choices. */
    private static RegDriftDialog dialogShowing(Probe probe, RegDriftMacroOptions options) {
        CompareDialog dialog = new CompareDialog(probe.openImages());
        dialog.applyOptions(options);
        return dialog;
    }

    /** A successful result carrying nothing but its settings. */
    private static RegDriftResult succeeded(RegDriftParameters parameters) {
        return RegDriftResult.builder(parameters)
                .verdict(Verdict.REGISTRABLE, "The movement in this recording can be registered.")
                .build();
    }

    private static ImagePlus stack(int channels, int slices, int frames) {
        ImageStack planes = new ImageStack(8, 8);
        for (int i = 0; i < channels * slices * frames; i++) {
            planes.addSlice("plane " + (i + 1), new ByteProcessor(8, 8));
        }
        ImagePlus image = new ImagePlus("test stack", planes);
        image.setDimensions(channels, slices, frames);
        return image;
    }

    private static String pluginsConfig() throws Exception {
        InputStream in = EntryRoutingTest.class.getResourceAsStream("/plugins.config");
        assertNotNull("the menu file should be on the classpath", in);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /**
     * Runs one press of a menu entry the way a macro runs it: on a thread ImageJ
     * recognises as a macro's own, with the settings put where
     * {@code Macro.getOptions()} looks for them.
     */
    private static void asMacro(final String options, final RegDriftEntry entry, final String arg)
            throws Exception {
        final AtomicReference<Throwable> failed = new AtomicReference<Throwable>();
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                Macro.setOptions(options);
                try {
                    entry.run(arg);
                } catch (Throwable problem) {
                    failed.set(problem);
                } finally {
                    Macro.setOptions(null);
                }
            }
        }, "Run$_regdrift-entry-routing-test");
        thread.start();
        thread.join(30000);
        if (thread.isAlive()) fail("the macro route did not come back");
        if (failed.get() != null) {
            throw new AssertionError("the macro route threw: " + failed.get(), failed.get());
        }
    }

    private static Probe probeFor(RegDriftEntry real) {
        return new Probe(real);
    }

    /** One line the recorder was given. */
    private static final class Recorded {

        private final String selectTitle;
        private final String line;

        Recorded(String selectTitle, String line) {
            this.selectTitle = selectTitle;
            this.line = line;
        }
    }

    /** A dialog that hands back a different recording than the one it opened on. */
    private static final class ChosenRecording extends RegDriftDialog {

        private final CompareDialog inner;
        private final String title;

        ChosenRecording(CompareDialog inner, String title) {
            super("standing in for a dialog", ImageChoices.none());
            this.inner = inner;
            this.title = title;
        }

        @Override protected String[] modeLabels() {
            return new String[]{LABEL_DIAGNOSE_AND_RECOMMEND};
        }

        @Override protected Mode initialMode() {
            return Mode.DIAGNOSE_AND_RECOMMEND;
        }

        @Override public String imageTitle() {
            return title;
        }

        @Override public RegDriftMacroOptions options() {
            return inner.options();
        }
    }

    /**
     * A real menu entry with the outside world replaced by lists.
     *
     * <p>It keeps the entry it stands for, and answers {@code command()},
     * {@code otherCommand()} and {@code modesRunHere()} out of it, so what is
     * tested here is the identity the shipped class really carries rather than
     * one written out again in a test.
     */
    private static final class Probe extends RegDriftEntry {

        private final RegDriftEntry real;
        private final Map<String, ImagePlus> images = new LinkedHashMap<String, ImagePlus>();

        private final List<String> loud = new ArrayList<String>();
        private final List<String> quiet = new ArrayList<String>();
        private final List<String> status = new ArrayList<String>();
        private final List<Recorded> recorded = new ArrayList<Recorded>();
        private final List<RegDriftResult> shown = new ArrayList<RegDriftResult>();
        private final List<RegDriftResult> finished = new ArrayList<RegDriftResult>();

        private String activeTitle = "";
        private RegDriftDialog dialog;
        private boolean accept = true;
        private int dialogsAsked;
        private int recordedWhenFinished = -1;

        Probe(RegDriftEntry real) {
            this.real = real;
        }

        void open(String title, ImagePlus image) {
            image.setTitle(title);
            images.put(title, image);
            if (activeTitle.isEmpty()) activeTitle = title;
        }

        @Override public String command() {
            return real.command();
        }

        @Override public String otherCommand() {
            return real.otherCommand();
        }

        @Override public List<Mode> modesRunHere() {
            return real.modesRunHere();
        }

        @Override protected RegDriftDialog newDialog(ImageChoices choices) {
            return dialog != null ? dialog : real.newDialog(choices);
        }

        @Override protected ImageChoices openImages() {
            List<ImageChoices.Entry> entries = new ArrayList<ImageChoices.Entry>();
            for (Map.Entry<String, ImagePlus> open : images.entrySet()) {
                ImagePlus image = open.getValue();
                entries.add(new ImageChoices.Entry(open.getKey(), image.getNChannels(),
                        image.getNSlices(), image.getNFrames()));
            }
            return ImageChoices.of(entries, activeTitle);
        }

        @Override protected ImagePlus activeImage() {
            return images.get(activeTitle);
        }

        @Override protected ImagePlus imageNamed(String title) {
            return images.get(title);
        }

        @Override protected boolean canAsk() {
            return true;
        }

        @Override protected boolean askUser(RegDriftDialog asked) {
            dialogsAsked++;
            return accept;
        }

        @Override protected Progress newProgress() {
            return Progress.silent();
        }

        @Override protected void record(String selectTitle, String line) {
            recorded.add(new Recorded(selectTitle, line));
        }

        @Override protected void reportFailure(String message, boolean quietly) {
            (quietly ? quiet : loud).add(message);
        }

        @Override protected void showStatus(String text) {
            status.add(text);
        }

        @Override protected void display(RegDriftResult result) {
            shown.add(result);
        }

        @Override protected void finish(RegDriftResult result) {
            finished.add(result);
            recordedWhenFinished = recorded.size();
            super.finish(result);
        }
    }
}
