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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Runs both estimators over a list of frame pairs and reports how far apart
 * their answers were.
 *
 * <p>The everyday version: two people measure the same set of distances with two
 * different instruments. The measurements are the point, and so is the spread
 * between them - where they agree the number can be relied on, and where they do
 * not, the honest report is that the recording did not give either of them
 * enough to go on.
 *
 * <p>That spread is {@code agreement_px} in the diagnosis, and it is the
 * <b>median</b> of the per-pair differences, never the mean. One pair sitting on
 * the search bound, or one transition where a bubble crossed the field, would
 * otherwise swamp a confidence signal computed over thirty-five good ones.
 *
 * <h2>What is shared between the two estimators, and what is not</h2>
 *
 * <p>Shared: reading the frames. Each frame is read from the {@link FrameSource}
 * once, on the calling thread, and both estimators are handed the same float
 * array. Reading is what touches ImageJ and what costs disk.
 *
 * <p>Not shared: everything after that. Each estimator gets its own
 * {@link PyramidCache} holding its own per-frame derived form - transforms for
 * one, image pyramids for the other - because a schedule that suited both would
 * have to be a compromise, and the point of running two is that they are not
 * compromised toward each other.
 *
 * <h2>A plain chain, and no reconciliation, ever</h2>
 *
 * <p>Every pair here is measured on its own and reported on its own. Nothing
 * adjusts one pair's answer using another pair's, and there is no parameter that
 * would turn such a thing on. This is defect D5, and it is not a style
 * preference.
 *
 * <p>Multi-lag reconciliation - measuring frame 1 against 3, 1 against 4, and
 * solving for a consistent chain - genuinely does reduce error, by averaging it
 * out along the trace. That is precisely the problem: averaging along the trace
 * changes the motion process the descriptors then characterise. It would suppress
 * the difference between white jitter and a random walk <em>by construction</em>,
 * and that difference is the whole content of the {@code wander} descriptor.
 * Somebody will read that reconciliation improves every estimator by an order of
 * magnitude and want to switch it on; a test fails if they thread it in.
 *
 * <h2>Which pairs, and which bound</h2>
 *
 * <p>Neither is decided here. The pairs come from the window sampler and the
 * bound is one global number worked out from all the windows and bridges
 * together, both in stage 08. Nothing in this class stores a bound or invents one
 * per pair.
 */
public final class Estimators {

    private static final PhaseCorrelation PHASE_CORRELATION = new PhaseCorrelation();
    private static final PyramidSsd PYRAMID_SSD = new PyramidSsd();

    private Estimators() {
    }

    /** The two methods, in the order their columns appear. Published names, for provenance. */
    public static List<String> names() {
        return Collections.unmodifiableList(Arrays.asList(
                PHASE_CORRELATION.name(), PYRAMID_SSD.name()));
    }

    /**
     * Measure every listed pair with both estimators.
     *
     * @param frames       where the planes come from. Read on the calling thread,
     *                     before any worker starts, because ImageJ state belongs
     *                     to the coordinator
     * @param pairs        one {@code {from, to}} per pair, zero-based frame
     *                     indices. Measured and reported in the order given
     * @param maxShift     one bound for the whole run, in the pixels
     *                     {@code frames} is sampled at
     * @param workers      0 or less to decide automatically, 1 to force serial
     * @param progress     told as pairs complete; may be called from any thread
     * @param cancellation looked at between pairs
     * @throws java.util.concurrent.CancellationException if the run was stopped
     *         part-way
     */
    public static Result measure(FrameSource frames, int[][] pairs, double maxShift, int workers,
                                 PairScheduler.Progress progress, Cancellation cancellation) {
        if (frames == null) throw new IllegalArgumentException("no frames to measure");
        if (pairs == null) throw new IllegalArgumentException("no pairs to measure");
        if (!(maxShift > 0)) {
            throw new IllegalArgumentException("the search bound must be a positive number of"
                    + " pixels, was " + maxShift + ". Stage 08 derives one bound for the whole"
                    + " recording; there is no per-pair bound to fall back on");
        }
        final Frames.Bin scale = frames.bin();
        if (scale == null) {
            throw new IllegalArgumentException("these frames do not say what scale they were"
                    + " sampled at, and a displacement in pixels is meaningless without it."
                    + " See defect D12");
        }
        final int width = frames.width();
        final int height = frames.height();
        final int count = frames.count();
        for (int p = 0; p < pairs.length; p++) {
            if (pairs[p] == null || pairs[p].length != 2) {
                throw new IllegalArgumentException("pair " + p + " is not a {from, to}");
            }
            for (int side = 0; side < 2; side++) {
                int t = pairs[p][side];
                if (t < 0 || t >= count) {
                    throw new IllegalArgumentException("pair " + p + " names frame " + t
                            + ", outside 0.." + (count - 1));
                }
            }
        }
        if (pairs.length == 0) {
            return new Result(new PairEstimate[0], Double.NaN, scale, 0, 0, 0);
        }

        // Every plane is read here, on the coordinator, before any worker starts, and each frame is
        // read once however many pairs it appears in. Workers see float arrays and hand numbers
        // back; nothing on a worker thread touches ImageJ.
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

        final int levels = PyramidSsd.levelsFor(width, height, maxShift);
        final int transformSize = PhaseCorrelation.transformSize(width, height);
        final int distinct = planes.length;
        final int used = PairScheduler.workersFor(pairs.length, workers,
                memoryPerPair(width, height, transformSize), 0);

        // One cache per estimator. A shared cache of downsampled planes would be a work saving and
        // would not breach the independence claim, but a shared *schedule* forced on both would
        // degrade one of them, and each of these holds a different thing anyway.
        final PyramidCache<double[][]> transforms = new PyramidCache<double[][]>(
                new PyramidCache.Builder<double[][]>() {
                    @Override
                    public double[][] build(int slot) {
                        return PhaseCorrelation.transform(planes[slot], width, height);
                    }
                }, PyramidCache.capacityFor(distinct, width, height,
                bytesPerPixel(16L * transformSize * transformSize, width, height),
                1, used, 0));
        final PyramidCache<float[][]> pyramids = new PyramidCache<float[][]>(
                new PyramidCache.Builder<float[][]>() {
                    @Override
                    public float[][] build(int slot) {
                        return PyramidSsd.pyramid(planes[slot], width, height, levels);
                    }
                }, PyramidCache.capacityFor(distinct, width, height, 6L, 1, used, 0));

        try {
            // The bound goes into each call from the argument this method was given. It is not
            // copied into a field of anything, here or in either estimator - see Estimator.
            List<PairEstimate> estimates = PairScheduler.map(pairs.length, used,
                    new PairScheduler.Task<PairEstimate>() {
                        @Override
                        public PairEstimate run(int index) {
                            int from = pairs[index][0];
                            int to = pairs[index][1];
                            int a = slots.get(Integer.valueOf(from)).intValue();
                            int b = slots.get(Integer.valueOf(to)).intValue();
                            Estimator.Displacement byPhase = PhaseCorrelation.shiftOf(
                                    transforms.get(a), transforms.get(b), transformSize,
                                    maxShift, scale);
                            Estimator.Displacement bySsd = PyramidSsd.shiftOf(
                                    pyramids.get(a), pyramids.get(b), width, height,
                                    maxShift, scale);
                            return new PairEstimate(from, to, byPhase, bySsd, scale);
                        }
                    }, progress, cancellation);

            PairEstimate[] ordered = estimates.toArray(new PairEstimate[estimates.size()]);
            double[] differences = new double[ordered.length];
            int compared = 0;
            for (int i = 0; i < ordered.length; i++) {
                double d = ordered[i].differencePx();
                if (!Double.isNaN(d)) differences[compared++] = d;
            }
            int builds = (int) Math.min(Integer.MAX_VALUE,
                    transforms.misses() + pyramids.misses());
            return new Result(ordered, median(differences, compared), scale, compared,
                    distinct, builds);
        } finally {
            transforms.clear();
            pyramids.clear();
        }
    }

    /** Bytes one pair needs live: both estimators' per-frame forms for two frames. */
    private static long memoryPerPair(int width, int height, int transformSize) {
        long transform = 16L * transformSize * transformSize;
        long pyramid = 6L * width * height;
        return 2L * (transform + pyramid);
    }

    /** A per-entry cost expressed per full-resolution pixel, which is what the cache asks for. */
    private static long bytesPerPixel(long bytesPerEntry, int width, int height) {
        long pixels = Math.max(1L, (long) width * height);
        return Math.max(1L, (bytesPerEntry + pixels - 1) / pixels);
    }

    /**
     * The middle value of the first {@code n} entries.
     *
     * <p>Sorts a copy: the caller's ordering is the pair ordering and stays that
     * way.
     */
    private static double median(double[] values, int n) {
        if (n <= 0) return Double.NaN;
        double[] sorted = Arrays.copyOf(values, n);
        Arrays.sort(sorted);
        return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    // ------------------------------------------------------------ the results

    /** What both estimators made of one frame pair, and how far apart they were. */
    public static final class PairEstimate {

        private final int from;
        private final int to;
        private final Estimator.Displacement byPhaseCorrelation;
        private final Estimator.Displacement byPyramidSsd;
        private final double differencePx;
        private final Frames.Bin measuredAt;

        PairEstimate(int from, int to, Estimator.Displacement byPhaseCorrelation,
                     Estimator.Displacement byPyramidSsd, Frames.Bin measuredAt) {
            this.from = from;
            this.to = to;
            this.byPhaseCorrelation = byPhaseCorrelation;
            this.byPyramidSsd = byPyramidSsd;
            this.differencePx = byPhaseCorrelation.distanceTo(byPyramidSsd);
            this.measuredAt = measuredAt;
        }

        /** Zero-based index of the earlier frame. */
        public int from() {
            return from;
        }

        /** Zero-based index of the later frame. */
        public int to() {
            return to;
        }

        /** What phase correlation made of it. */
        public Estimator.Displacement byPhaseCorrelation() {
            return byPhaseCorrelation;
        }

        /** What the pyramid sum-of-squared-differences search made of it. */
        public Estimator.Displacement byPyramidSsd() {
            return byPyramidSsd;
        }

        /**
         * Distance between the two answers, in the pixels of
         * {@link #measuredAt()}. {@link Double#NaN} when either estimator declined
         * to guess, so that a pair neither could read does not enter the
         * confidence signal as perfect agreement on zero.
         */
        public double differencePx() {
            return differencePx;
        }

        /** The effective pixel size all three numbers are expressed in. */
        public Frames.Bin measuredAt() {
            return measuredAt;
        }

        /** True when both estimators measured this pair from its pixels. */
        public boolean compared() {
            return !Double.isNaN(differencePx);
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%d->%d phase %s ssd %s differ %.3f px",
                    from, to, byPhaseCorrelation, byPyramidSsd, differencePx);
        }
    }

    /** Every pair, measured by both, plus the confidence signal over the set. */
    public static final class Result {

        private final PairEstimate[] estimates;
        private final double agreementPx;
        private final Frames.Bin measuredAt;
        private final int comparedPairs;
        private final int framesUsed;
        private final int perFrameBuilds;

        Result(PairEstimate[] estimates, double agreementPx, Frames.Bin measuredAt,
               int comparedPairs, int framesUsed, int perFrameBuilds) {
            this.estimates = estimates;
            this.agreementPx = agreementPx;
            this.measuredAt = measuredAt;
            this.comparedPairs = comparedPairs;
            this.framesUsed = framesUsed;
            this.perFrameBuilds = perFrameBuilds;
        }

        /** The pairs, in the order they were asked for. */
        public List<PairEstimate> pairs() {
            return Collections.unmodifiableList(new ArrayList<PairEstimate>(
                    Arrays.asList(estimates)));
        }

        /**
         * Median distance between the two estimators across the pairs both could
         * read - the {@code agreement_px} column.
         *
         * <p>{@link Double#NaN} when no pair was read by both, which is itself the
         * finding: nothing in the recording pinned a displacement down.
         */
        public double agreementPx() {
            return agreementPx;
        }

        /** The effective pixel size every number here is expressed in. */
        public Frames.Bin measuredAt() {
            return measuredAt;
        }

        /** How many pairs both estimators read, and so how many the median is over. */
        public int comparedPairs() {
            return comparedPairs;
        }

        /** How many pairs were asked for. */
        public int measuredPairs() {
            return estimates.length;
        }

        /** How many distinct frames those pairs named, and so how many were read. */
        public int framesUsed() {
            return framesUsed;
        }

        /**
         * How many per-frame forms the two estimators built between them - the transforms and the
         * pyramids.
         *
         * <p>Twice {@link #framesUsed()} when nothing had to be built a second time, which is the
         * point of caching them: a thirty-five pair chain over thirty-six frames does seventy-two
         * of these rather than a hundred and forty. It rises above that only when the frames are
         * large enough that the memory budget cannot hold them all at once.
         */
        public int perFrameBuilds() {
            return perFrameBuilds;
        }

        /** One sentence naming what produced these numbers, for the saved notes. */
        public String provenance() {
            return "phase correlation and " + PYRAMID_SSD.name() + ", " + estimates.length
                    + (estimates.length == 1 ? " frame pair" : " frame pairs")
                    + " at " + measuredAt.provenance();
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s, agreement %.3f px over %d",
                    provenance(), agreementPx, comparedPairs);
        }
    }
}
