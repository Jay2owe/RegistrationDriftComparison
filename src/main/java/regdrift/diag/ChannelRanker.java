/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import ij.ImagePlus;
import regdrift.Cancellation;
import regdrift.internal.PairScheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Scores every channel of an image and puts them in order, so a run - or a
 * dialog - can pick which one the movement is measured on and say why.
 *
 * <p>The ranking key is {@link Localisability}: the fall in frame-to-frame
 * correlation when one frame is displaced a single pixel. Frame correlation is
 * reported beside it and is never ranked on.
 *
 * <p><b>It never ranks by gradient magnitude.</b> On a photon-limited recording
 * the channel with the largest mean gradient is usually the noisiest, not the
 * most structured - on a bioluminescence test stack the sharpest-looking channel
 * was pure shot noise with consecutive frames essentially uncorrelated, and phase
 * correlation returned 1264 px of nonsense on it. Gradient magnitude picks
 * exactly the channel that cannot be registered.
 *
 * <h2>Cost, and how it is kept fixed</h2>
 *
 * <p>Scoring every consecutive pair of every channel is {@code O(C x T x WH)},
 * and this runs before anything else does, so it is bounded two ways. Pairs are
 * taken on a stride - every {@code stride}-th pair, each pair still two
 * <em>consecutive</em> frames, because the measure is defined on a single step
 * and a wider step measures something else. And the stride defaults to whatever
 * makes {@link #DEFAULT_PAIRS} pairs, so a 500-frame recording costs what a
 * 20-frame one does.
 *
 * <h2>Determinism</h2>
 *
 * <p>Channels are measured in parallel, one channel per task, each writing into
 * its own slot; the coordinator merges in channel order and sorts with ties
 * broken on the channel index. Serial, two-worker and full-width runs give
 * bit-identical rankings on the same image. A default channel that changed
 * between runs on the same data would be worse than no default at all.
 *
 * <p>Every plane is read from the image on the coordinator thread before any
 * worker starts. Workers see float arrays and nothing else.
 *
 * <p>Grown from {@code rankChannels} and {@code ChannelQuality} in
 * {@code logratio\StackFrames.java}, which measured every pair serially.
 */
public final class ChannelRanker {

    /**
     * How many frame pairs a ranking measures when it chooses the stride itself.
     *
     * <p>A pre-run check has to cost the same on a 500-frame recording as on a
     * 20-frame one, or nobody runs it. This is that bound, and it is this
     * ranking's alone - it is not the measurement window the fingerprint uses.
     */
    public static final int DEFAULT_PAIRS = 12;

    private ChannelRanker() {
    }

    /** Rank at native resolution, choosing the stride and the worker count. */
    public static Ranking rank(ImagePlus imp) {
        return rank(imp, Frames.Bin.none());
    }

    /** Rank at a stated scale, choosing the stride and the worker count. */
    public static Ranking rank(ImagePlus imp, Frames.Bin bin) {
        return rank(imp, bin, 0, 0, PairScheduler.Progress.NONE, Cancellation.never());
    }

    /**
     * Rank every channel.
     *
     * @param imp          the image; its channel count is what is ranked
     * @param bin          the scale to measure at. Required - a localisability
     *                     number without its scale cannot be read, and that is
     *                     defect D12
     * @param stride       measure every {@code stride}-th consecutive pair; 0 or
     *                     less chooses a stride that measures about
     *                     {@link #DEFAULT_PAIRS} pairs
     * @param workers      how many channels to measure at once; 0 or less decides
     *                     automatically, 1 forces serial
     * @param progress     reported as channels finish; must be thread-safe
     * @param cancellation polled between channels
     * @return a ranking that always names every channel and always says what it
     *         settled on and why. Never {@code null}
     * @throws java.util.concurrent.CancellationException when the run was stopped
     *         part-way; the coordinator turns that into the typed reason a caller
     *         sees
     */
    public static Ranking rank(ImagePlus imp, Frames.Bin bin, int stride, int workers,
                               PairScheduler.Progress progress, Cancellation cancellation) {
        if (imp == null) throw new IllegalArgumentException("no image to rank");
        if (bin == null) {
            throw new IllegalArgumentException("a channel ranking needs the scale it measures at;"
                    + " pass Frames.Bin.none() for native resolution. See defect D12");
        }
        final Frames.Bin scale = bin;
        Frames first = Frames.of(imp, 1, Frames.PROJECT_Z, scale);
        final int channels = first.channels();
        final int count = first.count();
        final int width = first.width();
        final int height = first.height();
        first.release();

        if (count < 2) {
            ChannelQuality[] unmeasured = new ChannelQuality[channels];
            for (int c = 1; c <= channels; c++) {
                unmeasured[c - 1] = new ChannelQuality(c, Localisability.at(Double.NaN, scale),
                        Double.NaN, 0);
            }
            Arrays.sort(unmeasured);
            return new Ranking(unmeasured, 0, scale, 1, 0,
                    "The recording has " + count + (count == 1 ? " frame" : " frames")
                            + ", so there is no frame pair to measure a channel on.");
        }

        final int chosenStride = stride > 0
                ? stride
                : Math.max(1, (count - 1 + DEFAULT_PAIRS - 1) / DEFAULT_PAIRS);
        final int[][] pairs = pairsFor(count, chosenStride);

        // Every plane is read here, on the coordinator, before any worker starts. ImageJ state is
        // the coordinator's alone; workers are handed float arrays and hand numbers back.
        final float[][][] planes = new float[channels][][];
        for (int c = 1; c <= channels; c++) {
            if (cancellation.canceled()) {
                throw new java.util.concurrent.CancellationException("canceled");
            }
            planes[c - 1] = read(imp, c, scale, pairs);
        }

        long memPerTask = 8L * width * height;
        int used = PairScheduler.workersFor(channels, workers, memPerTask, 0);
        List<ChannelQuality> measured = PairScheduler.map(channels, used,
                new PairScheduler.Task<ChannelQuality>() {
                    @Override
                    public ChannelQuality run(int index) {
                        return quality(index + 1, planes[index], width, height, scale);
                    }
                }, progress, cancellation);

        ChannelQuality[] ranked = measured.toArray(new ChannelQuality[measured.size()]);
        // Merged in channel order above, then sorted on the measurement with ties broken on the
        // channel index - never on which worker finished first.
        Arrays.sort(ranked);

        int chosen = 0;
        for (int i = 0; i < ranked.length; i++) {
            if (ranked[i].localisability().defined()) {
                chosen = ranked[i].channel();
                break;
            }
        }
        return new Ranking(ranked, chosen, scale, chosenStride, pairs.length,
                reasonFor(ranked, chosen, scale, pairs.length));
    }

    // ------------------------------------------------------------ the pieces

    /** Pair starts on the stride. Each pair is two consecutive frames. */
    private static int[][] pairsFor(int count, int stride) {
        List<int[]> out = new ArrayList<int[]>();
        for (int t = 0; t + 1 < count; t += stride) {
            out.add(new int[]{t, t + 1});
        }
        return out.toArray(new int[out.size()][]);
    }

    /** Reads one channel's planes for the pairs that will be measured, once each. */
    private static float[][] read(ImagePlus imp, int channel, Frames.Bin bin, int[][] pairs) {
        Frames frames = Frames.of(imp, channel, Frames.PROJECT_Z, bin);
        try {
            TreeMap<Integer, float[]> wanted = new TreeMap<Integer, float[]>();
            for (int p = 0; p < pairs.length; p++) {
                for (int side = 0; side < 2; side++) {
                    Integer t = Integer.valueOf(pairs[p][side]);
                    if (!wanted.containsKey(t)) wanted.put(t, frames.plane(t.intValue()));
                }
            }
            float[][] out = new float[pairs.length * 2][];
            for (int p = 0; p < pairs.length; p++) {
                out[2 * p] = wanted.get(Integer.valueOf(pairs[p][0]));
                out[2 * p + 1] = wanted.get(Integer.valueOf(pairs[p][1]));
            }
            return out;
        } finally {
            frames.release();
        }
    }

    /** One channel's score, from planes already read. Runs on a worker thread. */
    private static ChannelQuality quality(int channel, float[][] planes, int width, int height,
                                          Frames.Bin bin) {
        double totalLocalisability = 0;
        int localisabilityPairs = 0;
        double totalCorrelation = 0;
        int correlationPairs = 0;
        for (int p = 0; p + 1 < planes.length; p += 2) {
            float[] a = planes[p];
            float[] b = planes[p + 1];
            double d = Localisability.ofPair(a, b, width, height, bin).value();
            if (!Double.isNaN(d)) {
                totalLocalisability += d;
                localisabilityPairs++;
            }
            double r = Localisability.correlation(a, b);
            if (!Double.isNaN(r)) {
                totalCorrelation += r;
                correlationPairs++;
            }
        }
        double localisability = localisabilityPairs > 0
                ? totalLocalisability / localisabilityPairs
                : Double.NaN;
        double correlation = correlationPairs > 0
                ? totalCorrelation / correlationPairs
                : Double.NaN;
        return new ChannelQuality(channel, Localisability.at(localisability, bin), correlation,
                localisabilityPairs);
    }

    private static String reasonFor(ChannelQuality[] ranked, int chosen, Frames.Bin bin,
                                    int pairs) {
        if (chosen == 0) {
            return "No channel carried a defined measurement at " + bin.provenance()
                    + ", so no estimation channel was settled on.";
        }
        ChannelQuality top = ranked[0];
        String sentence = String.format(Locale.US,
                "Channel %d loses the most frame-to-frame correlation under a one-pixel shift"
                        + " (%.4f over %d frame %s at %s).",
                chosen, top.localisability().value(), pairs, pairs == 1 ? "pair" : "pairs",
                bin.provenance());
        // The number and its scale, and nothing more. This sentence used to add "it is still below
        // the warning threshold, so expect any method to struggle on this recording", which is the
        // claim stage 09 measured and withdrew: at every scale tried, that threshold either warns
        // on recordings that register well - it warns on the four cleanest in the library - or misses
        // recordings that do not. See defect D12 and docs/D12_MEASUREMENT.md. The ranking still
        // orders channels by this number, which is a comparison within one recording and needs no
        // threshold; what it no longer does is tell anybody what the number means.
        return sentence;
    }

    // ------------------------------------------------------------ the values

    /** One channel's registrability, as {@link #rank} reports it. */
    public static final class ChannelQuality implements Comparable<ChannelQuality> {

        private final int channel;
        private final Localisability.Result localisability;
        private final double frameCorrelation;
        private final int pairsMeasured;

        ChannelQuality(int channel, Localisability.Result localisability, double frameCorrelation,
                       int pairsMeasured) {
            this.channel = channel;
            this.localisability = localisability;
            this.frameCorrelation = frameCorrelation;
            this.pairsMeasured = pairsMeasured;
        }

        /** One-based, as ImageJ numbers channels. */
        public int channel() {
            return channel;
        }

        /**
         * The ranking key, with the scale it was measured at. Larger is more
         * localisable.
         */
        public Localisability.Result localisability() {
            return localisability;
        }

        /**
         * Pearson correlation between consecutive frames, averaged over the pairs
         * measured. Near 1 means persistent structure; near 0 means each frame is
         * independent noise and no method will work.
         *
         * <p><b>Reported, never ranked on.</b> It is also near 1 for a smooth
         * featureless blob, which is equally unregistrable. Read it as the
         * separate statement that this channel is or is not pure noise. The scale
         * it was measured at is {@link #measuredAt()}.
         */
        public double frameCorrelation() {
            return frameCorrelation;
        }

        /** The effective pixel size both numbers here were measured at. */
        public Frames.Bin measuredAt() {
            return localisability.measuredAt();
        }

        /** How many frame pairs carried a defined measurement. */
        public int pairsMeasured() {
            return pairsMeasured;
        }

        /** True when this channel is below {@link Localisability#WARN_BELOW}. */
        public boolean poor() {
            return localisability.poor();
        }

        /**
         * Most localisable first. An undefined measurement sorts last, so a
         * channel nothing could be measured on is never chosen over a measured
         * one, and equal measurements break on the channel index - never on which
         * worker finished first.
         */
        @Override
        public int compareTo(ChannelQuality other) {
            double mine = localisability.value();
            double theirs = other.localisability.value();
            boolean an = Double.isNaN(mine);
            boolean bn = Double.isNaN(theirs);
            if (an || bn) {
                return an == bn ? Integer.compare(channel, other.channel) : (an ? 1 : -1);
            }
            int c = Double.compare(theirs, mine);
            return c != 0 ? c : Integer.compare(channel, other.channel);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ChannelQuality)) return false;
            ChannelQuality o = (ChannelQuality) other;
            return channel == o.channel
                    && pairsMeasured == o.pairsMeasured
                    && localisability.equals(o.localisability)
                    && Double.compare(frameCorrelation, o.frameCorrelation) == 0;
        }

        @Override
        public int hashCode() {
            return ((channel * 31 + pairsMeasured) * 31 + localisability.hashCode()) * 31
                    + Double.hashCode(frameCorrelation);
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "channel %d: localisability %.4f, frame correlation %.4f, at %s%s",
                    channel, localisability.value(), frameCorrelation,
                    measuredAt().provenance(), poor() ? "  (poorly localisable)" : "");
        }
    }

    /**
     * Every channel scored, in order, with what the ranking settled on and why.
     *
     * <p>There is no empty ranking. A recording nothing could be measured on
     * still names every channel, reports {@link #chosen()} as 0, and carries the
     * sentence that says so.
     */
    public static final class Ranking {

        private final ChannelQuality[] channels;
        private final int chosen;
        private final Frames.Bin measuredAt;
        private final int stride;
        private final int pairsMeasured;
        private final String reason;

        Ranking(ChannelQuality[] channels, int chosen, Frames.Bin measuredAt, int stride,
                int pairsMeasured, String reason) {
            this.channels = channels;
            this.chosen = chosen;
            this.measuredAt = measuredAt;
            this.stride = stride;
            this.pairsMeasured = pairsMeasured;
            this.reason = reason;
        }

        /** Every channel, most localisable first. Never empty. */
        public ChannelQuality[] channels() {
            return channels.clone();
        }

        /** How many channels were scored. */
        public int size() {
            return channels.length;
        }

        /**
         * The 1-based channel the ranking settled on, or {@code 0} when nothing
         * could be measured.
         */
        public int chosen() {
            return chosen;
        }

        /** True when at least one channel carried a defined measurement. */
        public boolean measured() {
            return chosen != 0;
        }

        /**
         * Why, in one finished sentence, for the provenance record and the
         * dialog. Never empty, including when nothing could be measured.
         */
        public String reason() {
            return reason;
        }

        /** The effective pixel size every number here was measured at. */
        public Frames.Bin measuredAt() {
            return measuredAt;
        }

        /** Every {@code stride}-th consecutive pair was measured. */
        public int stride() {
            return stride;
        }

        /** How many frame pairs each channel was scored on. */
        public int pairsMeasured() {
            return pairsMeasured;
        }

        @Override
        public String toString() {
            return "Ranking[chosen=" + chosen + " of " + channels.length
                    + ", " + pairsMeasured + " pairs on stride " + stride
                    + ", " + measuredAt + "]";
        }
    }
}
