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
import org.junit.Test;
import regdrift.Cancellation;
import regdrift.internal.PairScheduler;

import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * E2, and the determinism the whole plugin rests on: the channel ranking must be the same at any
 * worker count, ties included.
 *
 * <p>A default estimation channel that changed between runs on the same recording would be worse
 * than having no default at all - two people would get two answers from one file and neither could
 * say why.
 */
public class RankingParallelTest {

    private static final int W = 64;
    private static final int H = 64;
    private static final int FRAMES = 6;

    /**
     * <b>The exit-gate measurement.</b> Serial, two workers and as many workers as this machine has
     * give bit-identical rankings, and the two channels that tie come back in channel order.
     */
    @Test
    public void serialTwoWorkerAndWideRunsAreBitIdentical() {
        ImagePlus imp = fourChannels();
        int wide = Math.max(2, Runtime.getRuntime().availableProcessors());

        ChannelRanker.Ranking serial = rank(imp, 1);
        ChannelRanker.Ranking two = rank(imp, 2);
        ChannelRanker.Ranking many = rank(imp, wide);

        assertSameRanking("serial vs two workers", serial, two);
        assertSameRanking("serial vs " + wide + " workers", serial, many);

        assertEquals("the textured channel is the most localisable", 1, serial.chosen());
        assertEquals(4, serial.size());
        assertEquals(1, serial.channels()[0].channel());
        assertEquals("a tie breaks on the channel index, not on who finished first",
                2, serial.channels()[1].channel());
        assertEquals(3, serial.channels()[2].channel());
        assertEquals("a channel nothing could be measured on sorts last",
                4, serial.channels()[3].channel());
        assertFalse(serial.channels()[3].localisability().defined());
    }

    /** The tie has to be a real tie, or the assertion above proves nothing about ties. */
    @Test
    public void theTiedChannelsReallyDoTie() {
        ChannelRanker.Ranking ranking = rank(fourChannels(), 1);
        ChannelRanker.ChannelQuality second = ranking.channels()[1];
        ChannelRanker.ChannelQuality third = ranking.channels()[2];
        assertEquals("channels 2 and 3 hold the same pixels and must measure the same",
                second.localisability().value(), third.localisability().value(), 0.0);
        assertEquals(second.frameCorrelation(), third.frameCorrelation(), 0.0);
    }

    /** Running the same image again gives the same ranking, byte for byte. */
    @Test
    public void repeatedRunsAgree() {
        ImagePlus imp = fourChannels();
        assertSameRanking("run 1 vs run 2", rank(imp, 3), rank(imp, 3));
    }

    /**
     * <b>Never rank by gradient magnitude.</b> On photon-limited data the channel with the largest
     * per-pixel contrast is the noisiest, and nothing can register it - consecutive frames of shot
     * noise are essentially uncorrelated.
     */
    @Test
    public void theNoisiestChannelIsNotChosen() {
        float[][][] planes = new float[2][FRAMES][];
        Random rng = new Random(20260813L);
        for (int t = 0; t < FRAMES; t++) {
            planes[0][t] = noise(rng);
            planes[1][t] = Synth.frame(W, H, 0.3 * t, 0.2 * t);
        }
        ImagePlus imp = Synth.hyperstack("noise and structure", W, H, planes);

        assertTrue("the noise channel must have the larger mean gradient, or this test is not"
                        + " about the right thing",
                meanGradient(planes[0][0]) > meanGradient(planes[1][0]));

        ChannelRanker.Ranking ranking = ChannelRanker.rank(imp);
        assertEquals("the structured channel is chosen", 2, ranking.chosen());
        assertTrue("and the noise channel is near zero frame correlation: "
                        + ranking.channels()[1].frameCorrelation(),
                Math.abs(ranking.channels()[1].frameCorrelation()) < 0.2);
    }

    // ------------------------------------------------------------- the shape

    /** Nothing measurable still names every channel and says so in a sentence. */
    @Test
    public void aRecordingWithNoPairStillReportsEveryChannel() {
        float[][][] planes = new float[3][1][];
        for (int c = 0; c < 3; c++) planes[c][0] = Synth.flat(8, 8, c + 1f);
        ChannelRanker.Ranking ranking = ChannelRanker.rank(Synth.hyperstack("one frame", 8, 8,
                planes));
        assertEquals(3, ranking.size());
        assertEquals(0, ranking.chosen());
        assertFalse(ranking.measured());
        assertTrue(ranking.reason(), ranking.reason().contains("no frame pair"));
        assertEquals(1, ranking.channels()[0].channel());
        for (ChannelRanker.ChannelQuality q : ranking.channels()) {
            assertNotNull("even an unmeasured channel carries its scale", q.measuredAt());
        }
    }

    /** Every number reported carries the scale it was measured at. */
    @Test
    public void everyRankedChannelCarriesTheScale() {
        ChannelRanker.Ranking ranking = ChannelRanker.rank(fourChannels(), Frames.Bin.factor(2));
        assertEquals(2, ranking.measuredAt().factor());
        for (ChannelRanker.ChannelQuality q : ranking.channels()) {
            assertEquals(2, q.measuredAt().factor());
            assertEquals(2, q.localisability().measuredAt().factor());
        }
        assertTrue(ranking.reason(), ranking.reason().contains("2 x 2 pixel mean"));
    }

    /** The cost of a pre-run check does not grow with the recording. */
    @Test
    public void theStrideBoundsTheWork() {
        float[][][] planes = new float[1][60][];
        for (int t = 0; t < 60; t++) planes[0][t] = Synth.frame(W, H, 0.2 * t, 0.1 * t);
        ChannelRanker.Ranking ranking = ChannelRanker.rank(
                Synth.hyperstack("long", W, H, planes));
        assertTrue("59 pairs must not all be measured: " + ranking.pairsMeasured(),
                ranking.pairsMeasured() <= ChannelRanker.DEFAULT_PAIRS);
        assertTrue("but enough to mean something: " + ranking.pairsMeasured(),
                ranking.pairsMeasured() >= 8);
        assertEquals(5, ranking.stride());

        ChannelRanker.Ranking everyPair = ChannelRanker.rank(
                Synth.hyperstack("long", W, H, planes), Frames.Bin.none(), 1, 0,
                PairScheduler.Progress.NONE, Cancellation.never());
        assertEquals(59, everyPair.pairsMeasured());
    }

    /** Progress reaches the channel count, and reports nothing beyond it. */
    @Test
    public void progressReachesTheChannelCount() {
        final AtomicInteger last = new AtomicInteger();
        ChannelRanker.rank(fourChannels(), Frames.Bin.none(), 1, 2,
                new PairScheduler.Progress() {
                    @Override
                    public void update(int done, int total) {
                        assertEquals(4, total);
                        last.accumulateAndGet(done, Math::max);
                    }
                }, Cancellation.never());
        assertEquals(4, last.get());
    }

    /** Stopping a ranking stops it, rather than handing back half a table. */
    @Test
    public void stoppingTheRunStopsTheRanking() {
        Cancellation.Flag stop = Cancellation.flag();
        stop.cancel();
        try {
            ChannelRanker.rank(fourChannels(), Frames.Bin.none(), 1, 2,
                    PairScheduler.Progress.NONE, stop);
            fail("expected the run to stop");
        } catch (CancellationException expected) {
            // the contract; the coordinator turns this into the typed reason a caller sees
        }
    }

    @Test
    public void aRankingWithoutAStatedScaleIsRefused() {
        try {
            ChannelRanker.rank(fourChannels(), null, 1, 1, PairScheduler.Progress.NONE,
                    Cancellation.never());
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("D12"));
        }
    }

    // -------------------------------------------------------------- fixtures

    private static ChannelRanker.Ranking rank(ImagePlus imp, int workers) {
        return ChannelRanker.rank(imp, Frames.Bin.none(), 1, workers,
                PairScheduler.Progress.NONE, Cancellation.never());
    }

    /**
     * Four channels: textured and moving, two identical smooth blobs that must tie, and a flat
     * channel nothing can be measured on.
     */
    private static ImagePlus fourChannels() {
        float[][][] planes = new float[4][FRAMES][];
        for (int t = 0; t < FRAMES; t++) {
            planes[0][t] = Synth.frame(W, H, 0.3 * t, 0.2 * t);
            planes[1][t] = Synth.blob(W, H);
            planes[2][t] = Synth.blob(W, H);
            planes[3][t] = Synth.flat(W, H, 1500f);
        }
        return Synth.hyperstack("four channels", W, H, planes);
    }

    private static float[] noise(Random rng) {
        float[] out = new float[W * H];
        for (int i = 0; i < out.length; i++) out[i] = (float) (500 + 400 * rng.nextGaussian());
        return out;
    }

    private static double meanGradient(float[] plane) {
        double total = 0;
        int counted = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x + 1 < W; x++) {
                total += Math.abs(plane[y * W + x + 1] - plane[y * W + x]);
                counted++;
            }
        }
        return total / counted;
    }

    private static void assertSameRanking(String what, ChannelRanker.Ranking a,
                                          ChannelRanker.Ranking b) {
        assertEquals(what + ": channel count", a.size(), b.size());
        assertEquals(what + ": chosen channel", a.chosen(), b.chosen());
        assertEquals(what + ": reason", a.reason(), b.reason());
        assertEquals(what + ": scale", a.measuredAt(), b.measuredAt());
        assertEquals(what + ": pairs", a.pairsMeasured(), b.pairsMeasured());
        ChannelRanker.ChannelQuality[] left = a.channels();
        ChannelRanker.ChannelQuality[] right = b.channels();
        for (int i = 0; i < left.length; i++) {
            assertEquals(what + ": position " + i, left[i].channel(), right[i].channel());
            assertEquals(what + ": localisability at position " + i,
                    Double.doubleToRawLongBits(left[i].localisability().value()),
                    Double.doubleToRawLongBits(right[i].localisability().value()));
            assertEquals(what + ": frame correlation at position " + i,
                    Double.doubleToRawLongBits(left[i].frameCorrelation()),
                    Double.doubleToRawLongBits(right[i].frameCorrelation()));
            assertEquals(what + ": whole value at position " + i, left[i], right[i]);
        }
    }
}
