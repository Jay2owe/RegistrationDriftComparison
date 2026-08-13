/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import regdrift.Cancellation;
import regdrift.internal.PairScheduler;
import regdrift.internal.PyramidCache;

import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Decides which frame pairs get measured, and works out the one search bound they
 * are all measured with.
 *
 * <p>The everyday version: to describe how a long train journey rode, you do not
 * have to feel every metre of track. Sit still for a minute near the start, a
 * minute in the middle and a minute near the end, and note how far the scenery
 * moved between each sitting. Three minutes of attention describes a two-hour
 * journey as well as it describes a ten-hour one.
 *
 * <h2>Why this class is the reason the plugin can be run first</h2>
 *
 * <p>Measuring every consecutive pair of a recording costs 3-10 s on a 48-frame
 * stack and grows in a straight line with length: 30-100 s on a 500-frame
 * overnight recording. That is too slow for a step whose whole purpose is to run
 * <em>before</em> anybody decides anything.
 *
 * <p>This sampler measures {@code W} windows of {@code K} consecutive frames,
 * evenly spaced, plus one <b>bridge pair</b> across each gap - the last frame of
 * one window against the first frame of the next. The cost is
 * {@code W*(K-1) + (W-1)} frame pairs, which does not depend on the length of the
 * recording at all: <b>35 pairs at the default {@code W=3, K=12} whether the
 * recording is 48 frames or 5,000</b>.
 *
 * <p>Every pair inside a window is genuinely consecutive, so the step statistics,
 * the localisability and the estimator agreement are exact rather than corrected
 * for a stride. Subsampling by stride was considered and rejected for exactly
 * that reason: it changes what is measured, not only how much of it, and the
 * {@code wander} descriptor - which exists to tell white jitter from a random
 * walk - is one of the things it changes.
 *
 * <h2>Prefer fewer, longer windows. Do not add windows for coverage</h2>
 *
 * <p>This is measured, it is counter-intuitive, and it sets the default.
 * {@code W=4, K=12} on a 48-frame recording is not a subsample at all - four
 * contiguous twelve-frame windows cover every frame, and their three bridges are
 * themselves consecutive pairs, so it measures the same 47 transitions as
 * measuring everything. It reproduced the full-recording motion label on 11 of
 * the 12 library recordings. {@code W=3, K=12} measured twelve <em>fewer</em>
 * pairs and reproduced 12 of 12.
 *
 * <p>Each window adds a stitch point, and stitching error accumulates faster than
 * extra coverage removes it. Adding windows is therefore not a way to buy
 * confidence, and this class's defaults are not a starting point for tuning.
 *
 * <h2>One bound for the whole recording, derived in two passes</h2>
 *
 * <p>A displacement search needs to be told how far to look. {@link #bound} runs
 * the cheaper of the two estimators over <b>exactly the pairs the sampler will
 * measure</b> - the window pairs and the bridge pairs together - and turns the
 * largest displacement it finds into one number that every later measurement
 * uses.
 *
 * <p>Deriving it per window instead is the mistake this method exists to prevent.
 * A quiet stretch of a recording gets a bound near the floor, that bound then
 * clamps the genuine motion in it, and the descriptors end up describing the
 * search box rather than the movie. The first run of the measurement that settled
 * this scored 3-6 of 8 with per-window bounds; the same definition with one
 * global bound scored 5-10 of 12.
 *
 * <p>Measured 2026-08-12 on the twelve library recordings; the record is
 * {@code t4\RESULT.md} beside the plugin's contract. Everything there is IncuCyte
 * phase contrast and only one recording is longer than 48 frames, so the
 * constant-cost claim at 500 frames is an extrapolation from one measurement, not
 * a measurement. Nothing here should be tightened on the strength of it.
 */
public final class WindowSampler {

    /** Windows in the measured default. Three. */
    public static final int DEFAULT_WINDOWS = 3;

    /** Consecutive frames per window in the measured default. Twelve. */
    public static final int DEFAULT_FRAMES_PER_WINDOW = 12;

    /**
     * The smallest search bound, and what it is for.
     *
     * <p>A recording that barely moves would otherwise be measured with a bound of
     * a fraction of a pixel, and the first frame pair that does move would sit on
     * it. Copied from the survey these constants come from.
     */
    static final double MIN_SHIFT_PX = 12;

    /** How much room past the largest displacement seen the bound leaves. */
    static final double SHIFT_HEADROOM = 1.5;

    /** The largest bound this will ever return, however far the frames moved. */
    static final double MAX_SHIFT_PX = 256;

    private final int windows;
    private final int framesPerWindow;

    private WindowSampler(int windows, int framesPerWindow) {
        this.windows = windows;
        this.framesPerWindow = framesPerWindow;
    }

    /**
     * The measured default: three windows of twelve consecutive frames, two
     * bridges, 35 frame pairs whatever the recording's length.
     */
    public static WindowSampler measured() {
        return new WindowSampler(DEFAULT_WINDOWS, DEFAULT_FRAMES_PER_WINDOW);
    }

    /**
     * A sampler with a stated shape.
     *
     * @param windows         how many windows; {@code 0} means measure every
     *                        consecutive pair and take no shortcut at all
     * @param framesPerWindow consecutive frames in each window, at least 2. Read
     *                        only when {@code windows} is above zero
     */
    public static WindowSampler of(int windows, int framesPerWindow) {
        if (windows < 0) {
            throw new IllegalArgumentException("a sampler measures zero or more windows, was "
                    + windows + "; zero means every consecutive pair");
        }
        if (windows == 0) return new WindowSampler(0, 0);
        if (framesPerWindow < 2) {
            throw new IllegalArgumentException("a window of " + framesPerWindow + " frames holds"
                    + " no transition; a window is 2 frames or more");
        }
        return new WindowSampler(windows, framesPerWindow);
    }

    /**
     * Measure every consecutive pair - the exact measurement the windowed one is
     * checked against, and what a user asks for with {@code windows=0}.
     */
    public static WindowSampler everyConsecutivePair() {
        return new WindowSampler(0, 0);
    }

    /** How many windows were asked for; {@code 0} means every consecutive pair. */
    public int windows() {
        return windows;
    }

    /** How many consecutive frames per window were asked for. */
    public int framesPerWindow() {
        return framesPerWindow;
    }

    /**
     * Where the windows fall on a recording of this length, and which pairs that
     * makes.
     *
     * <p>Deterministic: the same length always gives the same placement, and the
     * placement is written into the provenance so a diagnosis can be reproduced.
     *
     * @param frameCount frames on the time axis, at least 2
     */
    public Plan plan(int frameCount) {
        if (frameCount < 2) {
            throw new IllegalArgumentException("a recording needs two frames to have a transition"
                    + " between them, and this one has " + frameCount);
        }
        int w = windows;
        int k = framesPerWindow;
        boolean reduced = false;
        if (w == 0) {
            // Every consecutive pair is one window covering the whole recording, so the placement
            // arithmetic below is the same code and there is no separate path to divide by zero in.
            w = 1;
            k = frameCount;
        } else if ((long) w * k > frameCount) {
            // Too long for this recording. Shed WINDOWS first, never shorten them and never let
            // them overlap: an overlapping window measures the same steps twice, which inflates
            // step_rms and the knock threshold that is computed from it. Fewer and longer is also
            // the direction the measurement pointed.
            reduced = true;
            w = Math.max(1, frameCount / k);
            if ((long) w * k > frameCount || w == 1) {
                // One window left. Grow it to the whole recording rather than measuring twelve
                // frames of a twenty-frame stack: at this length the exact measurement is cheaper
                // than the default sample would have been anyway.
                w = 1;
                k = frameCount;
            }
        }
        int[] starts = windowStarts(frameCount, w, k);
        return new Plan(windows, framesPerWindow, frameCount, w, k, starts, reduced);
    }

    /**
     * Where the windows start: evenly spaced, the first at frame 0 and the last
     * ending on the final frame.
     *
     * <p>Copied from the harness that measured the sampler. {@code W == 1} takes
     * its own line because the spacing divides by {@code W - 1}.
     */
    private static int[] windowStarts(int frameCount, int w, int k) {
        int[] starts = new int[w];
        if (w == 1) return starts;
        double span = frameCount - k;
        for (int i = 0; i < w; i++) {
            starts[i] = (int) Math.round(i * span / (w - 1));
        }
        return starts;
    }

    /**
     * The bound a largest observed displacement earns: headroom above it, floored
     * so a still recording is not measured through a keyhole, and capped.
     */
    public static double boundFor(double largestStepPx) {
        if (Double.isNaN(largestStepPx) || largestStepPx < 0) return MIN_SHIFT_PX;
        return Math.max(MIN_SHIFT_PX,
                Math.min(MAX_SHIFT_PX, SHIFT_HEADROOM * largestStepPx + MIN_SHIFT_PX));
    }

    // ------------------------------------------------------------- the bound

    /**
     * Pass one: measure this plan's pairs cheaply and derive <b>one</b> search
     * bound for the whole recording from the window pairs and the bridge pairs
     * together.
     *
     * <p>Phase correlation does this pass on its own because it needs no search
     * box - it reads a displacement off a correlation surface rather than
     * searching for one - so the bound it produces is not a function of a bound
     * somebody had to guess first. Its own bound here is
     * {@link #MAX_SHIFT_PX}, which is also the largest number this method can
     * return, so the pass is never narrower than its own output range.
     *
     * <p>Pairs are measured on the calling thread's frames: every plane is read
     * before any worker starts, because ImageJ state belongs to the coordinator.
     *
     * @param frames       where the planes come from
     * @param plan         which pairs; both kinds contribute to the one answer
     * @param workers      0 or less to decide automatically, 1 to force serial.
     *                     Serial, two-worker and full-width runs give the same
     *                     bits
     * @param progress     told as pairs complete; may be called from any thread
     * @param cancellation looked at between pairs
     * @throws java.util.concurrent.CancellationException if the run was stopped
     *         part-way
     */
    public static Bound bound(FrameSource frames, Plan plan, int workers,
                              PairScheduler.Progress progress, Cancellation cancellation) {
        if (frames == null) throw new IllegalArgumentException("no frames to measure");
        if (plan == null) throw new IllegalArgumentException("no plan to measure");
        final Frames.Bin scale = frames.bin();
        if (scale == null) {
            throw new IllegalArgumentException("these frames do not say what scale they were"
                    + " sampled at, and a search bound in pixels is meaningless without it."
                    + " See defect D12");
        }
        final int width = frames.width();
        final int height = frames.height();
        if (plan.frameCount() > frames.count()) {
            throw new IllegalArgumentException("the plan covers " + plan.frameCount()
                    + " frames and the source holds " + frames.count());
        }
        final int[][] pairs = plan.pairs();
        if (pairs.length == 0) {
            return new Bound(MIN_SHIFT_PX, 0, 0, 0, 0, 0, scale, plan);
        }

        final TreeMap<Integer, Integer> slots = new TreeMap<Integer, Integer>();
        for (int p = 0; p < pairs.length; p++) {
            for (int side = 0; side < 2; side++) {
                Integer t = Integer.valueOf(pairs[p][side]);
                if (!slots.containsKey(t)) slots.put(t, Integer.valueOf(slots.size()));
            }
        }
        final float[][] planes = new float[slots.size()][];
        for (java.util.Map.Entry<Integer, Integer> entry : slots.entrySet()) {
            if (cancellation.canceled()) {
                throw new java.util.concurrent.CancellationException("canceled");
            }
            planes[entry.getValue().intValue()] = frames.plane(entry.getKey().intValue());
        }

        final int size = PhaseCorrelation.transformSize(width, height);
        final long perFrame = 16L * size * size;
        final int used = PairScheduler.workersFor(pairs.length, workers, 2L * perFrame, 0);
        final PyramidCache<double[][]> transforms = new PyramidCache<double[][]>(
                new PyramidCache.Builder<double[][]>() {
                    @Override
                    public double[][] build(int slot) {
                        return PhaseCorrelation.transform(planes[slot], width, height);
                    }
                }, PyramidCache.capacityFor(planes.length, width, height,
                bytesPerPixel(perFrame, width, height), 1, used, 0));

        List<Estimator.Displacement> pass;
        try {
            pass = PairScheduler.map(pairs.length, used,
                    new PairScheduler.Task<Estimator.Displacement>() {
                        @Override
                        public Estimator.Displacement run(int index) {
                            int a = slots.get(Integer.valueOf(pairs[index][0])).intValue();
                            int b = slots.get(Integer.valueOf(pairs[index][1])).intValue();
                            return PhaseCorrelation.shiftOf(transforms.get(a), transforms.get(b),
                                    size, MAX_SHIFT_PX, scale);
                        }
                    }, progress, cancellation);
        } finally {
            transforms.clear();
        }

        // Reduced in index order, so a serial run and a sixteen-worker run fold the same numbers in
        // the same sequence and land on the same bits.
        int windowPairs = plan.windowPairCount();
        double largestWindow = 0;
        double largestBridge = 0;
        int measured = 0;
        int refused = 0;
        for (int i = 0; i < pass.size(); i++) {
            Estimator.Displacement d = pass.get(i);
            if (!d.defined()) {
                refused++;
                continue;
            }
            measured++;
            double magnitude = d.magnitude();
            if (i < windowPairs) {
                if (magnitude > largestWindow) largestWindow = magnitude;
            } else {
                if (magnitude > largestBridge) largestBridge = magnitude;
            }
        }
        double largest = Math.max(largestWindow, largestBridge);
        return new Bound(boundFor(largest), largest, largestWindow, largestBridge,
                measured, refused, scale, plan);
    }

    /** A per-entry cost expressed per full-resolution pixel, which is what the cache asks for. */
    private static long bytesPerPixel(long bytesPerEntry, int width, int height) {
        long pixels = Math.max(1L, (long) width * height);
        return Math.max(1L, (bytesPerEntry + pixels - 1) / pixels);
    }

    @Override
    public String toString() {
        return windows == 0
                ? "WindowSampler[every consecutive pair]"
                : "WindowSampler[" + windows + " windows of " + framesPerWindow + " frames]";
    }

    // -------------------------------------------------------------- the plan

    /**
     * Where the windows landed on one recording, and every frame pair that follows
     * from that.
     *
     * <p>Pairs are held in one order and stay in it: <b>all the within-window
     * pairs first</b>, window by window and then frame by frame, followed by the
     * bridge pairs in gap order. Window {@code w}'s pairs are therefore the
     * {@code K-1} entries starting at {@code w * (K - 1)}, and bridge {@code i}
     * is at {@code windowPairCount() + i}. Everything downstream indexes by that,
     * which is what lets a measurement come back as one flat list and still be
     * read as windows and gaps.
     */
    public static final class Plan {

        private final int requestedWindows;
        private final int requestedFramesPerWindow;
        private final int frameCount;
        private final int windows;
        private final int framesPerWindow;
        private final int[] starts;
        private final int[][] pairs;
        private final boolean reduced;

        Plan(int requestedWindows, int requestedFramesPerWindow, int frameCount, int windows,
             int framesPerWindow, int[] starts, boolean reduced) {
            this.requestedWindows = requestedWindows;
            this.requestedFramesPerWindow = requestedFramesPerWindow;
            this.frameCount = frameCount;
            this.windows = windows;
            this.framesPerWindow = framesPerWindow;
            this.starts = starts;
            this.reduced = reduced;
            int within = windows * (framesPerWindow - 1);
            int bridges = windows - 1;
            this.pairs = new int[within + bridges][];
            for (int w = 0; w < windows; w++) {
                for (int j = 1; j < framesPerWindow; j++) {
                    int t = starts[w] + j;
                    pairs[w * (framesPerWindow - 1) + (j - 1)] = new int[]{t - 1, t};
                }
            }
            for (int i = 0; i < bridges; i++) {
                pairs[within + i] = new int[]{starts[i] + framesPerWindow - 1, starts[i + 1]};
            }
            for (int w = 1; w < windows; w++) {
                if (starts[w] < starts[w - 1] + framesPerWindow) {
                    throw new IllegalStateException("windows " + (w - 1) + " and " + w
                            + " overlap at frames " + starts[w - 1] + " and " + starts[w]
                            + "; an overlapping window measures the same steps twice");
                }
            }
        }

        /** Frames on the recording's time axis. */
        public int frameCount() {
            return frameCount;
        }

        /** Windows actually placed. */
        public int windows() {
            return windows;
        }

        /** Consecutive frames in each window, as placed. */
        public int framesPerWindow() {
            return framesPerWindow;
        }

        /** Windows asked for; {@code 0} means every consecutive pair was asked for. */
        public int requestedWindows() {
            return requestedWindows;
        }

        /** Frames per window asked for. */
        public int requestedFramesPerWindow() {
            return requestedFramesPerWindow;
        }

        /**
         * True when the recording was too short for the shape asked for and the
         * sampler fell back. What it fell back to is in {@link #provenance()}.
         */
        public boolean reduced() {
            return reduced;
        }

        /** Zero-based frame each window starts on, in order. A fresh copy. */
        public int[] windowStarts() {
            return starts.clone();
        }

        /** Zero-based frame window {@code w} ends on. */
        public int windowEnd(int w) {
            return starts[w] + framesPerWindow - 1;
        }

        /**
         * Every pair to measure, as {@code {from, to}} zero-based frame indices, in
         * the order described on this class. A fresh copy.
         */
        public int[][] pairs() {
            int[][] copy = new int[pairs.length][];
            for (int i = 0; i < pairs.length; i++) copy[i] = pairs[i].clone();
            return copy;
        }

        /** How many of {@link #pairs()} are within-window pairs. */
        public int windowPairCount() {
            return windows * (framesPerWindow - 1);
        }

        /** How many gaps there are, and so how many bridge pairs. */
        public int bridgeCount() {
            return windows - 1;
        }

        /** Where bridge {@code i}'s pair sits in {@link #pairs()}. */
        public int bridgePairIndex(int i) {
            return windowPairCount() + i;
        }

        /** The two zero-based frames bridge {@code i} spans. A fresh copy. */
        public int[] bridge(int i) {
            return pairs[bridgePairIndex(i)].clone();
        }

        /** Where window {@code w}'s first pair sits in {@link #pairs()}. */
        public int firstPairOfWindow(int w) {
            return w * (framesPerWindow - 1);
        }

        /**
         * Frame pairs this plan measures. Constant in the length of the recording
         * once the windows fit: {@code W*(K-1) + (W-1)}, which is 35 at the
         * measured default.
         */
        public int measuredPairs() {
            return pairs.length;
        }

        /**
         * Displacement estimates the fingerprint will make - two per pair, because
         * two independent estimators measure every one of them and the distance
         * between their answers is the confidence signal.
         */
        public int estimateCount() {
            return 2 * pairs.length;
        }

        /**
         * True when this plan happens to measure the whole consecutive chain: one
         * window covering every frame, and no gaps to bridge.
         */
        public boolean everyConsecutivePair() {
            return windows == 1 && framesPerWindow == frameCount;
        }

        /** One sentence naming what was measured and where, for the saved notes. */
        public String provenance() {
            StringBuilder out = new StringBuilder();
            if (everyConsecutivePair()) {
                out.append("every consecutive pair of ").append(frameCount).append(" frames");
            } else {
                out.append(windows).append(windows == 1 ? " window of " : " windows of ")
                        .append(framesPerWindow).append(" consecutive frames starting at ");
                for (int w = 0; w < windows; w++) {
                    if (w > 0) out.append(", ");
                    out.append(starts[w]);
                }
                out.append(", with ").append(bridgeCount())
                        .append(bridgeCount() == 1 ? " bridge pair" : " bridge pairs")
                        .append(" across the gaps");
            }
            out.append("; ").append(pairs.length)
                    .append(pairs.length == 1 ? " frame pair" : " frame pairs").append(" measured");
            if (reduced) {
                out.append(". ").append(requestedWindows).append(" windows of ")
                        .append(requestedFramesPerWindow)
                        .append(" frames were asked for and need ")
                        .append(requestedWindows * requestedFramesPerWindow)
                        .append(" frames; this recording has ").append(frameCount)
                        .append(", so fewer windows were placed rather than overlapping them");
            }
            return out.toString();
        }

        @Override
        public String toString() {
            return "Plan[" + provenance() + "]";
        }
    }

    // ------------------------------------------------------------- the bound

    /**
     * One search bound for a whole recording, and what it was derived from.
     *
     * <p>The same number reaches every window and every bridge. The two
     * {@code largest...} readings are kept apart only so a reader can see which
     * kind of pair set the bound - a bridge spans a gap and routinely moves
     * further than any single step inside a window does.
     */
    public static final class Bound {

        private final double px;
        private final double largestStepPx;
        private final double largestWindowStepPx;
        private final double largestBridgeStepPx;
        private final int measuredPairs;
        private final int refusedPairs;
        private final Frames.Bin measuredAt;
        private final Plan plan;

        Bound(double px, double largestStepPx, double largestWindowStepPx,
              double largestBridgeStepPx, int measuredPairs, int refusedPairs,
              Frames.Bin measuredAt, Plan plan) {
            this.px = px;
            this.largestStepPx = largestStepPx;
            this.largestWindowStepPx = largestWindowStepPx;
            this.largestBridgeStepPx = largestBridgeStepPx;
            this.measuredPairs = measuredPairs;
            this.refusedPairs = refusedPairs;
            this.measuredAt = measuredAt;
            this.plan = plan;
        }

        /** The bound itself, in the pixels of {@link #measuredAt()}. */
        public double px() {
            return px;
        }

        /** Largest displacement pass one found anywhere, window or bridge. */
        public double largestStepPx() {
            return largestStepPx;
        }

        /** Largest displacement pass one found inside a window. */
        public double largestWindowStepPx() {
            return largestWindowStepPx;
        }

        /** Largest displacement pass one found across a gap. Zero when there are no gaps. */
        public double largestBridgeStepPx() {
            return largestBridgeStepPx;
        }

        /** How many pairs pass one could read. */
        public int measuredPairs() {
            return measuredPairs;
        }

        /**
         * How many pairs pass one refused - featureless, saturated, or with
         * nothing measured in common. They contribute nothing to the bound rather
         * than contributing a zero.
         */
        public int refusedPairs() {
            return refusedPairs;
        }

        /** The effective pixel size the bound is expressed in. */
        public Frames.Bin measuredAt() {
            return measuredAt;
        }

        /** The placement this bound was derived over. */
        public Plan plan() {
            return plan;
        }

        /** True when the bound is the floor because nothing moved far enough to raise it. */
        public boolean atFloor() {
            return px <= MIN_SHIFT_PX;
        }

        /** One sentence saying where the number came from, for the saved notes. */
        public String provenance() {
            if (measuredPairs == 0) {
                return String.format(Locale.US, "search bound %.1f px, the floor: none of the %d"
                                + " frame pairs could be read, so nothing raised it",
                        px, plan.measuredPairs());
            }
            return String.format(Locale.US, "search bound %.1f px for the whole recording, from"
                            + " the largest of %d measured pairs (%.1f px within a window, %.1f px"
                            + " across a gap) at %s",
                    px, measuredPairs, largestWindowStepPx, largestBridgeStepPx,
                    measuredAt.provenance());
        }

        @Override
        public String toString() {
            return "Bound[" + provenance() + "]";
        }
    }
}
