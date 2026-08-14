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
import ij.io.FileSaver;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Reading a folder, labelling what is in it, and what happens to the rest of a
 * batch when one recording goes wrong.
 *
 * <h2>What is being guarded</h2>
 *
 * <p>A batch is where a defect a person would catch on one image goes unnoticed
 * across two hundred, because nobody looks at every row. Four of the ways that
 * happens are measured here: a pattern that quietly takes half a folder, a
 * recording that failed and left no trace of having failed, a run stopped part
 * way that reads afterwards as a smaller folder, and a batch that gave up
 * altogether because one file in it was a spreadsheet.
 *
 * <p>The recordings are small and real - textured pixels written to real TIFF
 * files and opened back off the disk by the code a person's folder goes through.
 * Ordering across worker counts is {@link BatchParallelTest}'s subject and is not
 * repeated here.
 *
 * @see RegDriftBatchRunner
 */
public class BatchRunnerTest {

    private static final int SIDE = 48;
    private static final int FRAMES = 6;

    /** The four words no string a person reads may carry, assembled from pieces. */
    private static final String[] FORBIDDEN = {
            "accu" + "racy",
            "b" + "est",
            "opti" + "mal",
            "on" + "ly",
    };

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private RegDriftBatchRunner.Bench realBatchBench;
    private File movies;

    @Before
    public void aFolderOfRecordings() throws IOException {
        realBatchBench = RegDriftBatchRunner.bench;
        movies = temp.newFolder("movies");
        writeRecording(new File(movies, "A1_t0.tif"), 0.0);
        writeRecording(new File(movies, "A1_t1.tif"), 0.4);
        writeRecording(new File(movies, "A2_t0.tif"), 0.8);
    }

    @After
    public void putTheRealMachineBack() {
        RegDriftBatchRunner.bench = realBatchBench;
    }

    // ------------------------------------ one bad file does not stop the run

    /**
     * A folder holding a file that is not an image, and a stack with no time
     * axis, finishes: each gets a row carrying a typed reason, and every other
     * recording is measured.
     *
     * <p>The row is the point. A batch that dropped the file would leave a folder
     * of two hundred looking like a folder of a hundred and ninety-eight, and
     * nobody counts.
     */
    @Test
    public void oneFileThatIsNotARecordingGetsATypedRowAndTheFolderCarriesOn() throws IOException {
        Files.write(new File(movies, "A3_notes.tif").toPath(),
                "this is a spreadsheet somebody renamed".getBytes(StandardCharsets.UTF_8));
        writeSingleFrame(new File(movies, "A4_snapshot.tif"));

        RegDriftBatchResult result = RegDriftBatch.run(request().build());

        assertNull(said(result), result.failure());
        assertEquals("every file the pattern matched has a row, whether it worked or not",
                5, result.rows().size());
        assertEquals("the three real recordings were measured", 3, result.finishedCount());
        assertEquals("and the two that are not recordings did not stop them", 2,
                result.failedCount());

        RegDriftBatchResult.MovieRow notAnImage = row(result, "A3_notes.tif");
        assertEquals(Failure.Kind.IMAGE_UNREADABLE, notAnImage.failure().kind());
        assertTrue("the reason names the file: " + notAnImage.failure().message(),
                notAnImage.failure().message().contains("A3_notes.tif"));
        assertTrue("and says the rest of the folder was still worked through: "
                        + notAnImage.failure().message(),
                notAnImage.failure().message().contains("rest of the folder"));

        RegDriftBatchResult.MovieRow oneFrame = row(result, "A4_snapshot.tif");
        assertEquals(Failure.Kind.NO_TIME_AXIS, oneFrame.failure().kind());
        assertTrue("a stack with no time axis says so in words: "
                + oneFrame.failure().message(), oneFrame.failure().message().contains("frames"));

        assertEquals("and the status column carries the kind a script can branch on",
                "image_unreadable",
                notAnImage.cells().get(RegDriftBatchResult.COLUMNS.indexOf("status")));
        assertEquals("ok", row(result, "A1_t0.tif").cells()
                .get(RegDriftBatchResult.COLUMNS.indexOf("status")));
    }

    /**
     * A row that failed still says which recording it is about and where it sat,
     * so a two-hundred-row file can be read back against the folder.
     */
    @Test
    public void aFailedRowStillCarriesItsPlaceItsLabelAndItsReason() throws IOException {
        Files.write(new File(movies, "A3_notes.tif").toPath(),
                "not an image".getBytes(StandardCharsets.UTF_8));

        RegDriftBatchResult result =
                RegDriftBatch.run(request().pattern("^([A-Z]\\d+)_.*\\.tif$").build());
        RegDriftBatchResult.MovieRow failed = row(result, "A3_notes.tif");
        List<String> cells = failed.cells();

        assertEquals("4", cells.get(RegDriftBatchResult.COLUMNS.indexOf("index")));
        assertEquals("A3", cells.get(RegDriftBatchResult.COLUMNS.indexOf("group")));
        assertEquals("A3_notes.tif", cells.get(RegDriftBatchResult.COLUMNS.indexOf("file")));
        assertEquals(Mode.DIAGNOSE.macroValue(),
                cells.get(RegDriftBatchResult.COLUMNS.indexOf("mode")));
        assertFalse("and the detail column is the sentence, not an empty cell",
                cells.get(RegDriftBatchResult.COLUMNS.indexOf("detail")).isEmpty());
    }

    /**
     * <b>A batch row and the same recording measured on its own carry the same
     * numbers.</b>
     *
     * <p>The rule this whole type rests on: a batch adds no analysis. If it grew
     * its own version of a figure, the folder and the single recording would
     * disagree sooner or later and nobody would know which of the two was right -
     * and in a folder of two hundred nobody would notice at all. So the same
     * recording is measured twice, once through the folder and once through the
     * facade a person's single stack goes through, and every measured column is
     * compared.
     */
    @Test
    public void aBatchRowCarriesTheSameNumbersAsTheSameRecordingMeasuredOnItsOwn() {
        RegDriftBatchResult batch = RegDriftBatch.run(request().build());
        assertNull(said(batch), batch.failure());
        RegDriftBatchResult.MovieRow throughTheFolder = row(batch, "A1_t0.tif");
        assertTrue("the folder measured it", throughTheFolder.isSuccess());

        ImagePlus onItsOwn = new ij.io.Opener()
                .openImage(new File(movies, "A1_t0.tif").getAbsolutePath());
        assertNotNull("the fixture has to open on its own too", onItsOwn);
        onItsOwn.setTitle("A1_t0.tif");
        RegDriftResult single = RegDrift.run(RegDriftParameters.builder(onItsOwn)
                .mode(Mode.DIAGNOSE)
                .hideDisplay(true)
                .serial(true)
                .build());
        assertNull(single.failure() == null ? "" : single.failure().message(), single.failure());

        RegDriftBatchResult.MovieRow onItsOwnAsARow = RegDriftBatchResult.MovieRow.of(
                throughTheFolder.index(), new File(movies, "A1_t0.tif"), "A1_t0.tif",
                throughTheFolder.group(), single);

        for (String column : Arrays.asList("verdict", "motion_label", "motion_dominant",
                "severity", "channel", "measured_at_bin", "recommended_engine", "calibration",
                "expected_error_px", "expected_seconds", "top_engine", "sd_vs_control",
                "detail")) {
            int at = RegDriftBatchResult.COLUMNS.indexOf(column);
            assertEquals("column '" + column + "' says one thing in a batch row and another"
                            + " for the same recording measured on its own, so a number in this"
                            + " plugin depends on how it was asked for",
                    onItsOwnAsARow.cells().get(at), throughTheFolder.cells().get(at));
        }
        assertFalse("and the columns being compared are not all empty",
                throughTheFolder.cells().get(RegDriftBatchResult.COLUMNS.indexOf("motion_label"))
                        .isEmpty());
    }

    // ------------------------------------------------- the preview is the run

    /**
     * The group preview shows the grouping the run then uses, over three
     * patterns - one that labels by well, one that labels nothing, and one that
     * matches no file at all.
     *
     * <p>The pattern is the setting of a batch somebody is most likely to get
     * wrong, and getting it wrong is silent. So the preview is not a second
     * reading of the folder that agrees most of the time: it is the same call,
     * and this asserts that what it showed is what happened.
     */
    @Test
    public void theGroupPreviewMatchesTheGroupingTheRunUses() {
        assertGroupingMatchesItsPreview("^([A-Z]\\d+)_.*\\.tif$",
                Arrays.asList("A1", "A1", "A2"));
        assertGroupingMatchesItsPreview(RegDriftBatchParameters.DEFAULT_PATTERN,
                Arrays.asList(RegDriftBatchParameters.UNGROUPED_LABEL,
                        RegDriftBatchParameters.UNGROUPED_LABEL,
                        RegDriftBatchParameters.UNGROUPED_LABEL));

        RegDriftBatchParameters nothing = request()
                .pattern("^(Z\\d+)_no-such-recording\\.tif$").build();
        String preview = RegDriftBatch.preview(nothing);
        assertTrue("a pattern that matches nothing says so rather than showing an empty list: "
                + preview, preview.contains("No file here matches this pattern"));
        assertTrue("and says how many files it did read and reject: " + preview,
                preview.contains("3 files were read"));
        assertTrue("naming every file it left alone, so the mistake is visible: " + preview,
                preview.contains("A1_t0.tif") && preview.contains("A2_t0.tif"));
        assertTrue("the reading behind that preview found no recording",
                RegDriftBatchRunner.scan(nothing).movies().isEmpty());

        RegDriftBatchResult result = RegDriftBatch.run(nothing);
        assertNotNull("and running it is refused with a typed reason rather than producing an"
                + " empty table a script would read as a folder of nothing", result.failure());
        assertEquals(Failure.Kind.INVALID_PARAMETERS, result.failure().kind());
        assertTrue("naming the pattern that took nothing: " + result.failure().message(),
                result.failure().message().contains("^(Z\\d+)_no-such-recording\\.tif$"));
    }

    private void assertGroupingMatchesItsPreview(String pattern, List<String> expectedLabels) {
        RegDriftBatchParameters parameters = request().pattern(pattern).build();
        Map<String, List<File>> previewed = RegDriftBatchRunner.scan(parameters).groups();
        String preview = RegDriftBatch.preview(parameters);
        for (Map.Entry<String, List<File>> group : previewed.entrySet()) {
            assertTrue("the preview names the group '" + group.getKey() + "': " + preview,
                    preview.contains(group.getKey()));
            for (File file : group.getValue()) {
                assertTrue("and the recordings under it: " + preview,
                        preview.contains(file.getName()));
            }
        }

        RegDriftBatchResult result = RegDriftBatch.run(parameters);
        assertNull(said(result), result.failure());
        assertEquals("the grouping the run reports is the one the preview showed",
                previewed.keySet(), result.groups().keySet());
        List<String> labels = new ArrayList<String>();
        for (RegDriftBatchResult.MovieRow movie : result.rows()) labels.add(movie.group());
        assertEquals("and every row carries the label the preview gave its file, under '"
                + pattern + "'", expectedLabels, labels);
    }

    /**
     * A preview of a real recursive folder names enough files to recognise what
     * is being taken, counts the rest, and tells same-named recordings apart.
     *
     * <p>Both halves came from running this over a working library folder: twelve
     * recordings sat beside 12,583 files the pattern did not match, and naming
     * every one of them produced six hundred kilobytes of text - which is not a
     * preview, it is the folder again. The twelve recordings were all called
     * {@code original.tif}, so listing them by filename printed the same word
     * twelve times.
     */
    @Test
    public void aPreviewOfADeepFolderNamesEnoughToRecogniseAndCountsTheRest() throws IOException {
        File deep = temp.newFolder("library");
        for (int plate = 1; plate <= 12; plate++) {
            File entry = new File(deep, String.format(Locale.ROOT, "plate-%02d", plate));
            assertTrue(entry.mkdirs());
            assertTrue(new File(entry, "original.tif").createNewFile());
            assertTrue(new File(entry, "kymograph.tif").createNewFile());
            assertTrue(new File(entry, "shifts.csv").createNewFile());
        }

        RegDriftBatchParameters parameters = RegDriftBatchParameters.builder(deep)
                .pattern("(?i)^original[.]tif$").recursive(true).mode(Mode.DIAGNOSE).build();
        assertEquals("the fixture holds twelve recordings", 12,
                RegDriftBatchRunner.scan(parameters).movies().size());
        assertEquals("beside twenty-four files the pattern does not match", 24,
                RegDriftBatchRunner.scan(parameters).skipped().size());

        String preview = RegDriftBatch.preview(parameters);
        assertTrue("a recording is named by where it sits, so twelve files all called"
                        + " original.tif can be told apart: " + preview,
                preview.contains("plate-01/original.tif")
                        && preview.contains("plate-02/original.tif"));
        assertTrue("the recordings it did not name are counted: " + preview,
                preview.contains("and 2 more"));
        assertTrue("and so are the files it is leaving alone: " + preview,
                preview.contains("and 14 more"));
        assertTrue("a preview is short enough to read: " + preview.length() + " characters",
                preview.length() < 2000);
    }

    // ------------------------------------------------------- sub-folders

    /**
     * Reading sub-folders is a choice, and both answers are the one that was
     * asked for.
     */
    @Test
    public void aRecursiveScanFindsSubFolderRecordingsAndAPlainOneDoesNot() throws IOException {
        File deeper = new File(movies, "plate-2");
        assertTrue("the fixture sub-folder could not be made", deeper.mkdirs());
        writeRecording(new File(deeper, "A9_t0.tif"), 1.2);

        RegDriftBatchResult here = RegDriftBatch.run(request().recursive(false).build());
        assertEquals("the folder somebody chose is the folder they meant", 3, here.rows().size());
        assertNull("nothing under it was read", find(here, "A9_t0.tif"));

        RegDriftBatchResult everywhere = RegDriftBatch.run(request().recursive(true).build());
        assertEquals("turned on, the folders under it are read too", 4, everywhere.rows().size());
        RegDriftBatchResult.MovieRow deep = row(everywhere, "A9_t0.tif");
        assertEquals("and a row says which sub-folder its recording came from",
                "plate-2/A9_t0.tif", deep.relativePath());
        assertTrue("the recording in the sub-folder was measured, not just listed: "
                + (deep.isSuccess() ? "" : deep.failure().message()), deep.isSuccess());
    }

    // ------------------------------------------------------- what is written

    /**
     * A batch given a folder to write into writes one line per recording plus the
     * folder's own line, a README beside it saying what those lines are, and each
     * recording's own tables written the way a single recording's are.
     */
    @Test
    public void theWrittenBatchIsOneLinePerRecordingPlusTheFolderAndAReadmeBesideIt()
            throws IOException {
        File saveRoot = temp.newFolder("results");
        RegDriftBatchResult result = RegDriftBatch.run(request().saveRoot(saveRoot).build());

        assertNull(said(result), result.failure());
        File tree = new File(saveRoot, RegDriftAutoSave.TREE_FOLDER);
        File batch = new File(tree, RegDriftAutoSave.BATCH_FOLDER);
        File summary = new File(batch, RegDriftAutoSave.SUMMARY_FILE);
        File readme = new File(batch, RegDriftAutoSave.README_FILE);

        assertTrue("the batch folder holds its own summary", summary.isFile());
        assertTrue("and its own README", readme.isFile());
        assertTrue("the tree's own README is beside them",
                new File(tree, RegDriftAutoSave.README_FILE).isFile());

        List<String> lines = Files.readAllLines(summary.toPath(), StandardCharsets.UTF_8);
        assertEquals("a header, three recordings and the folder's own line", 5, lines.size());
        assertTrue("the header starts with when the batch ran",
                lines.get(0).startsWith("run_utc,"));
        assertTrue("and the last line is the folder's, not a recording's: " + lines.get(4),
                lines.get(4).contains(RegDriftBatchResult.AGGREGATE_LABEL));

        String readmeText = read(readme);
        assertTrue("the README says which pattern was read: " + readmeText,
                readmeText.contains(RegDriftBatchParameters.DEFAULT_PATTERN));
        assertTrue("how many recordings at a time, and why",
                readmeText.contains("at a time."));
        assertTrue("and that the rows do not depend on which recording finished first",
                readmeText.contains("the recordings finished"));

        assertTrue("each recording's own tables are written the same way a single recording's"
                        + " are, so no number in a batch row was measured anywhere else",
                new File(new File(tree, RegDriftAutoSave.DIAGNOSIS_FOLDER),
                        "A1_t0_diagnosis.csv").isFile());
        assertEquals("and every row says its writing worked", "ok",
                row(result, "A1_t0.tif").saved());
    }

    /**
     * A batch whose files would not fit in a path this system accepts says so,
     * naming the path and its length, before a single recording is opened.
     *
     * <p>Windows stops at 259 characters, and a recursive scan inside a
     * synchronized folder reaches that without anybody noticing. Found out on the
     * way in rather than on the way out: the alternative is an hour of
     * measurement followed by a folder that could not be written, reported as an
     * IO exception nobody can act on.
     */
    @Test
    public void aFolderTooDeepForThisSystemIsRefusedByNameBeforeAnythingIsMeasured() {
        int limit = RegDriftAutoSave.pathLimit();
        Assume.assumeTrue("this system enforces no path limit this plugin has to work around",
                limit > 0);

        StringBuilder deep = new StringBuilder(temp.getRoot().getAbsolutePath());
        while (deep.length() < limit - 60) {
            deep.append(File.separatorChar).append("a-folder-with-a-long-name");
        }
        File tooDeep = new File(deep.toString());

        RegDriftBatchResult result = RegDriftBatch.run(request().saveRoot(tooDeep).build());

        assertNotNull("a batch that could not write a single one of its recordings is refused"
                + " before it measures two hundred", result.failure());
        assertEquals(Failure.Kind.PATH_TOO_LONG, result.failure().kind());
        String message = result.failure().message();
        assertTrue("the reason names the path: " + message, message.contains(tooDeep.getName()));
        assertTrue("and its length against the limit: " + message,
                message.contains(String.valueOf(limit)));
        assertTrue("and says what to do about it: " + message,
                message.contains("closer to the drive root"));
        assertTrue("nothing was written under a folder that has no room for it",
                !new File(tooDeep, RegDriftAutoSave.TREE_FOLDER).exists());
    }

    // -------------------------------------------------------------- stopping

    /**
     * A batch stopped part way keeps the rows it already has, gives every
     * recording it never reached a row of its own saying so, and leaves no
     * threads behind.
     *
     * <p>The rows for the recordings never reached are what stops a folder of six
     * that stopped at four from reading afterwards as a folder of four.
     */
    @Test
    public void stoppingABatchKeepsWhatItHasSaysWhatItMissedAndLeavesNoThreadsBehind()
            throws IOException {
        writeRecording(new File(movies, "A5_t0.tif"), 1.6);
        writeRecording(new File(movies, "A6_t0.tif"), 2.0);
        writeRecording(new File(movies, "A7_t0.tif"), 2.4);

        final Cancellation.Flag stop = Cancellation.flag();
        RegDriftBatchRunner.bench = new RegDriftBatchRunner.Bench() {
            @Override
            public RegDriftResult measure(RegDriftParameters parameters) {
                // Pulled once the first recording has a measurement, so that what is asserted
                // below is a batch stopped part way rather than one stopped before it started.
                RegDriftResult produced = RegDrift.run(parameters);
                stop.cancel();
                return produced;
            }

            @Override
            public long memoryBudget() {
                return 0L;
            }
        };
        int threadsBefore = liveBatchThreads();

        RegDriftBatchResult result = RegDriftBatch.run(
                request().movieWorkers(2).cancellation(stop).build());

        assertNull(said(result), result.failure());
        assertTrue("a batch that was stopped says so", result.stopped());
        assertEquals("every recording the folder held still has a row", 6, result.rows().size());
        assertTrue("the recordings already under way kept their measurements",
                result.finishedCount() >= 1);
        assertTrue("and the ones never reached have rows of their own",
                result.failedCount() >= 1);

        boolean sawOneNeverReached = false;
        for (RegDriftBatchResult.MovieRow movie : result.rows()) {
            if (movie.isSuccess()) continue;
            assertEquals("a recording a stopped batch did not finish says that is why, rather"
                            + " than carrying some other reason: " + movie.failure().message(),
                    Failure.Kind.CANCELED, movie.failure().kind());
            sawOneNeverReached |= movie.failure().message().contains("was not opened");
        }
        assertTrue("a recording the batch never reached says it was never opened, which is what"
                + " stops a folder of six that stopped at four from reading afterwards as a"
                + " folder of four", sawOneNeverReached);
        assertEquals("and the pool it owned is shut down before it returns",
                threadsBefore, liveBatchThreadsOnceSettled(threadsBefore));
    }

    /**
     * A recording the batch never reached carries the same sentence whether the
     * folder was being worked through one at a time or several at a time.
     *
     * <p>The stopped path is the one place the two ways through a folder could
     * quietly say different things, because they refuse a recording in different
     * code. A batch already stopped before it starts reaches every recording the
     * same way in both, so the rows can be compared cell for cell rather than
     * left to whichever recordings a fast machine happened to finish.
     */
    @Test
    public void aStoppedBatchSaysTheSameThingWhicheverWayTheFolderWasBeingWorkedThrough() {
        Cancellation.Flag pulledAlready = Cancellation.flag();
        pulledAlready.cancel();

        RegDriftBatchResult oneAtATime = RegDriftBatch.run(
                request().movieWorkers(1).cancellation(pulledAlready).build());
        RegDriftBatchResult severalAtOnce = RegDriftBatch.run(
                request().movieWorkers(3).cancellation(pulledAlready).build());

        assertNull(said(oneAtATime), oneAtATime.failure());
        assertNull(said(severalAtOnce), severalAtOnce.failure());
        assertEquals("no recording was measured either way", 0, oneAtATime.finishedCount());
        assertEquals(0, severalAtOnce.finishedCount());
        assertEquals("and every recording still has a row saying why", 3,
                severalAtOnce.rows().size());
        assertEquals("the same rows, word for word",
                oneAtATime.summaryLines(), severalAtOnce.summaryLines());
        assertTrue("naming the recording that was never opened: "
                        + severalAtOnce.rows().get(0).failure().message(),
                severalAtOnce.rows().get(0).failure().message().contains("A1_t0.tif")
                        && severalAtOnce.rows().get(0).failure().message()
                        .contains("was not opened"));
    }

    // --------------------------------------------------------------- memory

    /**
     * A batch over recordings larger than the heap allows runs with fewer workers
     * rather than failing - defect D9's clamp, driven through a real run.
     *
     * <p>Driven with a stated budget rather than a real shortage, because a
     * machine with too little memory to hold two of these recordings is not
     * something a test can arrange. What is being asserted is that the clamp is
     * on the path a real batch takes, and that a clamped batch produces the same
     * rows as an unclamped one rather than a shorter table.
     */
    @Test
    public void aBatchTooLargeForTheHeapRunsWithFewerWorkersRatherThanFailing() {
        RegDriftBatchResult roomy = RegDriftBatch.run(request().movieWorkers(3).build());

        RegDriftBatchRunner.bench = new RegDriftBatchRunner.Bench() {
            @Override
            public RegDriftResult measure(RegDriftParameters parameters) {
                return RegDrift.run(parameters);
            }

            @Override
            public long memoryBudget() {
                return 1L;                      // one byte: room for a single recording at most
            }
        };
        RegDriftBatchResult cramped = RegDriftBatch.run(request().movieWorkers(3).build());

        assertNull(said(roomy), roomy.failure());
        assertNull(said(cramped), cramped.failure());
        assertEquals("three recordings and room for them", 3, roomy.movieWorkers());
        assertEquals("no room for three, so one at a time", 1, cramped.movieWorkers());
        assertEquals("and the folder still finished", 3, cramped.finishedCount());
        assertEquals("with the same rows a roomy machine produced",
                roomy.summaryLines(), cramped.summaryLines());
    }

    /**
     * The worker budget does not overflow into a negative number and force a
     * batch serial without a word to anybody - defect D9, at the batch level.
     */
    @Test
    public void aGenerousMemoryBudgetDoesNotOverflowIntoASilentlySerialBatch() {
        assertEquals("a budget large enough to have overflowed an int still gives the workers"
                        + " that were asked for", 8,
                RegDriftBatchRunner.movieWorkersFor(Mode.DIAGNOSE, 8, 40, 1L, Long.MAX_VALUE));
        assertEquals("a recording that needs more than the whole budget leaves one worker", 1,
                RegDriftBatchRunner.movieWorkersFor(Mode.DIAGNOSE, 8, 40, 1L << 40, 1024L));
        assertEquals("and never more workers than there are recordings", 3,
                RegDriftBatchRunner.movieWorkersFor(Mode.DIAGNOSE, 8, 3, 0L, 0L));
    }

    /** The memory one recording is budgeted at is a floor, not a guess at the file. */
    @Test
    public void oneRecordingIsBudgetedAtSeveralTimesItsFileSize() {
        List<File> files = Arrays.asList(new File(movies, "A1_t0.tif"),
                new File(movies, "A1_t1.tif"));
        long largest = Math.max(files.get(0).length(), files.get(1).length());
        assertTrue("the fixture recordings have to exist for this to measure anything",
                largest > 0);
        assertEquals(largest * RegDriftBatchRunner.MEMORY_PER_MOVIE_FACTOR,
                RegDriftBatchRunner.memoryPerMovie(files));
    }

    // ---------------------------------------------------- what a folder cannot do

    /**
     * Scoring needs two stacks per recording, which a folder and one pattern
     * cannot say, so it is refused as its own sentence rather than failing once
     * per recording two hundred times.
     */
    @Test
    public void aFolderCannotBeScoredAndSaysSoOnceRatherThanTwoHundredTimes() {
        RegDriftBatchResult result = RegDriftBatch.run(request().mode(Mode.SCORE).build());

        assertNotNull(result.failure());
        assertEquals(Failure.Kind.INVALID_PARAMETERS, result.failure().kind());
        assertTrue("it names the modes a folder can run: " + result.failure().message(),
                result.failure().message().contains(Mode.COMPARE.macroValue())
                        && result.failure().message().contains(Mode.DIAGNOSE.macroValue()));
        assertTrue("and nothing was run", result.rows().isEmpty());
    }

    /** A folder that is not there is a sentence, not an exception. */
    @Test
    public void aFolderThisComputerCannotReadIsATypedReason() {
        RegDriftBatchResult result = RegDriftBatch.run(
                RegDriftBatchParameters.builder(new File(movies, "no-such-folder")).build());

        assertNotNull(result.failure());
        assertEquals(Failure.Kind.INVALID_PARAMETERS, result.failure().kind());
        assertTrue("naming the folder: " + result.failure().message(),
                result.failure().message().contains("no-such-folder"));
    }

    // ------------------------------------------------------------- the words

    /**
     * Nothing a folder run puts in front of somebody uses a word the house rules
     * forbid.
     *
     * <p>{@code DialogWordingTest} scans the dialogs; a batch writes most of its
     * text from the runner and the summary instead, and those strings reach a
     * person through the preview box, the aggregate row and the README. So they
     * are checked as the strings they actually come out as, rather than by
     * widening a scan over source files whose comments are not read by anybody.
     */
    @Test
    public void nothingAFolderRunSaysUsesAWordTheHouseRulesForbid() throws IOException {
        File saveRoot = temp.newFolder("worded");
        RegDriftBatchParameters parameters = request().saveRoot(saveRoot).build();

        List<String> said = new ArrayList<String>();
        said.add(RegDriftBatch.preview(parameters));
        said.add(RegDriftBatch.preview(request().pattern("^no-such-file$").build()));
        for (Mode mode : Mode.values()) {
            said.add(RegDriftBatchRunner.movieWorkerNoteFor(mode));
        }
        said.add(RegDriftBatchRunner.movieWorkerNoteFor(null));

        RegDriftBatchResult result = RegDriftBatch.run(parameters);
        assertNull(said(result), result.failure());
        for (List<String> line : result.summaryLines()) said.addAll(line);
        said.add(read(new File(new File(saveRoot, RegDriftAutoSave.TREE_FOLDER),
                RegDriftAutoSave.BATCH_FOLDER + File.separator + RegDriftAutoSave.README_FILE)));

        for (String text : said) {
            String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
            for (String word : FORBIDDEN) {
                assertFalse("a string a person reads uses a word the house rules forbid, '"
                                + word + "': " + text,
                        Pattern.compile("\\b" + word + "\\b").matcher(lower).find());
            }
        }
        assertTrue("and the scan is looking at strings that really carry words",
                said.size() > 10);
    }

    // ---------------------------------------------------------------- helpers

    private RegDriftBatchParameters.Builder request() {
        return RegDriftBatchParameters.builder(movies).mode(Mode.DIAGNOSE);
    }

    private static String said(RegDriftBatchResult result) {
        return result.failure() == null ? "" : result.failure().message();
    }

    private static RegDriftBatchResult.MovieRow row(RegDriftBatchResult result, String name) {
        RegDriftBatchResult.MovieRow found = find(result, name);
        assertNotNull("no row for '" + name + "' in " + result.rows(), found);
        return found;
    }

    private static RegDriftBatchResult.MovieRow find(RegDriftBatchResult result, String name) {
        for (RegDriftBatchResult.MovieRow movie : result.rows()) {
            if (movie.file() != null && name.equals(movie.file().getName())) return movie;
        }
        return null;
    }

    private static String read(File file) throws IOException {
        assertTrue("expected a file at " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** How many of this batch runner's worker threads are alive right now. */
    private static int liveBatchThreads() {
        int alive = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("regdrift-batch-movie")) alive++;
        }
        return alive;
    }

    /**
     * The same, once the pool has had a bounded moment to finish shutting down.
     *
     * <p>A thread that has been asked to stop is not always off the list the
     * instant its pool returns, and a test that read the list once would fail on
     * a busy machine over something that is not a leak.
     */
    private static int liveBatchThreadsOnceSettled(int expected) {
        int alive = liveBatchThreads();
        for (int attempt = 0; attempt < 40 && alive > expected; attempt++) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                break;
            }
            alive = liveBatchThreads();
        }
        return alive;
    }

    // ------------------------------------------------------ the recordings

    /** A small drifting recording written to disk as a real TIFF stack. */
    private static void writeRecording(File file, double extra) {
        int field = SIDE + 32;
        float[] world = texture(field, field, 91L + (long) (extra * 100));
        ImageStack images = new ImageStack(SIDE, SIDE);
        for (int t = 0; t < FRAMES; t++) {
            images.addSlice("t" + (t + 1), new FloatProcessor(SIDE, SIDE,
                    sampled(world, field, field, 16 + (0.7 + extra) * t, 16 - 0.4 * t, SIDE),
                    null));
        }
        assertTrue("the fixture recording could not be written to " + file.getAbsolutePath(),
                new FileSaver(new ImagePlus(file.getName(), images))
                        .saveAsTiffStack(file.getAbsolutePath()));
    }

    /** One frame: an image with no time axis to measure. */
    private static void writeSingleFrame(File file) {
        float[] world = texture(SIDE, SIDE, 7L);
        ImagePlus snapshot = new ImagePlus(file.getName(),
                new FloatProcessor(SIDE, SIDE, world, null));
        assertTrue("the fixture snapshot could not be written",
                new FileSaver(snapshot).saveAsTiff(file.getAbsolutePath()));
    }

    /** Fine-grained texture with structure at every scale, from a fixed seed. */
    private static float[] texture(int w, int h, long seed) {
        java.util.Random random = new java.util.Random(seed);
        float[] plane = new float[w * h];
        for (int i = 0; i < plane.length; i++) {
            plane[i] = 400f + 120f * (float) random.nextGaussian();
        }
        for (int pass = 0; pass < 3; pass++) {
            float[] smoothed = new float[plane.length];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double sum = 0;
                    int counted = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                            sum += plane[ny * w + nx];
                            counted++;
                        }
                    }
                    smoothed[y * w + x] = (float) (sum / counted);
                }
            }
            plane = smoothed;
        }
        return plane;
    }

    /** A window of the field, sampled bilinearly at a fractional offset. */
    private static float[] sampled(float[] field, int fieldWidth, int fieldHeight,
                                   double ox, double oy, int side) {
        float[] out = new float[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                double fx = ox + x;
                double fy = oy + y;
                int x0 = (int) Math.floor(fx);
                int y0 = (int) Math.floor(fy);
                double ax = fx - x0;
                double ay = fy - y0;
                out[y * side + x] = (float) (
                        at(field, fieldWidth, fieldHeight, x0, y0) * (1 - ax) * (1 - ay)
                                + at(field, fieldWidth, fieldHeight, x0 + 1, y0) * ax * (1 - ay)
                                + at(field, fieldWidth, fieldHeight, x0, y0 + 1) * (1 - ax) * ay
                                + at(field, fieldWidth, fieldHeight, x0 + 1, y0 + 1) * ax * ay);
            }
        }
        return out;
    }

    private static double at(float[] field, int w, int h, int x, int y) {
        int cx = Math.max(0, Math.min(w - 1, x));
        int cy = Math.max(0, Math.min(h - 1, y));
        return field[cy * w + cx];
    }
}
