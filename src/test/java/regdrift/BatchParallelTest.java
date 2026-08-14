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
import ij.WindowManager;
import ij.io.FileSaver;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineFixtures;
import regdrift.autofix.EngineId;
import regdrift.harness.EngineDescriptor;
import regdrift.harness.EngineRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The claim a folder of two hundred recordings rests on: <b>a batch run one
 * recording at a time and the same batch run several at a time produce the same
 * rows, in the same order, with the same numbers in them.</b>
 *
 * <h2>Why this is the test the stage exists for</h2>
 *
 * <p>Nobody reads every row of a folder of two hundred. A single recording that
 * came out in the wrong place would be spotted in a second; two hundred rows in
 * an order that depends on which recording happened to finish first look exactly
 * like two hundred rows in the right order. So the ordering is not left to be
 * noticed - it is measured, against a run whose recordings are <em>forced</em> to
 * finish backwards.
 *
 * <p>The everyday version: a row of numbered pigeonholes. Several people work at
 * once, each drops its answer into its own hole, and the coordinator reads the
 * holes left to right afterwards. Who finished first never reaches the paper.
 *
 * <h2>What is real here and what is invented</h2>
 *
 * <p>The recordings are real work - textured pixels drifting by a stated
 * fraction of a pixel per frame, written to real TIFF files and opened back off
 * the disk by the same code a person's folder goes through. The <em>computer</em>
 * is invented, through {@link RegDrift#bench}: which engines a machine has is a
 * fact about somebody else's install, and a determinism test that changed its
 * answer with the machine it ran on would be measuring the wrong thing.
 *
 * <p>{@link RegDriftBatchRunner#bench} is replaced as well, but only to
 * <em>watch</em>: every wrapper below hands the settings it was given straight to
 * {@link RegDrift#run} and hands back what came out. The numbers being compared
 * are the ones the real path produced.
 *
 * @see RegDriftBatchRunner
 * @see BatchRunnerTest
 */
public class BatchParallelTest {

    /** Wide enough to carry structure at more than one scale, small enough to be quick. */
    private static final int SIDE = 64;

    private static final int FRAMES = 12;

    /**
     * Deliberately not whole numbers: a whole-pixel drift takes the block-copy
     * path and leaves the control an identity warp, which is refused by name
     * (defect D11).
     */
    private static final double STEP_X = 0.73;
    private static final double STEP_Y = -0.41;

    /** Long enough that a stuck latch fails the test rather than hanging the build. */
    private static final long PATIENCE_MILLIS = 30_000L;

    /** The engines this invented computer has. TurboReg is here because StackReg needs it. */
    private static final Set<EngineId> HERE = EnumSet.of(EngineId.TURBOREG, EngineId.STACKREG,
            EngineId.MULTISTACKREG);

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private RegDrift.Bench realBench;
    private RegDriftBatchRunner.Bench realBatchBench;
    private File movies;

    @Before
    public void putAnInventedComputerInFrontAndAFolderOfRecordingsOnTheDisk() throws IOException {
        realBench = RegDrift.bench;
        realBatchBench = RegDriftBatchRunner.bench;
        RegDrift.bench = inventedComputer();

        movies = temp.newFolder("movies");
        writeRecording(new File(movies, "A1_t0.tif"), 0.0, 0.0);
        writeRecording(new File(movies, "A1_t1.tif"), 0.5, 0.25);
        writeRecording(new File(movies, "A2_t0.tif"), 1.0, -0.5);
        writeRecording(new File(movies, "A2_t1.tif"), -0.75, 0.6);
        writeRecording(new File(movies, "B1_t0.tif"), 0.25, 1.1);
        writeRecording(new File(movies, "B1_t1.tif"), -1.2, -0.2);
    }

    @After
    public void putTheRealComputerBack() {
        RegDrift.bench = realBench;
        RegDriftBatchRunner.bench = realBatchBench;
    }

    // ------------------------------------------------------ the same rows

    /**
     * A serial batch and a parallel batch over the same folder produce identical
     * rows in identical order, the aggregate line included.
     *
     * <p>Cell for cell, over every column the summary file carries, so a figure
     * that came out differently because two recordings were measured beside each
     * other fails here rather than in somebody's results.
     */
    @Test
    public void serialAndParallelBatchesProduceIdenticalRowsInIdenticalOrder() {
        Watching serial = watching(1);
        RegDriftBatchRunner.bench = serial;
        RegDriftBatchResult oneAtATime = RegDriftBatch.run(request().movieWorkers(1).build());

        int expected = RegDriftBatchRunner.movieWorkersFor(Mode.DIAGNOSE_AND_RECOMMEND, 4, 6, 0L);
        Watching parallel = watching(expected);
        RegDriftBatchRunner.bench = parallel;
        RegDriftBatchResult severalAtOnce = RegDriftBatch.run(request().movieWorkers(4).build());

        assertNull(reason(oneAtATime), oneAtATime.failure());
        assertNull(reason(severalAtOnce), severalAtOnce.failure());
        assertEquals("one at a time means one worker", 1, oneAtATime.movieWorkers());
        assertTrue("and the other run has to have really been parallel, or this test compares"
                        + " a run against itself: " + severalAtOnce.movieWorkers(),
                severalAtOnce.movieWorkers() >= 2);
        assertTrue("with more than one recording genuinely inside the measurement at the same"
                        + " moment: " + parallel.mostAtOnce(), parallel.mostAtOnce() >= 2);
        assertEquals("and one at a time really is one at a time", 1, serial.mostAtOnce());

        assertEquals("every recording produced a measurement, or this compares two piles of"
                        + " failures: " + reasons(oneAtATime), 6, oneAtATime.finishedCount());
        assertEquals(6, severalAtOnce.finishedCount());

        assertEquals("one row per recording plus the folder's own line",
                7, oneAtATime.summaryLines().size());
        assertEquals(oneAtATime.summaryLines().size(), severalAtOnce.summaryLines().size());
        for (int line = 0; line < oneAtATime.summaryLines().size(); line++) {
            assertEquals("line " + line + " of the summary differs between a run one at a time"
                            + " and a run several at a time, so the output of this plugin depends"
                            + " on which recording finished first",
                    oneAtATime.summaryLines().get(line), severalAtOnce.summaryLines().get(line));
        }

        assertEquals("and the last line is the folder's own, in both",
                RegDriftBatchResult.AGGREGATE_LABEL,
                severalAtOnce.aggregateCells().get(RegDriftBatchResult.COLUMNS.indexOf("group")));
    }

    /**
     * The rows are in the order the folder was read even when the recordings are
     * forced to finish backwards.
     *
     * <p>The measurement the test above cannot make on its own: six recordings
     * measured beside each other will usually finish in roughly the order they
     * started, so a runner that merged by completion order would pass by luck.
     * Here every recording is held until the one after it has finished, so the
     * completion order is exactly the reverse of the reading order and the two
     * cannot be confused.
     */
    @Test
    public void theRowsAreInReadingOrderEvenWhenTheRecordingsFinishBackwards() throws Exception {
        int workers = RegDriftBatchRunner.movieWorkersFor(Mode.DIAGNOSE_AND_RECOMMEND, 6, 6, 0L);
        assertEquals("this measurement needs every recording in flight at once, so that holding"
                + " each one for the next cannot deadlock", 6, workers);

        Backwards backwards = new Backwards(namesInReadingOrder());
        RegDriftBatchRunner.bench = backwards;
        RegDriftBatchResult result = RegDriftBatch.run(request().movieWorkers(6).build());

        assertNull(reason(result), result.failure());
        assertEquals("the recordings finished in the reverse of the order they were read",
                reversed(namesInReadingOrder()), backwards.finishedOrder());
        assertEquals("and the rows came out in reading order all the same",
                namesInReadingOrder(), rowNames(result));
        for (int i = 0; i < result.rows().size(); i++) {
            assertEquals("a row's own index is where the folder put it, not where it finished",
                    i + 1, Integer.parseInt(result.rows().get(i).cells().get(0)));
        }
    }

    /**
     * The written file carries the same lines in the same order, whichever way
     * the folder was worked through.
     *
     * <p>The rows are what a script reads; the file is what a person keeps. Two
     * save folders rather than one, so neither run can be reading the other's
     * work, and the two columns that are <em>meant</em> to differ - when the
     * batch ran and where it was written - are taken out before the comparison.
     */
    @Test
    public void theWrittenSummaryIsTheSameFileWhicheverWayTheFolderWasWorkedThrough()
            throws IOException {
        File oneAtATimeRoot = temp.newFolder("written-serially");
        File severalAtOnceRoot = temp.newFolder("written-in-parallel");

        RegDriftBatchRunner.bench = watching(1);
        RegDriftBatchResult oneAtATime =
                RegDriftBatch.run(request().movieWorkers(1).saveRoot(oneAtATimeRoot).build());
        RegDriftBatchRunner.bench = watching(
                RegDriftBatchRunner.movieWorkersFor(Mode.DIAGNOSE_AND_RECOMMEND, 4, 6, 0L));
        RegDriftBatchResult severalAtOnce =
                RegDriftBatch.run(request().movieWorkers(4).saveRoot(severalAtOnceRoot).build());

        assertNull(reason(oneAtATime), oneAtATime.failure());
        assertNull(reason(severalAtOnce), severalAtOnce.failure());
        assertTrue("the second run has to have really been parallel",
                severalAtOnce.movieWorkers() >= 2);

        List<String> serialLines = summaryLines(oneAtATimeRoot);
        List<String> parallelLines = summaryLines(severalAtOnceRoot);
        assertEquals("a header, one line per recording and the folder's own line",
                8, serialLines.size());
        assertEquals(serialLines.size(), parallelLines.size());
        for (int line = 0; line < serialLines.size(); line++) {
            assertEquals("line " + line + " of the written summary differs between the two runs",
                    withoutWhenAndWhere(serialLines.get(line), oneAtATimeRoot),
                    withoutWhenAndWhere(parallelLines.get(line), severalAtOnceRoot));
        }
    }

    // ------------------------------------------------- serial inside, always

    /**
     * <b>Every recording inside a batch is measured with {@code serial} set.</b>
     *
     * <p>The never-nests rule, asserted where it can actually fail. A batch is
     * the outer parallel axis: it moves the work out one level rather than adding
     * one underneath, so a recording being measured inside a batch runs its frame
     * pairs on a single worker. That is one line in
     * {@link RegDriftBatchParameters#perMovie}, it is easy to omit, and omitting
     * it would oversubscribe the machine while making the worker budget
     * meaningless - which reads as a slow computer, not as a defect.
     *
     * <p>Asserted on the settings the runner really handed over rather than on
     * {@code perMovie} called by hand, because the second only says the line
     * exists somewhere.
     */
    @Test
    public void everyRecordingInsideABatchIsMeasuredSeriallyAndWithoutAWindow() {
        Watching serial = watching(1);
        RegDriftBatchRunner.bench = serial;
        RegDriftBatch.run(request().movieWorkers(1).build());

        int expected = RegDriftBatchRunner.movieWorkersFor(Mode.DIAGNOSE_AND_RECOMMEND, 4, 6, 0L);
        Watching parallel = watching(expected);
        RegDriftBatchRunner.bench = parallel;
        RegDriftBatch.run(request().movieWorkers(4).build());

        assertEquals("six recordings, six inner runs, one at a time", 6, serial.seen().size());
        assertEquals("and six again several at a time", 6, parallel.seen().size());
        for (RegDriftParameters inner : serial.seen()) {
            assertTrue("a batch run one at a time still hands every recording over serially:"
                    + " the rule is unconditional so that a serial batch and a parallel batch"
                    + " are the same run at two speeds", inner.serial());
        }
        for (RegDriftParameters inner : parallel.seen()) {
            assertTrue("a batch is the outer parallel axis and never nests, so the settings"
                    + " handed to RegDrift.run inside one carry serial", inner.serial());
            assertTrue("and no recording of a folder run opens a window, a table or a plot",
                    inner.hideDisplay());
        }
    }

    /**
     * Nothing a batch opens ever reaches ImageJ's list of images.
     *
     * <p>House rule 13 at its narrowest point. The recordings are opened by the
     * coordinator and handed to workers as objects; a worker that registered one
     * with {@code WindowManager} would be showing somebody two hundred windows
     * and racing the event dispatch thread while it did.
     */
    @Test
    public void nothingABatchOpensIsEverRegisteredWithImageJ() {
        int before = WindowManager.getImageCount();
        Watching watcher = watching(
                RegDriftBatchRunner.movieWorkersFor(Mode.DIAGNOSE_AND_RECOMMEND, 4, 6, 0L));
        RegDriftBatchRunner.bench = watcher;

        RegDriftBatchResult result = RegDriftBatch.run(request().movieWorkers(4).build());

        assertNull(reason(result), result.failure());
        assertEquals("a folder run leaves ImageJ's list of open images where it found it",
                before, WindowManager.getImageCount());
        for (String title : watcher.titlesSeen()) {
            assertNull("the recording '" + title + "' a worker was handed was never registered"
                    + " with ImageJ", WindowManager.getImage(title));
        }
    }

    // ---------------------------------------------------------- the fixture

    private RegDriftBatchParameters.Builder request() {
        return RegDriftBatchParameters.builder(movies)
                .pattern("(?i)^([A-Z]\\d+)_.*\\.tif$")
                .mode(Mode.DIAGNOSE_AND_RECOMMEND);
    }

    private List<String> namesInReadingOrder() {
        return Arrays.asList("A1_t0.tif", "A1_t1.tif", "A2_t0.tif", "A2_t1.tif",
                "B1_t0.tif", "B1_t1.tif");
    }

    private static List<String> rowNames(RegDriftBatchResult result) {
        List<String> names = new ArrayList<String>();
        for (RegDriftBatchResult.MovieRow row : result.rows()) names.add(row.file().getName());
        return names;
    }

    private static List<String> reversed(List<String> names) {
        List<String> backwards = new ArrayList<String>(names);
        Collections.reverse(backwards);
        return backwards;
    }

    private static String reason(RegDriftBatchResult result) {
        return result.failure() == null ? "" : result.failure().message();
    }

    private static String reasons(RegDriftBatchResult result) {
        StringBuilder said = new StringBuilder();
        for (RegDriftBatchResult.MovieRow row : result.rows()) {
            if (row.isSuccess()) continue;
            said.append('\n').append(row.file().getName()).append(": ")
                    .append(row.failure().message());
        }
        return said.toString();
    }

    /** The lines of the batch summary written under a save root. */
    private static List<String> summaryLines(File saveRoot) throws IOException {
        File batch = new File(new File(saveRoot, RegDriftAutoSave.TREE_FOLDER),
                RegDriftAutoSave.BATCH_FOLDER);
        File summary = new File(batch, RegDriftAutoSave.SUMMARY_FILE);
        assertTrue("a batch given a folder to write into writes its summary there: "
                + summary.getAbsolutePath(), summary.isFile());
        return Files.readAllLines(summary.toPath(), StandardCharsets.UTF_8);
    }

    /**
     * One line of a written summary with the two things that are meant to differ
     * taken out: the moment the batch ran, and the folder it was written into.
     */
    private static String withoutWhenAndWhere(String line, File saveRoot) {
        int firstComma = line.indexOf(',');
        String withoutStamp = firstComma < 0 ? line : line.substring(firstComma + 1);
        return withoutStamp.replace(saveRoot.getAbsolutePath(), "<the folder it was written into>");
    }

    // --------------------------------------------------------- the watchers

    private Watching watching(int expectedAtOnce) {
        return new Watching(Math.max(1, expectedAtOnce));
    }

    /**
     * A wrapper round the real measurement that records what it was handed and
     * how many recordings were inside it at once.
     *
     * <p>The overlap is forced rather than hoped for: each recording waits at the
     * door until as many have arrived as the batch has workers, so "these two
     * were measured at the same moment" is a fact about this run rather than
     * about the scheduler's mood. Recordings arriving after the door has opened
     * walk straight through, so a folder with more recordings than workers does
     * not stall.
     */
    private static final class Watching implements RegDriftBatchRunner.Bench {

        private final CountDownLatch door;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger mostAtOnce = new AtomicInteger();
        private final List<RegDriftParameters> seen =
                Collections.synchronizedList(new ArrayList<RegDriftParameters>());
        private final List<String> titles = Collections.synchronizedList(new ArrayList<String>());

        Watching(int expectedAtOnce) {
            this.door = new CountDownLatch(expectedAtOnce);
        }

        @Override
        public RegDriftResult measure(RegDriftParameters parameters) {
            seen.add(parameters);
            titles.add(parameters.image().getTitle());
            int now = inFlight.incrementAndGet();
            int previousMost;
            do {
                previousMost = mostAtOnce.get();
            } while (now > previousMost && !mostAtOnce.compareAndSet(previousMost, now));
            try {
                door.countDown();
                waitAtTheDoor(door);
                return RegDrift.run(parameters);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public long memoryBudget() {
            return 0L;
        }

        List<RegDriftParameters> seen() {
            return new ArrayList<RegDriftParameters>(seen);
        }

        List<String> titlesSeen() {
            return new ArrayList<String>(titles);
        }

        int mostAtOnce() {
            return mostAtOnce.get();
        }
    }

    /**
     * A wrapper that makes the recordings finish in exactly the reverse of the
     * order they were read.
     *
     * <p>Every recording is held until the one after it has finished; the last
     * one is held by nobody and goes first. Needs every recording in flight at
     * once, which is why the test that uses it asks for as many workers as there
     * are recordings and asserts it got them.
     */
    private static final class Backwards implements RegDriftBatchRunner.Bench {

        private final List<String> reading;
        private final List<CountDownLatch> finished = new ArrayList<CountDownLatch>();
        private final List<String> order = Collections.synchronizedList(new ArrayList<String>());

        Backwards(List<String> namesInReadingOrder) {
            this.reading = new ArrayList<String>(namesInReadingOrder);
            for (int i = 0; i < reading.size(); i++) finished.add(new CountDownLatch(1));
        }

        @Override
        public RegDriftResult measure(RegDriftParameters parameters) {
            String title = parameters.image().getTitle();
            int at = reading.indexOf(title);
            if (at < 0) throw new AssertionError("a recording this batch measured is not one the"
                    + " folder holds: " + title + " is not in " + reading);
            RegDriftResult produced = RegDrift.run(parameters);
            if (at + 1 < finished.size()) waitAtTheDoor(finished.get(at + 1));
            order.add(title);
            finished.get(at).countDown();
            return produced;
        }

        @Override
        public long memoryBudget() {
            return 0L;
        }

        List<String> finishedOrder() {
            return new ArrayList<String>(order);
        }
    }

    private static void waitAtTheDoor(CountDownLatch latch) {
        try {
            if (!latch.await(PATIENCE_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("the forced order did not reach its next step within "
                        + PATIENCE_MILLIS + " ms");
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while forcing the order of a batch");
        }
    }

    // ------------------------------------------------- the invented computer

    /**
     * A computer with a fixed set of engines on it.
     *
     * <p>Nothing is driven in this mode, so the driver is never reached; it is
     * here because a runner needs one. What matters is that the catalogue answers
     * the same way on every machine, so that two runs of the same folder can be
     * compared at all.
     */
    private RegDrift.Bench inventedComputer() {
        final AutofixService catalogue =
                EngineFixtures.serviceWherePresent(HERE.toArray(new EngineId[0]));
        final EngineRunner runner = new EngineRunner(new EngineRunner.Presence() {
            @Override
            public boolean installed(EngineId engine) {
                return HERE.contains(engine);
            }
        }, new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options) {
                throw new AssertionError("a folder run that only measures and ranks drove "
                        + engine.displayName());
            }
        });
        return new RegDrift.Bench() {
            @Override
            public AutofixService catalogue() {
                return catalogue;
            }

            @Override
            public EngineRunner runner() {
                return runner;
            }
        };
    }

    // ------------------------------------------------------ the recordings

    /** A drifting recording written to disk as a real TIFF stack. */
    private static void writeRecording(File file, double extraX, double extraY) {
        int field = SIDE + 64;
        float[] world = texture(field, field, 4242L + (long) (extraX * 1000));
        ImageStack images = new ImageStack(SIDE, SIDE);
        for (int t = 0; t < FRAMES; t++) {
            float[] plane = sampled(world, field, field,
                    32 + (STEP_X + extraX) * t, 32 + (STEP_Y + extraY) * t, SIDE);
            images.addSlice("t" + (t + 1), new FloatProcessor(SIDE, SIDE, plane, null));
        }
        ImagePlus recording = new ImagePlus(file.getName(), images);
        assertTrue("the fixture recording could not be written to " + file.getAbsolutePath(),
                new FileSaver(recording).saveAsTiffStack(file.getAbsolutePath()));
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
