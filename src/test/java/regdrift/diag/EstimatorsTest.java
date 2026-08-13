/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import org.junit.Test;
import regdrift.Cancellation;
import regdrift.internal.PairScheduler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Running both estimators over a set of pairs: the agreement column, the sharing, and the promises
 * about how the work is scheduled.
 *
 * <p>The recording used here is cut out of one broadband field, so every displacement in it is exact
 * by construction and both estimators can read it. {@link Synth#frame} would not do: it is a handful
 * of sinusoids, which phase correlation cannot localise, and an agreement test on content one of the
 * two cannot read would be measuring the fixture.
 */
public class EstimatorsTest {

    private static final int W = 96;
    private static final int H = 96;
    private static final int MARGIN = 48;
    private static final double BOUND = 12;
    private static final Frames.Bin SCALE = Frames.Bin.none();

    /** Whole-pixel steps, so the truth is exact and no interpolator is assumed anywhere. */
    private static final int[][] STEPS = {{2, -1}, {3, 1}, {-1, -2}, {4, 0}, {0, 3}};

    // ------------------------------------------------------- what it produces

    /** The two estimators agree, and the agreement is the median of the per-pair differences. */
    @Test
    public void theTwoEstimatorsAgreeOnARecordingWithKnownSteps() {
        Estimators.Result result = measure(chain(), 1);
        assertEquals(STEPS.length, result.measuredPairs());
        assertEquals("every pair was read by both", STEPS.length, result.comparedPairs());
        assertTrue("the two estimators are " + result.agreementPx() + " px apart on a recording"
                + " whose steps are exact by construction", result.agreementPx() < 0.5);
        for (int i = 0; i < STEPS.length; i++) {
            Estimators.PairEstimate pair = result.pairs().get(i);
            assertEquals("phase dx of step " + i, STEPS[i][0],
                    pair.byPhaseCorrelation().dx(), 0.20);
            assertEquals("phase dy of step " + i, STEPS[i][1],
                    pair.byPhaseCorrelation().dy(), 0.20);
            assertEquals("pyramid dx of step " + i, STEPS[i][0], pair.byPyramidSsd().dx(), 0.20);
            assertEquals("pyramid dy of step " + i, STEPS[i][1], pair.byPyramidSsd().dy(), 0.20);
        }
    }

    /**
     * The agreement is a median, not a mean.
     *
     * <p>One pair where the two estimators land far apart - a bubble crossing the field, a step past
     * the bound - must not swamp a confidence number computed over thirty-five good ones. Asserted by
     * making one pair of a recording featureless: the median over what is left is unchanged, which a
     * mean could not manage.
     */
    @Test
    public void theAgreementIsAMedianSoOneBadPairDoesNotSwampIt() {
        float[][] planes = walk();
        Estimators.Result clean = measure(Synth.source(W, H, SCALE, planes), 1);

        float[][] withAWreckedFrame = walk();
        withAWreckedFrame[3] = Synth.flat(W, H, 900f);
        Estimators.Result spoiled = measure(Synth.source(W, H, SCALE, withAWreckedFrame), 1);

        assertEquals("two pairs touch the wrecked frame", STEPS.length - 2,
                spoiled.comparedPairs());
        assertTrue("the median moved by " + Math.abs(spoiled.agreementPx() - clean.agreementPx())
                        + " px, which is more than one pair should be able to do",
                Math.abs(spoiled.agreementPx() - clean.agreementPx()) < 0.25);
    }

    /** Every number carries the scale, and the provenance says what produced it. */
    @Test
    public void everyResultCarriesItsScaleAndSaysWhatProducedIt() {
        Frames.Bin four = Frames.Bin.factor(4);
        FrameSource binned = Synth.source(W, H, four, walk());
        Estimators.Result result = measure(binned, 1);
        assertEquals(four, result.measuredAt());
        for (Estimators.PairEstimate pair : result.pairs()) {
            assertEquals(four, pair.measuredAt());
            assertEquals(four, pair.byPhaseCorrelation().measuredAt());
            assertEquals(four, pair.byPyramidSsd().measuredAt());
        }
        assertTrue(result.provenance(), result.provenance().contains("phase correlation"));
        assertTrue(result.provenance(),
                result.provenance().contains("pyramid sum of squared differences"));
        assertTrue(result.provenance(), result.provenance().contains("4 x 4 pixel mean"));
        assertEquals(2, Estimators.names().size());
    }

    // ------------------------------------------------------ how it is scheduled

    /**
     * Serial, two workers and as many as the machine will give: bit-for-bit the same numbers.
     *
     * <p>Not "the same to a tolerance". A diagnosis that changed in its last digit between two runs
     * of the same recording on the same machine would be a diagnosis nobody could quote.
     */
    @Test
    public void serialTwoWorkerAndMaxWorkerRunsAreBitIdentical() {
        FrameSource recording = chain();
        Estimators.Result serial = measure(recording, 1);
        Estimators.Result two = measure(recording, 2);
        Estimators.Result many = measure(recording, Runtime.getRuntime().availableProcessors());
        for (int i = 0; i < serial.measuredPairs(); i++) {
            assertSameBits("two workers, pair " + i, serial.pairs().get(i), two.pairs().get(i));
            assertSameBits("max workers, pair " + i, serial.pairs().get(i), many.pairs().get(i));
        }
        assertEquals(Double.doubleToRawLongBits(serial.agreementPx()),
                Double.doubleToRawLongBits(two.agreementPx()));
        assertEquals(Double.doubleToRawLongBits(serial.agreementPx()),
                Double.doubleToRawLongBits(many.agreementPx()));
    }

    /**
     * Each frame is read once however many pairs it appears in, and all of it on the calling thread.
     *
     * <p>Reading is what touches ImageJ, and ImageJ belongs to the coordinator. It is also the part
     * that costs disk: a five-pair consecutive chain names ten frame slots and six distinct frames.
     */
    @Test
    public void eachFrameIsReadOnceAndOnlyOnTheCallingThread() {
        final AtomicInteger reads = new AtomicInteger();
        final List<String> readingThreads = new ArrayList<String>();
        final FrameSource underneath = chain();
        FrameSource counted = new FrameSource() {
            @Override
            public int count() {
                return underneath.count();
            }

            @Override
            public int width() {
                return underneath.width();
            }

            @Override
            public int height() {
                return underneath.height();
            }

            @Override
            public Frames.Bin bin() {
                return underneath.bin();
            }

            @Override
            public float[] plane(int frame) {
                reads.incrementAndGet();
                synchronized (readingThreads) {
                    readingThreads.add(Thread.currentThread().getName());
                }
                return underneath.plane(frame);
            }
        };
        String caller = Thread.currentThread().getName();
        Estimators.Result result = Estimators.measure(counted, consecutive(STEPS.length), BOUND, 4,
                PairScheduler.Progress.NONE, Cancellation.never());
        assertEquals("one read per distinct frame, not one per pair", STEPS.length + 1, reads.get());
        assertEquals(STEPS.length + 1, result.framesUsed());
        for (String thread : readingThreads) {
            assertEquals("a worker read the stack", caller, thread);
        }
    }

    /**
     * Neither estimator builds its per-frame form twice: one transform and one pyramid per frame,
     * not one per pair.
     *
     * <p>A frame in the middle of a chain belongs to two pairs, and the expensive part of measuring
     * a pair is reducing a frame rather than comparing two already-reduced ones. Built per pair, a
     * five-pair chain would do twenty of these; cached, it does twelve. Six frames, two estimators.
     */
    @Test
    public void neitherEstimatorBuildsAFramesFormTwice() {
        Estimators.Result result = measure(chain(), 1);
        assertEquals("distinct frames", STEPS.length + 1, result.framesUsed());
        assertEquals("one per frame per estimator, and no more",
                2 * (STEPS.length + 1), result.perFrameBuilds());
        assertTrue("which is fewer than one per pair would be",
                result.perFrameBuilds() < 2 * 2 * result.measuredPairs());
    }

    /** Progress is reported once per pair and the pairs come back in the order they were asked for. */
    @Test
    public void progressIsReportedPerPairAndTheOrderIsTheOrderAskedFor() {
        final AtomicInteger updates = new AtomicInteger();
        int[][] pairs = {{3, 4}, {0, 1}, {2, 3}};
        Estimators.Result result = Estimators.measure(chain(), pairs, BOUND, 2,
                new PairScheduler.Progress() {
                    @Override
                    public void update(int done, int total) {
                        updates.incrementAndGet();
                    }
                }, Cancellation.never());
        assertEquals(pairs.length, updates.get());
        for (int i = 0; i < pairs.length; i++) {
            assertEquals("pair " + i + " came back out of order", pairs[i][0],
                    result.pairs().get(i).from());
            assertEquals(pairs[i][1], result.pairs().get(i).to());
        }
    }

    // --------------------------------------------------- the bound is an argument

    /**
     * No class in the measurement stores a search bound.
     *
     * <p>Stage 08 derives one bound for the whole recording. A bound worked out per pair - or worse,
     * remembered from the last one - gave a quiet stretch of a recording a bound near the floor,
     * which clamped the genuine motion in it and left the descriptors describing the search box.
     *
     * <p>Fields the compiler generates to capture a method argument are skipped, and are not the
     * thing being guarded against: they hold that call's argument, they are made fresh per call, and
     * nothing can read them from outside.
     */
    @Test
    public void nothingInTheMeasurementStoresASearchBound() throws ClassNotFoundException {
        List<String> classes = Bytecode.classesIn("regdrift.diag");
        assertTrue("the scan must be looking at something", classes.size() >= 10);
        for (String className : classes) {
            if (className.endsWith("package-info")) continue;
            for (Field field : Class.forName(className).getDeclaredFields()) {
                if (field.isSynthetic()) continue;
                String name = field.getName().toLowerCase(Locale.ROOT);
                assertFalse(className + "." + field.getName() + " stores a search bound. The bound"
                                + " is an argument on every path; stage 08 derives one for the whole"
                                + " recording.",
                        name.contains("maxshift") || name.contains("searchbound")
                                || name.contains("shiftbound"));
            }
        }
    }

    /** The same recording with two different bounds gives two different answers, from the argument. */
    @Test
    public void theBoundTakesEffectFromTheArgumentAlone() {
        FrameSource recording = chain();
        int[][] one = {{0, 1}};
        Estimators.Result roomy = Estimators.measure(recording, one, BOUND, 1,
                PairScheduler.Progress.NONE, Cancellation.never());
        Estimators.Result tight = Estimators.measure(recording, one, 1.0, 1,
                PairScheduler.Progress.NONE, Cancellation.never());
        assertEquals(Estimator.Status.OK, roomy.pairs().get(0).byPyramidSsd().status());
        assertEquals(Estimator.Status.AT_SHIFT_BOUND,
                tight.pairs().get(0).byPyramidSsd().status());
        assertEquals("and the roomy run was not changed by the tight one", 2.0,
                roomy.pairs().get(0).byPyramidSsd().dx(), 0.20);
    }

    // ------------------------------------------------------------ awkward input

    @Test
    public void noPairsIsAnEmptyResultRatherThanNothingAtAll() {
        Estimators.Result result = Estimators.measure(chain(), new int[0][], BOUND, 1,
                PairScheduler.Progress.NONE, Cancellation.never());
        assertNotNull(result);
        assertEquals(0, result.measuredPairs());
        assertEquals(0, result.comparedPairs());
        assertTrue(Double.isNaN(result.agreementPx()));
        assertEquals(SCALE, result.measuredAt());
    }

    @Test
    public void aFrameOutsideTheRecordingIsRefusedByName() {
        try {
            Estimators.measure(chain(), new int[][]{{0, 99}}, BOUND, 1,
                    PairScheduler.Progress.NONE, Cancellation.never());
            fail("a pair naming a frame that is not there was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("99"));
        }
    }

    @Test
    public void aBoundOfZeroIsRefusedRatherThanTreatedAsUnbounded() {
        try {
            Estimators.measure(chain(), consecutive(2), 0, 1, PairScheduler.Progress.NONE,
                    Cancellation.never());
            fail("a search bound of zero was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("bound"));
        }
    }

    @Test
    public void framesWithNoScaleOnThemAreRefused() {
        final FrameSource underneath = chain();
        FrameSource unscaled = new FrameSource() {
            @Override
            public int count() {
                return underneath.count();
            }

            @Override
            public int width() {
                return underneath.width();
            }

            @Override
            public int height() {
                return underneath.height();
            }

            @Override
            public Frames.Bin bin() {
                return null;
            }

            @Override
            public float[] plane(int frame) {
                return underneath.plane(frame);
            }
        };
        try {
            Estimators.measure(unscaled, consecutive(2), BOUND, 1, PairScheduler.Progress.NONE,
                    Cancellation.never());
            fail("frames with no scale on them were measured anyway");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("scale"));
        }
    }

    // ------------------------------------------------------------- machinery

    /** A recording whose frames are exact crops of one broadband field, stepping by {@link #STEPS}. */
    private static float[][] walk() {
        int fw = W + 2 * MARGIN;
        int fh = H + 2 * MARGIN;
        float[] field = Synth.texture(fw, fh, 20260813L, 2);
        float[][] planes = new float[STEPS.length + 1][];
        int ox = MARGIN;
        int oy = MARGIN;
        planes[0] = Synth.crop(field, fw, ox, oy, W, H);
        for (int t = 0; t < STEPS.length; t++) {
            // Content moves by the step, so the crop origin moves by minus it.
            ox -= STEPS[t][0];
            oy -= STEPS[t][1];
            planes[t + 1] = Synth.crop(field, fw, ox, oy, W, H);
        }
        return planes;
    }

    private static FrameSource chain() {
        return Synth.source(W, H, SCALE, walk());
    }

    private static int[][] consecutive(int steps) {
        int[][] pairs = new int[steps][];
        for (int t = 0; t < steps; t++) pairs[t] = new int[]{t, t + 1};
        return pairs;
    }

    private static Estimators.Result measure(FrameSource frames, int workers) {
        return Estimators.measure(frames, consecutive(STEPS.length), BOUND, workers,
                PairScheduler.Progress.NONE, Cancellation.never());
    }

    private static void assertSameBits(String what, Estimators.PairEstimate one,
                                       Estimators.PairEstimate other) {
        assertEquals(what + ": phase dx", Double.doubleToRawLongBits(one.byPhaseCorrelation().dx()),
                Double.doubleToRawLongBits(other.byPhaseCorrelation().dx()));
        assertEquals(what + ": phase dy", Double.doubleToRawLongBits(one.byPhaseCorrelation().dy()),
                Double.doubleToRawLongBits(other.byPhaseCorrelation().dy()));
        assertEquals(what + ": pyramid dx", Double.doubleToRawLongBits(one.byPyramidSsd().dx()),
                Double.doubleToRawLongBits(other.byPyramidSsd().dx()));
        assertEquals(what + ": pyramid dy", Double.doubleToRawLongBits(one.byPyramidSsd().dy()),
                Double.doubleToRawLongBits(other.byPyramidSsd().dy()));
        assertEquals(what + ": difference", Double.doubleToRawLongBits(one.differencePx()),
                Double.doubleToRawLongBits(other.differencePx()));
    }
}
