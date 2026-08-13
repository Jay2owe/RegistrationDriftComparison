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
import regdrift.internal.Transform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Carried across from {@code logratio\core\WarperTest.java}: a whole-pixel shift
 * copies pixels rather than resampling them, and the copy is bit-exact.
 *
 * <h2>Why this is worth a test of its own</h2>
 *
 * <p>Two things rest on it. The first is speed - a block copy is several times
 * faster than resampling a plane, and on a whole-recording warp that is the
 * difference between a comparison somebody waits for and one they abandon. The
 * second matters more: a registered 16-bit recording that went through the block
 * copy still holds <b>exactly</b> the original counts, so counting photons in it
 * is still counting photons. A quietly-resampled one holds weighted averages of
 * them that look like counts.
 *
 * <p>The upstream repository recorded this as a real defect: the documented
 * "no interpolation" default fell through to the resampler, and it showed up as
 * whole-pixel and bilinear runs producing identical numbers to four figures.
 * {@link #noInterpolationNeverResamples} is the regression test for it.
 */
public class WarperExactnessTest {

    private static final int WIDTH = 37;
    private static final int HEIGHT = 29;

    // ------------------------------------------------------ the path that is taken

    @Test
    public void aWholePixelShiftTakesTheBlockCopyPathAtEveryInterpolation() {
        Transform whole = Transform.translation(3, -2);
        for (ControlWarp.Interpolation how : ControlWarp.Interpolation.values()) {
            assertEquals("a whole-pixel shift never needs resampling, and " + how
                            + " must not do any", ControlWarp.Path.BLOCK_COPY,
                    ControlWarp.pathFor(whole, how));
        }
    }

    @Test
    public void aFractionalShiftIsResampledUnlessNoInterpolationWasAskedFor() {
        Transform fractional = Transform.translation(3.25, -2.5);
        assertEquals(ControlWarp.Path.RESAMPLED,
                ControlWarp.pathFor(fractional, ControlWarp.Interpolation.BILINEAR));
        assertEquals(ControlWarp.Path.RESAMPLED,
                ControlWarp.pathFor(fractional, ControlWarp.Interpolation.BICUBIC));
        assertEquals("no interpolation means round to whole pixels, not resample",
                ControlWarp.Path.BLOCK_COPY,
                ControlWarp.pathFor(fractional, ControlWarp.Interpolation.NONE));
    }

    @Test
    public void aRotationIsAlwaysResampled() {
        Transform turned = new Transform(0, 0, 0.01);
        for (ControlWarp.Interpolation how : ControlWarp.Interpolation.values()) {
            assertEquals(ControlWarp.Path.RESAMPLED, ControlWarp.pathFor(turned, how));
        }
    }

    // --------------------------------------------------------------- bit exactness

    /**
     * The claim in full: after a whole-pixel shift, every pixel that had a source
     * is the same bits it was, and every pixel that did not is the fill value.
     */
    @Test
    public void anIntegerShiftIsBitExactAgainstTheSourcePixels() {
        float[] source = ramp();
        int[] shifts = {0, 1, -1, 3, -4, 12, -12};
        for (int i = 0; i < shifts.length; i++) {
            for (int j = 0; j < shifts.length; j++) {
                int ix = shifts[i];
                int iy = shifts[j];
                float[] out = ControlWarp.warp(source, WIDTH, HEIGHT,
                        Transform.translation(ix, iy), ControlWarp.Interpolation.BILINEAR,
                        Float.NaN);
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        int sx = x + ix;
                        int sy = y + iy;
                        float got = out[y * WIDTH + x];
                        if (sx < 0 || sy < 0 || sx >= WIDTH || sy >= HEIGHT) {
                            assertTrue("(" + x + ", " + y + ") shifted by (" + ix + ", " + iy
                                    + ") has no source and must be the fill value, and was " + got,
                                    Float.isNaN(got));
                            continue;
                        }
                        float wanted = source[sy * WIDTH + sx];
                        assertEquals("(" + x + ", " + y + ") shifted by (" + ix + ", " + iy
                                        + ") must be the source pixel to the last bit",
                                Float.floatToRawIntBits(wanted), Float.floatToRawIntBits(got));
                    }
                }
            }
        }
    }

    /** The public path and the block copy it delegates to produce the same array. */
    @Test
    public void thePublicPathAndTheBlockCopyAgreeToTheLastBit() {
        float[] source = ramp();
        float[] viaWarp = ControlWarp.warp(source, WIDTH, HEIGHT, Transform.translation(5, -3),
                ControlWarp.Interpolation.BILINEAR, 0f);
        float[] viaBlockCopy = new float[WIDTH * HEIGHT];
        ControlWarp.blockCopy(source, viaBlockCopy, WIDTH, HEIGHT, 5, -3, 0f);
        assertSameBits("the warp must reach the block copy, not a resampler",
                viaBlockCopy, viaWarp);
    }

    /**
     * A shift out and back restores the interior exactly. Resampling could not do
     * this: two bilinear passes smooth twice and never come back.
     */
    @Test
    public void aWholePixelShiftAndItsInverseRestoreTheInterior() {
        float[] source = ramp();
        float[] out = ControlWarp.warp(source, WIDTH, HEIGHT, Transform.translation(4, -3),
                ControlWarp.Interpolation.BILINEAR, 0f);
        float[] back = ControlWarp.warp(out, WIDTH, HEIGHT, Transform.translation(-4, 3),
                ControlWarp.Interpolation.BILINEAR, 0f);
        // Out by (4, -3) then back by (-4, 3) leaves the pixels that had a source both times:
        // four columns are lost on the left going out, three rows at the bottom coming back.
        for (int y = 0; y < HEIGHT - 3; y++) {
            for (int x = 4; x < WIDTH; x++) {
                assertEquals("interior pixel (" + x + ", " + y + ") came back changed",
                        Float.floatToRawIntBits(source[y * WIDTH + x]),
                        Float.floatToRawIntBits(back[y * WIDTH + x]));
            }
        }
    }

    /** A fractional shift really does resample, so the test above is not vacuous. */
    @Test
    public void aFractionalShiftChangesThePixels() {
        float[] source = ramp();
        float[] out = ControlWarp.warp(source, WIDTH, HEIGHT, Transform.translation(0.5, 0.25),
                ControlWarp.Interpolation.BILINEAR, 0f);
        int changed = 0;
        for (int i = 0; i < source.length; i++) {
            if (Float.floatToRawIntBits(source[i]) != Float.floatToRawIntBits(out[i])) changed++;
        }
        assertTrue("a half-pixel shift has to move most of the pixels, and moved " + changed,
                changed > source.length / 2);
    }

    /**
     * The upstream defect: {@link ControlWarp.Interpolation#NONE} used to fall
     * through to the bilinear sampler, so the documented default silently blurred.
     */
    @Test
    public void noInterpolationNeverResamples() {
        float[] source = ramp();
        float[] rounded = ControlWarp.warp(source, WIDTH, HEIGHT, Transform.translation(3.4, -1.6),
                ControlWarp.Interpolation.NONE, 0f);
        float[] whole = ControlWarp.warp(source, WIDTH, HEIGHT, Transform.translation(3, -2),
                ControlWarp.Interpolation.NONE, 0f);
        assertSameBits("no interpolation must round the shift and copy, not sample", whole,
                rounded);

        float[] sampled = ControlWarp.warp(source, WIDTH, HEIGHT, Transform.translation(3.4, -1.6),
                ControlWarp.Interpolation.BILINEAR, 0f);
        int differing = 0;
        for (int i = 0; i < sampled.length; i++) {
            if (Float.floatToRawIntBits(sampled[i]) != Float.floatToRawIntBits(rounded[i])) {
                differing++;
            }
        }
        assertTrue("if these two agree, the no-interpolation path is quietly bilinear again",
                differing > sampled.length / 4);
    }

    // ------------------------------------------------------------------- edges

    @Test
    public void aShiftLargerThanTheFrameLeavesNothingButFill() {
        float[] source = ramp();
        float[] out = ControlWarp.warp(source, WIDTH, HEIGHT, Transform.translation(WIDTH + 5, 0),
                ControlWarp.Interpolation.BILINEAR, -7f);
        for (int i = 0; i < out.length; i++) {
            assertEquals(-7f, out[i], 0f);
        }
    }

    @Test
    public void aNullTransformCopies() {
        float[] source = ramp();
        float[] out = ControlWarp.warp(source, WIDTH, HEIGHT, null,
                ControlWarp.Interpolation.BILINEAR, 0f);
        assertSameBits("no transform is a copy", source, out);
    }

    @Test
    public void aPlaneOfTheWrongLengthIsRefusedByName() {
        try {
            ControlWarp.warp(new float[3], WIDTH, HEIGHT, Transform.IDENTITY,
                    ControlWarp.Interpolation.BILINEAR, 0f);
            fail("a plane of the wrong length must be refused");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage(),
                    refused.getMessage().contains(String.valueOf(WIDTH * HEIGHT)));
        }
    }

    // ------------------------------------------------------------- the margin

    @Test
    public void theValidMarginCoversTheWholeExcursionPlusTheInterpolatorsReach() {
        Transform[] cumulative = {
                Transform.translation(0, 0),
                Transform.translation(4.2, -3.7),
                Transform.translation(-2.1, 6.4),
        };
        ControlWarp.Margin margin = ControlWarp.validMargin(cumulative, 64, 64,
                ControlWarp.Interpolation.BILINEAR);
        assertEquals("4 px right, plus one for bilinear's reach", 5, margin.right());
        assertEquals("2 px left, plus one", 3, margin.left());
        assertEquals("6 px down, plus one", 7, margin.bottom());
        assertEquals("4 px up, plus one", 5, margin.top());
        assertEquals(64 - 5 - 3, margin.croppedWidth(64));
        assertEquals(64 - 7 - 5, margin.croppedHeight(64));
        assertNotEquals(0L, margin.croppedPixels(64, 64));
    }

    @Test
    public void aMarginNeverCropsTheWholeFrameAway() {
        Transform[] wild = {Transform.translation(10_000, 10_000)};
        ControlWarp.Margin margin = ControlWarp.validMargin(wild, 64, 64,
                ControlWarp.Interpolation.BILINEAR);
        assertTrue(margin.croppedWidth(64) > 0);
        assertTrue(margin.croppedHeight(64) > 0);
    }

    // ---------------------------------------------------------------- machinery

    /** A plane whose every pixel is a different value, so a misplaced copy shows. */
    private static float[] ramp() {
        float[] out = new float[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                out[y * WIDTH + x] = (float) (1000.0 + 3.25 * x + 7.5 * y + 0.125 * x * y);
            }
        }
        return out;
    }

    private static void assertSameBits(String what, float[] expected, float[] actual) {
        assertEquals(what + ": different lengths", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(what + " at " + i, Float.floatToRawIntBits(expected[i]),
                    Float.floatToRawIntBits(actual[i]));
        }
    }
}
