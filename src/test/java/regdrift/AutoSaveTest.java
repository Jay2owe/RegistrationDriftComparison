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
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The auto-save tree: its shape, its {@code README.txt}, and the one question
 * that has a wrong answer - what a run does with a {@code summary.csv} an
 * earlier version of this plugin wrote with different columns.
 */
public class AutoSaveTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    // ---------------------------------------------------------------- the tree

    @Test
    public void aRunWritesTheWholeTree() throws IOException {
        File root = folder.newFolder("results");
        RegDriftAutoSave.Report report = RegDriftAutoSave.save(root, measuredRun("movie.tif"));

        assertTrue(message(report), report.isSuccess());
        File tree = new File(root, RegDriftAutoSave.TREE_FOLDER);
        assertTrue("the tree folder was not made", tree.isDirectory());
        for (String subFolder : RegDriftAutoSave.FOLDERS) {
            assertTrue(subFolder + "/ was not made", new File(tree, subFolder).isDirectory());
        }
        assertTrue("README.txt is missing",
                new File(tree, RegDriftAutoSave.README_FILE).isFile());
        assertTrue("summary.csv is missing",
                new File(tree, RegDriftAutoSave.SUMMARY_FILE).isFile());
        assertTrue(new File(tree, "diagnosis/movie_diagnosis.csv").isFile());
        assertTrue(new File(tree, "recommendation/movie_recommendation.csv").isFile());
        assertTrue(new File(tree, "comparison/movie_comparison.csv").isFile());
        assertTrue(new File(tree, "frames/movie_frames.csv").isFile());
        assertEquals(new File(tree, RegDriftAutoSave.SUMMARY_FILE), report.summaryFile());
    }

    @Test
    public void eachTableIsWrittenWithItsContractColumns() throws IOException {
        File root = folder.newFolder("results");
        RegDriftAutoSave.save(root, measuredRun("movie.tif"));
        File tree = new File(root, RegDriftAutoSave.TREE_FOLDER);

        assertEquals(RegDriftTables.DIAGNOSIS_COLUMNS,
                header(new File(tree, "diagnosis/movie_diagnosis.csv")));
        assertEquals(RegDriftTables.RECOMMENDATION_COLUMNS,
                header(new File(tree, "recommendation/movie_recommendation.csv")));
        assertEquals(RegDriftTables.COMPARISON_COLUMNS,
                header(new File(tree, "comparison/movie_comparison.csv")));
        assertEquals(RegDriftTables.FRAMES_COLUMNS,
                header(new File(tree, "frames/movie_frames.csv")));

        List<String> row = RegDriftAutoSave.parseCsvLine(
                lines(new File(tree, "diagnosis/movie_diagnosis.csv")).get(1));
        assertEquals("1", row.get(RegDriftTables.DIAGNOSIS_COLUMNS.indexOf("channel")));
        assertEquals("DRIFT", row.get(RegDriftTables.DIAGNOSIS_COLUMNS.indexOf("motion_label")));
    }

    /** A table with no rows still gets its header, or nothing can read the file. */
    @Test
    public void anEmptyTableIsStillWrittenWithItsHeader() throws IOException {
        File root = folder.newFolder("results");
        RegDriftResult result = RegDriftResult.builder(settings("empty.tif", ""))
                .diagnosis(RegDriftTables.diagnosis())
                .build();
        RegDriftAutoSave.save(root, result);

        File written = new File(root,
                RegDriftAutoSave.TREE_FOLDER + "/diagnosis/empty_diagnosis.csv");
        assertEquals(1, lines(written).size());
        assertEquals(RegDriftTables.DIAGNOSIS_COLUMNS, header(written));
    }

    /** A title a filesystem cannot hold becomes one it can. */
    @Test
    public void anAwkwardTitleBecomesAFilenameThatWorks() {
        assertEquals("movie_1", RegDriftAutoSave.fileNameFor(image("movie 1.tif")));
        assertEquals("well_A1__t0", RegDriftAutoSave.fileNameFor(image("well[A1]:t0.TIF")));
        assertEquals("untitled", RegDriftAutoSave.fileNameFor(image("....")));
        assertEquals("untitled", RegDriftAutoSave.fileNameFor(null));
    }

    // ----------------------------------------------------------- README.txt

    @Test
    public void theReadmeRecordsTheVersionAndTheSettings() throws IOException {
        File root = folder.newFolder("results");
        RegDriftResult result = measuredRun("movie.tif");
        RegDriftAutoSave.save(root, result);

        String readme = text(new File(root,
                RegDriftAutoSave.TREE_FOLDER + "/" + RegDriftAutoSave.README_FILE));
        assertTrue("the README must name the version that wrote it",
                readme.contains(RegDrift.VERSION));
        assertTrue("the README must carry the settings this run used",
                readme.contains(RegDriftMacroOptions.from(result.parameters()).toMacroOptions()));
        assertTrue("the README must name every folder it made",
                readme.contains(RegDriftAutoSave.QC_FOLDER)
                        && readme.contains(RegDriftAutoSave.REGISTERED_FOLDER));
        assertTrue("the README must say how the summary file grows",
                readme.contains("summary_2.csv"));
        assertTrue("the README must state the scale the measurement was made at",
                readme.contains("measured_at_bin"));
        assertTrue("the README must record the engine versions this run found",
                readme.contains("Correct 3D drift") && readme.contains("2.1.1"));
        assertTrue("the README must name the calibration set the ranking came from",
                readme.contains("incucyte-phase-3seed"));
    }

    /** The words the house rules keep out of anything a user reads. */
    @Test
    public void theReadmeAvoidsTheWordsThatOverclaim() throws IOException {
        File root = folder.newFolder("results");
        RegDriftAutoSave.save(root, measuredRun("movie.tif"));
        String readme = text(new File(root,
                RegDriftAutoSave.TREE_FOLDER + "/" + RegDriftAutoSave.README_FILE))
                .toLowerCase(java.util.Locale.ROOT);

        for (String word : new String[]{"accuracy", "best", "optimal", "only"}) {
            assertFalse("the README says '" + word + "'", readme.contains(word));
        }
        for (String british : new String[]{"colour", "centre", "normalise", "behaviour"}) {
            assertFalse("the README is not US English: " + british, readme.contains(british));
        }
    }

    // ---------------------------------------------------------- summary.csv

    @Test
    public void aSecondRunAppendsWithoutRewritingTheFirst() throws IOException {
        File root = folder.newFolder("results");
        RegDriftAutoSave.save(root, measuredRun("first.tif"));
        File summary = new File(root,
                RegDriftAutoSave.TREE_FOLDER + "/" + RegDriftAutoSave.SUMMARY_FILE);
        List<String> afterOne = lines(summary);
        assertEquals(2, afterOne.size());

        RegDriftAutoSave.save(root, measuredRun("second.tif"));
        List<String> afterTwo = lines(summary);
        assertEquals(3, afterTwo.size());
        assertEquals("the header must not be rewritten", afterOne.get(0), afterTwo.get(0));
        assertEquals("the first run's line must not be touched", afterOne.get(1), afterTwo.get(1));
        assertEquals(RegDriftAutoSave.SUMMARY_COLUMNS, RegDriftAutoSave.parseCsvLine(
                afterTwo.get(0)));

        List<String> second = RegDriftAutoSave.parseCsvLine(afterTwo.get(2));
        assertEquals("second.tif", second.get(RegDriftAutoSave.SUMMARY_COLUMNS.indexOf("image")));
        assertEquals("ok", second.get(RegDriftAutoSave.SUMMARY_COLUMNS.indexOf("status")));
        assertEquals(RegDrift.VERSION,
                second.get(RegDriftAutoSave.SUMMARY_COLUMNS.indexOf("plugin_version")));
        assertFalse("the settings this run used belong in its own line",
                second.get(RegDriftAutoSave.SUMMARY_COLUMNS.indexOf("settings")).isEmpty());
    }

    /**
     * The decision this stage had to make: a summary written by an earlier
     * version with different columns is left exactly as it is, and this run
     * starts {@code summary_2.csv}. Reshaping the old file to fit would drop
     * whichever column it lacks, and a results file that silently loses a
     * measurement is worse than one that stops.
     */
    @Test
    public void anOlderSummaryWithOtherColumnsIsLeftAloneAndANewOneIsStarted()
            throws IOException {
        File root = folder.newFolder("results");
        File tree = new File(root, RegDriftAutoSave.TREE_FOLDER);
        assertTrue(tree.mkdirs());
        File old = new File(tree, RegDriftAutoSave.SUMMARY_FILE);
        String oldContents = "image,mode,verdict" + System.lineSeparator()
                + "older.tif,diagnose,registrable" + System.lineSeparator();
        Files.write(old.toPath(), oldContents.getBytes(StandardCharsets.UTF_8));

        RegDriftAutoSave.Report report = RegDriftAutoSave.save(root, measuredRun("new.tif"));

        assertTrue(message(report), report.isSuccess());
        assertEquals("the older file must be left byte for byte as it was",
                oldContents, text(old));
        File second = new File(tree, "summary_2.csv");
        assertEquals(second, report.summaryFile());
        assertEquals(RegDriftAutoSave.SUMMARY_COLUMNS,
                RegDriftAutoSave.parseCsvLine(lines(second).get(0)));
        assertEquals(2, lines(second).size());

        RegDriftAutoSave.save(root, measuredRun("newer.tif"));
        assertEquals("a later run of this version keeps using the same file",
                3, lines(second).size());
        assertFalse("and does not start a third", new File(tree, "summary_3.csv").exists());
    }

    /** A file left without a final line break does not swallow the next run. */
    @Test
    public void aSummaryWithNoFinalLineBreakStillGetsItsOwnLine() throws IOException {
        File root = folder.newFolder("results");
        File tree = new File(root, RegDriftAutoSave.TREE_FOLDER);
        assertTrue(tree.mkdirs());
        File summary = new File(tree, RegDriftAutoSave.SUMMARY_FILE);
        StringBuilder header = new StringBuilder();
        for (String column : RegDriftAutoSave.SUMMARY_COLUMNS) {
            if (header.length() > 0) header.append(',');
            header.append(column);
        }
        Files.write(summary.toPath(), header.toString().getBytes(StandardCharsets.UTF_8));

        RegDriftAutoSave.save(root, measuredRun("movie.tif"));

        List<String> written = lines(summary);
        assertEquals(2, written.size());
        assertEquals(RegDriftAutoSave.SUMMARY_COLUMNS,
                RegDriftAutoSave.parseCsvLine(written.get(0)));
    }

    /** A run that measured nothing still says so, in its own line. */
    @Test
    public void aRunThatProducedNothingStillRecordsWhy() throws IOException {
        File root = folder.newFolder("results");
        RegDriftParameters parameters = settings("movie.tif", "");
        RegDriftResult result = RegDrift.run(parameters);
        assertFalse(result.isSuccess());

        RegDriftAutoSave.Report report = RegDriftAutoSave.save(root, result);
        assertTrue(message(report), report.isSuccess());

        File tree = new File(root, RegDriftAutoSave.TREE_FOLDER);
        assertFalse("no diagnosis was made, so no diagnosis file",
                new File(tree, "diagnosis/movie_diagnosis.csv").exists());
        List<String> line = RegDriftAutoSave.parseCsvLine(
                lines(new File(tree, RegDriftAutoSave.SUMMARY_FILE)).get(1));
        assertEquals("not_implemented",
                line.get(RegDriftAutoSave.SUMMARY_COLUMNS.indexOf("status")));
    }

    // -------------------------------------------------------- refusing early

    @Test
    public void aSaveWithNoFolderSaysSoRatherThanThrowing() {
        RegDriftAutoSave.Report report = RegDriftAutoSave.save(
                RegDriftResult.builder(settings("movie.tif", "")).build());
        assertFalse(report.isSuccess());
        assertEquals(Failure.Kind.INVALID_PARAMETERS, report.failure().kind());
        assertTrue(report.failure().message(),
                report.failure().message().contains(RegDriftMacroOptions.SAVE_ROOT));
        assertTrue(report.written().isEmpty());
        assertNull(report.summaryFile());
    }

    /**
     * Windows stops at 260 characters, and a microscope title inside a
     * synchronized folder reaches that. The answer names the path and its
     * length, and nothing is created first.
     */
    @Test
    public void aPathThisSystemCannotHoldIsRefusedByNameBeforeAnythingIsWritten()
            throws IOException {
        Assume.assumeTrue("this system enforces no path-length limit",
                RegDriftAutoSave.pathLimit() > 0);
        File root = folder.newFolder("results");
        StringBuilder title = new StringBuilder();
        while (title.length() < RegDriftAutoSave.WINDOWS_PATH_LIMIT) {
            title.append("plate01_wellA01_field3_channel2_");
        }

        RegDriftAutoSave.Report report = RegDriftAutoSave.save(root,
                measuredRun(title + ".tif"));

        assertFalse(report.isSuccess());
        assertEquals(Failure.Kind.PATH_TOO_LONG, report.failure().kind());
        assertTrue("the message must name the path it could not create",
                report.failure().message().contains(root.getAbsolutePath()));
        assertTrue("the message must give the length and the limit",
                report.failure().message().contains(
                        Integer.toString(RegDriftAutoSave.WINDOWS_PATH_LIMIT)));
        assertFalse("nothing should be created when a path is refused",
                new File(root, RegDriftAutoSave.TREE_FOLDER).exists());
    }

    // -------------------------------------------------------------- fixtures

    /** A run that measured something, ranked something and scored something. */
    private static RegDriftResult measuredRun(String title) {
        ResultsTable diagnosis = RegDriftTables.diagnosis();
        RegDriftTables.diagnosisRow()
                .channel(1)
                .localisability(0.081)
                .measuredAtBin(4)
                .driftRatePx(0.42)
                .motionLabel("DRIFT")
                .motionDominant("DRIFT")
                .severity("moderate")
                .verdict(Verdict.REGISTRABLE)
                .appendTo(diagnosis);

        Recommendation ranked = Recommendation.builder("Correct 3D drift", 1)
                .reason("measured on slow drift with little jitter")
                .expectedErrorPx(0.6)
                .expectedSeconds(31.0)
                .calibration(Recommendation.Calibration.IN_RANGE)
                .presence(Recommendation.Presence.PRESENT)
                .menuPath("Plugins > Registration > Correct 3D drift")
                .build();
        ResultsTable recommendation = RegDriftTables.recommendation();
        RegDriftTables.append(recommendation, ranked);

        ResultsTable comparison = RegDriftTables.comparison();
        RegDriftTables.comparisonRow().engine("Correct 3D drift").cpuSeconds(31.5)
                .residualBefore(6.0).residualAfter(1.5).residualRemoved(4.5)
                .sdVsControl(0.72).framesFlagged(0).status("ok").appendTo(comparison);

        ResultsTable frames = RegDriftTables.frames();
        RegDriftTables.framesRow().t(1).cumDx(0).cumDy(0).status(FrameStatus.OK).appendTo(frames);

        return RegDriftResult.builder(settings(title, ""))
                .diagnosis(diagnosis)
                .recommendation(recommendation)
                .comparison(comparison)
                .frames(frames)
                .verdict(Verdict.REGISTRABLE, "The movement is a slow drift and can be registered.")
                .ranked(java.util.Collections.singletonList(ranked))
                .provenance(Provenance.builder()
                        .pluginVersion(RegDrift.VERSION)
                        .mode(Mode.DIAGNOSE_AND_RECOMMEND)
                        .channel(1)
                        .channelReason("ranked highest by localisability")
                        .measuredAtBin(4)
                        .windowStarts(new int[]{0, 40, 80})
                        .windowFrames(12)
                        .engineVersions(java.util.Collections.singletonMap(
                                "Correct 3D drift", "2.1.1"))
                        .calibrationSet("incucyte-phase-3seed")
                        .build())
                .build();
    }

    private static RegDriftParameters settings(String title, String saveRoot) {
        return RegDriftParameters.builder(image(title)).saveRoot(saveRoot).build();
    }

    private static ImagePlus image(String title) {
        ImageStack stack = new ImageStack(8, 8);
        stack.addSlice("1", new ByteProcessor(8, 8));
        stack.addSlice("2", new ByteProcessor(8, 8));
        stack.addSlice("3", new ByteProcessor(8, 8));
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, 1, 3);
        return image;
    }

    private static List<String> header(File file) throws IOException {
        return RegDriftAutoSave.parseCsvLine(lines(file).get(0));
    }

    private static List<String> lines(File file) throws IOException {
        assertTrue(file.getName() + " was not written", file.isFile());
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String message(RegDriftAutoSave.Report report) {
        assertNotNull(report);
        return report.isSuccess() ? "saved" : report.failure().toString();
    }
}
