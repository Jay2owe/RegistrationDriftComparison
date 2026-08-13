/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.internal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Defect D9, in both directions: the worker budget must be capped by memory, and
 * a generous budget must not silently force the run serial.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code workersFor} divides a memory budget by the cost of one task to get a
 * memory cap. Both are {@code long}. A generous budget divided by a small
 * per-task cost is a {@code long} that does not fit in an {@code int}; narrowed
 * without a clamp it came out <b>negative</b>, a negative number wins every
 * {@code min()}, the final {@code max(1, ...)} returned 1, and the whole run went
 * serial with nobody told. It looked like a slow machine.
 *
 * <p>The half of this test that catches it is
 * {@link #aGenerousBudgetDoesNotOverflowIntoSerial}. Run against the unfixed
 * narrowing it reports 1 worker where 16 were asked for and afforded. Run against
 * the fixed one it reports 16.
 *
 * <h2>Why the sweep as well as the single case</h2>
 *
 * <p>One budget value proves one budget value. The sweep walks the whole range
 * where {@code long} stops fitting in {@code int}, because the sign of a
 * truncated {@code long} depends on which bit lands in position 31 - some budgets
 * truncate to a large positive number and pass by luck. Every value in the sweep
 * has to give a sane worker count, not just the one that happened to be written
 * down first.
 */
public class PairSchedulerBudgetTest {

    /** A pair of 2048x2048 image pyramids, live, in bytes. */
    private static final long PAIR_OF_2048 =
            2L * PyramidCache.BYTES_PER_PIXEL * 2048 * 2048;

    /**
     * <b>D9.</b> A budget so large that the memory cap overflows {@code int} must
     * not turn into a cap of one.
     *
     * <p>{@code Long.MAX_VALUE / 4} divided by a one-byte task is
     * {@code 2^61 - 1}, whose low 32 bits are all ones - which is {@code -1} as an
     * {@code int}. That is the exact value that used to win every {@code min()}.
     */
    @Test
    public void aGenerousBudgetDoesNotOverflowIntoSerial() {
        assertEquals("a budget that affords thousands of workers must not read as one",
                16, PairScheduler.workersFor(1000, 16, 1, Long.MAX_VALUE / 4));
        assertEquals("nor at the very top of the range",
                16, PairScheduler.workersFor(1000, 16, 1, Long.MAX_VALUE));
        assertEquals("and the task count still caps it",
                3, PairScheduler.workersFor(3, 16, 1, Long.MAX_VALUE / 4));
    }

    /**
     * <b>D9, swept.</b> Across every budget from "affords a few" to "affords more
     * than an {@code int} can hold", the answer is the number asked for, capped by
     * the work available - and never 1 by accident.
     */
    @Test
    public void noBudgetAnywhereInTheRangeCollapsesTheWorkerCount() {
        for (int shift = 4; shift < 63; shift++) {
            long budget = 1L << shift;
            int workers = PairScheduler.workersFor(1000, 16, 1, budget);
            assertTrue("budget 2^" + shift + " gave " + workers + " workers, which is not a"
                    + " count at all", workers >= 1);
            assertEquals("budget 2^" + shift + " affords " + budget + " one-byte tasks and must"
                    + " give the 16 workers asked for", 16, workers);
        }
    }

    /**
     * The other direction: the cap is real, and a tight budget still bounds the
     * pool.
     *
     * <p>A pair of 2048x2048 pyramids is around 150 MB live. Sixteen workers would
     * ask for 2.4 GB of pyramid alone, which is how a fast plugin becomes an
     * {@code OutOfMemoryError} on a default Fiji heap.
     */
    @Test
    public void workersAreCappedByMemory() {
        assertEquals("a 300 MB budget affords two of these",
                2, PairScheduler.workersFor(1000, 16, PAIR_OF_2048, 300L * 1024 * 1024));
        assertEquals("never fewer than one", 1,
                PairScheduler.workersFor(1000, 16, PAIR_OF_2048, 1024));
        assertEquals("never more than there is work", 3,
                PairScheduler.workersFor(3, 16, 1, Long.MAX_VALUE / 4));
        assertEquals("one task is always serial", 1,
                PairScheduler.workersFor(1, 16, 1, Long.MAX_VALUE / 4));
        assertEquals("an explicit 1 forces serial", 1,
                PairScheduler.workersFor(1000, 1, 1, Long.MAX_VALUE / 4));
    }

    /** No argument, however odd, produces a worker count below one. */
    @Test
    public void theAnswerIsAlwaysAtLeastOne() {
        long[] budgets = {Long.MIN_VALUE, -1, 0, 1, 1024, Long.MAX_VALUE};
        long[] costs = {Long.MIN_VALUE, -1, 0, 1, PAIR_OF_2048, Long.MAX_VALUE};
        for (int t = 0; t <= 1000; t += 250) {
            for (int requested = -1; requested <= 32; requested += 11) {
                for (long budget : budgets) {
                    for (long cost : costs) {
                        int workers = PairScheduler.workersFor(t, requested, cost, budget);
                        assertTrue("tasks=" + t + " requested=" + requested + " cost=" + cost
                                + " budget=" + budget + " gave " + workers, workers >= 1);
                        assertTrue("and never more than the work: " + workers,
                                t <= 1 || workers <= t);
                    }
                }
            }
        }
    }

    /** A zero per-task cost means "memory is not the limit here", not "no workers". */
    @Test
    public void aZeroCostTaskIsBoundedByCoresAndWork() {
        assertEquals(16, PairScheduler.workersFor(1000, 16, 0, 1024));
        assertEquals(4, PairScheduler.workersFor(4, 16, 0, 0));
    }

    /** Asking for nothing in particular still gives a usable pool on this machine. */
    @Test
    public void anAutomaticChoiceIsBoundedByTheMachine() {
        int automatic = PairScheduler.workersFor(1000, 0, 1, Long.MAX_VALUE / 4);
        int cores = Runtime.getRuntime().availableProcessors();
        assertTrue("automatic gave " + automatic + " on a " + cores + "-core machine",
                automatic >= 1 && automatic <= Math.max(1, cores));
    }
}
