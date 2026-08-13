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
import regdrift.Cancellation;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The parallel contract: deterministic order, clean cancellation and clean failure. */
public class PairSchedulerTest {

    /**
     * Results must land at their planned index whatever the completion order.
     *
     * <p>Forced here by making early tasks slow and late tasks fast, so completion order is close to
     * reversed. A table whose row order depended on this would be a different scientific output on
     * every run.
     */
    @Test
    public void resultsAreInIndexOrderUnderReversedCompletion() {
        final int n = 32;
        List<Integer> out = PairScheduler.map(n, 8, new PairScheduler.Task<Integer>() {
            @Override
            public Integer run(int index) throws Exception {
                Thread.sleep((n - index) % 8);
                return Integer.valueOf(index * index);
            }
        }, PairScheduler.Progress.NONE, Cancellation.never());
        for (int i = 0; i < n; i++) {
            assertEquals("index " + i, Integer.valueOf(i * i), out.get(i));
        }
    }

    @Test
    public void serialAndParallelProduceTheSameList() {
        int n = 40;
        PairScheduler.Task<Integer> task = new PairScheduler.Task<Integer>() {
            @Override
            public Integer run(int index) {
                return Integer.valueOf(index * 7 + 1);
            }
        };
        List<Integer> serial = PairScheduler.map(n, 1, task,
                PairScheduler.Progress.NONE, Cancellation.never());
        List<Integer> parallel = PairScheduler.map(n, 6, task,
                PairScheduler.Progress.NONE, Cancellation.never());
        assertEquals(serial, parallel);
    }

    @Test
    public void progressReachesTheTotal() {
        final int n = 20;
        final AtomicInteger last = new AtomicInteger();
        PairScheduler.map(n, 4, new PairScheduler.Task<Integer>() {
            @Override
            public Integer run(int index) {
                return Integer.valueOf(index);
            }
        }, new PairScheduler.Progress() {
            @Override
            public void update(int done, int total) {
                assertEquals(n, total);
                last.accumulateAndGet(done, Math::max);
            }
        }, Cancellation.never());
        assertEquals(n, last.get());
    }

    @Test
    public void cancellationBeforeAnyWorkThrows() {
        Cancellation.Flag stop = Cancellation.flag();
        stop.cancel();
        try {
            PairScheduler.map(50, 4, new PairScheduler.Task<Integer>() {
                @Override
                public Integer run(int index) {
                    return Integer.valueOf(index);
                }
            }, PairScheduler.Progress.NONE, stop);
            fail("expected cancellation");
        } catch (CancellationException expected) {
            // the contract
        }
    }

    /** Cancelling part-way must stop the run rather than quietly returning a half-filled list. */
    @Test
    public void cancellationPartWayThroughThrows() {
        final AtomicInteger started = new AtomicInteger();
        final Cancellation.Flag stop = Cancellation.flag();
        try {
            PairScheduler.map(200, 4, new PairScheduler.Task<Integer>() {
                @Override
                public Integer run(int index) throws Exception {
                    if (started.incrementAndGet() > 20) stop.cancel();
                    Thread.sleep(1);
                    return Integer.valueOf(index);
                }
            }, PairScheduler.Progress.NONE, stop);
            fail("expected cancellation");
        } catch (CancellationException expected) {
            assertTrue("should have stopped well short of 200: " + started.get(),
                    started.get() < 200);
        }
    }

    /** The first failure propagates, and nothing is left running. */
    @Test
    public void firstFailurePropagates() {
        try {
            PairScheduler.map(30, 4, new PairScheduler.Task<Integer>() {
                @Override
                public Integer run(int index) {
                    if (index == 7) throw new IllegalStateException("boom at 7");
                    return Integer.valueOf(index);
                }
            }, PairScheduler.Progress.NONE, Cancellation.never());
            fail("expected the failure to propagate");
        } catch (IllegalStateException expected) {
            assertEquals("boom at 7", expected.getMessage());
        }
    }

    /** A checked exception is wrapped with the index that caused it, not swallowed. */
    @Test
    public void checkedExceptionsAreWrappedWithTheirIndex() {
        try {
            PairScheduler.map(10, 1, new PairScheduler.Task<Integer>() {
                @Override
                public Integer run(int index) throws Exception {
                    if (index == 3) throw new Exception("checked");
                    return Integer.valueOf(index);
                }
            }, PairScheduler.Progress.NONE, Cancellation.never());
            fail("expected a wrapped failure");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("task 3"));
        }
    }

    @Test
    public void zeroTasksIsNotAnError() {
        assertTrue(PairScheduler.map(0, 4, new PairScheduler.Task<Integer>() {
            @Override
            public Integer run(int index) {
                return Integer.valueOf(index);
            }
        }, PairScheduler.Progress.NONE, Cancellation.never()).isEmpty());
    }

    /** Every worker count gives the same answers, bit for bit, on arithmetic that can show it. */
    @Test
    public void everyWorkerCountGivesTheSameAnswers() {
        int n = 64;
        PairScheduler.Task<Double> task = new PairScheduler.Task<Double>() {
            @Override
            public Double run(int index) {
                double total = 0;
                for (int i = 0; i < 100; i++) total += Math.sin(index + i * 0.017);
                return Double.valueOf(total);
            }
        };
        List<Double> serial = PairScheduler.map(n, 1, task,
                PairScheduler.Progress.NONE, Cancellation.never());
        List<Double> two = PairScheduler.map(n, 2, task,
                PairScheduler.Progress.NONE, Cancellation.never());
        List<Double> wide = PairScheduler.map(n, Math.max(2, Runtime.getRuntime()
                .availableProcessors()), task, PairScheduler.Progress.NONE, Cancellation.never());
        for (int i = 0; i < n; i++) {
            assertEquals("index " + i, serial.get(i).doubleValue(), two.get(i).doubleValue(), 0.0);
            assertEquals("index " + i, serial.get(i).doubleValue(), wide.get(i).doubleValue(), 0.0);
        }
    }
}
