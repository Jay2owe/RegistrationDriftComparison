/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.score;

import org.junit.Test;
import regdrift.Cancellation;
import regdrift.Failure;
import regdrift.Mode;
import regdrift.RegDrift;
import regdrift.RegDriftParameters;
import regdrift.RegDriftResult;
import regdrift.diag.FrameSource;
import regdrift.internal.PairScheduler;
import regdrift.internal.Transform;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T11. The fused walk gives the same bits as three separate ones, and the answer
 * does not depend on how many workers computed it or on the order they finished
 * in.
 *
 * <h2>Why "identical" and not "close"</h2>
 *
 * <p>Three passes over the same pixels were fused into one to save two thirds of
 * the reads. A fused pass is exactly where a quiet numerical difference creeps in:
 * an accumulator reused, a value scaled once instead of twice, a condition
 * covering three quantities where it used to cover one. A tolerance would let all
 * three through and would only ever catch a difference so large it would have been
 * obvious anyway. So the reference implementation below runs the three
 * measurements as three separate loops, in the same order, and the assertion is on
 * the raw bits of the doubles.
 *
 * <h2>Why bit-identical across worker counts</h2>
 *
 * <p>Floating-point addition is not associative: {@code (a + b) + c} and
 * {@code a + (b + c)} genuinely differ. A reduction folded in the order workers
 * happened to report in therefore gives a different last digit on every run, and a
 * recording would have two scores with neither of them being the measurement. The
 * arbiter writes each frame's contribution into its own buffer and the coordinator
 * adds them <b>in frame order</b>. {@link #completionOrderDoesNotChangeTheAnswer}
 * scrambles the order workers finish in on purpose and asserts nothing moves.
 */
public class ArbiterFusedPassTest {

    private static final int SIDE = 72;
    private static final int FRAMES = 17;
    private static final ControlWarp.Interpolation HOW = ControlWarp.Interpolation.BILINEAR;

    // -------------------------------------------------- fused against three passes

    /**
     * The claim, on the raw bits: one walk over each frame pair produces the same
     * numbers as three walks.
     */
    @Test
    public void theFusedWalkIsIdenticalToThreeSeparatePasses() {
        Fixture.Drifting movie = movie();
        FrameSource raw = movie.rawSource();
        FrameSource registered = movie.registeredSource(HOW);

        Arbiter.Scoring fused = Arbiter.score(raw, registered, movie.cumulative, HOW, 1,
                PairScheduler.Progress.NONE, Cancellation.never());
        ThreePass separate = threePasses(raw, registered, movie.cumulative);

        assertBits("mean temporal SD of the registered recording",
                separate.meanSdRegistered, fused.meanSdRegistered());
        assertBits("mean temporal SD of the control", separate.meanSdControl,
                fused.meanSdControl());
        assertBits("sd_vs_control", separate.meanSdRegistered / separate.meanSdControl - 1,
                fused.sdVsControl());
        double[] before = fused.residualBefore();
        double[] after = fused.residualAfter();
        for (int t = 0; t < movie.raw.length; t++) {
            assertBits("residual_before at frame " + t, separate.residualBefore[t], before[t]);
            assertBits("residual_after at frame " + t, separate.residualAfter[t], after[t]);
        }
        assertTrue("the fixture has to produce a real effect, or this asserts that zero equals"
                + " zero", fused.sdVsControlPercent() < -10);
    }

    // ------------------------------------------------------------- determinism

    @Test
    public void serialTwoWorkerAndFullWidthRunsAreBitIdentical() {
        Fixture.Drifting movie = movie();
        FrameSource raw = movie.rawSource();
        FrameSource registered = movie.registeredSource(HOW);

        Arbiter.Scoring one = score(raw, registered, movie.cumulative, 1);
        Arbiter.Scoring two = score(raw, registered, movie.cumulative, 2);
        Arbiter.Scoring wide = score(raw, registered, movie.cumulative, wideOpen());

        assertSame("one worker against two", one, two);
        assertSame("one worker against every worker the machine will give", one, wide);
    }

    /**
     * Workers made to finish in the reverse of the order they started in. The
     * answer is the same bits, because the merge is over the frame index and never
     * over completion order.
     */
    @Test
    public void completionOrderDoesNotChangeTheAnswer() {
        Fixture.Drifting movie = movie();
        FrameSource raw = movie.rawSource();
        FrameSource registered = movie.registeredSource(HOW);

        Arbiter.Scoring straight = score(raw, registered, movie.cumulative, wideOpen());
        Arbiter.Scoring scrambled = Arbiter.score(raw, registered, movie.cumulative, HOW,
                wideOpen(), new ReversesCompletionOrder(), Cancellation.never());

        assertSame("in order against deliberately reversed", straight, scrambled);
    }

    // ----------------------------------------------------------- cancellation

    /** Stopped before anything was queued: nothing runs, and the reason is typed. */
    @Test
    public void cancellationBeforeTheRunStartsStopsItCleanly() {
        Fixture.Drifting movie = movie();
        Cancellation.Flag stop = Cancellation.flag();
        stop.cancel();
        try {
            Arbiter.score(movie.rawSource(), movie.registeredSource(HOW), movie.cumulative, HOW,
                    2, PairScheduler.Progress.NONE, stop);
            fail("a run stopped before it started must not return a score");
        } catch (CancellationException stopped) {
            assertEquals("canceled", stopped.getMessage());
        }
    }

    /** Stopped while frames are in flight: same, and nothing half-measured comes back. */
    @Test
    public void cancellationWhileRunningStopsItCleanlyAndLeavesNoThreads() {
        int poolThreadsBefore = poolThreads();
        Fixture.Drifting movie = Fixture.drifting(SIDE, 60, 0.31, -0.19, 5150L);
        try {
            Arbiter.score(movie.rawSource(), movie.registeredSource(HOW), movie.cumulative, HOW,
                    2, PairScheduler.Progress.NONE, new StopsAfter(4));
            fail("a run stopped part-way must not return a score");
        } catch (CancellationException stopped) {
            assertTrue(stopped.getMessage(), stopped.getMessage().contains("cancel")
                    || stopped.getMessage().contains("interrupt"));
        }
        assertEquals("the run owns its pool and shuts it down before returning",
                poolThreadsBefore, settledPoolThreads(poolThreadsBefore));
    }

    /** And the whole run turns that into a sentence rather than an exception. */
    @Test
    public void theScoreModeReportsCancellationAsATypedReason() {
        Fixture.Drifting movie = movie();
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie.rawStack("raw"))
                .mode(Mode.SCORE)
                .compareWith(movie.registeredStack("registered", HOW))
                .cancellation(new StopsAfter(3))
                .build());

        assertFalse("a stopped run is not a success", result.isSuccess());
        assertEquals(Failure.Kind.CANCELED, result.failure().kind());
        assertNull("nothing half-scored is handed out", result.comparison());
        assertNull(result.frames());
    }

    // -------------------------------------------- the worker budget, D9 for real

    /**
     * Defect D9, driven with this stage's real per-frame cost. A frame pair here
     * keeps five planes and four {@code double} accumulator buffers live, which on
     * a large recording is hundreds of megabytes per worker - so the memory cap is
     * the one that decides, not the core count.
     */
    @Test
    public void theWorkerBudgetIsHonouredUnderASmallMemoryBudget() {
        int width = 2048;
        int height = 2048;
        int pixels = width * height;
        long perFrame = Arbiter.memoryPerFrame(width, height, pixels);
        assertTrue("a 2048x2048 frame pair must be budgeted at more than 100 MB, and was "
                + perFrame, perFrame > 100L * 1024 * 1024);

        assertEquals("a 256 MB budget holds one of these and no more, however many cores are"
                        + " asked for", 1,
                PairScheduler.workersFor(500, 8, perFrame, 256L * 1024 * 1024));
        assertEquals("twice that holds two, and memory rather than the core count decides", 2,
                PairScheduler.workersFor(500, 8, perFrame, 2 * perFrame));
        assertEquals("and a budget large enough to have overflowed an int must not come back"
                        + " negative or silently serial - that is defect D9", 8,
                PairScheduler.workersFor(500, 8, 1L, Long.MAX_VALUE / 4));
    }

    /** The clamp changes how many workers run and does not change a single digit of the answer. */
    @Test
    public void clampingTheWorkersDoesNotChangeTheAnswer() {
        Fixture.Drifting movie = movie();
        FrameSource raw = movie.rawSource();
        FrameSource registered = movie.registeredSource(HOW);
        ControlWarp.Margin margin = ControlWarp.validMargin(movie.cumulative, SIDE, SIDE, HOW);
        long perFrame = Arbiter.memoryPerFrame(SIDE, SIDE,
                (int) margin.croppedPixels(SIDE, SIDE));

        assertEquals("a budget of one frame's worth forces the run serial", 1,
                PairScheduler.workersFor(FRAMES, 0, perFrame, perFrame));
        assertSame("clamped against full width", score(raw, registered, movie.cumulative, 1),
                score(raw, registered, movie.cumulative, wideOpen()));
    }

    // ------------------------------------------------- nothing is left behind

    @Test
    public void scoringLeavesNoImagesOrThreadsBehind() {
        int imagesBefore = ij.WindowManager.getImageCount();
        int poolThreadsBefore = poolThreads();
        Fixture.Drifting movie = movie();

        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie.rawStack("raw"))
                .mode(Mode.SCORE)
                .compareWith(movie.registeredStack("registered", HOW))
                .build());

        assertTrue(result.isSuccess() ? "" : result.failure().message(), result.isSuccess());
        assertEquals("scoring shows nothing, so it can leave nothing on screen",
                imagesBefore, ij.WindowManager.getImageCount());
        assertEquals("and every pool it opened is shut", poolThreadsBefore,
                settledPoolThreads(poolThreadsBefore));
    }

    // ------------------------------------------------------- the reference passes

    /** What three separate walks over the same pixels produce. */
    private static final class ThreePass {

        final double[] residualBefore;
        final double[] residualAfter;
        double meanSdRegistered;
        double meanSdControl;

        ThreePass(int frames) {
            residualBefore = new double[frames];
            residualAfter = new double[frames];
            java.util.Arrays.fill(residualBefore, Double.NaN);
            java.util.Arrays.fill(residualAfter, Double.NaN);
        }
    }

    /**
     * The same three measurements, as three separate loops over the pixels, in the
     * same order and with the same arithmetic. Nothing here is shared with the
     * class under test except {@link Arbiter#frameMedian} and the warper, which are
     * the inputs to the walk rather than part of it.
     */
    private static ThreePass threePasses(FrameSource raw, FrameSource registered,
                                         Transform[] cumulative) {
        int n = raw.count();
        int width = raw.width();
        int height = raw.height();
        Transform[] control = ControlWarp.fractionalOf(cumulative);
        ControlWarp.Margin margin = ControlWarp.validMargin(cumulative, width, height, HOW);
        int croppedWidth = margin.croppedWidth(width);
        int croppedHeight = margin.croppedHeight(height);
        int pixels = croppedWidth * croppedHeight;

        float[] firstRaw = raw.plane(0);
        double referenceRaw = Arbiter.frameMedian(firstRaw, width, height, margin);
        double referenceRegistered = Arbiter.frameMedian(registered.plane(0), width, height,
                margin);
        double referenceControl = Arbiter.frameMedian(
                ControlWarp.warp(firstRaw, width, height, control[0], HOW, 0f),
                width, height, margin);

        ThreePass out = new ThreePass(n);
        double[] sumRegistered = new double[pixels];
        double[] sumSqRegistered = new double[pixels];
        double[] sumControl = new double[pixels];
        double[] sumSqControl = new double[pixels];

        int top = margin.top();
        int bottom = height - margin.bottom();
        int left = margin.left();
        int right = width - margin.right();

        for (int t = 0; t < n; t++) {
            float[] rawCurrent = raw.plane(t);
            float[] registeredCurrent = registered.plane(t);
            float[] controlCurrent = ControlWarp.warp(rawCurrent, width, height, control[t], HOW,
                    0f);
            float[] rawPrevious = t == 0 ? null : raw.plane(t - 1);
            float[] registeredPrevious = t == 0 ? null : registered.plane(t - 1);

            double scaleRawCurrent = scale(referenceRaw, rawCurrent, width, height, margin);
            double scaleRawPrevious = t == 0 ? Double.NaN
                    : scale(referenceRaw, rawPrevious, width, height, margin);
            double scaleRegistered = scale(referenceRegistered, registeredCurrent, width, height,
                    margin);
            double scaleRegisteredPrevious = t == 0 ? Double.NaN
                    : scale(referenceRegistered, registeredPrevious, width, height, margin);
            double scaleControl = scale(referenceControl, controlCurrent, width, height, margin);

            // Pass one: residual mismatch before registration.
            if (t > 0) {
                double diffSq = 0;
                double gradSq = 0;
                for (int y = top; y < bottom; y++) {
                    int row = y * width;
                    int above = (y > 0 ? y - 1 : y) * width;
                    int below = (y + 1 < height ? y + 1 : y) * width;
                    for (int x = left; x < right; x++) {
                        int i = row + x;
                        int west = row + (x > 0 ? x - 1 : x);
                        int east = row + (x + 1 < width ? x + 1 : x);
                        double before = rawCurrent[i] * scaleRawCurrent
                                - rawPrevious[i] * scaleRawPrevious;
                        double gx = 0.5 * (rawCurrent[east] - rawCurrent[west]) * scaleRawCurrent;
                        double gy = 0.5 * (rawCurrent[below + x] - rawCurrent[above + x])
                                * scaleRawCurrent;
                        diffSq += before * before;
                        gradSq += gx * gx + gy * gy;
                    }
                }
                out.residualBefore[t] = Math.sqrt(2.0 * diffSq / gradSq);
            }

            // Pass two: residual mismatch after registration.
            if (t > 0) {
                double diffSq = 0;
                double gradSq = 0;
                for (int y = top; y < bottom; y++) {
                    int row = y * width;
                    int above = (y > 0 ? y - 1 : y) * width;
                    int below = (y + 1 < height ? y + 1 : y) * width;
                    for (int x = left; x < right; x++) {
                        int i = row + x;
                        int west = row + (x > 0 ? x - 1 : x);
                        int east = row + (x + 1 < width ? x + 1 : x);
                        double after = registeredCurrent[i] * scaleRegistered
                                - registeredPrevious[i] * scaleRegisteredPrevious;
                        double gx = 0.5 * (registeredCurrent[east] - registeredCurrent[west])
                                * scaleRegistered;
                        double gy = 0.5 * (registeredCurrent[below + x]
                                - registeredCurrent[above + x]) * scaleRegistered;
                        diffSq += after * after;
                        gradSq += gx * gx + gy * gy;
                    }
                }
                out.residualAfter[t] = Math.sqrt(2.0 * diffSq / gradSq);
            }

            // Pass three: the temporal standard deviations.
            for (int y = top; y < bottom; y++) {
                int row = y * width;
                int outRow = (y - top) * croppedWidth;
                for (int x = left; x < right; x++) {
                    int i = row + x;
                    int o = outRow + (x - left);
                    double registeredValue = registeredCurrent[i] * scaleRegistered;
                    double controlValue = controlCurrent[i] * scaleControl;
                    sumRegistered[o] += registeredValue;
                    sumSqRegistered[o] += registeredValue * registeredValue;
                    sumControl[o] += controlValue;
                    sumSqControl[o] += controlValue * controlValue;
                }
            }
        }

        double totalRegistered = 0;
        double totalControl = 0;
        long counted = 0;
        for (int o = 0; o < pixels; o++) {
            double meanRegistered = sumRegistered[o] / n;
            double meanControl = sumControl[o] / n;
            double sdRegistered = Math.sqrt(Math.max(0,
                    sumSqRegistered[o] / n - meanRegistered * meanRegistered));
            double sdControl = Math.sqrt(Math.max(0,
                    sumSqControl[o] / n - meanControl * meanControl));
            if (Double.isNaN(sdRegistered) || Double.isNaN(sdControl)) continue;
            totalRegistered += sdRegistered;
            totalControl += sdControl;
            counted++;
        }
        out.meanSdRegistered = totalRegistered / counted;
        out.meanSdControl = totalControl / counted;
        return out;
    }

    private static double scale(double reference, float[] plane, int width, int height,
                                ControlWarp.Margin margin) {
        double median = Arbiter.frameMedian(plane, width, height, margin);
        return median > 0 ? reference / median : 1.0;
    }

    // ---------------------------------------------------------------- machinery

    private static Fixture.Drifting movie() {
        return Fixture.drifting(SIDE, FRAMES, 0.41, -0.27, 20260812L);
    }

    private static Arbiter.Scoring score(FrameSource raw, FrameSource registered,
                                         Transform[] cumulative, int workers) {
        return Arbiter.score(raw, registered, cumulative, HOW, workers,
                PairScheduler.Progress.NONE, Cancellation.never());
    }

    private static void assertSame(String what, Arbiter.Scoring a, Arbiter.Scoring b) {
        assertBits(what + ": sd_vs_control", a.sdVsControl(), b.sdVsControl());
        assertBits(what + ": mean SD of the registered recording",
                a.meanSdRegistered(), b.meanSdRegistered());
        assertBits(what + ": mean SD of the control", a.meanSdControl(), b.meanSdControl());
        assertBits(what + ": residual_before", a.medianResidualBefore(), b.medianResidualBefore());
        assertBits(what + ": residual_after", a.medianResidualAfter(), b.medianResidualAfter());
        assertBits(what + ": path_px", a.pathPx(), b.pathPx());
        assertBits(what + ": net_px", a.netPx(), b.netPx());
        assertEquals(what + ": separation", a.separation(), b.separation());
        double[] before = a.residualBefore();
        double[] otherBefore = b.residualBefore();
        double[] after = a.residualAfter();
        double[] otherAfter = b.residualAfter();
        assertEquals(before.length, otherBefore.length);
        for (int t = 0; t < before.length; t++) {
            assertBits(what + ": residual_before at frame " + t, before[t], otherBefore[t]);
            assertBits(what + ": residual_after at frame " + t, after[t], otherAfter[t]);
        }
    }

    /** Equal to the last bit, NaN included, because a score is not a rounded quantity. */
    private static void assertBits(String what, double expected, double actual) {
        assertEquals(what + ": " + expected + " against " + actual,
                Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }

    private static int wideOpen() {
        return Math.max(4, 4 * Runtime.getRuntime().availableProcessors());
    }

    /**
     * A progress reporter that makes the first worker to report wait longest, so
     * the frames finish in roughly the reverse of the order they started in. The
     * sleep is short and bounded; the point is the ordering, not the delay.
     */
    private static final class ReversesCompletionOrder implements PairScheduler.Progress {

        @Override
        public void update(int done, int total) {
            try {
                Thread.sleep(Math.max(0, total - done));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A switch pulled part-way through a run rather than before it starts. */
    private static final class StopsAfter implements Cancellation {

        private final AtomicInteger looks = new AtomicInteger();
        private final int after;

        StopsAfter(int after) {
            this.after = after;
        }

        @Override
        public boolean canceled() {
            return looks.getAndIncrement() >= after;
        }
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
}
