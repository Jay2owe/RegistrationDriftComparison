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
import regdrift.Failure;
import regdrift.Mode;
import regdrift.RegDrift;
import regdrift.RegDriftParameters;
import regdrift.RegDriftResult;
import regdrift.internal.PairScheduler;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A fingerprint measured on one worker, two workers and as many as the machine will give is the
 * <b>same bits</b> - the derived global search bound included.
 *
 * <h2>Why bit-identical and not "close enough"</h2>
 *
 * <p>A diagnosis is a scientific output. If a run on a laptop and a run on a cluster node produce
 * 0.0407 and 0.0409, the recording has two localisability values and neither is the measurement.
 * A tolerance would also hide the failure this test exists to catch, which is not a rounding
 * difference: it is a reduction folded in <em>completion order</em> rather than index order.
 *
 * <h2>The bound is the one that bites</h2>
 *
 * <p>Pass one derives one search bound for the whole recording from the largest displacement it
 * finds across the window pairs and the bridge pairs together. A running maximum taken as workers
 * report in would give the same answer every time - a maximum does not care about order - but a
 * running <em>minimum with a floor</em>, a mean, or a first-past-the-post tie-break would not, and
 * every later number is measured with that bound. So the bound is asserted separately and first,
 * rather than being left to fall out of the numbers downstream.
 *
 * <h2>And the sampler's whole claim</h2>
 *
 * <p>The last test here is the reason the sampler exists: a 500-frame recording is measured over the
 * same number of frame pairs, reading the same number of frames, as a 48-frame one. The wall-clock
 * comparison beside it is deliberately generous - a strict timing assertion on a shared build
 * machine tests the machine - and the measured figures are in the stage 09 completion note.
 */
public class FingerprintParallelTest {

    private static final int SIDE = 96;
    private static final int FIELD = 176;
    private static final int FRAMES = 36;

    // ------------------------------------------------------------ bit identity

    @Test
    public void serialTwoWorkerAndFullWidthRunsDeriveTheSameGlobalBound() {
        ImagePlus movie = movie(FRAMES);
        WindowSampler.Bound one = measure(movie, 1).bound();
        WindowSampler.Bound two = measure(movie, 2).bound();
        WindowSampler.Bound wide = measure(movie, wideOpen()).bound();

        assertBits("the bound every later number is measured with", one.px(), two.px());
        assertBits("the bound every later number is measured with", one.px(), wide.px());
        assertBits("largest step anywhere", one.largestStepPx(), two.largestStepPx());
        assertBits("largest step anywhere", one.largestStepPx(), wide.largestStepPx());
        assertBits("largest step inside a window",
                one.largestWindowStepPx(), wide.largestWindowStepPx());
        assertBits("largest step across a gap",
                one.largestBridgeStepPx(), wide.largestBridgeStepPx());
        assertEquals(one.measuredPairs(), wide.measuredPairs());
        assertEquals(one.refusedPairs(), wide.refusedPairs());
        assertTrue("the fixture has to move enough to raise the bound off its floor, or this test"
                + " is asserting that a constant equals itself", one.largestStepPx() > 1);
    }

    @Test
    public void serialTwoWorkerAndFullWidthRunsProduceTheSameFingerprint() {
        ImagePlus movie = movie(FRAMES);
        Fingerprint one = measure(movie, 1);
        Fingerprint two = measure(movie, 2);
        Fingerprint wide = measure(movie, wideOpen());

        assertSameFingerprint("one worker against two", one, two);
        assertSameFingerprint("one worker against every worker", one, wide);
    }

    @Test
    public void everyPairIsMeasuredIdenticallyAtEveryWorkerCount() {
        ImagePlus movie = movie(FRAMES);
        List<Estimators.PairEstimate> one = measure(movie, 1).estimates().pairs();
        List<Estimators.PairEstimate> wide = measure(movie, wideOpen()).estimates().pairs();

        assertEquals(one.size(), wide.size());
        assertTrue("the sampler has to have measured something", one.size() >= 20);
        for (int i = 0; i < one.size(); i++) {
            Estimators.PairEstimate a = one.get(i);
            Estimators.PairEstimate b = wide.get(i);
            assertEquals("pair " + i + " is a different pair", a.from(), b.from());
            assertEquals("pair " + i + " is a different pair", a.to(), b.to());
            assertBits("pair " + i + " phase correlation dx",
                    a.byPhaseCorrelation().dx(), b.byPhaseCorrelation().dx());
            assertBits("pair " + i + " phase correlation dy",
                    a.byPhaseCorrelation().dy(), b.byPhaseCorrelation().dy());
            assertBits("pair " + i + " pyramid ssd dx",
                    a.byPyramidSsd().dx(), b.byPyramidSsd().dx());
            assertBits("pair " + i + " pyramid ssd dy",
                    a.byPyramidSsd().dy(), b.byPyramidSsd().dy());
            assertBits("pair " + i + " difference", a.differencePx(), b.differencePx());
        }
    }

    // ------------------------------------------------- the scale travels with it

    @Test
    public void theMeasurementScaleIsChosenBeforeAnythingIsMeasuredAndIsCarriedEverywhere() {
        ImagePlus movie = movie(FRAMES);
        Fingerprint measured = measure(movie, 1);

        Frames.Bin scale = measured.measuredAt();
        assertNotNull(scale);
        assertEquals(scale, measured.localisability().measuredAt());
        assertEquals(scale, measured.bound().measuredAt());
        assertEquals(scale, measured.estimates().measuredAt());
        assertEquals(scale, measured.motion().measuredAt());
        assertTrue("the provenance has to name the scale in the form the table holds",
                measured.provenance().contains("measured_at_bin=" + scale.factor()));
    }

    /**
     * The scale is a function of the image's size and of nothing else, so two runs over the same
     * recording measure at the same scale and a saved {@code measured_at_bin} can be reproduced.
     */
    @Test
    public void theScaleIsTheSurveysBinFactorUntilTheFrameIsTooSmallToCarryIt() {
        assertEquals(Fingerprint.MEASUREMENT_BIN, Fingerprint.binFor(2048, 2048).factor());
        assertEquals(Fingerprint.MEASUREMENT_BIN, Fingerprint.binFor(512, 512).factor());
        assertEquals("256 / 4 = 64, which is the smallest measured plane allowed",
                Fingerprint.MEASUREMENT_BIN, Fingerprint.binFor(256, 256).factor());
        assertEquals("192 / 4 = 48, too small, so the factor comes down",
                3, Fingerprint.binFor(192, 192).factor());
        assertEquals(2, Fingerprint.binFor(128, 128).factor());
        assertEquals(1, Fingerprint.binFor(96, 96).factor());
        assertEquals("the short side decides", 1, Fingerprint.binFor(4096, 96).factor());
        assertEquals(1, Fingerprint.binFor(8, 8).factor());
    }

    // ------------------------------------------------------------- cancellation

    /**
     * Stopping a run part-way hands back a typed reason, not a half-filled table, and leaves
     * nothing running behind it.
     */
    @Test
    public void stoppingARunPartWayReturnsATypedReasonAndLeavesNoThreadsOrImages() {
        int poolThreadsBefore = poolThreads();
        int imagesBefore = ij.WindowManager.getImageCount();

        ImagePlus movie = movie(FRAMES);
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie)
                .mode(Mode.DIAGNOSE)
                .cancellation(new StopsAfter(3))
                .build());

        assertFalse("a stopped run is not a success", result.isSuccess());
        assertNotNull(result.failure());
        assertEquals(Failure.Kind.CANCELED, result.failure().kind());
        assertNull("nothing half-measured is handed out", result.diagnosis());
        assertNull("and no verdict is reached", result.verdict());
        assertEquals("no image was made, so none can be left behind",
                imagesBefore, ij.WindowManager.getImageCount());
        assertEquals("the run owns its pool and shuts it down before returning",
                poolThreadsBefore, settledPoolThreads(poolThreadsBefore));
    }

    // --------------------------------------------------- the sampler's own claim

    /**
     * The reason this plugin can be the first thing anybody runs: measuring a 500-frame recording
     * costs what measuring a 48-frame one costs.
     */
    @Test
    public void aFiveHundredFrameRecordingIsMeasuredOverTheSamePairsAsAFortyEightFrameOne() {
        ImagePlus shortMovie = movie(48);
        ImagePlus longMovie = movie(500);

        long shortNanos = System.nanoTime();
        Fingerprint small = measure(shortMovie, 1);
        shortNanos = System.nanoTime() - shortNanos;

        long longNanos = System.nanoTime();
        Fingerprint large = measure(longMovie, 1);
        longNanos = System.nanoTime() - longNanos;

        assertEquals("the same number of frame pairs, whatever the recording's length",
                small.measuredPairs(), large.measuredPairs());
        assertEquals("and the same number of frames read",
                small.framesRead(), large.framesRead());
        assertEquals(47, small.availablePairs());
        assertEquals(499, large.availablePairs());
        assertEquals(35, small.measuredPairs());

        // Deliberately generous: this asserts that the cost does not grow with the recording, not
        // that two runs on a shared machine take the same wall-clock. Ten-fold length against a
        // three-fold budget still fails loudly if the sampler is ever bypassed.
        assertTrue("a 10x longer recording took " + (longNanos / 1e9) + " s against "
                        + (shortNanos / 1e9) + " s, which is not constant cost",
                longNanos < 3 * shortNanos + 1_000_000_000L);
    }

    // ---------------------------------------------------------------- machinery

    private static Fingerprint measure(ImagePlus movie, int workers) {
        Frames.Bin bin = Fingerprint.binFor(movie.getWidth(), movie.getHeight());
        Frames frames = Frames.of(movie, 1, Frames.PROJECT_Z, bin);
        try {
            WindowSampler.Plan plan = WindowSampler.measured().plan(frames.count());
            return Fingerprint.measure(frames, plan, workers,
                    PairScheduler.Progress.NONE, Cancellation.never());
        } finally {
            frames.release();
        }
    }

    private static void assertSameFingerprint(String what, Fingerprint a, Fingerprint b) {
        assertBits(what + ": localisability", a.localisability().value(), b.localisability().value());
        assertEquals(what + ": scale", a.measuredAt(), b.measuredAt());
        assertBits(what + ": frame correlation", a.frameCorrelation(), b.frameCorrelation());
        assertBits(what + ": agreement", a.agreementPx(), b.agreementPx());
        assertBits(what + ": intensity trend", a.log2Trend(), b.log2Trend());
        assertBits(what + ": bright share", a.brightFraction(), b.brightFraction());
        assertBits(what + ": drift rate", a.motion().driftRatePx(), b.motion().driftRatePx());
        assertBits(what + ": wander", a.motion().wander(), b.motion().wander());
        assertBits(what + ": step rms", a.motion().stepRmsPx(), b.motion().stepRmsPx());
        assertBits(what + ": step max", a.motion().stepMaxPx(), b.motion().stepMaxPx());
        assertBits(what + ": bridge max", a.motion().bridgeMaxPx(), b.motion().bridgeMaxPx());
        assertEquals(what + ": bridge span", a.motion().bridgeSpan(), b.motion().bridgeSpan());
        assertEquals(what + ": label", a.motion().label(), b.motion().label());
        assertEquals(what + ": severity", a.motion().severity(), b.motion().severity());
        assertEquals(what + ": knock", a.motion().knockPresent(), b.motion().knockPresent());
        assertEquals(what + ": pairs compared", a.comparedPairs(), b.comparedPairs());
        assertEquals(what + ": frames read", a.framesRead(), b.framesRead());
        assertEquals(what + ": verdict", Verdict.of(a).kind(), Verdict.of(b).kind());
        assertEquals(what + ": the sentence a user reads", Verdict.of(a).text(), Verdict.of(b).text());
    }

    /** Equal to the last bit, NaN included, because a diagnosis is not a rounded quantity. */
    private static void assertBits(String what, double expected, double actual) {
        assertEquals(what + ": " + expected + " against " + actual,
                Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }

    private static int wideOpen() {
        return Math.max(4, 4 * Runtime.getRuntime().availableProcessors());
    }

    /**
     * A recording that drifts, jitters and takes one knock, cut as overlapping windows out of one
     * broadband field so every displacement is exact by construction and nothing is interpolated.
     */
    private static ImagePlus movie(int frames) {
        float[] field = Synth.texture(FIELD, FIELD, 20260813L, 4);
        float[][] planes = new float[frames][];
        for (int t = 0; t < frames; t++) {
            int knock = t >= frames / 2 ? 9 : 0;
            int ox = 8 + (int) Math.round(0.35 * t) % 24 + knock;
            int oy = 8 + (t % 5) + knock;
            planes[t] = Synth.crop(field, FIELD, ox, oy, SIDE, SIDE);
        }
        return Synth.stack("fingerprint fixture", SIDE, SIDE, planes);
    }

    /** How many worker threads a bounded pool has left running. */
    private static int poolThreads() {
        Thread[] all = new Thread[Thread.activeCount() * 2 + 16];
        int found = Thread.enumerate(all);
        int pooled = 0;
        for (int i = 0; i < found; i++) {
            if (all[i] != null && all[i].getName().startsWith("pool-")) pooled++;
        }
        return pooled;
    }

    /** A shut-down pool's threads die asynchronously; give them a bounded moment to. */
    private static int settledPoolThreads(int target) {
        for (int attempt = 0; attempt < 50; attempt++) {
            int now = poolThreads();
            if (now <= target) return now;
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return now;
            }
        }
        return poolThreads();
    }

    /** A switch that is pulled part-way through a run rather than before it starts. */
    private static final class StopsAfter implements Cancellation {

        private final java.util.concurrent.atomic.AtomicInteger looks =
                new java.util.concurrent.atomic.AtomicInteger();
        private final int after;

        StopsAfter(int after) {
            this.after = after;
        }

        @Override
        public boolean canceled() {
            return looks.getAndIncrement() >= after;
        }
    }
}
