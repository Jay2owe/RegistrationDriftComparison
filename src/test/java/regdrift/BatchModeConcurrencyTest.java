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
import ij.macro.Interpreter;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import regdrift.autofix.EngineId;
import regdrift.harness.ArmResult;
import regdrift.harness.ArmStatus;
import regdrift.harness.EngineDescriptor;
import regdrift.harness.EngineRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The measurement that decides how many movies a batch runs at once: what
 * happens when two recordings drive registration engines at the same time.
 *
 * <h2>The question, and why it had to be measured rather than argued</h2>
 *
 * <p>A batch is parallel over movies. In the two modes that drive engines, that
 * would mean two or more movies inside {@link EngineRunner} at the same moment,
 * and an arm turns ImageJ's <b>batch mode</b> on so an engine can find its input
 * without a window being built. Batch mode is
 * {@code public static boolean ij.macro.Interpreter.batchMode}: <b>one switch for
 * the whole of ImageJ</b>, with no lock on it and no per-thread copy. Stage 11
 * measured that no installed engine leaks threads. That is a different question
 * from whether two of them can be driven at once, and the answer to it cannot be
 * read off the first.
 *
 * <p>Think of a shared light switch in a corridor. Each person notes whether it
 * was on when they arrived, turns it on to do their work, and puts it back to
 * what they found when they leave. That works perfectly while one person is in
 * the corridor. With two, the second arrives after the first has already turned
 * it on, notes "it was on", and on the way out turns it <b>on</b> - leaving it
 * burning after everybody has gone.
 *
 * <h2>What this measures</h2>
 *
 * <p>Two arms, forced to overlap by hand rather than by chance, so the result is
 * the same on every machine and on every run. The engines are invented through
 * {@link EngineRunner.Driver} - what a real engine does to a stack is a fact
 * about somebody else's software, and the switch being measured here is
 * ImageJ's, not theirs.
 *
 * <p>Both failures below are recorded, and both are the kind nobody notices from
 * a table of results:
 *
 * <ul>
 *   <li><b>Batch mode is left on after the whole batch has finished</b>, so every
 *       image the person opens for the rest of that Fiji session is invisible.
 *       Nothing errors; images simply stop appearing.</li>
 *   <li><b>An arm carries on driving with the switch already back off</b>, which
 *       is the state batch mode exists to avoid: an engine that shows its result
 *       opens a real window in somebody's face, and the working recording is no
 *       longer findable by title, so a perfectly good engine reports that it
 *       could not be driven.</li>
 * </ul>
 *
 * <p>The decision this drove is asserted at the bottom: a batch in the two modes
 * that drive engines runs <b>one movie at a time</b>, and says so rather than
 * being quietly slower than the dialog implies.
 *
 * @see RegDriftBatchRunner#movieWorkersFor
 */
public class BatchModeConcurrencyTest {

    /** Long enough that a stuck latch fails the test rather than hanging the build. */
    private static final long PATIENCE_MILLIS = 20_000L;

    /** Two engines this plugin drives in place, so an arm's answer is the copy it made. */
    private static final EngineId FIRST = EngineId.STACKREG;
    private static final EngineId SECOND = EngineId.MULTISTACKREG;

    private boolean batchModeBefore;

    @Before
    public void rememberTheSwitch() {
        batchModeBefore = Interpreter.batchMode;
        Interpreter.batchMode = false;
    }

    /**
     * Puts the switch back, and clears out anything the corrupted pair left in
     * ImageJ's batch-mode list.
     *
     * <p>The second half is the measured defect doing its second kind of damage,
     * and it has to be undone here or it spreads. An arm takes its working
     * recording back out of that list in a {@code finally}, and that list is
     * reachable while batch mode is on; the arm that was left driving with the
     * switch already off therefore cannot reach it, and its recording stays
     * registered under a title a later arm would go looking for. Inside a real
     * Fiji that is a stale image somebody's next comparison finds instead of its
     * own input; inside this build it would be one test poisoning the next, which
     * is the same defect wearing a different hat.
     */
    @After
    public void putTheSwitchBackAndClearWhatWasLeftBehind() {
        Interpreter.batchMode = true;
        int[] leftBehind = Interpreter.getBatchModeImageIDs();
        if (leftBehind != null) {
            for (int id : leftBehind) {
                ImagePlus stale = WindowManager.getImage(id);
                if (stale == null) continue;
                Interpreter.removeBatchModeImage(stale);
                stale.changes = false;
                stale.close();
            }
        }
        Interpreter.batchMode = batchModeBefore;
    }

    // ------------------------------------------------------- the measurement

    /**
     * Two movies driving engines at once leave ImageJ in batch mode after both
     * have finished, and one of them drives with the switch already off.
     *
     * <p>Measured, not reasoned about. The interleaving is forced with latches so
     * that it is the same every time; left to chance it would happen on some runs
     * and not others, which is exactly what makes this class of defect survive
     * into a release.
     */
    @Test
    public void twoMoviesDrivingEnginesAtOnceCorruptTheOneGlobalBatchModeSwitch()
            throws Exception {
        Overlap measured = twoOverlappingArms();

        assertTrue("both arms have to have finished for this to be a measurement of the switch"
                + " rather than of a stuck test: " + measured, measured.bothArmsRan());

        assertTrue("batch mode was off before the pair started and both arms restore what they"
                + " found, so a correct pair leaves it off. It is left ON, which is the shared"
                + " switch being set and restored against itself: the second arm read 'on' as the"
                + " state to go back to because the first had already set it. Every image opened"
                + " for the rest of that Fiji session would be invisible.",
                measured.batchModeAfterBoth);

        assertFalse("the second arm was still driving with batch mode already back off - the state"
                + " batch mode exists to keep an engine out of. An engine that shows its result"
                + " here opens a window in somebody's face.",
                measured.secondArmSawBatchModeOn);

        assertNull("and with the switch off the working recording is no longer findable by title,"
                + " which is the route every engine driven through ImageJ's command table uses to"
                + " find its input. A working engine would report that it could not be driven.",
                measured.secondArmFoundItsInputByTitle);
    }

    /**
     * One movie at a time is not corrupted by any of that.
     *
     * <p>The control for the measurement above: the same two arms, the same
     * runner, the same invented engines, run one after the other. Batch mode ends
     * where it started and both arms drove with it on. Without this the reading
     * above could be an artefact of the fixture rather than of the overlap.
     */
    @Test
    public void oneMovieAtATimeLeavesTheSwitchWhereItFoundIt() {
        EngineRunner runner = runner();
        ImagePlus first = recording("movie-a.tif");
        ImagePlus second = recording("movie-b.tif");
        final AtomicBoolean sawBatchModeOn = new AtomicBoolean(true);
        Watching watcher = new Watching(sawBatchModeOn);

        ArmResult one = new EngineRunner(presence(), watcher)
                .run(EngineDescriptor.forEngine(FIRST), first, Cancellation.never(), 5_000L);
        ArmResult two = runner.run(EngineDescriptor.forEngine(SECOND), second,
                Cancellation.never(), 5_000L);

        assertEquals(one.detail(), ArmStatus.OK, one.status());
        assertEquals(two.detail(), ArmStatus.OK, two.status());
        assertTrue("every arm run one after another drives with batch mode on",
                sawBatchModeOn.get());
        assertFalse("and puts the switch back to what it found", Interpreter.batchMode);
    }

    // --------------------------------------------------------- the decision

    /**
     * So a batch that drives engines runs one movie at a time, and a batch that
     * only measures does not.
     *
     * <p>The remedy the measurement above forced. It is narrow on purpose:
     * diagnosing and recommending touch no engine and no global switch, so those
     * two modes keep the parallel path that makes a folder of two hundred
     * recordings worth starting.
     */
    @Test
    public void aBatchThatDrivesEnginesRunsOneMovieAtATimeAndOneThatMeasuresDoesNot() {
        assertEquals("comparing drives engines, so movies run one at a time whatever was asked"
                        + " for", 1, RegDriftBatchRunner.movieWorkersFor(Mode.COMPARE, 8, 40, 0L));
        assertEquals("and so does applying", 1,
                RegDriftBatchRunner.movieWorkersFor(Mode.APPLY, 8, 40, 0L));
        assertEquals("measuring keeps the parallel path", 4,
                RegDriftBatchRunner.movieWorkersFor(Mode.DIAGNOSE, 4, 40, 0L));
        assertEquals("and so does measuring with a ranking", 4,
                RegDriftBatchRunner.movieWorkersFor(Mode.DIAGNOSE_AND_RECOMMEND, 4, 40, 0L));
    }

    /**
     * And the dialog says so, rather than the batch being quietly slower than the
     * control implies.
     */
    @Test
    public void theReasonIsWordedForSomebodyRatherThanLeftInACommit() {
        String said = RegDriftBatchRunner.movieWorkerNoteFor(Mode.COMPARE);
        assertFalse("a mode that drives engines has something to say about it", said.isEmpty());
        assertTrue("naming what runs one at a time: " + said, said.contains("one movie at a time"));
        assertTrue("and why: " + said, said.contains("batch mode"));
        assertTrue("measuring says it runs several at once: "
                        + RegDriftBatchRunner.movieWorkerNoteFor(Mode.DIAGNOSE),
                RegDriftBatchRunner.movieWorkerNoteFor(Mode.DIAGNOSE).contains("at once"));
    }

    // ------------------------------------------------------- the interleaving

    /** What the forced overlap of two arms produced. */
    private static final class Overlap {

        private boolean batchModeAfterBoth;
        private boolean secondArmSawBatchModeOn;
        private ImagePlus secondArmFoundItsInputByTitle;
        private ArmResult first;
        private ArmResult second;

        boolean bothArmsRan() {
            return first != null && second != null;
        }

        @Override
        public String toString() {
            return "first=" + first + ", second=" + second;
        }
    }

    /**
     * Runs two arms with the second starting while the first is still inside its
     * engine, and hands back what the shared switch did.
     *
     * <p>The order is the whole measurement, so it is spelled out rather than
     * left to a scheduler: first arm in, second arm in, first arm out, second arm
     * out. That is the ordinary shape of two movies of different lengths, and it
     * is the one that leaves the switch on.
     */
    private Overlap twoOverlappingArms() throws Exception {
        final Overlap measured = new Overlap();
        final CountDownLatch firstIsInside = new CountDownLatch(1);
        final CountDownLatch letTheFirstFinish = new CountDownLatch(1);
        final CountDownLatch secondIsInside = new CountDownLatch(1);
        final CountDownLatch letTheSecondFinish = new CountDownLatch(1);
        final AtomicReference<String> secondTitle = new AtomicReference<String>("");

        EngineRunner runner = runner();

        Thread first = arm(runner, FIRST, "movie-a.tif", new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options)
                    throws Throwable {
                registerTheArmDidSomething(working);
                firstIsInside.countDown();
                await(letTheFirstFinish);
            }
        }, measured, true);

        await(firstIsInside);

        Thread second = arm(runner, SECOND, "movie-b.tif", new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options)
                    throws Throwable {
                registerTheArmDidSomething(working);
                secondTitle.set(working.getTitle());
                secondIsInside.countDown();
                await(letTheSecondFinish);
                // The first arm has finished and put the switch back by now. This is what the
                // second arm is driving with for the rest of its run.
                measured.secondArmSawBatchModeOn = Interpreter.batchMode;
                measured.secondArmFoundItsInputByTitle =
                        WindowManager.getImage(secondTitle.get());
            }
        }, measured, false);

        await(secondIsInside);
        letTheFirstFinish.countDown();
        first.join(PATIENCE_MILLIS);
        letTheSecondFinish.countDown();
        second.join(PATIENCE_MILLIS);

        measured.batchModeAfterBoth = Interpreter.batchMode;
        // Left as this pair left it would poison every test after this one, which is the defect
        // being measured doing its damage. Put back by hand, on the way out.
        Interpreter.batchMode = false;
        return measured;
    }

    private Thread arm(final EngineRunner runner, final EngineId engine, final String title,
                       final EngineRunner.Driver driver, final Overlap measured,
                       final boolean isFirst) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                ArmResult result = new EngineRunner(presence(), driver).run(
                        EngineDescriptor.forEngine(engine), recording(title),
                        Cancellation.never(), PATIENCE_MILLIS);
                if (isFirst) {
                    measured.first = result;
                } else {
                    measured.second = result;
                }
            }
        }, "batch-mode-probe-" + engine);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Changes a pixel, because an arm that hands back the recording it was given
     * is reported as one that could not be driven - see {@link EngineRunner}.
     */
    private static void registerTheArmDidSomething(ImagePlus working) {
        for (int slice = 1; slice <= working.getStackSize(); slice++) {
            float[] pixels = (float[]) working.getStack().getPixels(slice);
            pixels[0] = pixels[0] + 1f;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(PATIENCE_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("the forced interleaving did not reach its next step"
                        + " within " + PATIENCE_MILLIS + " ms");
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while forcing the interleaving");
        }
    }

    private EngineRunner runner() {
        return new EngineRunner(presence(), new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options) {
                registerTheArmDidSomething(working);
            }
        });
    }

    /** Every engine is here, so nothing is refused before the switch is touched. */
    private static EngineRunner.Presence presence() {
        return new EngineRunner.Presence() {
            @Override
            public boolean installed(EngineId engine) {
                return true;
            }
        };
    }

    /** An arm that records whether batch mode was on while it drove. */
    private static final class Watching implements EngineRunner.Driver {

        private final AtomicBoolean sawBatchModeOn;

        Watching(AtomicBoolean sawBatchModeOn) {
            this.sawBatchModeOn = sawBatchModeOn;
        }

        @Override
        public void drive(ImagePlus working, EngineDescriptor engine, String options) {
            if (!Interpreter.batchMode) sawBatchModeOn.set(false);
            assertSame("an arm finds its input the way an engine does, by title",
                    working, WindowManager.getImage(working.getTitle()));
            registerTheArmDidSomething(working);
        }
    }

    private static ImagePlus recording(String title) {
        ImageStack stack = new ImageStack(16, 16);
        for (int t = 0; t < 4; t++) {
            float[] pixels = new float[16 * 16];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = (i * 7 + t * 13) % 251;
            }
            stack.addSlice("t" + (t + 1), new FloatProcessor(16, 16, pixels, null));
        }
        return new ImagePlus(title, stack);
    }
}
