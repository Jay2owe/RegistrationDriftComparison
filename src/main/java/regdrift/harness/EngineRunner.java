/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.harness;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Macro;
import ij.WindowManager;
import ij.macro.Interpreter;
import regdrift.Cancellation;
import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineId;
import sc.fiji.autofix.core.DependencyStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one registration engine over one recording, inside somebody's live Fiji,
 * and hands back what it produced.
 *
 * <p>The everyday version: this is a test bench with a fume hood over it. The
 * engine is somebody else's machine, it is bolted to the bench rather than to
 * the room, and whatever it gives off is measured on the way out instead of
 * being let into the building.
 *
 * <h2>What this is protecting, and from what</h2>
 *
 * <p>This code runs beside a person's unsaved images. The research harness the
 * measurements came from ended the whole Java process when it had finished
 * driving TurboReg, with a comment saying TurboReg left window-system threads
 * behind. In a benchmark that is tidying up. In somebody's Fiji it closes their
 * session and every unsaved image in it. So:
 *
 * <ul>
 *   <li><b>Nothing here ends the Java process.</b> Not on success, not on
 *       failure, not in a shutdown hook. {@code EngineRunnerIsolationTest} reads
 *       the compiled classes of this package and fails the build if any of them
 *       so much as name the call that ends it.</li>
 *   <li><b>No window is opened.</b> Not the recording that goes in, not the one
 *       that comes back. See the next section, because the obvious way to do
 *       that does not work.</li>
 *   <li><b>No thread is stopped.</b> Threads an engine leaves running are
 *       counted, named and reported. Stopping a thread from outside is what
 *       unlocks half-written state, and half-written state here is somebody's
 *       image.</li>
 *   <li><b>The recording handed in is never touched.</b> Every arm works on a
 *       duplicate under a title of its own.</li>
 * </ul>
 *
 * <h2>Hidden, but findable: ImageJ's batch mode</h2>
 *
 * <p>An engine driven through ImageJ's command table finds its input the way a
 * person would - by looking through the list of open images for one with the
 * right title. So the recording has to be in that list. A plain hidden image is
 * not in it, and the research harness solved that by opening two real windows
 * and swapping the pixels inside them between frame pairs, which in a live
 * session flickers windows at somebody and races the thread that draws them.
 *
 * <p>ImageJ already has the thing both constraints need. In <b>batch mode</b> an
 * image is registered with {@code WindowManager} - reachable by title, present
 * in the list, usable by any engine - and no window is ever built for it. So an
 * arm turns batch mode on before it starts and puts it back to what it was in a
 * {@code finally}, including when the engine throws.
 *
 * <p>Two details of that are deliberate and are worth stating, because they are
 * the difference between hidden and nearly hidden:
 *
 * <ul>
 *   <li>Batch mode is one switch shared by the whole of ImageJ, so it is set on
 *       the thread that coordinates the run and never from a worker, and arms
 *       run one after another rather than beside each other. Two arms setting it
 *       at once would each restore the other's value.</li>
 *   <li>The working copy is put into the list by hand rather than by asking it
 *       to show itself. {@code ImagePlus.show()} builds a real window whenever
 *       batch mode is not actually on, so an arm that used it would open a
 *       window on the one path where everything else had already gone wrong.
 *       Registering it directly cannot do that: the worst it can do is fail to
 *       register, which the arm reports as an engine it could not drive. The
 *       isolation test reads the bytecode and fails the build if anything in
 *       this package calls {@code show()}.</li>
 * </ul>
 *
 * <h2>An arm that quietly did nothing is not a success</h2>
 *
 * <p>ImageJ writes some failures into its log window instead of raising them, so
 * "no exception came back" is not evidence that anything happened. Every arm
 * therefore takes a fingerprint of the pixels before and after and reports
 * {@link ArmStatus#COULD_NOT_DRIVE} when they are identical. Without that check
 * an engine that silently declined would be recorded as a success that happened
 * to improve the recording by nothing at all, and it would be ranked against
 * engines that really ran.
 *
 * <p>The cost of that check is a recording that genuinely needed no registration
 * reading as an engine that could not be driven. That is the safer of the two
 * mistakes: it names an arm nobody should trust rather than inventing one
 * somebody would.
 *
 * <h2>Every timing from the processor clock</h2>
 *
 * <p>Defect D6. {@link CpuTimer} explains the incident: a benchmark reported one
 * arm at 237,000 ms per frame pair, which was a laptop in standby being counted
 * as computation. The figure an arm reports is processor time on the thread the
 * engine ran on. Elapsed time is measured too and goes to the progress bar and
 * nowhere else.
 *
 * <h2>Why {@code catch (Throwable)}</h2>
 *
 * <p>Normally a mistake. Here it is the point. A third-party plugin whose
 * library is half-installed throws {@code NoClassDefFoundError}, and one whose
 * static setup fails throws {@code ExceptionInInitializerError}; neither is an
 * {@code Exception}, and either would otherwise unwind out of the comparison and
 * take the other engines' arms with it. One engine failing has to cost one row.
 * What was thrown is recorded by type and message in the row - it is never
 * written to a log and forgotten, which is house rule 14.
 */
public final class EngineRunner {

    /**
     * How long an arm is given before the run stops waiting for it, when nobody
     * says otherwise.
     *
     * <p>Ten minutes. Long enough for a slow engine on a long recording, short
     * enough that a plugin that has stopped and is waiting for somebody to
     * answer a question does not hold a session forever. An arm that runs out of
     * time is reported and left alone - see {@link ArmStatus#TIMED_OUT}.
     */
    public static final long DEFAULT_TIMEOUT_MILLIS = 10L * 60L * 1000L;

    /** The shortest arm anybody may ask for. Below this nothing could finish. */
    public static final long MIN_TIMEOUT_MILLIS = 100L;

    /**
     * How long the run waits, after an arm has finished, before deciding a
     * thread is still there rather than on its way out.
     *
     * <p>A thread that was about to exit anyway would otherwise be counted as
     * something left behind, which would turn every engine into one this plugin
     * drives once. Quarter of a second, checked ten times, and it stops early
     * the moment the group is empty.
     */
    static final int SETTLE_MILLIS = 250;

    private static final int SETTLE_CHECKS = 10;

    /** The first half of every working title. Long enough that nothing collides. */
    static final String TITLE_PREFIX = "RegDrift arm";

    private static EngineRunner session;

    private final Presence presence;
    private final Driver driver;
    private final AtomicInteger arms = new AtomicInteger();
    /**
     * The engines this session is finished with, each with the sentence saying
     * why.
     *
     * <p>The sentence is kept rather than reconstructed, because there are two
     * ways to land here - an arm really left a thread running, or the engine was
     * recorded as one to drive once before anybody ran it - and a row that gave
     * the wrong one of those reasons would send somebody looking for a thread
     * that was never there.
     */
    private final Map<EngineId, String> drivenAlready = new EnumMap<EngineId, String>(
            EngineId.class);

    /**
     * Whether an engine is on this computer.
     *
     * <p>A seam, so that the arm's behavior on an absent engine can be asserted
     * without arranging for one to be absent.
     */
    public interface Presence {

        /** True when this engine can be attempted on this computer. */
        boolean installed(EngineId engine);
    }

    /**
     * The one call that hands a recording to somebody else's code.
     *
     * <p>A seam for the same reason, and a narrow one on purpose: everything
     * this class does about windows, threads, timing and cleanup happens around
     * this call, so a test that substitutes it exercises all of that against an
     * engine that misbehaves exactly as the test needs it to.
     */
    public interface Driver {

        /**
         * Runs one engine over one recording.
         *
         * @param working the copy this arm made, already findable by title
         * @param engine  which engine, and how it is spelled
         * @param options the arguments, with the working title written in
         * @throws Throwable whatever somebody else's code throws, unchanged
         */
        void drive(ImagePlus working, EngineDescriptor engine, String options) throws Throwable;
    }

    /**
     * A runner for the Fiji this is running inside.
     *
     * <p>One per session, because what an engine left behind last time decides
     * whether it is driven again this time, and that is a fact about the session
     * rather than about the run.
     */
    public static synchronized EngineRunner forThisSession() {
        if (session == null) session = new EngineRunner(new CataloguePresence(), new ImageJDriver());
        return session;
    }

    /**
     * A runner over a supplied presence answer and driving step.
     *
     * @param presence how to tell whether an engine is here
     * @param driver   how an engine is actually run
     */
    public EngineRunner(Presence presence, Driver driver) {
        if (presence == null || driver == null) {
            throw new IllegalArgumentException("an engine runner needs both a way to tell whether"
                    + " an engine is present and a way to drive it");
        }
        this.presence = presence;
        this.driver = driver;
    }

    // ------------------------------------------------------------- many arms

    /**
     * Every engine in turn, one after another, on the same recording.
     *
     * <p><b>Serial by construction, and not as a precaution.</b> Third-party
     * plugins are not written to be run beside themselves, several of these keep
     * their settings in ImageJ's own global state, batch mode is one switch for
     * the whole application, and at least one of them leaves threads running.
     * Two arms at once would produce numbers that are not about either engine.
     *
     * <p>Stopping is checked between arms, where stopping is safe, and every
     * engine that was never reached still gets a row saying so. An engine that
     * silently vanished from the table would read as one nobody asked about.
     */
    public List<ArmResult> runAll(List<EngineDescriptor> engines, ImagePlus stack,
                                  Cancellation cancel, long timeoutMillis) {
        if (engines == null) {
            throw new IllegalArgumentException("a comparison needs engines to compare");
        }
        Cancellation stop = cancel == null ? Cancellation.never() : cancel;
        List<ArmResult> results = new ArrayList<ArmResult>(engines.size());
        for (EngineDescriptor engine : engines) {
            results.add(run(engine, stack, stop, timeoutMillis));
        }
        return Collections.unmodifiableList(results);
    }

    /** What a person is shown before an arm is dispatched, so nothing is a surprise. */
    public String dispatchNotice(EngineDescriptor engine, double expectedSeconds,
                                 long timeoutMillis) {
        if (engine == null) throw new IllegalArgumentException("a notice needs an engine");
        StringBuilder said = new StringBuilder("Running ").append(engine.displayName());
        if (expectedSeconds > 0 && !Double.isNaN(expectedSeconds)) {
            said.append(String.format(", which the measured table puts at about %.0f s of"
                    + " processor time on a recording of this size", expectedSeconds));
        } else {
            said.append(", which the measured table has no timing for");
        }
        said.append(". It is given ").append(boundedTimeout(timeoutMillis) / 1000L)
                .append(" s before this run stops waiting for it. Stopping the run stops before"
                        + " the next engine rather than in the middle of this one.");
        return said.toString();
    }

    /** True when this session is finished with this engine. */
    public boolean drivenAlready(EngineId engine) {
        return !driveOnceNote(engine).isEmpty();
    }

    /**
     * Why this session is finished with this engine, or an empty string when it
     * is not.
     *
     * <p>Finished text. The Engines section shows it under the row and adds no
     * words of its own.
     */
    public String driveOnceNote(EngineId engine) {
        synchronized (drivenAlready) {
            String note = drivenAlready.get(engine);
            return note == null ? "" : note;
        }
    }

    // -------------------------------------------------------------- one arm

    /**
     * One engine, driven once over one recording.
     *
     * <p>Never null, never a bare exception, and never a registered recording
     * without a status beside it. The recording that comes back is in memory: it
     * is not shown, not saved and not registered with anything.
     *
     * @param engine        which engine, and how to drive it
     * @param stack         the person's recording. Duplicated, never touched
     * @param cancel        the switch that says the run was stopped
     * @param timeoutMillis how long to wait, bounded below by
     *                      {@link #MIN_TIMEOUT_MILLIS}
     */
    public ArmResult run(EngineDescriptor engine, ImagePlus stack, Cancellation cancel,
                         long timeoutMillis) {
        if (engine == null) throw new IllegalArgumentException("an arm needs an engine to drive");
        if (stack == null) throw new IllegalArgumentException("an arm needs a recording to drive"
                + " an engine over");
        Cancellation stop = cancel == null ? Cancellation.never() : cancel;
        String settings = engine.optionsTemplate();

        if (stop.canceled()) {
            return refused(engine, ArmStatus.CANCELED, "The run was stopped before "
                    + engine.displayName() + " was dispatched, so this engine was not driven.",
                    settings);
        }
        if (!engine.isDrivable()) {
            return refused(engine, ArmStatus.COULD_NOT_DRIVE, engine.notDrivableReason(), settings);
        }
        String absent = firstAbsent(engine);
        if (absent != null) {
            return refused(engine, ArmStatus.NOT_INSTALLED, absent, settings);
        }
        String alreadyDriven = alreadyDriven(engine);
        if (alreadyDriven != null) {
            return refused(engine, ArmStatus.COULD_NOT_DRIVE, alreadyDriven, settings);
        }
        return drive(engine, stack, boundedTimeout(timeoutMillis), settings);
    }

    /**
     * The arm body: batch mode on and back off, a thread group of its own, a
     * bounded wait, and no path out of here that ends the process or stops a
     * thread.
     */
    private ArmResult drive(EngineDescriptor engine, ImagePlus stack, long timeoutMillis,
                            String settings) {
        int number = arms.incrementAndGet();
        ThreadGroup group = new ThreadGroup("regdrift-arm-" + number + "-" + engine.id());
        CpuTimer elapsed = CpuTimer.start();            // elapsed only; the arm's own clock is below
        boolean wasBatch = Interpreter.batchMode;
        boolean wasRedirecting = IJ.redirectingErrorMessages();
        ImagePlus previousCurrent = WindowManager.getTempCurrentImage();
        Set<ImagePlus> registered = new LinkedHashSet<ImagePlus>();
        ImagePlus working = null;
        ImagePlus keep = null;
        try {
            working = stack.duplicate();
            working.setTitle(freeTitle(engine, number));
            long before = contentHash(working);
            List<ImagePlus> openBefore = openImages();

            Interpreter.batchMode = true;
            if (!Interpreter.isBatchMode()) {
                return refused(engine, ArmStatus.COULD_NOT_DRIVE, "ImageJ would not go into batch"
                        + " mode, which is what lets an engine find its input without a window"
                        + " being opened. Nothing was driven and no window was opened.", settings);
            }
            Interpreter.addBatchModeImage(working);
            registered.add(working);

            /*
             * ImageJ's own reaction to a command it cannot run is a modal box
             * with an OK button on it. Inside a comparison that is a frozen
             * session: the arm waits for somebody to click, the timeout expires,
             * and the box is still there. Redirected, the same message goes to
             * ImageJ's Log window instead, where it is read back below and put
             * into the row - a non-modal window somebody can ignore, in place of
             * a modal one they cannot. Put back to what it was in the finally.
             */
            IJ.getErrorMessage();                       // drops anything left from before
            IJ.redirectErrorMessages(true);

            Arm arm = new Arm(engine, working, driver);
            Thread worker = new Thread(group, arm, "regdrift-arm-" + number);
            worker.setDaemon(true);
            worker.start();
            join(worker, timeoutMillis);
            boolean timedOut = worker.isAlive();
            if (!timedOut) settle(group);

            for (ImagePlus opened : openImages()) {
                if (!openBefore.contains(opened)) registered.add(opened);
            }
            List<String> leftBehind = liveThreads(group, timedOut ? null : worker);
            long cpuNanos = arm.cpuNanos(timedOut);
            long wallNanos = elapsed.stop().wallNanos();
            String found = engine.installedVersion();

            if (timedOut) {
                return ArmResult.failed(engine, ArmStatus.TIMED_OUT, timedOutDetail(engine,
                        timeoutMillis, leftBehind), settings, cpuNanos, wallNanos, found,
                        leftBehind);
            }
            if (arm.thrown() != null) {
                return ArmResult.failed(engine, ArmStatus.COULD_NOT_DRIVE,
                        thrownDetail(engine, arm.thrown(), found, IJ.getErrorMessage()), settings,
                        cpuNanos, wallNanos, found, leftBehind);
            }
            keep = produced(engine, working, openBefore);
            if (keep == null) {
                return ArmResult.failed(engine, ArmStatus.COULD_NOT_DRIVE, engine.displayName()
                        + " finished without producing a registered recording. ImageJ writes some"
                        + " failures into its log window rather than raising them, so this arm is"
                        + " reported as one that could not be driven rather than as one that"
                        + " improved the recording by nothing.", settings, cpuNanos, wallNanos,
                        found, leftBehind);
            }
            String wrongShape = notTheSameRecording(stack, keep);
            if (wrongShape != null) {
                keep = null;
                return ArmResult.failed(engine, ArmStatus.COULD_NOT_DRIVE, engine.displayName()
                        + " came back with something that is not a registered form of this"
                        + " recording: " + wrongShape + ". Scoring it against the original would"
                        + " compare two different recordings, so the arm is reported as one that"
                        + " could not be driven.", settings, cpuNanos, wallNanos, found,
                        leftBehind);
            }
            if (contentHash(keep) == before) {
                keep = null;
                return ArmResult.failed(engine, ArmStatus.COULD_NOT_DRIVE, engine.displayName()
                        + " finished without changing a pixel. ImageJ writes some failures into"
                        + " its log window rather than raising them, so an arm that came back"
                        + " with the recording it was handed is reported as one that could not be"
                        + " driven. A recording that genuinely needed no registration reads the"
                        + " same way.", settings, cpuNanos, wallNanos, found, leftBehind);
            }
            registered.remove(keep);
            remember(engine, leftBehind);
            return ArmResult.finished(engine, settings, keep, cpuNanos, wallNanos, found,
                    leftBehind);
        } catch (Throwable anything) {
            /*
             * Everything above is this plugin's own code, so this is the belt on
             * top of the braces: a duplicate that ran out of memory, a title
             * that could not be found free. One arm's worth of failure, never
             * the comparison's.
             */
            return ArmResult.failed(engine, ArmStatus.COULD_NOT_DRIVE,
                    thrownDetail(engine, anything, engine.installedVersion(), ""), settings,
                    -1L, -1L, engine.installedVersion(), Collections.<String>emptyList());
        } finally {
            /*
             * Order matters. The images are taken out of ImageJ's batch-mode
             * list while batch mode is still on, because that list is only
             * reachable while it is; then batch mode goes back to whatever it
             * was, on this thread, on every path including a throw.
             */
            unregister(registered, keep);
            Interpreter.batchMode = wasBatch;
            IJ.redirectErrorMessages(wasRedirecting);
            WindowManager.setTempCurrentImage(previousCurrent);
        }
    }

    // ------------------------------------------------------ the refusals

    /** An engine nothing was attempted on: no timing, no threads, a reason. */
    private ArmResult refused(EngineDescriptor engine, ArmStatus status, String detail,
                              String settings) {
        return ArmResult.failed(engine, status, detail, settings, -1L, -1L, "",
                Collections.<String>emptyList());
    }

    /**
     * The sentence for an engine that is not here, or null when it is.
     *
     * <p>StackReg and MultiStackReg both call TurboReg while they run, so an
     * engine's own requirements are checked as well as the engine. An arm
     * dispatched without TurboReg fails part-way through with a message naming
     * StackReg, which sends somebody to reinstall something they already have.
     */
    private String firstAbsent(EngineDescriptor engine) {
        if (!presence.installed(engine.id())) {
            return engine.displayName() + " is not on this computer, so nothing was driven. The"
                    + " comparison carried on with the next engine. Nothing here fetches anything:"
                    + " installing an engine is a button in the Engines section.";
        }
        for (EngineId needed : engine.requires()) {
            if (!presence.installed(needed)) {
                return engine.displayName() + " calls " + displayName(needed) + " while it runs,"
                        + " and " + displayName(needed) + " is not on this computer, so nothing"
                        + " was driven. " + engine.displayName() + " itself is here; it is "
                        + displayName(needed) + " that is missing.";
            }
        }
        return null;
    }

    /** The sentence for an engine this session has already had its one run of, or null. */
    private String alreadyDriven(EngineDescriptor engine) {
        String note = driveOnceNote(engine.id());
        return note.isEmpty() ? null : note;
    }

    /**
     * Records that this engine is finished for this session, when it left
     * something running or when it was recorded as one that would.
     */
    private void remember(EngineDescriptor engine, List<String> leftBehind) {
        if (leftBehind.isEmpty() && !engine.driveOncePerSession()) return;
        String why = leftBehind.isEmpty()
                ? engine.displayName() + " is recorded as an engine to drive once in a Fiji"
                        + " session, and it has been driven once already, so this plugin does not"
                        + " drive it again. Restart Fiji to compare it a second time."
                : engine.displayName() + " was driven once in this Fiji session and left "
                        + leftBehind.size() + (leftBehind.size() == 1 ? " thread" : " threads")
                        + " running afterwards (" + joined(leftBehind) + "), so this plugin does"
                        + " not drive it again. Nothing was stopped and nothing was closed."
                        + " Restart Fiji to compare it a second time.";
        synchronized (drivenAlready) {
            drivenAlready.put(engine.id(), why);
        }
    }

    // ------------------------------------------------------ images and titles

    /**
     * A title no open image already has.
     *
     * <p>An engine that finds its input by title will find the person's own
     * image just as happily if the titles collide, and then register that
     * instead. So the title is generated, checked against everything ImageJ
     * currently knows about, and changed until it is free.
     */
    private String freeTitle(EngineDescriptor engine, int number) {
        List<String> taken = Arrays.asList(titles());
        for (int attempt = 0; attempt < 1000; attempt++) {
            String title = TITLE_PREFIX + " " + (number + attempt) + " - " + engine.displayName();
            if (!taken.contains(title) && WindowManager.getImage(title) == null) return title;
        }
        throw new IllegalStateException("could not find a working title that no open image already"
                + " has, after a thousand tries");
    }

    private static String[] titles() {
        String[] found = WindowManager.getImageTitles();
        return found == null ? new String[0] : found;
    }

    /** Every image ImageJ currently knows about, windows and batch-mode alike. */
    private static List<ImagePlus> openImages() {
        List<ImagePlus> found = new ArrayList<ImagePlus>();
        int[] ids = WindowManager.getIDList();
        if (ids == null) return found;
        for (int id : ids) {
            ImagePlus imp = WindowManager.getImage(id);
            if (imp != null) found.add(imp);
        }
        return found;
    }

    /**
     * The registered recording this arm produced, or null when it produced none.
     *
     * <p>Two habits to meet. An engine that rewrites what it was handed leaves
     * the answer in the working copy; one that makes a new recording leaves it
     * somewhere in ImageJ's list, where it was not before. Guessing wrong either
     * way looks like a working arm from the outside - it would score an
     * unregistered recording, or throw the registered one away - so the
     * descriptor says which to expect and this looks for that one.
     */
    private static ImagePlus produced(EngineDescriptor engine, ImagePlus working,
                                      List<ImagePlus> openBefore) {
        if (engine.output() == EngineDescriptor.Output.IN_PLACE) return working;
        ImagePlus newest = null;
        for (ImagePlus opened : openImages()) {
            if (opened == working || openBefore.contains(opened)) continue;
            newest = opened;
        }
        return newest;
    }

    /**
     * Takes every image this arm put into ImageJ's list back out of it, except
     * the one being handed back.
     *
     * <p>What is kept is kept as a recording in memory: taken out of the list,
     * never shown, never saved. What is not kept is closed, with its changed
     * flag cleared first so that closing it cannot put a save prompt on the
     * screen of somebody who never asked for the image in the first place.
     */
    private static void unregister(Set<ImagePlus> registered, ImagePlus keep) {
        for (ImagePlus imp : registered) {
            try {
                Interpreter.removeBatchModeImage(imp);
                if (imp == keep) continue;
                imp.changes = false;
                imp.close();
            } catch (Throwable stubborn) {
                // An image that will not let go is one leaked object. Abandoning the rest of the
                // cleanup over it, or losing the arm's result, would both be worse. There is
                // nothing here for a person to act on, so there is nothing here to report.
                continue;
            }
        }
    }

    /**
     * What is wrong with the shape of what an arm came back with, or null when
     * nothing is.
     *
     * <p>Measured on a real install, and the reason this check exists: an engine
     * given arguments that fit its dialog but not the question being asked can
     * come back with one frame of a twelve-frame recording, having raised
     * nothing at all. Every other check would pass it - a recording came back,
     * the pixels differ from the ones that went in - and the arbiter would then
     * compare a single frame against a whole recording and produce a number.
     *
     * <p>Frames, width and height, because those are what make the comparison in
     * the stage after this one mean anything. Bit depth is not checked: an engine
     * that returns 32-bit where it was handed 8-bit has still registered the
     * recording it was given.
     */
    private static String notTheSameRecording(ImagePlus given, ImagePlus back) {
        if (back.getStackSize() != given.getStackSize()) {
            return "it has " + back.getStackSize()
                    + (back.getStackSize() == 1 ? " frame" : " frames") + " where the recording"
                    + " has " + given.getStackSize();
        }
        if (back.getWidth() != given.getWidth() || back.getHeight() != given.getHeight()) {
            return "it is " + back.getWidth() + " by " + back.getHeight() + " pixels where the"
                    + " recording is " + given.getWidth() + " by " + given.getHeight();
        }
        return null;
    }

    /**
     * A fingerprint of every pixel in a recording.
     *
     * <p>Not a checksum anybody should trust against tampering - it is a fast
     * pass over the pixel arrays, and its one job is to tell "this engine
     * rewrote the recording" from "this engine handed back what it was given".
     */
    static long contentHash(ImagePlus imp) {
        if (imp == null) return 0L;
        ImageStack stack = imp.getStack();
        long hash = 1125899906842597L;
        hash = hash * 31L + imp.getWidth();
        hash = hash * 31L + imp.getHeight();
        hash = hash * 31L + stack.getSize();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            hash = hash * 31L + hashOf(stack.getPixels(slice));
        }
        return hash;
    }

    private static int hashOf(Object pixels) {
        if (pixels instanceof byte[]) return Arrays.hashCode((byte[]) pixels);
        if (pixels instanceof short[]) return Arrays.hashCode((short[]) pixels);
        if (pixels instanceof int[]) return Arrays.hashCode((int[]) pixels);
        if (pixels instanceof float[]) return Arrays.hashCode((float[]) pixels);
        return pixels == null ? 0 : pixels.getClass().hashCode();
    }

    // ------------------------------------------------------------- threads

    /**
     * The threads still running in this arm's group, by name, sorted.
     *
     * <p>Counted and reported. Never stopped, never interrupted, and the group
     * itself is left alone rather than destroyed: a thread stopped from outside
     * drops whatever lock it was holding while whatever it was writing is half
     * written, and what it is writing here is somebody's image.
     */
    private static List<String> liveThreads(ThreadGroup group, Thread ignore) {
        Thread[] found = new Thread[group.activeCount() + 16];
        int counted = group.enumerate(found, true);
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < counted && i < found.length; i++) {
            Thread thread = found[i];
            if (thread == null || thread == ignore || !thread.isAlive()) continue;
            names.add(thread.getName());
        }
        Collections.sort(names);
        return names;
    }

    /**
     * Waits briefly for an arm's own threads to finish on their own.
     *
     * <p>A thread that was already on its way out would otherwise be counted as
     * one left behind, and an engine wrongly marked as one to drive once a
     * session is a comparison somebody cannot re-run. Bounded, and it stops the
     * moment the group is empty.
     */
    private static void settle(ThreadGroup group) {
        for (int check = 0; check < SETTLE_CHECKS; check++) {
            if (group.activeCount() == 0) return;
            sleep(SETTLE_MILLIS / SETTLE_CHECKS);
        }
    }

    private static void join(Thread worker, long timeoutMillis) {
        try {
            worker.join(timeoutMillis);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private static long boundedTimeout(long timeoutMillis) {
        if (timeoutMillis <= 0) return DEFAULT_TIMEOUT_MILLIS;
        return Math.max(MIN_TIMEOUT_MILLIS, timeoutMillis);
    }

    // ------------------------------------------------------------ sentences

    private static String displayName(EngineId engine) {
        return EngineDescriptor.forEngine(engine).displayName();
    }

    /** A wait as a person reads it: whole seconds when it is whole, else one decimal. */
    static String seconds(long millis) {
        if (millis % 1000L == 0L) return (millis / 1000L) + " s";
        return String.format(java.util.Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    private static String timedOutDetail(EngineDescriptor engine, long timeoutMillis,
                                         List<String> leftBehind) {
        StringBuilder said = new StringBuilder(engine.displayName())
                .append(" did not come back within ").append(seconds(timeoutMillis))
                .append(", so this run stopped waiting for it. It was not stopped and it was not")
                .append(" interrupted - stopping a thread from outside drops whatever it was")
                .append(" holding half-written. ");
        said.append(leftBehind.size()).append(leftBehind.size() == 1 ? " thread is" : " threads are")
                .append(" still running: ").append(joined(leftBehind)).append(".");
        said.append(" Batch mode has been put back to what it was, so an engine that finishes"
                + " later and shows its result will show it in a window.");
        return said.toString();
    }

    /**
     * What went wrong, in the row rather than in a log somebody has to go and
     * find - house rule 14.
     *
     * <p>ImageJ's own way of refusing a command is to show a message and then
     * stop whatever was running, which arrives here as a plain runtime exception
     * carrying the words "Macro canceled" and nothing about the actual problem.
     * The real sentence is the one it was about to put on the screen, which the
     * arm redirected and reads back here, so the row says "Unrecognized command"
     * rather than "canceled".
     */
    private static String thrownDetail(EngineDescriptor engine, Throwable thrown, String found,
                                       String fromImageJ) {
        StringBuilder said = new StringBuilder(engine.displayName())
                .append(" could not be driven on this computer: ");
        String message = thrown.getMessage();
        boolean stoppedByImageJ = message != null && message.endsWith(Macro.MACRO_CANCELED);
        if (stoppedByImageJ && fromImageJ != null && !fromImageJ.trim().isEmpty()) {
            said.append(fromImageJ.trim());
        } else {
            said.append(thrown.getClass().getName());
            if (message != null && !message.trim().isEmpty()) {
                said.append(" - ").append(message.trim());
            }
        }
        said.append(".");
        if (found != null && !found.trim().isEmpty()) {
            said.append(" The version found here is ").append(found.trim()).append(".");
        }
        said.append(" The comparison carried on with the next engine.");
        return said.toString();
    }

    private static String joined(List<String> names) {
        if (names.isEmpty()) return "none";
        StringBuilder joined = new StringBuilder();
        for (String name : names) {
            if (joined.length() > 0) joined.append(", ");
            joined.append(name);
        }
        return joined.toString();
    }

    // ------------------------------------------------------- the moving parts

    /**
     * One engine's run, on a thread of its own inside the arm's group.
     *
     * <p>The group is the whole reason the engine runs on a thread at all rather
     * than on the one coordinating: a thread a plugin starts joins the group of
     * the thread that started it, so counting what is left in the group counts
     * what the engine left behind. Nothing about it needs a worker otherwise -
     * arms are serial and the coordinator does nothing while this runs but wait.
     *
     * <p>Its own processor clock, started and stopped on this thread, because
     * processor time is counted per thread and a figure taken anywhere else
     * measures other work.
     */
    private static final class Arm implements Runnable {

        private final EngineDescriptor engine;
        private final ImagePlus working;
        private final Driver driver;

        private volatile long threadId = -1L;
        private volatile long cpuNanos = -1L;
        private volatile Throwable thrown;

        Arm(EngineDescriptor engine, ImagePlus working, Driver driver) {
            this.engine = engine;
            this.working = working;
            this.driver = driver;
        }

        @Override
        public void run() {
            threadId = Thread.currentThread().getId();
            CpuTimer timer = CpuTimer.start();
            try {
                WindowManager.setTempCurrentImage(working);
                driver.drive(working, engine, engine.options(working.getTitle()));
            } catch (Throwable anything) {
                // Deliberate, and one of the few places it is right. See the class javadoc.
                thrown = anything;
            } finally {
                cpuNanos = timer.stop().cpuNanos();
                WindowManager.setTempCurrentImage(null);
            }
        }

        Throwable thrown() {
            return thrown;
        }

        /**
         * What this arm cost on the processor clock.
         *
         * <p>An arm still running has not stopped its own timer, so its figure
         * is read off the thread while it is still readable. That is how a run
         * that gave up waiting can still say what the arm had cost by then.
         */
        long cpuNanos(boolean stillRunning) {
            if (!stillRunning) return cpuNanos;
            long id = threadId;
            return id < 0 ? -1L : CpuTimer.threadCpuNanos(id);
        }
    }

    /**
     * Whether the catalogue found this engine, asked once per session.
     *
     * <p>An engine the catalogue could not check - no Fiji folder to look in, no
     * command table yet - is attempted rather than refused. Attempting it and
     * reporting what happened is a truer answer than declaring absent something
     * nobody looked for.
     */
    private static final class CataloguePresence implements Presence {

        @Override
        public boolean installed(EngineId engine) {
            try {
                DependencyStatus status = AutofixService.forThisFiji().status(engine);
                return status == null || !status.isMissing();
            } catch (RuntimeException noCatalogue) {
                return true;
            }
        }
    }

    /**
     * The real driving step: ImageJ's own command table, the same entry a person
     * would pick from the menu, with the arguments the macro recorder writes.
     *
     * <p>{@code IJ.run} is what makes an arm and the copyable recipe on the
     * recommendation row the same action. It is also what swallows some failures
     * into the log window, which is why the arm checks the pixels afterwards
     * rather than trusting it.
     */
    private static final class ImageJDriver implements Driver {

        @Override
        public void drive(ImagePlus working, EngineDescriptor engine, String options) {
            IJ.run(working, engine.command(), options);
        }
    }
}
