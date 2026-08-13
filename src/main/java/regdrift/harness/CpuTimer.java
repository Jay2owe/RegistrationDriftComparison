/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.harness;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * One measured interval, as both the clock on the wall and the time a processor
 * actually spent on it.
 *
 * <h2>Why a harness must not time itself by the clock on the wall</h2>
 *
 * <p>The everyday version: a stopwatch left running while you go for lunch says
 * you took two hours over a job that took ten minutes. That is what
 * {@link System#nanoTime()} does. It measures how much time passed, not how much
 * work was done, and those are the same number while the process is being given
 * a processor - which on a laptop it frequently is not. Sleep, hibernation,
 * Windows Modern Standby and aggressive power throttling all stop the process
 * while the clock carries on.
 *
 * <p><b>This is not hypothetical, and it is defect D6.</b> The benchmark run
 * this plugin's calibration came from reported one arm at 237,000 ms per frame
 * pair, against a median of 81 ms elsewhere, and that figure was written into a
 * CSV file and quoted as a blocking defect before anybody checked it. It was not
 * computation. The machine went into standby at 17:52 and came out at 20:58, and
 * the reported figure is that interval to within a second. Re-run in one sitting,
 * the same arm takes 3.1 s.
 *
 * <p>Processor time can tell those two situations apart, because a process that
 * is not running accrues none of it. So every timing this plugin puts in a table
 * or ranks on comes from here, and the wall clock appears in the progress bar and
 * nowhere else.
 *
 * <h2>What this figure is not</h2>
 *
 * <p>It is counted per thread. Garbage collection and just-in-time compilation
 * run elsewhere and are excluded, so on a healthy run expect it to sit a few
 * percent under the wall clock. An engine that hands work to threads of its own
 * is counted for the thread this timer watches and not for those, which makes
 * the figure a floor rather than a total - stated here so nobody reads it as a
 * processor-seconds bill. Windows updates the counter on the scheduler tick,
 * about every 15.6 ms, so a single short interval is quantized and an aggregate
 * is the thing worth quoting.
 *
 * <p>None of those limits touches what this exists to catch, which is a
 * three-thousand-fold discrepancy between an interval and the work in it.
 *
 * <p>Lifted from {@code logratio/Timing.java}, whose measurement of the standby
 * incident is the evidence above.
 */
public final class CpuTimer {

    /**
     * Below this, the gap between the two clocks is ordinary background:
     * collection, compilation, other processes. Above it - and above
     * {@link #SUSPECT_SHARE} as well - the process was not running.
     */
    private static final long SUSPECT_FLOOR_NANOS = 1_000_000_000L;

    /**
     * And the gap has to be this share of the interval too. Both conditions, so
     * a legitimately long arm is not flagged for losing a second to collection,
     * and a short one is not flagged for noise.
     */
    private static final double SUSPECT_SHARE = 0.25;

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private final long threadId;
    private final long wallStart;
    private final long cpuStart;

    private long wallNanos = -1;
    private long cpuNanos = -1;

    private CpuTimer() {
        this.threadId = Thread.currentThread().getId();
        this.wallStart = System.nanoTime();
        this.cpuStart = cpuNow();
    }

    /**
     * Begins measuring on the calling thread.
     *
     * <p>{@link #stop()} has to be called on that same thread, because processor
     * time is counted per thread and a figure collected on another one would be
     * a different thread's work.
     */
    public static CpuTimer start() {
        return new CpuTimer();
    }

    /** Ends the interval. Returns itself, so a call can be chained on. */
    public CpuTimer stop() {
        if (Thread.currentThread().getId() != threadId) {
            throw new IllegalStateException("A CpuTimer has to be stopped on the thread that"
                    + " started it. Processor time is counted per thread, so a figure taken on"
                    + " another one measures other work.");
        }
        long cpuEnd = cpuNow();
        wallNanos = System.nanoTime() - wallStart;
        // The processor span is read inside the wall span at both ends, so it can never exceed
        // it and unscheduledNanos() can never come out negative from ordering alone.
        cpuNanos = cpuStart < 0 || cpuEnd < 0 ? -1 : cpuEnd - cpuStart;
        return this;
    }

    /**
     * The processor time this thread has used since the Java process started, or
     * -1 where this Java runtime does not report it.
     *
     * <p>What the arm body reads at both ends of the work it drives.
     */
    public static long threadCpuNanos() {
        return cpuNow();
    }

    /**
     * The processor time another thread has used, or -1 when that thread has
     * finished or this runtime does not report it.
     *
     * <p>Needed for the arm that has not come back: its timing is still readable
     * while it is running, and reading it is how a run that gave up waiting can
     * still say what the arm had cost by then.
     */
    public static long threadCpuNanos(long otherThreadId) {
        try {
            if (!THREADS.isThreadCpuTimeSupported()) return -1;
            return THREADS.getThreadCpuTime(otherThreadId);
        } catch (RuntimeException unsupported) {
            return -1;
        }
    }

    /**
     * The processor time a piece of work costs, with the wall clock discarded.
     *
     * <p>The shape a test uses: hand it work that stalls, and the figure that
     * comes back is the work rather than the stall.
     */
    public static long measureCpuNanos(Runnable work) {
        if (work == null) {
            throw new IllegalArgumentException("CpuTimer.measureCpuNanos needs work to measure.");
        }
        CpuTimer timer = start();
        try {
            work.run();
        } finally {
            timer.stop();
        }
        return timer.cpuNanos();
    }

    /** True when this Java runtime reported real processor time. */
    public boolean cpuMeasured() {
        return cpuNanos >= 0;
    }

    /**
     * Time a processor actually spent on this thread.
     *
     * <p>Falls back to the wall clock on a runtime that cannot report it, which
     * {@link #cpuMeasured()} is how a caller tells apart.
     */
    public long cpuNanos() {
        checkStopped();
        return cpuNanos >= 0 ? cpuNanos : wallNanos;
    }

    /** Elapsed time, including any stretch in which the process was not running. */
    public long wallNanos() {
        checkStopped();
        return wallNanos;
    }

    /** Elapsed time that was not spent computing. Zero on a healthy run. */
    public long unscheduledNanos() {
        long lost = wallNanos() - cpuNanos();
        return lost > 0 ? lost : 0;
    }

    /** The figure a table carries, in seconds. */
    public double cpuSeconds() {
        return cpuNanos() / 1e9;
    }

    /** The figure a progress bar carries, in seconds. Never put in a table. */
    public double wallSeconds() {
        return wallNanos() / 1e9;
    }

    /**
     * True when the interval is dominated by time the process was not running -
     * a suspend, a sleep, or contention severe enough that a wall-clock figure is
     * not worth quoting.
     */
    public boolean unscheduled() {
        return cpuMeasured()
                && unscheduledNanos() >= SUSPECT_FLOOR_NANOS
                && unscheduledNanos() > SUSPECT_SHARE * wallNanos();
    }

    /**
     * A sentence to show when {@link #unscheduled()}, or an empty string.
     *
     * <p>Reported rather than discarded: an arm that lost three hours to standby
     * is a fact about the run worth seeing. It is not a fact about the engine,
     * which is why it never reaches the ranking.
     */
    public String note() {
        if (!unscheduled()) return "";
        return String.format("%.1f s of the %.1f s that passed was not spent computing - this"
                        + " computer was asleep or was not giving the run a processor. The"
                        + " figure to quote is %.1f s.",
                unscheduledNanos() / 1e9, wallNanos() / 1e9, cpuNanos() / 1e9);
    }

    @Override
    public String toString() {
        return String.format("%.1f ms cpu / %.1f ms elapsed", cpuNanos() / 1e6, wallNanos() / 1e6);
    }

    private void checkStopped() {
        if (wallNanos < 0) throw new IllegalStateException("This CpuTimer was never stopped.");
    }

    private static long cpuNow() {
        try {
            if (!THREADS.isCurrentThreadCpuTimeSupported()) return -1;
            return THREADS.getCurrentThreadCpuTime();       // -1 when the runtime has it turned off
        } catch (RuntimeException unsupported) {
            return -1;
        }
    }
}
