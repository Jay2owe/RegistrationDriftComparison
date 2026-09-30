/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineFixtures;
import regdrift.autofix.EngineId;
import regdrift.diag.Frames;
import regdrift.harness.EngineDescriptor;
import regdrift.harness.EngineRunner;
import regdrift.internal.PairScheduler;
import regdrift.internal.Transform;
import regdrift.score.Arbiter;
import regdrift.score.ControlWarp;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Slow, steady drift is read at its true size: defects B8 and B9 of 0.1.0.
 *
 * <p>0.1.0 read a sub-pixel shift with a parabola through the correlation peak,
 * which pulls every answer toward the nearest whole pixel, and gave every
 * frequency the same vote although a fractional shift of an integrating pixel
 * moves the high frequencies less than the low ones. Together they read a drift
 * of 0.84 px per frame as 0.38 at bin 4 (B8), and the comparison's own
 * measurement of 0.3 px per frame as about 60% of it, which left a correct
 * engine outside the region it is scored over (B9).
 *
 * <p>The recordings are {@link GoldenOutputsTest.Fixture}s: a textured field
 * sampled at exact fractional offsets, so the truth is known at every frame.
 */
public class SubpixelDriftTest {

    /** The drift rates B8 and B9 were found at, in native pixels per frame. */
    private static final double[] SLOW = {0.3, 0.84, 1.5, 1.68};

    /** The tolerance the fix is held to. */
    private static final double WITHIN = 0.05;

    /** StackReg runs on TurboReg, so a computer with StackReg has both. */
    private static final Set<EngineId> HERE = EnumSet.of(EngineId.TURBOREG, EngineId.STACKREG,
            EngineId.MULTISTACKREG);

    private RegDrift.Bench realBench;
    private CorrectEngines engines;

    @Before
    public void putTwoCorrectEnginesInFront() {
        realBench = RegDrift.bench;
        engines = new CorrectEngines();
        final AutofixService catalogue = EngineFixtures.serviceWherePresent(
                HERE.toArray(new EngineId[0]));
        final EngineRunner runner = new EngineRunner(new EngineRunner.Presence() {
            @Override
            public boolean installed(EngineId engine) {
                return HERE.contains(engine);
            }
        }, engines);
        RegDrift.bench = new RegDrift.Bench() {
            @Override
            public AutofixService catalogue() {
                return catalogue;
            }

            @Override
            public EngineRunner runner() {
                return runner;
            }
        };
    }

    @After
    public void putTheRealComputerBack() {
        RegDrift.bench = realBench;
    }

    @Test
    public void driftRateOnASmallFrameIsWithinFivePercent() {
        for (double rate : SLOW) {
            assertDriftRate(96, 1, rate);
        }
    }

    @Test
    public void driftRateOnABinnedLargeFrameIsWithinFivePercent() {
        for (double rate : SLOW) {
            assertDriftRate(256, 4, rate);
        }
    }

    @Test
    public void theComparisonsOwnMovementIsWithinFivePercent() {
        for (double rate : new double[]{0.3, 0.6, 1.5}) {
            ImagePlus image = recording(128, 24, rate).raw("own");
            Arbiter.Recovered own = Arbiter.ownMotion(Frames.of(image, 1, 1, Frames.Bin.none()),
                    0, PairScheduler.Progress.NONE, Cancellation.never());
            Transform last = own.cumulative()[23];
            double truth = rate * 23;
            double read = Math.hypot(last.dx, last.dy);
            assertEquals("own movement over 23 steps at " + rate + " px per frame", truth, read,
                    WITHIN * truth);
            image.close();
        }
        for (double[] step : new double[][]{{0.3, -0.2}, {0.6, -0.4}, {1.5, -1.0}}) {
            ImagePlus image = b9Recording(256, 24, step[0], step[1]);
            Arbiter.Recovered own = Arbiter.ownMotion(Frames.of(image, 1, 1, Frames.Bin.none()),
                    0, PairScheduler.Progress.NONE, Cancellation.never());
            Transform last = own.cumulative()[23];
            double truth = 23 * Math.hypot(step[0], step[1]);
            assertEquals("own movement on the B9 recording at " + step[0] + ", " + step[1]
                    + " px per frame", truth, Math.hypot(last.dx, last.dy), WITHIN * truth);
            image.close();
        }
    }

    /**
     * The B9 case: steady drift at 0.3, 0.6 and 1.5 px per frame, and two engines
     * that each remove all of it. 0.1.0 refused both a score, so there was no
     * ranking; both are now scored, and they tie.
     */
    @Test
    public void compareScoresBothStackRegArmsOnSteadySlowDrift() {
        // The three B9 cases from the 0.1.0 GUI check, rebuilt exactly as that check built them.
        double[][] cases = {{12, 0.3, -0.2}, {24, 0.6, -0.4}, {24, 1.5, -1.0}};
        for (double[] c : cases) {
            int frames = (int) c[0];
            double rate = Math.hypot(c[1], c[2]);
            ImagePlus image = b9Recording(256, frames, c[1], c[2]);
            engines.stepX = c[1];
            engines.stepY = c[2];
            RegDriftResult result = RegDrift.run(RegDriftParameters.builder(image)
                    .mode(Mode.COMPARE).hideDisplay(true).build());
            assertTrue(rate + ": " + result.failure(), result.isSuccess());
            ResultsTable comparison = result.comparison();
            java.util.List<Integer> driven = new java.util.ArrayList<Integer>();
            for (int row = 0; row < comparison.size(); row++) {
                String engine = RegDriftTables.cellText(comparison, "engine", row);
                if (engine.equals("StackReg") || engine.equals("MultiStackReg")) {
                    driven.add(Integer.valueOf(row));
                }
            }
            assertEquals(rate + ": a row for each StackReg arm", 2, driven.size());
            double[] scores = new double[2];
            for (int k = 0; k < 2; k++) {
                int row = driven.get(k).intValue();
                String score = RegDriftTables.cellText(comparison, "sd_vs_control", row);
                assertFalse(rate + " px per frame: "
                                + RegDriftTables.cellText(comparison, "engine", row)
                                + " was given no sd_vs_control - "
                                + RegDriftTables.cellText(comparison, "status", row),
                        score.isEmpty());
                scores[k] = Double.parseDouble(score);
                assertTrue(rate + ": a correct engine is stiller than its control, read "
                        + scores[k], scores[k] < 0);
            }
            assertEquals(rate + ": the same correction scores the same", scores[0], scores[1],
                    1e-9);
            image.close();
        }
    }

    /**
     * The recording the 0.1.0 GUI check found B9 on: uniform noise blurred at
     * sigma 2.5, moved {@code t * (sx, sy)} by ImageJ's own bilinear translate, and
     * cropped from a padded field.
     */
    static ImagePlus b9Recording(int side, int frames, double sx, double sy) {
        int pad = (int) Math.ceil(Math.max(Math.abs(sx), Math.abs(sy)) * frames) + 8;
        FloatProcessor base = new FloatProcessor(side + 2 * pad, side + 2 * pad);
        java.util.Random random = new java.util.Random(7L);
        float[] pixels = (float[]) base.getPixels();
        for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextFloat();
        base.blurGaussian(2.5);
        base.multiply(1000);
        base.add(200);
        ImageStack stack = new ImageStack(side, side);
        for (int t = 0; t < frames; t++) {
            ImageProcessor moved = base.duplicate();
            moved.setInterpolationMethod(ImageProcessor.BILINEAR);
            moved.translate(t * sx, t * sy);
            moved.setRoi(pad, pad, side, side);
            stack.addSlice("t" + (t + 1), moved.crop());
        }
        ImagePlus image = new ImagePlus("b9", stack);
        image.setDimensions(1, 1, frames);
        return image;
    }

    // ----------------------------------------------------------------- helpers

    private static void assertDriftRate(int side, int bin, double rate) {
        ImagePlus image = recording(side, 48, rate).raw("drift");
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(image)
                .mode(Mode.DIAGNOSE).build());
        assertTrue(String.valueOf(result.failure()), result.isSuccess());
        assertEquals(side + " px frame measured at bin " + bin, String.valueOf(bin),
                RegDriftTables.cellText(result.diagnosis(), "measured_at_bin", 0));
        double read = Double.parseDouble(RegDriftTables.cellText(result.diagnosis(),
                "drift_rate_px", 0));
        assertEquals("drift_rate_px on a " + side + " px frame at " + rate + " px per frame",
                rate, read, WITHIN * rate);
        image.close();
    }

    /** Drift of {@code rate} px per frame in the direction (0.8, -0.6). */
    static GoldenOutputsTest.Fixture recording(int side, int frames, double rate) {
        int margin = (int) Math.ceil(rate * frames) + 8;
        return GoldenOutputsTest.Fixture.drifting("drift", side, frames, 0.8 * rate, -0.6 * rate,
                0, 0, 0, margin, 1, 1, 32);
    }

    /**
     * Engines that take out all of the true movement, frame by frame, as StackReg
     * does: frame {@code t} moved by {@code t * (stepX, stepY)}.
     */
    private static final class CorrectEngines implements EngineRunner.Driver {

        double stepX;
        double stepY;

        @Override
        public void drive(ImagePlus working, EngineDescriptor engine, String options) {
            ImageStack stack = working.getStack();
            int width = working.getWidth();
            int height = working.getHeight();
            for (int slice = 1; slice <= stack.getSize(); slice++) {
                int t = slice - 1;
                // A Transform is the content's motion; the warper undoes it.
                Transform undo = Transform.translation(t * stepX, t * stepY);
                float[] pixels = (float[]) stack.getProcessor(slice).convertToFloat().getPixels();
                float[] moved = ControlWarp.warp(pixels, width, height, undo,
                        ControlWarp.Interpolation.BILINEAR, 0f);
                ImageProcessor back = new FloatProcessor(width, height, moved, null);
                stack.setProcessor(back, slice);
            }
        }
    }
}
