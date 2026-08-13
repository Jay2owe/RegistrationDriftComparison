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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The cache builds each frame once, holds a bounded number, and evicts the oldest. */
public class PyramidCacheTest {

    private static PyramidCache.Builder<float[]> counting(final AtomicInteger builds) {
        return new PyramidCache.Builder<float[]>() {
            @Override
            public float[] build(int frame) {
                builds.incrementAndGet();
                float[] out = new float[16];
                for (int i = 0; i < out.length; i++) out[i] = frame * 100f + i;
                return out;
            }
        };
    }

    /** Two threads asking for the same frame must build it once. */
    @Test
    public void eachFrameIsBuiltOnce() {
        final AtomicInteger builds = new AtomicInteger();
        final PyramidCache<float[]> cache = new PyramidCache<float[]>(counting(builds), 8);
        PairScheduler.map(64, 8, new PairScheduler.Task<float[]>() {
            @Override
            public float[] run(int index) {
                return cache.get(index % 4);
            }
        }, PairScheduler.Progress.NONE, Cancellation.never());
        assertEquals("four distinct frames requested sixteen times each", 4, builds.get());
        assertTrue("and the rest were hits: " + cache.hits(), cache.hits() > 50);
        assertEquals(4, cache.misses());
    }

    /** A hit hands back the same object, not a rebuild. */
    @Test
    public void aHitIsTheSameObject() {
        PyramidCache<float[]> cache = new PyramidCache<float[]>(counting(new AtomicInteger()), 4);
        assertSame(cache.get(2), cache.get(2));
        assertEquals(1, cache.size());
    }

    /** Beyond capacity, the least recently used entry goes. */
    @Test
    public void evictionIsBoundedAndLeastRecentlyUsed() {
        AtomicInteger builds = new AtomicInteger();
        PyramidCache<float[]> cache = new PyramidCache<float[]>(counting(builds), 2);
        cache.get(0);
        cache.get(1);
        cache.get(0);                        // 0 is now the more recently used of the two
        cache.get(2);                        // evicts 1
        assertEquals(2, cache.size());
        assertEquals(3, builds.get());
        cache.get(0);
        assertEquals("0 was still resident", 3, builds.get());
        cache.get(1);
        assertEquals("1 had been evicted and is rebuilt", 4, builds.get());
    }

    @Test
    public void clearingReleasesEverything() {
        PyramidCache<float[]> cache = new PyramidCache<float[]>(counting(new AtomicInteger()), 4);
        cache.get(0);
        cache.get(1);
        cache.clear();
        assertEquals(0, cache.size());
    }

    /** Capacity must cover how far ahead the caller looks, and never fall below a working pair. */
    @Test
    public void capacityCoversTheReachAndTheWorkers() {
        assertTrue("a tiny budget must still afford a pair",
                PyramidCache.capacityFor(100, 2048, 2048, 16, 8, 1) >= 2);
        assertTrue("a generous budget should hold the whole recording",
                PyramidCache.capacityFor(20, 64, 64, 1, 1, 1L << 30) >= 20);
        assertEquals("never more entries than there are frames",
                20, PyramidCache.capacityFor(20, 64, 64, 1, 1, 1L << 30));
        assertTrue("reach and workers set the floor",
                PyramidCache.capacityFor(100, 2048, 2048, 16, 8, 1) >= 16 + 1 + 8);
    }

    /** A float plane costs a quarter of what a pyramid does, so more of them fit. */
    @Test
    public void aStatedEntrySizeChangesWhatIsAffordable() {
        long budget = 40L * 1024 * 1024;                 // 40 MB
        int pyramids = PyramidCache.capacityFor(500, 512, 512, 2, 1, budget);
        int planes = PyramidCache.capacityFor(500, 512, 512,
                PyramidCache.PLANE_BYTES_PER_PIXEL, 2, 1, budget);
        assertTrue("plane cache " + planes + " should hold more than pyramid cache " + pyramids,
                planes > pyramids);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aCapacityBelowOnePairIsRefused() {
        new PyramidCache<float[]>(counting(new AtomicInteger()), 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aCacheWithNoBuilderIsRefused() {
        new PyramidCache<float[]>(null, 4);
    }
}
