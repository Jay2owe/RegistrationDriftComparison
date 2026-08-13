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
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * E1: the ImageJ half of the frame source.
 *
 * <p>Which channel, which Z slice, whether to project, and - new in this lift - what one pixel means.
 * The binning half is why this is not a straight copy of the research repository's class: the
 * registrability measure is scale-dependent, so the scale has to be a stated parameter rather than
 * whatever the file happened to be saved at.
 */
public class FramesTest {

    // ------------------------------------------------------------- the scale

    /**
     * <b>The exit-gate measurement.</b> A 512x512 stack binned by 2 gives 256x256 planes, and binning
     * moves no light around: the mean of the binned plane is the mean of the plane it came from.
     */
    @Test
    public void binningHalvesTheSizeAndPreservesTheMean() {
        ImagePlus imp = textured(512, 512, 2);

        Frames unbinned = Frames.of(imp, 1, Frames.PROJECT_Z, Frames.Bin.none());
        Frames binned = Frames.of(imp, 1, Frames.PROJECT_Z, Frames.Bin.factor(2));

        assertEquals(512, unbinned.width());
        assertEquals(512, unbinned.height());
        assertEquals(256, binned.width());
        assertEquals(256, binned.height());
        assertEquals("the image itself is unchanged", 512, binned.nativeWidth());
        assertEquals(512, binned.nativeHeight());
        assertEquals(256 * 256, binned.plane(0).length);

        double before = Synth.mean(unbinned.plane(0));
        double after = Synth.mean(binned.plane(0));
        assertEquals("binning is a mean, so the mean does not move", before, after, 1e-3);
        assertEquals(2, binned.bin().factor());
        assertEquals(1, unbinned.bin().factor());
    }

    /** An axis that does not divide evenly keeps its edge pixels in a smaller block. */
    @Test
    public void anAxisThatDoesNotDivideKeepsItsEdge() {
        Frames.Bin three = Frames.Bin.factor(3);
        assertEquals("100 pixels in threes is 34 blocks, the last one short", 34, three.size(100));
        float[] plane = new float[10 * 10];
        java.util.Arrays.fill(plane, 5f);
        float[] out = three.apply(plane, 10, 10);
        assertEquals(4 * 4, out.length);
        for (int i = 0; i < out.length; i++) {
            assertEquals("a short block is averaged over what it has", 5f, out[i], 0f);
        }
    }

    /** Pixels marked as not measured are left out of the block mean rather than poisoning it. */
    @Test
    public void unmeasuredPixelsAreLeftOutOfTheBlockMean() {
        float[] plane = {
                Float.NaN, 4f, 8f, 8f,
                2f, 2f, 8f, 8f,
                Float.NaN, Float.NaN, 1f, 3f,
                Float.NaN, Float.NaN, 5f, 7f,
        };
        float[] out = Frames.Bin.factor(2).apply(plane, 4, 4);
        assertEquals(4, out.length);
        assertEquals("mean of the three measured pixels", (4f + 2f + 2f) / 3f, out[0], 1e-6f);
        assertEquals(8f, out[1], 0f);
        assertTrue("a block with nothing measured in it stays unmeasured",
                Float.isNaN(out[2]));
        assertEquals(4f, out[3], 1e-6f);
    }

    @Test
    public void aFactorOfOneIsNativeResolution() {
        assertTrue(Frames.Bin.factor(1).isNative());
        assertEquals(Frames.Bin.none(), Frames.Bin.factor(1));
        assertEquals("native resolution", Frames.Bin.none().provenance());
        assertEquals("2 x 2 pixel mean", Frames.Bin.factor(2).provenance());
        assertEquals(7, Frames.Bin.none().size(7));
    }

    @Test
    public void aFactorBelowOneIsRefused() {
        try {
            Frames.Bin.factor(0);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("1 or more"));
        }
    }

    /** No scale means no frames. A default here is how the scale goes stale. */
    @Test
    public void framesWithoutAStatedScaleAreRefused() {
        try {
            Frames.of(textured(16, 16, 2), 1, Frames.PROJECT_Z, null);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("D12"));
        }
    }

    // --------------------------------------------------- channel, slice, time

    /** A plain stack has no hyperstack dimensions set, so its slices are the time axis. */
    @Test
    public void aPlainStackCountsItsSlicesAsFrames() {
        ImagePlus imp = Synth.stack("plain", 8, 8,
                Synth.flat(8, 8, 1f), Synth.flat(8, 8, 2f), Synth.flat(8, 8, 3f));
        Frames frames = Frames.of(imp);
        assertEquals(3, frames.count());
        assertEquals(1, frames.channel());
        assertEquals(Frames.PROJECT_Z, frames.slice());
        assertEquals(2f, frames.plane(1)[0], 0f);
    }

    /** Each channel of a hyperstack is read separately, and the channel is 1-based as ImageJ shows it. */
    @Test
    public void eachChannelIsReadSeparately() {
        float[][][] planes = new float[3][2][];
        for (int c = 0; c < 3; c++) {
            for (int t = 0; t < 2; t++) {
                planes[c][t] = Synth.flat(8, 8, (c + 1) * 10f + t);
            }
        }
        ImagePlus imp = Synth.hyperstack("three channels", 8, 8, planes);
        assertEquals(3, Frames.of(imp).channels());
        for (int c = 1; c <= 3; c++) {
            Frames frames = Frames.of(imp, c, Frames.PROJECT_Z);
            assertEquals(2, frames.count());
            assertEquals(c * 10f, frames.plane(0)[0], 0f);
            assertEquals(c * 10f + 1, frames.plane(1)[0], 0f);
        }
    }

    /** Z is projected by maximum, so a thin in-focus feature is not diluted by the slices around it. */
    @Test
    public void projectingZTakesTheMaximum() {
        ImageStack stack = new ImageStack(4, 4);
        // one channel, three slices, two frames: c fastest, then z, then t
        float[][] byIndex = {
                Synth.flat(4, 4, 1f), Synth.flat(4, 4, 9f), Synth.flat(4, 4, 5f),
                Synth.flat(4, 4, 2f), Synth.flat(4, 4, 3f), Synth.flat(4, 4, 4f),
        };
        for (int i = 0; i < byIndex.length; i++) {
            stack.addSlice("s" + i, new FloatProcessor(4, 4, byIndex[i], null));
        }
        ImagePlus imp = new ImagePlus("z stack", stack);
        imp.setDimensions(1, 3, 2);
        imp.setOpenAsHyperStack(true);

        Frames projected = Frames.of(imp, 1, Frames.PROJECT_Z);
        assertEquals(2, projected.count());
        assertEquals(3, projected.slices());
        assertEquals("brightest of 1, 9, 5", 9f, projected.plane(0)[0], 0f);
        assertEquals("brightest of 2, 3, 4", 4f, projected.plane(1)[0], 0f);

        Frames middle = Frames.of(imp, 1, 2);
        assertEquals("the second slice of the first frame", 9f, middle.plane(0)[0], 0f);
        assertEquals("the second slice of the second frame", 3f, middle.plane(1)[0], 0f);
    }

    @Test
    public void anImpossibleChannelOrSliceIsRefused() {
        ImagePlus imp = textured(16, 16, 2);
        try {
            Frames.of(imp, 4, Frames.PROJECT_Z);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("channel 4"));
        }
        try {
            Frames.of(imp, 1, 9);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("slice 9"));
        }
        try {
            Frames.of(imp).plane(7);
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("frame 7"));
        }
    }

    // -------------------------------------------------------- what is handed out

    /**
     * Every caller gets its own array. The binning is done once and cached, so the work is not
     * repeated; the copy is what stops one stage writing into another stage's pixels.
     */
    @Test
    public void everyCallHandsOutAFreshArray() {
        Frames frames = Frames.of(textured(64, 64, 3), 1, Frames.PROJECT_Z, Frames.Bin.factor(2));
        float[] first = frames.plane(1);
        float[] second = frames.plane(1);
        assertNotSame(first, second);
        assertTrue(java.util.Arrays.equals(first, second));

        first[0] = -12345f;
        assertFalse("writing into one copy must not reach the cache",
                frames.plane(1)[0] == -12345f);
    }

    /** Reading the same frame twice bins it once. */
    @Test
    public void aBinnedPlaneIsComputedOncePerFrame() {
        Frames frames = Frames.of(textured(64, 64, 4), 1, Frames.PROJECT_Z, Frames.Bin.factor(2));
        float[] once = frames.plane(2);
        for (int i = 0; i < 20; i++) {
            assertTrue("the cached plane must not drift", java.util.Arrays.equals(once,
                    frames.plane(2)));
        }
        frames.release();
        assertTrue("and rebuilding after release gives the same pixels",
                java.util.Arrays.equals(once, frames.plane(2)));
    }

    /**
     * The measurement must run over the same number of frames the run reports.
     *
     * <p>Two places decide which axis is time - the facade, which reports the count and refuses a
     * recording with no time axis, and this class, which reads the pixels. If they disagreed, a
     * diagnosis would quietly be measured over a different recording than the one named beside it.
     */
    @Test
    public void theTimeAxisAgreesWithTheFacade() {
        ImagePlus plain = textured(16, 16, 5);
        assertEquals(regdrift.RegDrift.frameCount(plain), Frames.of(plain).count());

        float[][][] multi = new float[2][3][];
        for (int c = 0; c < 2; c++) {
            for (int t = 0; t < 3; t++) multi[c][t] = Synth.flat(8, 8, c * 10f + t);
        }
        ImagePlus hyper = Synth.hyperstack("hyper", 8, 8, multi);
        assertEquals(regdrift.RegDrift.frameCount(hyper), Frames.of(hyper).count());

        ImagePlus single = Synth.stack("single", 8, 8, Synth.flat(8, 8, 1f));
        assertEquals(regdrift.RegDrift.frameCount(single), Frames.of(single).count());
    }

    @Test
    public void aFrameSourceAlwaysStatesItsScale() {
        FrameSource native1 = Frames.of(textured(32, 32, 2));
        FrameSource binned = Frames.of(textured(32, 32, 2), 1, Frames.PROJECT_Z,
                Frames.Bin.factor(4));
        assertEquals(1, native1.bin().factor());
        assertEquals(4, binned.bin().factor());
        assertEquals(8, binned.width());
        assertEquals(8, binned.height());
    }

    // ------------------------------------------------------------- fixtures

    /** A single-channel plain stack of analytic frames that drift by a fraction of a pixel. */
    private static ImagePlus textured(int w, int h, int frames) {
        float[][] planes = new float[frames][];
        for (int t = 0; t < frames; t++) planes[t] = Synth.frame(w, h, 0.3 * t, 0.2 * t);
        return Synth.stack("textured", w, h, planes);
    }
}
