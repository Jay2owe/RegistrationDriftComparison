/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.internal;

import regdrift.Cancellation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs independent numbered pieces of work across a bounded pool of threads, and
 * hands the answers back in the order they were numbered.
 *
 * <p>Like a row of numbered pigeonholes: several people work at once, but each
 * one drops its answer into its own hole, and the coordinator reads the holes
 * left to right afterwards. Who finished first never shows up in the output.
 *
 * <p><b>Results are stored by index, never by completion order.</b> A table
 * whose row order depended on which thread finished first would be a different
 * scientific output on every run.
 *
 * <p><b>Workers are capped by memory as well as by cores.</b> A pair of
 * 2048x2048 pyramids is around 150 MB live, so sixteen workers on a sixteen-core
 * machine would ask for 2.4 GB of pyramid alone and fail on a default Fiji heap.
 * Deriving the cap from cores only is the standard way to turn a fast plugin into
 * an {@code OutOfMemoryError}.
 *
 * <p><b>One pool per call, never nested.</b> Each run owns its pool and shuts it
 * down before returning; there is no shared static pool anywhere in this plugin.
 * Inner loops stay serial: the outer axis already saturates the pool, and a
 * nested pool would oversubscribe the machine while making the worker budget
 * meaningless.
 *
 * <p><b>Workers never touch ImageJ.</b> They are handed float arrays and hand
 * float arrays back. The ImageJ classes, the window list, tables, plots and
 * every file write stay on the coordinator thread that called {@link #map}.
 *
 * <p>Cancellation is checked before each task starts and is propagated by the
 * task itself for long work. It arrives here as a {@link CancellationException},
 * which the coordinator turns into the typed reason a caller sees; the exception
 * never leaves the plugin. The first failure cancels and drains the rest,
 * restores the interrupt flag if it was set, bounds the shutdown wait, and leaves
 * no threads behind.
 *
 * <p>Lifted from {@code logratio\core\PairScheduler.java} in the Log-Ratio
 * Registration research repository, carrying that repository's fix for the
 * worker-budget overflow recorded here as defect D9. It schedules work; it holds
 * no registration criterion.
 */
public final class PairScheduler {

    private PairScheduler() {
    }

    /** Work for one index. Must not touch ImageJ windows, tables or global state. */
    public interface Task<T> {
        T run(int index) throws Exception;
    }

    /**
     * Called on the coordinator's behalf as tasks complete. Implementations must
     * be thread-safe, and must not touch ImageJ: a worker reporting straight into
     * the status bar is the same defect as a worker reading a stack.
     */
    public interface Progress {
        void update(int done, int total);

        /** Reports nothing. What a headless call or a test passes. */
        Progress NONE = new Progress() {
            @Override
            public void update(int done, int total) {
            }
        };
    }

    /**
     * How many workers to use.
     *
     * <p><b>Defect D9 lives in the narrowing on the last two lines, and it is the
     * reason this method computes in {@code long}.</b> A generous memory budget
     * divided by a small per-task cost is a {@code long} that does not fit in an
     * {@code int}. Truncated, it came out negative; a negative number wins every
     * {@code min()}; the final {@code max(1, ...)} then returned 1 and the whole
     * run went serial without a word to anybody. It looked like a slow machine,
     * not like a bug. So the memory cap is clamped to {@link Integer#MAX_VALUE}
     * <em>before</em> it is narrowed, and a test drives a budget large enough to
     * have overflowed.
     *
     * @param tasks      how many units of work there are
     * @param requested  the caller's choice; 0 or less means decide automatically,
     *                   1 forces serial
     * @param memPerTask bytes a single task needs live
     * @param budget     bytes available; 0 or less asks for a fraction of
     *                   {@link Runtime#maxMemory}
     * @return at least 1, never more than {@code tasks}
     */
    public static int workersFor(int tasks, int requested, long memPerTask, long budget) {
        if (tasks <= 1) return 1;
        if (requested == 1) return 1;
        int cores = requested > 1
                ? requested
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        long available = budget > 0 ? budget : Runtime.getRuntime().maxMemory() / 2;
        long byMemoryLong = memPerTask > 0 ? Math.max(1L, available / memPerTask) : cores;
        int byMemory = (int) Math.min((long) Integer.MAX_VALUE, byMemoryLong);
        return Math.max(1, Math.min(Math.min(cores, byMemory), tasks));
    }

    /**
     * Run {@code count} tasks and return their results in index order.
     *
     * @throws CancellationException if {@code cancellation} fired before the work
     *                               finished
     * @throws RuntimeException      wrapping the first task failure, after the
     *                               rest are drained
     */
    public static <T> List<T> map(int count, int workers, Task<T> task, Progress progress,
                                  Cancellation cancellation) {
        List<T> results = new ArrayList<T>(count);
        for (int i = 0; i < count; i++) results.add(null);
        if (count == 0) return results;

        if (workers <= 1) {
            for (int i = 0; i < count; i++) {
                if (cancellation.canceled()) throw new CancellationException("canceled");
                try {
                    results.set(i, task.run(i));
                } catch (Exception e) {
                    throw wrap(e, i);
                }
                progress.update(i + 1, count);
            }
            return results;
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers, new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "regdrift-worker");
                t.setDaemon(true);
                return t;
            }
        });
        final AtomicInteger done = new AtomicInteger();
        final AtomicBoolean stop = new AtomicBoolean();
        final List<T> sink = results;
        final Cancellation stopSwitch = cancellation;
        final Task<T> work = task;
        final Progress reporter = progress;
        final int total = count;
        List<Future<?>> futures = new ArrayList<Future<?>>(count);
        boolean interrupted = false;
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(new java.util.concurrent.Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        if (stop.get() || stopSwitch.canceled()) return null;
                        T value = work.run(index);
                        synchronized (sink) {
                            sink.set(index, value);
                        }
                        reporter.update(done.incrementAndGet(), total);
                        return null;
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (InterruptedException e) {
                    interrupted = true;
                    stop.set(true);
                    throw new CancellationException("interrupted");
                } catch (ExecutionException e) {
                    stop.set(true);
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw wrap(cause, i);
                }
            }
            if (cancellation.canceled()) throw new CancellationException("canceled");
            return results;
        } finally {
            stop.set(true);
            for (Future<?> f : futures) f.cancel(false);
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException wrap(Throwable cause, int index) {
        if (cause instanceof RuntimeException) return (RuntimeException) cause;
        return new RuntimeException("task " + index + " failed: " + cause.getMessage(), cause);
    }
}
