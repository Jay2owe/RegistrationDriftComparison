/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import regdrift.Channel;
import regdrift.EngineSelection;
import regdrift.Mode;
import regdrift.RegDriftBatchParameters;
import regdrift.RegDriftBatchRunner;
import regdrift.RegDriftMacroOptions;
import regdrift.RegDriftMacroOptionsParser;
import regdrift.Slice;
import regdrift.WindowFrames;
import regdrift.Windows;
import regdrift.autofix.EngineFixtures;
import regdrift.autofix.EngineId;
import regdrift.autofix.EngineRegistry;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The three dialogs, built and read without a window being put on the screen.
 *
 * <p>Every control is a Swing panel, and a panel can be built on a machine with
 * no display; a window cannot. The dialogs are written so that the whole content
 * exists after construction and the window is made when it is shown, which is
 * what lets this test check the sections, the defaults, the wording of the mode
 * control and what every control reads back as - the things a person would
 * otherwise have to open the dialog and look at.
 *
 * <p>The third is the one a folder goes through, and it carries two things the
 * other two do not: a preview of which files a filename pattern is about to
 * take, and a sentence saying how many recordings will be worked through at
 * once. Both are asserted below, because both are answers to questions somebody
 * would otherwise have to run a folder of two hundred recordings to find out.
 *
 * <p>What is left for the hands-on pass: that the window appears, that Cancel
 * closes it, that the disclosure animates the way it should, and that the ImageJ
 * recorder receives the line asserted here. This test settles what the dialog
 * <em>says</em>; a person still has to confirm it <em>appears</em>.
 */
public class DialogsTest {

    private static final ImageChoices TWO_RECORDINGS = ImageChoices.of(Arrays.asList(
            new ImageChoices.Entry("movie.tif", 3, 4, 48),
            new ImageChoices.Entry("registered.tif", 3, 4, 48)), "movie.tif");

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    // ------------------------------------------------------------- the shape

    @Test
    public void theCompareDialogShowsFourSectionsInTheContractsOrder() {
        assertEquals(Arrays.asList("Input", "Analysis", "Engines", "Output"),
                new CompareDialog(TWO_RECORDINGS).sections());
    }

    @Test
    public void theDiagnosticsDialogShowsTheSameThreeWithoutEngines() {
        assertEquals(Arrays.asList("Input", "Analysis", "Output"),
                new DiagnosticsDialog(TWO_RECORDINGS).sections());
    }

    @Test
    public void bothTitlesCarryTheDisplayName() {
        assertTrue(CompareDialog.TITLE, CompareDialog.TITLE.startsWith(
                "Registration & Drift Comparison"));
        assertTrue(DiagnosticsDialog.TITLE, DiagnosticsDialog.TITLE.startsWith(
                "Registration & Drift Comparison"));
    }

    @Test
    public void theCompareDialogOffersEveryModeAndTheOtherTheTwoThatMeasure() {
        assertEquals(Arrays.asList(
                        RegDriftDialog.LABEL_DIAGNOSE,
                        RegDriftDialog.LABEL_DIAGNOSE_AND_RECOMMEND,
                        RegDriftDialog.LABEL_APPLY,
                        RegDriftDialog.LABEL_COMPARE,
                        RegDriftDialog.LABEL_SCORE),
                new CompareDialog(TWO_RECORDINGS).modeChoices());

        assertEquals(Arrays.asList(
                        RegDriftDialog.LABEL_DIAGNOSE,
                        RegDriftDialog.LABEL_DIAGNOSE_AND_RECOMMEND),
                new DiagnosticsDialog(TWO_RECORDINGS).modeChoices());
    }

    @Test
    public void bothDialogsOpenOnTheModeThatMeasuresAndRanks() {
        assertEquals(Mode.DIAGNOSE_AND_RECOMMEND, new CompareDialog(TWO_RECORDINGS).mode());
        assertEquals(Mode.DIAGNOSE_AND_RECOMMEND, new DiagnosticsDialog(TWO_RECORDINGS).mode());
    }

    @Test
    public void everyPieceOfModeWordingMapsOntoAModeAndBackAgain() {
        for (Mode mode : Mode.values()) {
            String label = RegDriftDialog.labelOf(mode);
            assertNotNull(label);
            assertEquals(mode, RegDriftDialog.modeOf(label));
            assertFalse("every mode needs a sentence of its own",
                    RegDriftDialog.helpForMode(mode).trim().isEmpty());
        }
    }

    // ----------------------------------------------------------- the defaults

    @Test
    public void aFreshDialogReadsBackAsTheDocumentedDefaults() {
        assertEquals(new RegDriftMacroOptions(), new CompareDialog(TWO_RECORDINGS).options());
        assertEquals(new RegDriftMacroOptions(), new DiagnosticsDialog(TWO_RECORDINGS).options());
    }

    @Test
    public void theDropdownsFollowTheShapeOfTheChosenRecording() {
        CompareDialog dialog = new CompareDialog(TWO_RECORDINGS);
        assertEquals(Arrays.asList(RegDriftDialog.CHANNEL_AUTO, "1", "2", "3"),
                dialog.channelChoices());
        assertEquals(Arrays.asList(RegDriftDialog.SLICE_PROJECT, "1", "2", "3", "4"),
                dialog.sliceChoices());
    }

    @Test
    public void aDialogWithNothingToMeasureStillBuildsAndSaysSo() {
        CompareDialog dialog = new CompareDialog(ImageChoices.none());
        assertEquals("", dialog.imageTitle());
        assertNotNull(dialog.whatStopsThisRun());
    }

    // ---------------------------------------------------------- the disclosure

    @Test
    public void theAdvancedPaneStartsFolded() {
        assertFalse("the settings most people never touch stay out of the way",
                new CompareDialog(TWO_RECORDINGS).advanced().isExpanded());
        assertFalse(new DiagnosticsDialog(TWO_RECORDINGS).advanced().isExpanded());
    }

    /**
     * Opening the Advanced pane and closing it again is a change to what is
     * visible and to nothing else. The settings a run would be given, and the
     * line it would record, come back byte for byte the same.
     */
    @Test
    public void openingAndClosingTheAdvancedPaneChangesNoSetting() {
        CompareDialog dialog = new CompareDialog(TWO_RECORDINGS);
        RegDriftMacroOptions before = dialog.options();
        String lineBefore = before.toMacroOptions();

        dialog.advanced().setExpanded(true);
        assertTrue(dialog.advanced().isExpanded());
        assertEquals(before, dialog.options());

        dialog.advanced().setExpanded(false);
        assertFalse(dialog.advanced().isExpanded());
        assertEquals(before, dialog.options());
        assertEquals(lineBefore, dialog.options().toMacroOptions());
    }

    // ------------------------------------------------------- settings, both ways

    /**
     * Settings in, settings out. Every combination below differs from the
     * defaults in a different place, so a control wired to the wrong setting
     * shows up as a difference rather than as a value that happened to match.
     */
    @Test
    public void everySettingSurvivesGoingIntoTheControlsAndComingBackOut() {
        for (RegDriftMacroOptions wanted : combinations()) {
            CompareDialog dialog = new CompareDialog(TWO_RECORDINGS);
            dialog.applyOptions(wanted);

            assertEquals("the controls lost or changed a setting", wanted, dialog.options());
            assertEquals("and the recorded line has to read back the same way",
                    wanted, RegDriftMacroOptionsParser.parse(dialog.options().toMacroOptions()));
        }
    }

    @Test
    public void theWholeRecordingChoiceIsTheWindowsZeroSetting() {
        CompareDialog dialog = new CompareDialog(TWO_RECORDINGS);
        dialog.applyOptions(everyPair());

        assertEquals(RegDriftDialog.WINDOWS_EVERY_PAIR,
                RegDriftDialog.labelForWindows(dialog.options().getWindows()));
        assertTrue(dialog.options().getWindows().isAllPairs());
        assertTrue("the setting a macro writes for it is windows=0",
                dialog.options().toMacroOptions().contains("windows=0"));
    }

    @Test
    public void theModeControlDecidesWhichModeTheSettingsCarry() {
        CompareDialog dialog = new CompareDialog(TWO_RECORDINGS);
        for (Mode mode : Mode.values()) {
            RegDriftMacroOptions wanted = new RegDriftMacroOptions();
            wanted.setMode(mode);
            if (mode == Mode.SCORE) wanted.setCompareWith("registered.tif");
            dialog.applyOptions(wanted);

            assertEquals(mode, dialog.mode());
            assertEquals(mode, dialog.options().getMode());
        }
    }

    /**
     * A dialog that offers two modes cannot be set to a third, and says so by
     * staying where it was rather than by pretending.
     */
    @Test
    public void theDiagnosticsDialogCannotBeSetToAModeItDoesNotOffer() {
        DiagnosticsDialog dialog = new DiagnosticsDialog(TWO_RECORDINGS);
        RegDriftMacroOptions applying = new RegDriftMacroOptions();
        applying.setMode(Mode.APPLY);

        dialog.applyOptions(applying);

        assertEquals(Mode.DIAGNOSE_AND_RECOMMEND, dialog.mode());
    }

    // ------------------------------------------------------------ the OK check

    @Test
    public void aDialogAtItsDefaultsHasNothingStandingInItsWay() {
        assertNull(new CompareDialog(TWO_RECORDINGS).whatStopsThisRun());
        assertNull(new DiagnosticsDialog(TWO_RECORDINGS).whatStopsThisRun());
    }

    @Test
    public void scoringNeedsTheRegisteredStackBeforeItCanStart() {
        CompareDialog dialog = new CompareDialog(TWO_RECORDINGS);
        RegDriftMacroOptions scoring = new RegDriftMacroOptions();
        scoring.setMode(Mode.SCORE);
        dialog.applyOptions(scoring);

        String problem = dialog.whatStopsThisRun();

        assertNotNull("a score with nothing to score against cannot go ahead", problem);
        assertTrue(problem, problem.contains("registered stack"));
    }

    @Test
    public void aStackCannotBeScoredAgainstItself() {
        CompareDialog dialog = new CompareDialog(TWO_RECORDINGS);
        RegDriftMacroOptions scoring = new RegDriftMacroOptions();
        scoring.setMode(Mode.SCORE);
        scoring.setCompareWith("movie.tif");
        dialog.applyOptions(scoring);

        String problem = dialog.whatStopsThisRun();

        assertNotNull(problem);
        assertTrue(problem, problem.contains("movie.tif"));
    }

    /**
     * A window title holding a bracket cannot be written in the macro grammar at
     * all, so it is refused while somebody is still looking at the dialog rather
     * than turning up later as a recorded line that lost half a path.
     */
    @Test
    public void settingsThatCouldNotBeWrittenDownAreRefusedAtTheDialog() {
        CompareDialog dialog = new CompareDialog(ImageChoices.of(Arrays.asList(
                new ImageChoices.Entry("movie [2].tif", 1, 1, 48)), "movie [2].tif"));

        String problem = dialog.whatStopsThisRun();

        assertNotNull("a run nobody could repeat is not one to start", problem);
        assertTrue(problem, problem.contains("macro line"));
    }

    // ----------------------------------------------------------- the engines

    /**
     * The Engines section is the real one now: it reports what was found for
     * every engine in the catalogue, in the catalogue's order, and it says so
     * with a fixed set of answers rather than with whatever this computer has.
     */
    @Test
    public void theEnginesSectionReportsEveryEngineTheCatalogueHolds() {
        CompareDialog dialog = new CompareDialog(TWO_RECORDINGS,
                EngineFixtures.serviceWherePresent(EngineId.TURBOREG, EngineId.STACKREG));

        assertEquals(Arrays.asList(EngineId.values()), dialog.enginePanel().engines());
        assertEquals(EngineId.values().length, dialog.enginePanel().statuses().size());
    }

    @Test
    public void theEnginesSectionNamesTheEnginesTheWayTheirAuthorsDo() {
        assertTrue(EngineRegistry.displayNames().toString(),
                EngineRegistry.displayNames().containsAll(Arrays.asList(
                        "StackReg", "TurboReg", "Correct 3D drift", "Fast4DReg",
                        "Linear Stack Alignment with SIFT", "Image Stabilizer")));
    }

    @Test
    public void nothingInTheEnginesSectionRunsDuringAMeasurement() {
        assertTrue(EnginePanel.NOTHING_RUNS_HERE,
                EnginePanel.NOTHING_RUNS_HERE.contains("Nothing in this section runs"));
        assertTrue(EnginePanel.NOTHING_FETCHED_UNASKED,
                EnginePanel.NOTHING_FETCHED_UNASKED.contains("button here is pressed"));
    }

    /**
     * The dialog without an Engines section never asks which engines are here,
     * so opening it reads nothing about them at all.
     */
    @Test
    public void theDiagnosticsDialogAsksNothingAboutEngines() {
        assertFalse(new DiagnosticsDialog(TWO_RECORDINGS).sections()
                .contains(EnginePanel.HEADING));
    }

    // ------------------------------------------------------ the folder dialog

    @Test
    public void theFolderDialogShowsThreeSectionsAndCarriesTheDisplayName() {
        assertEquals(Arrays.asList("Input", "Analysis", "Output"),
                new BatchDialog().sections());
        assertTrue(BatchDialog.TITLE, BatchDialog.TITLE.startsWith(
                "Registration & Drift Comparison"));
    }

    /**
     * A folder run offers the four modes a folder can answer and says, where
     * somebody would look for it, why scoring is not among them.
     */
    @Test
    public void theFolderDialogOffersTheFourModesAFolderCanRunAndSaysWhyNotTheFifth() {
        assertEquals(Arrays.asList(
                        RegDriftDialog.LABEL_DIAGNOSE,
                        RegDriftDialog.LABEL_DIAGNOSE_AND_RECOMMEND,
                        RegDriftDialog.LABEL_APPLY,
                        RegDriftDialog.LABEL_COMPARE),
                new BatchDialog().modeChoices());
        assertFalse("a folder and one filename pattern cannot say which registered stack goes"
                        + " with which recording, so scoring is not offered",
                new BatchDialog().modeChoices().contains(RegDriftDialog.LABEL_SCORE));
        assertTrue(BatchDialog.WHY_NO_SCORE,
                BatchDialog.WHY_NO_SCORE.contains("two stacks for every recording"));
        assertTrue("and points at the menu item that does score a pair: "
                + BatchDialog.WHY_NO_SCORE, BatchDialog.WHY_NO_SCORE.contains("Compare"));
    }

    /**
     * <b>The dialog says how many recordings will be worked through at once, and
     * why - it is not quietly slower in the two modes that drive engines.</b>
     *
     * <p>Measured in {@code BatchModeConcurrencyTest}: two recordings driving
     * registration engines at the same time set and restore ImageJ's one
     * batch-mode switch against each other and leave it on. The remedy is one
     * recording at a time in those two modes, and a remedy nobody is told about
     * reads as a slow computer - which is exactly how the defect underneath it
     * would survive.
     */
    @Test
    public void theFolderDialogSaysHowManyRecordingsRunAtOnceAndWhy() {
        BatchDialog dialog = new BatchDialog();
        assertTrue("a mode that measures and ranks works through several at once: "
                + dialog.workerNoteText(), dialog.workerNoteText().contains("at once"));

        dialog.mode(Mode.COMPARE);
        assertTrue("comparing says it runs one at a time: " + dialog.workerNoteText(),
                dialog.workerNoteText().contains("one movie at a time"));
        assertTrue("and why: " + dialog.workerNoteText(),
                dialog.workerNoteText().contains("batch mode"));

        dialog.mode(Mode.APPLY);
        assertTrue("and so does applying: " + dialog.workerNoteText(),
                dialog.workerNoteText().contains("one movie at a time"));

        dialog.mode(Mode.DIAGNOSE);
        assertTrue("while measuring goes back to several at once: " + dialog.workerNoteText(),
                dialog.workerNoteText().contains("at once"));
        assertEquals("and the note is the one the runner itself acts on, not a second copy of it",
                DialogForm.wrapped(RegDriftBatchRunner.movieWorkerNoteFor(Mode.DIAGNOSE)),
                dialog.workerNoteText());
    }

    /**
     * The preview shows which files a pattern takes and which it leaves alone,
     * before anything is opened.
     */
    /**
     * A folder filled in by the folder button, or typed without pressing Enter,
     * still updates the preview. Found by the GUI checks: the preview went on
     * saying no folder had been chosen.
     */
    @Test
    public void aFolderFilledInWithoutEnterStillUpdatesThePreview() throws Exception {
        File folder = temp.newFolder("typed");
        assertTrue(new File(folder, "rec_a.tif").createNewFile());
        assertTrue(new File(folder, "notes.txt").createNewFile());
        final BatchDialog dialog = new BatchDialog();
        final String path = folder.getAbsolutePath();
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                dialog.folderFieldForTest().setText(path);
            }
        });
        long until = System.currentTimeMillis() + BatchDialog.SETTLE_MS + 5000;
        while (dialog.previewText().equals(BatchDialog.NOTHING_CHOSEN)
                && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { }
        });
        String said = dialog.previewText();
        assertTrue("the preview reads the folder once the text rests: " + said,
                said.contains("rec_a.tif") && said.contains("notes.txt"));
    }

    @Test
    public void theFolderDialogPreviewsWhatThePatternTakesAndWhatItLeavesAlone() throws IOException {
        File folder = temp.newFolder("plate");
        assertTrue(new File(folder, "A1_t0.tif").createNewFile());
        assertTrue(new File(folder, "A2_t0.tif").createNewFile());
        assertTrue(new File(folder, "notes.csv").createNewFile());

        BatchDialog dialog = new BatchDialog();
        assertEquals("before a folder is chosen it says so rather than showing an empty list",
                BatchDialog.NOTHING_CHOSEN, dialog.previewText());

        dialog.folder(folder.getAbsolutePath());
        String said = dialog.previewText();
        assertTrue("the two recordings are listed: " + said,
                said.contains("A1_t0.tif") && said.contains("A2_t0.tif"));
        assertTrue("and so is the file it will leave alone: " + said, said.contains("notes.csv"));
        assertTrue("named as skipped, so a pattern taking half a folder shows as one: " + said,
                said.contains("skipped"));
        assertEquals("and the preview is the reading the run itself uses, not a second one",
                RegDriftBatchRunner.preview(dialog.parameters()), said);

        dialog.pattern("^(A1)_.*\\.tif$");
        assertTrue("narrowing the pattern narrows what it takes: " + dialog.previewText(),
                dialog.previewText().contains("1 recording to run"));
        assertNull("and the dialog is happy to be accepted", dialog.whatStopsThisRun());

        dialog.pattern("^nothing-here-matches-this$");
        assertNotNull("a pattern that would run nothing is refused at the button rather than"
                + " producing an empty table", dialog.whatStopsThisRun());
        assertTrue(dialog.whatStopsThisRun(),
                dialog.whatStopsThisRun().contains(RegDriftBatchParameters.DEFAULT_PATTERN));
    }

    /** A folder run reads back as the settings its controls were left on. */
    @Test
    public void theFolderDialogReadsBackAsWhatItsControlsSay() throws IOException {
        File folder = temp.newFolder("read-back");
        assertTrue(new File(folder, "B1_t0.tif").createNewFile());

        BatchDialog dialog = new BatchDialog();
        dialog.folder(folder.getAbsolutePath());
        dialog.pattern("^([A-Z]\\d+)_.*\\.tif$");
        dialog.recursive(true);
        dialog.mode(Mode.COMPARE);

        RegDriftBatchParameters read = dialog.parameters();
        assertEquals(folder.getAbsolutePath(), read.folder().getAbsolutePath());
        assertEquals("^([A-Z]\\d+)_.*\\.tif$", read.pattern());
        assertTrue("sub-folders are read as well", read.recursive());
        assertEquals(Mode.COMPARE, read.mode());
        assertEquals("the bracketed part of the pattern is what labels a recording",
                "B1", read.labelFor("B1_t0.tif"));
        assertEquals("and a file it does not match is labelled nothing at all rather than being"
                + " quietly taken", null, read.labelFor("notes.csv"));
    }

    /** No folder chosen is a sentence, not a run that opens nothing and says nothing. */
    @Test
    public void theFolderDialogRefusesAnEmptyFolderByName() {
        BatchDialog dialog = new BatchDialog();
        assertNotNull(dialog.whatStopsThisRun());
        assertTrue(dialog.whatStopsThisRun(),
                dialog.whatStopsThisRun().contains("No folder was chosen"));

        dialog.folder(new File(temp.getRoot(), "no-such-folder").getAbsolutePath());
        assertTrue(dialog.whatStopsThisRun(),
                dialog.whatStopsThisRun().contains("is not a folder this computer can read"));
    }

    // ---------------------------------------------------------- the fixtures

    private static List<RegDriftMacroOptions> combinations() {
        List<RegDriftMacroOptions> all = new ArrayList<RegDriftMacroOptions>();
        all.add(new RegDriftMacroOptions());
        all.add(everyPair());

        RegDriftMacroOptions applying = new RegDriftMacroOptions();
        applying.setMode(Mode.APPLY);
        applying.setApplyEngine("Correct 3D drift");
        applying.setChannel(Channel.of(2));
        applying.setSlice(Slice.of(3));
        applying.setEngines(EngineSelection.all());
        applying.setSaveRoot("C:/results");
        all.add(applying);

        RegDriftMacroOptions scoring = new RegDriftMacroOptions();
        scoring.setMode(Mode.SCORE);
        scoring.setCompareWith("registered.tif");
        scoring.setFlagMotionLoss(false);
        scoring.setAdviseCeiling(false);
        scoring.setHideDisplay(true);
        scoring.setSerial(true);
        scoring.setWindows(Windows.of(5));
        scoring.setWindowFrames(WindowFrames.of(16));
        all.add(scoring);

        RegDriftMacroOptions comparing = new RegDriftMacroOptions();
        comparing.setMode(Mode.COMPARE);
        comparing.setUseRoi(true);
        comparing.setEngines(EngineSelection.named(Arrays.asList("StackReg", "TurboReg")));
        all.add(comparing);

        return all;
    }

    private static RegDriftMacroOptions everyPair() {
        RegDriftMacroOptions options = new RegDriftMacroOptions();
        options.setMode(Mode.DIAGNOSE);
        options.setWindows(Windows.allPairs());
        return options;
    }
}
