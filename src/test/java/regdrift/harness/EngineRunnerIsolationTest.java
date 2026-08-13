/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.harness;

import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.macro.Interpreter;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import regdrift.Cancellation;
import regdrift.autofix.EngineId;
import regdrift.diag.Bytecode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T9. The reason this stage exists: an arm runs somebody else's plugin inside a
 * live Fiji session, beside their unsaved images, and it has to be incapable of
 * ending that session, incapable of opening a window, and incapable of stopping
 * a thread.
 *
 * <h2>Two of these are asserted against the compiled bytecode, not the source</h2>
 *
 * <p>"Nothing here calls {@code System.exit}" cannot be tested by running the
 * code - a test that made it true would end the test runner. And a search
 * through the source would miss the same call reached by reflection, in a
 * shutdown hook, or written in a form the reader did not think of. So the
 * constant pool of every compiled class in this package is read, which is where
 * a written name and an imported one are the same entry. The same reader carries
 * the ban on {@code ImagePlus.show()}, because an arm that showed its working
 * copy would open a real window on any path where batch mode was not actually
 * on - and that is precisely the path where everything else has already gone
 * wrong.
 *
 * <h2>The rest run against engines that misbehave on purpose</h2>
 *
 * <p>The runner takes the one call that hands a recording to somebody else's
 * code as a seam, so these tests substitute engines that do the things real ones
 * do: leave a thread running, throw a linkage error out of a static
 * initializer, take longer than they were given, or quietly do nothing at all.
 * Everything the class does about windows, threads, timing, batch mode and
 * cleanup happens around that one call, so substituting it exercises all of it.
 */
public class EngineRunnerIsolationTest {

    private static final EngineDescriptor STACKREG =
            EngineDescriptor.forEngine(EngineId.STACKREG);
    private static final EngineDescriptor SIFT =
            EngineDescriptor.forEngine(EngineId.LINEAR_STACK_ALIGNMENT_WITH_SIFT);

    private final List<Thread> started = new ArrayList<Thread>();

    private boolean batchBefore;
    private int imagesBefore;
    private int windowsBefore;

    @Before
    public void note() {
        batchBefore = Interpreter.batchMode;
        imagesBefore = WindowManager.getImageCount();
        windowsBefore = WindowManager.getWindowCount();
    }

    /**
     * The fixture engines below start threads on purpose, so this test class
     * cleans up after itself. The runner never does: reporting a thread and
     * leaving it alone is the behavior being asserted.
     */
    @After
    public void releaseTheFixtureThreads() {
        for (Thread thread : started) thread.interrupt();
        started.clear();
        Interpreter.batchMode = batchBefore;
    }

    // --------------------------------------------------- read off the bytecode

    /**
     * Nothing in this package can end the Java process. Not on success, not on
     * failure, not in a shutdown hook, not through {@code Runtime.halt}.
     */
    @Test
    public void nothingOnThisPathCanEndTheSession() throws IOException {
        for (String className : Bytecode.classesIn("regdrift.harness")) {
            Bytecode.Pool pool = Bytecode.poolOf(className);
            assertFalse(className + " must not call System.exit: this code runs inside somebody's"
                            + " Fiji session, and ending the process takes their unsaved images"
                            + " with it. That is defect D1.",
                    pool.members.contains("java/lang/System#exit"));
            assertFalse(className + " must not call Runtime.exit or Runtime.halt, which end the"
                            + " process by another name",
                    pool.members.contains("java/lang/Runtime#exit")
                            || pool.members.contains("java/lang/Runtime#halt"));
            assertFalse(className + " must not register a shutdown hook",
                    pool.members.contains("java/lang/Runtime#addShutdownHook"));
        }
    }

    /**
     * The scan has to be able to see a call that is really there, or the
     * assertion above is decoration.
     */
    @Test
    public void theScanSeesCallsThatAreReallyThere() throws IOException {
        Bytecode.Pool runner = Bytecode.poolOf("regdrift.harness.EngineRunner");
        assertTrue("the scan must see the batch-mode field this class really writes",
                runner.members.contains("ij/macro/Interpreter#batchMode"));
        assertTrue("the scan must see the duplicate this class really makes",
                runner.members.contains("ij/ImagePlus#duplicate"));
        assertTrue("and the thread group every arm really runs in",
                runner.mentions("java/lang/ThreadGroup"));
    }

    /**
     * Nothing here shows an image, and nothing here stops a thread.
     *
     * <p>{@code ImagePlus.show()} builds a real window whenever batch mode is
     * not actually on. Registering the working copy with ImageJ by hand cannot
     * do that: the worst it can do is fail to register, which the arm reports.
     */
    @Test
    public void nothingHereShowsAnImageOrStopsAThread() throws IOException {
        /*
         * Thread.interrupt is not on this list, and the omission is deliberate:
         * the runner calls it on itself, to put back an interrupt flag it
         * swallowed while waiting. What must never happen is an arm's threads
         * being interrupted from outside, and every route to that is a group
         * call, which is why the group's are all here.
         */
        String[] banned = {
                "ij/ImagePlus#show", "ij/gui/ImageWindow#<init>", "ij/gui/StackWindow#<init>",
                "java/lang/Thread#stop", "java/lang/Thread#suspend",
                "java/lang/ThreadGroup#stop", "java/lang/ThreadGroup#destroy",
                "java/lang/ThreadGroup#interrupt", "java/lang/ThreadGroup#suspend",
        };
        for (String className : Bytecode.classesIn("regdrift.harness")) {
            Bytecode.Pool pool = Bytecode.poolOf(className);
            for (String call : banned) {
                int hash = call.indexOf('#');
                assertFalse(className + " must not call " + call.replace('/', '.').replace('#', '.')
                                + "(): see this package's javadoc for why an arm neither shows an"
                                + " image nor stops a thread",
                        pool.members.contains(call.substring(0, hash) + "#"
                                + call.substring(hash + 1)));
            }
        }
    }

    /**
     * Every timing an arm reports comes from {@link CpuTimer}. Defect D6, and
     * asserted structurally so that a wall-clock figure cannot creep into the
     * table three stages from now.
     */
    @Test
    public void nothingButTheTimerReadsAClock() throws IOException {
        for (String className : Bytecode.classesIn("regdrift.harness")) {
            if (className.startsWith("regdrift.harness.CpuTimer")) continue;
            Bytecode.Pool pool = Bytecode.poolOf(className);
            assertFalse(className + " must not read the wall clock. Every figure this package"
                            + " reports comes from CpuTimer, which is defect D6: a benchmark once"
                            + " reported a laptop's overnight standby as computation.",
                    pool.members.contains("java/lang/System#nanoTime")
                            || pool.members.contains("java/lang/System#currentTimeMillis"));
        }
    }

    // ------------------------------------------------------- driving for real

    /** Driving an engine opens no window and leaves ImageJ's image list as it was. */
    @Test
    public void drivingAnEngineOpensNoWindow() {
        ArmResult result = runner(new Shifting()).run(SIFT, movie(), Cancellation.never(), 30_000L);
        assertEquals("an arm that ran must report a registered recording",
                ArmStatus.OK, result.status());
        assertEquals("no window may be opened by an arm",
                windowsBefore, WindowManager.getWindowCount());
        assertEquals("ImageJ's image list must be as it was before the arm",
                imagesBefore, WindowManager.getImageCount());
        assertNotNull("the registered recording comes back in memory", result.registered());
        assertNull("and is not registered with ImageJ",
                WindowManager.getImage(result.registered().getTitle()));
    }

    /** The working copy is findable by title while the engine runs, and gone after. */
    @Test
    public void theWorkingCopyIsFindableByTitleAndOnlyWhileTheArmRuns() {
        final List<String> seen = new ArrayList<String>();
        final List<Boolean> found = new ArrayList<Boolean>();
        ArmResult result = runner(new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options) {
                seen.add(working.getTitle());
                found.add(WindowManager.getImage(working.getTitle()) == working);
                new Shifting().drive(working, engine, options);
            }
        }).run(STACKREG, movie(), Cancellation.never(), 30_000L);

        assertEquals(ArmStatus.OK, result.status());
        assertEquals("the engine is handed exactly one working copy", 1, seen.size());
        assertTrue("an engine must be able to find its input by title, which is what batch mode"
                + " is for", found.get(0).booleanValue());
        assertTrue("the working title must say which plugin made it",
                seen.get(0).startsWith(EngineRunner.TITLE_PREFIX));
        assertNull("and must not still be findable once the arm is over",
                WindowManager.getImage(seen.get(0)));
    }

    /** The arguments carry the working copy's title where the descriptor asks for it. */
    @Test
    public void theTitleIsWrittenIntoTheArguments() {
        final List<String> options = new ArrayList<String>();
        runner(new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String given) {
                options.add(given);
                new Shifting().drive(working, engine, given);
            }
        }).run(EngineDescriptor.forEngine(EngineId.MULTISTACKREG), movie(),
                Cancellation.never(), 30_000L);

        assertEquals(1, options.size());
        assertFalse("the title token must be replaced, never handed to an engine",
                options.get(0).contains(EngineDescriptor.TITLE_TOKEN));
        assertTrue("and replaced with the working copy's own title",
                options.get(0).contains(EngineRunner.TITLE_PREFIX));
    }

    /** A thread the engine left running is counted and named, and never stopped. */
    @Test
    public void aLeakedThreadIsReportedNotIgnored() {
        Leaking leaking = new Leaking();
        ArmResult result = runner(leaking).run(SIFT, movie(), Cancellation.never(), 30_000L);

        assertEquals("an arm that left a thread running says so",
                ArmStatus.LEAKED_THREADS, result.status());
        assertEquals("the thread is named, not just counted",
                Arrays.asList(Leaking.NAME), result.threadsLeftBehind());
        assertTrue("the registered recording is still real", result.hasRegisteredStack());
        assertTrue("and the engine is one to drive once a session now",
                result.driveOncePerSession());
        assertTrue("the thread is left running, never stopped", leaking.thread.isAlive());
        assertFalse("and never interrupted", leaking.thread.isInterrupted());
        assertTrue("the table cell carries the count",
                result.statusColumn().contains("leaked_threads=1"));
    }

    /** An engine that leaked is not driven again in the same session. */
    @Test
    public void anEngineThatLeakedIsNotDrivenAgainThisSession() {
        Leaking leaking = new Leaking();
        EngineRunner runner = runner(leaking);
        assertEquals(ArmStatus.LEAKED_THREADS,
                runner.run(SIFT, movie(), Cancellation.never(), 30_000L).status());
        assertTrue(runner.drivenAlready(SIFT.id()));

        ArmResult second = runner.run(SIFT, movie(), Cancellation.never(), 30_000L);
        assertEquals(ArmStatus.COULD_NOT_DRIVE, second.status());
        assertTrue("the row says why, and what to do about it",
                second.detail().contains("Restart Fiji"));
        assertEquals("nothing was driven the second time", 1, leaking.drives.get());

        ArmResult other = runner.run(STACKREG, movie(), Cancellation.never(), 30_000L);
        assertTrue("another engine is unaffected by the first one's leak",
                other.hasRegisteredStack());
        assertEquals("and really was dispatched", 2, leaking.drives.get());
    }

    /**
     * An engine that is not here reports it, and the comparison carries on.
     *
     * <p>The gap this closes: an absent engine that threw would end the run at
     * whatever position it happened to sit in the ranking.
     */
    @Test
    public void aMissingEngineReportsAndTheRunContinues() {
        EngineRunner runner = new EngineRunner(
                onlyPresent(EnumSet.of(EngineId.TURBOREG, EngineId.STACKREG)), new Shifting());
        List<ArmResult> results = runner.runAll(
                Arrays.asList(SIFT, STACKREG), movie(), Cancellation.never(), 30_000L);

        assertEquals(2, results.size());
        assertEquals("the absent one is reported", ArmStatus.NOT_INSTALLED, results.get(0).status());
        assertTrue("and nothing was fetched to fix it",
                results.get(0).detail().contains("Engines section"));
        assertEquals("and the next arm still runs", ArmStatus.OK, results.get(1).status());
    }

    /**
     * StackReg calls TurboReg while it runs. Without TurboReg the failure has to
     * name TurboReg, or somebody is sent to reinstall the thing they already
     * have.
     */
    @Test
    public void stackRegWithoutTurboRegNamesTurboReg() {
        ArmResult result = new EngineRunner(onlyPresent(EnumSet.of(EngineId.STACKREG)),
                new Shifting()).run(STACKREG, movie(), Cancellation.never(), 30_000L);

        assertEquals(ArmStatus.NOT_INSTALLED, result.status());
        assertTrue("the message must name TurboReg, which is what is missing: " + result.detail(),
                result.detail().contains("TurboReg is not on this computer"));
    }

    /**
     * A linkage error out of somebody else's static setup costs one arm, not the
     * comparison. This is the reason {@code catch (Throwable)} is right here.
     */
    @Test
    public void aPluginThatThrowsALinkageErrorCostsOneArm() {
        List<ArmResult> results = runner(new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options)
                    throws Throwable {
                if (engine.id() == EngineId.LINEAR_STACK_ALIGNMENT_WITH_SIFT) {
                    throw new NoClassDefFoundError("mpicbg/imagefeatures/Feature");
                }
                new Shifting().drive(working, engine, options);
            }
        }).runAll(Arrays.asList(SIFT, STACKREG), movie(), Cancellation.never(), 30_000L);

        assertEquals(ArmStatus.COULD_NOT_DRIVE, results.get(0).status());
        assertTrue("the type is recorded, not logged and forgotten: " + results.get(0).detail(),
                results.get(0).detail().contains("NoClassDefFoundError"));
        assertTrue("and the message with it",
                results.get(0).detail().contains("mpicbg/imagefeatures/Feature"));
        assertEquals("the next arm still runs", ArmStatus.OK, results.get(1).status());
    }

    /**
     * Batch mode is put back on every path, including the one where somebody
     * else's code threw. Leaving it set would hide the person's next image from
     * them.
     */
    @Test
    public void batchModeIsRestoredEvenWhenTheArmThrows() {
        assertFalse("this test starts outside batch mode", Interpreter.batchMode);
        runner(new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options) {
                throw new ExceptionInInitializerError("a plugin's static setup failed");
            }
        }).run(SIFT, movie(), Cancellation.never(), 30_000L);
        assertFalse("batch mode must be back off after an arm that threw", Interpreter.batchMode);

        runner(new Shifting()).run(SIFT, movie(), Cancellation.never(), 30_000L);
        assertFalse("and after one that worked", Interpreter.batchMode);
    }

    /** An arm that ran out of time is reported, and its thread is left alone. */
    @Test
    public void anArmThatRanOutOfTimeIsReportedAndLeftAlone() {
        Hanging hanging = new Hanging();
        ArmResult result = runner(hanging).run(SIFT, movie(), Cancellation.never(), 300L);

        assertEquals(ArmStatus.TIMED_OUT, result.status());
        assertFalse("nothing came back to score", result.hasRegisteredStack());
        assertTrue("the thread still running is reported: " + result.threadsLeftBehind(),
                !result.threadsLeftBehind().isEmpty());
        assertTrue("the row says the thread was not stopped: " + result.detail(),
                result.detail().contains("was not stopped"));
        assertFalse("and batch mode is off again", Interpreter.batchMode);
        hanging.release();
    }

    /**
     * An engine that finished without changing a pixel is reported as one that
     * could not be driven.
     *
     * <p>ImageJ writes some failures into its log window rather than raising
     * them, so the absence of an exception is not evidence that anything
     * happened. Without this check the arm would be ranked as a success that
     * improved the recording by nothing.
     */
    @Test
    public void anArmThatQuietlyDidNothingIsNotASuccess() {
        ArmResult result = runner(new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options) {
                // Exactly what a plugin that swallowed its own failure looks like from here.
            }
        }).run(STACKREG, movie(), Cancellation.never(), 30_000L);

        assertEquals(ArmStatus.COULD_NOT_DRIVE, result.status());
        assertFalse(result.hasRegisteredStack());
        assertTrue("the row says what was checked: " + result.detail(),
                result.detail().contains("without changing a pixel"));
    }

    /** An engine that came back with a different recording is not a success either. */
    @Test
    public void anArmThatCameBackWithADifferentRecordingIsNotASuccess() {
        ArmResult result = runner(new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options) {
                ImagePlus fused = new ImagePlus(working.getTitle() + " fused",
                        working.getStack().getProcessor(1).duplicate());
                fused.show();                       // a fixture may; the runner may not
            }
        }).run(SIFT, movie(), Cancellation.never(), 30_000L);

        assertEquals(ArmStatus.COULD_NOT_DRIVE, result.status());
        assertTrue("the row says what was wrong with it: " + result.detail(),
                result.detail().contains("1 frame where the recording has"));
        assertEquals("and the fused image is cleaned up",
                imagesBefore, WindowManager.getImageCount());
    }

    /** Stopping the run stops before the next arm, and leaves batch mode off. */
    @Test
    public void cancelingStopsBeforeTheNextArm() {
        final Cancellation.Flag stop = Cancellation.flag();
        final AtomicInteger drives = new AtomicInteger();
        List<ArmResult> results = runner(new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options) {
                drives.incrementAndGet();
                new Shifting().drive(working, engine, options);
                stop.cancel();
            }
        }).runAll(Arrays.asList(SIFT, STACKREG), movie(), stop, 30_000L);

        assertEquals("every engine still gets a row", 2, results.size());
        assertEquals(ArmStatus.OK, results.get(0).status());
        assertEquals("the arm after the stop is typed, not missing",
                ArmStatus.CANCELED, results.get(1).status());
        assertEquals("and it was never dispatched", 1, drives.get());
        assertFalse("batch mode is off", Interpreter.batchMode);
    }

    /** The person's own recording is never touched: not its pixels, not its title. */
    @Test
    public void theUsersRecordingIsNeverTouched() {
        ImagePlus users = movie();
        users.setTitle("somebody's unsaved movie");
        long before = EngineRunner.contentHash(users);

        runner(new Shifting()).run(SIFT, users, Cancellation.never(), 30_000L);

        assertEquals("the pixels must be untouched", before, EngineRunner.contentHash(users));
        assertEquals("the title must be untouched", "somebody's unsaved movie", users.getTitle());
        assertFalse("and it must not have been shown", users.isVisible());
    }

    /** Arms run one after another, never beside each other. */
    @Test
    public void armsRunOneAtATime() {
        final AtomicInteger running = new AtomicInteger();
        final AtomicBoolean overlapped = new AtomicBoolean(false);
        runner(new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options) {
                if (running.incrementAndGet() > 1) overlapped.set(true);
                new Shifting().drive(working, engine, options);
                running.decrementAndGet();
            }
        }).runAll(Arrays.asList(SIFT, STACKREG,
                        EngineDescriptor.forEngine(EngineId.MULTISTACKREG)),
                movie(), Cancellation.never(), 30_000L);
        assertFalse("two arms must never be in flight at once: third-party plugins are not"
                + " written to be run beside themselves", overlapped.get());
    }

    // ---------------------------------------------------------------- fixtures

    private EngineRunner runner(EngineRunner.Driver driver) {
        return new EngineRunner(onlyPresent(EnumSet.allOf(EngineId.class)), driver);
    }

    private static EngineRunner.Presence onlyPresent(final Set<EngineId> here) {
        return new EngineRunner.Presence() {
            @Override
            public boolean installed(EngineId engine) {
                return here.contains(engine);
            }
        };
    }

    /** Eight frames of a moving edge: small, and every pixel of it deterministic. */
    private static ImagePlus movie() {
        ImageStack stack = new ImageStack(24, 24);
        for (int t = 0; t < 8; t++) {
            byte[] pixels = new byte[24 * 24];
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    pixels[y * 24 + x] = (byte) (((x + t) % 24) * 10);
                }
            }
            stack.addSlice("t" + (t + 1), new ByteProcessor(24, 24, pixels, null));
        }
        return new ImagePlus("fixture", stack);
    }

    /**
     * An engine that does something to every pixel and puts the answer where the
     * descriptor says that engine puts it.
     *
     * <p>Both habits, because an arm looks in a different place for each, and a
     * fixture that only ever rewrote its input would leave the other half of
     * that untested.
     */
    private static final class Shifting implements EngineRunner.Driver {

        @Override
        public void drive(ImagePlus working, EngineDescriptor engine, String options) {
            if (engine.output() == EngineDescriptor.Output.IN_PLACE) {
                brighten(working);
                return;
            }
            ImagePlus made = working.duplicate();
            made.setTitle(working.getTitle() + " registered");
            brighten(made);
            made.show();                            // a fixture may; the runner may not
        }

        private static void brighten(ImagePlus imp) {
            ImageStack stack = imp.getStack();
            for (int slice = 1; slice <= stack.getSize(); slice++) {
                stack.getProcessor(slice).add(3);
            }
        }
    }

    /** An engine that registers the recording and leaves a thread running behind it. */
    private final class Leaking implements EngineRunner.Driver {

        static final String NAME = "a-plugin-that-did-not-tidy-up";

        private final AtomicInteger drives = new AtomicInteger();
        private volatile Thread thread;

        @Override
        public void drive(ImagePlus working, EngineDescriptor engine, String options) {
            drives.incrementAndGet();
            new Shifting().drive(working, engine, options);
            final CountDownLatch running = new CountDownLatch(1);
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    running.countDown();
                    try {
                        Thread.sleep(120_000L);
                    } catch (InterruptedException released) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, NAME);
            thread.setDaemon(true);
            thread.start();
            started.add(thread);
            await(running);
        }
    }

    /** An engine that does not come back, and is left alone when the arm gives up. */
    private final class Hanging implements EngineRunner.Driver {

        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void drive(ImagePlus working, EngineDescriptor engine, String options) {
            started.add(Thread.currentThread());
            await(release);
        }

        void release() {
            release.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException released) {
            Thread.currentThread().interrupt();
        }
    }
}
