/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import org.junit.Test;
import regdrift.Channel;
import regdrift.EngineSelection;
import regdrift.Mode;
import regdrift.RegDriftMacroOptions;
import regdrift.RegDriftMacroOptionsParser;
import regdrift.Slice;
import regdrift.WindowFrames;
import regdrift.Windows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The two dialogs, built and read without a window being put on the screen.
 *
 * <p>Every control is a Swing panel, and a panel can be built on a machine with
 * no display; a window cannot. The dialogs are written so that the whole content
 * exists after construction and the window is made when it is shown, which is
 * what lets this test check the sections, the defaults, the wording of the mode
 * control and what every control reads back as - the things a person would
 * otherwise have to open the dialog and look at.
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
     * The section says this build has not looked. It does not say the engines
     * are absent, because nothing here has looked for them, and a section that
     * reports a missing engine that is sitting there would send somebody off to
     * reinstall software they already have.
     */
    @Test
    public void theEnginesSectionSaysThisBuildHasNotChecked() {
        String notice = EnginesPlaceholder.NOTICE.toLowerCase();
        assertTrue(EnginesPlaceholder.NOTICE, notice.contains("later build"));
        assertTrue(EnginesPlaceholder.NOTICE, notice.contains("has not looked"));

        for (String claim : Arrays.asList("not installed", "no engines", "missing",
                "not found", "not present")) {
            assertFalse("the placeholder must not report a measurement nobody made: " + claim,
                    notice.contains(claim));
            assertFalse(claim, EnginesPlaceholder.NOT_CHECKED.toLowerCase().contains(claim));
        }
    }

    @Test
    public void theEnginesSectionNamesTheEnginesTheWayTheirAuthorsDo() {
        assertEquals(Arrays.asList("StackReg", "TurboReg", "Correct 3D drift", "Fast4DReg",
                        "Linear Stack Alignment with SIFT", "Image Stabilizer"),
                EnginesPlaceholder.CANDIDATE_ENGINES);
    }

    @Test
    public void nothingInTheEnginesSectionRunsDuringAMeasurement() {
        assertTrue(EnginesPlaceholder.NOTHING_RUNS_HERE,
                EnginesPlaceholder.NOTHING_RUNS_HERE.contains("Nothing in this section runs"));
        assertTrue(EnginesPlaceholder.NOTHING_RUNS_HERE,
                EnginesPlaceholder.NOTHING_RUNS_HERE.contains("pressing a button"));
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
