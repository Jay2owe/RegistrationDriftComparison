/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.internal;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Works out each frame's expensive derived form once and keeps a bounded number
 * of them alive.
 *
 * <p>The everyday version: a photocopier with a small out-tray. Copying a page is
 * the slow part, the same page gets asked for several times in a row, and the
 * tray is emptied oldest-first when it fills up.
 *
 * <p><b>What it holds is left to the caller</b> - an image pyramid, a binned
 * plane, whatever a stage builds per frame and reads more than once. The type
 * parameter is deliberate: this class knows about counting and evicting and
 * nothing about pixels.
 *
 * <p><b>Why it earns its place.</b> Each frame appears in more than one frame
 * pair, and its derived form is identical every time. Built on demand per pair,
 * a chain of {@code T} frames costs {@code 2T} constructions; cached, it costs
 * {@code T}. The number of pair measurements is unchanged - only the repeated
 * work disappears.
 *
 * <p><b>Why bounded, and not simply all of them.</b> An image pyramid costs
 * roughly 18 bytes per full-resolution pixel: three float arrays and a boolean
 * mask at each level, summing over the levels to about 1.33 times the base. For
 * a 101-frame 512x512 recording, holding every one is about 480 MB - survivable
 * but wasteful. For 1000 frames at 2048x2048 it is 75 GB, which is not. So
 * capacity is derived from a memory budget and from how far ahead the caller
 * actually looks, plus slack for the workers in flight.
 *
 * <p>Eviction is least-recently-used. Because work is visited in index order, LRU
 * behaves as a sliding window over the recording and the hit rate is essentially
 * one.
 *
 * <p><b>Per run, never static.</b> A cache that outlived a run would pin a
 * recording's worth of memory for as long as Fiji stayed open, and would hand one
 * run's frames to the next.
 *
 * <p>Thread-safe. Two workers asking for the same frame at the same time build it
 * once: the second waits on the first rather than duplicating the work, which
 * matters because building the thing is the expensive part this class exists to
 * avoid doing twice.
 *
 * <p>Lifted from {@code logratio\core\PyramidCache.java} in the Log-Ratio
 * Registration research repository and made generic in the lift, so that it
 * names no registration criterion of its own.
 *
 * @param <T> what is built per frame
 */
public final class PyramidCache<T> {

    /**
     * Bytes of image pyramid per full-resolution pixel: three floats and a
     * boolean per level, 1.33x for the levels below the base. Counted from the
     * arrays, not profiled - treat as indicative.
     */
    public static final long BYTES_PER_PIXEL = 18;

    /** Bytes one float plane costs per pixel. What a binned plane cache pays. */
    public static final long PLANE_BYTES_PER_PIXEL = 4;

    /** Builds the entry for one frame. Called at most once per frame per cache. */
    public interface Builder<T> {
        T build(int frame);
    }

    private final Builder<T> builder;
    private final int capacity;
    private final LinkedHashMap<Integer, T> entries;
    private final Map<Integer, Object> locks = new HashMap<Integer, Object>();
    private long hits;
    private long misses;

    public PyramidCache(Builder<T> builder, int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be >= 2, was " + capacity
                    + " - measuring a frame pair needs two entries live at once");
        }
        if (builder == null) {
            throw new IllegalArgumentException("a cache with no builder can never fill");
        }
        this.builder = builder;
        this.capacity = capacity;
        this.entries = new LinkedHashMap<Integer, T>(capacity * 2, 0.75f, true);
    }

    /**
     * Capacity for a run: enough for how far ahead the caller looks and the
     * workers in flight, then as much more as the memory budget allows, never
     * more than the frame count.
     *
     * @param budgetBytes memory to spend; 0 or less takes a quarter of the heap
     */
    public static int capacityFor(int frames, int width, int height, int reach, int workers,
                                  long budgetBytes) {
        return capacityFor(frames, width, height, BYTES_PER_PIXEL, reach, workers, budgetBytes);
    }

    /**
     * Capacity for a run, for an entry of a stated size.
     *
     * <p>Defaulting to the bare minimum was a false economy: the minimum is
     * exactly the reach, which is the size at which any imperfection in access
     * order thrashes. A quarter of the heap holds every entry of a typical
     * recording and still leaves room for the stack itself, the output, and
     * whatever else Fiji has open.
     *
     * @param bytesPerPixel what one entry costs per full-resolution pixel
     * @param budgetBytes   memory to spend; 0 or less takes a quarter of the heap
     */
    public static int capacityFor(int frames, int width, int height, long bytesPerPixel,
                                  int reach, int workers, long budgetBytes) {
        int minimum = Math.max(2, reach + 1 + Math.max(1, workers));
        long perFrame = Math.max(1L, Math.max(1L, bytesPerPixel) * (long) width * height);
        long available = budgetBytes > 0 ? budgetBytes : Runtime.getRuntime().maxMemory() / 4;
        long affordable = available / perFrame;
        int capacity = (int) Math.max(minimum, Math.min(frames, affordable));
        return Math.max(2, Math.min(capacity, Math.max(2, frames)));
    }

    /** The entry for {@code frame}, building it if it is not resident. */
    public T get(int frame) {
        Object lock;
        synchronized (this) {
            T hit = entries.get(frame);
            if (hit != null) {
                hits++;
                return hit;
            }
            Object existing = locks.get(frame);
            if (existing == null) {
                existing = new Object();
                locks.put(frame, existing);
            }
            lock = existing;
        }
        // Built outside the cache lock: construction is the expensive part and holding the lock
        // through it would serialise every worker onto one core.
        synchronized (lock) {
            synchronized (this) {
                T hit = entries.get(frame);
                if (hit != null) {
                    hits++;
                    return hit;
                }
            }
            T built = builder.build(frame);
            synchronized (this) {
                misses++;
                entries.put(frame, built);
                evict();
                locks.remove(frame);
            }
            return built;
        }
    }

    /** Drop everything. Called when a run finishes so the cache pins no memory. */
    public synchronized void clear() {
        entries.clear();
        locks.clear();
    }

    public synchronized long hits() {
        return hits;
    }

    public synchronized long misses() {
        return misses;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** How many entries this cache will hold before it starts evicting. */
    public int capacity() {
        return capacity;
    }

    /** Caller must hold the monitor. */
    private void evict() {
        while (entries.size() > capacity) {
            Integer oldest = entries.keySet().iterator().next();
            if (oldest == null) return;
            entries.remove(oldest);
        }
    }
}
