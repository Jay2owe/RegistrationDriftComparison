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

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T6, and defect D5: every frame pair is measured on its own and reported on its own.
 *
 * <h2>What is being kept out, and why it is tempting</h2>
 *
 * <p>Multi-lag reconciliation means measuring frame 1 against 3 and 1 against 4 as well as 1 against
 * 2, and then solving for the chain that is most consistent with all of them. It genuinely does
 * reduce error - by an order of magnitude on some published comparisons - and somebody who reads that
 * will want to switch it on here.
 *
 * <p>It reduces error by <b>averaging it out along the trace</b>, and averaging along the trace is
 * exactly what must not happen here. These displacements are not being used to register anything;
 * they are being used to describe what kind of movement the recording contains. Smoothing a trace
 * turns a sequence of independent random steps into something that looks like a slow walk, which
 * would suppress the difference between white jitter and a random walk <em>by construction</em> - and
 * that difference is the entire content of the {@code wander} descriptor.
 *
 * <p>So it is not a setting, not a default, and not a flag. It is absent, and this test is what keeps
 * it absent.
 *
 * <h2>Three ways of asserting it</h2>
 *
 * <p>By name, so a parameter called {@code reconcile} cannot be threaded through. By bytecode, so a
 * class from somewhere else cannot be reached for. And <b>by behaviour</b>, which is the one that
 * would catch a reconciliation written from scratch under a different word: measuring a set of pairs
 * together gives bit-for-bit the same answers as measuring each one alone. If any pair's answer were
 * adjusted using another pair's, it could not.
 */
public class NoReconciliationInFingerprintTest {

    private static final int W = 96;
    private static final int H = 96;
    private static final double BOUND = 10;
    private static final Frames.Bin SCALE = Frames.Bin.none();

    /** Words for the thing, in every spelling somebody might reach for. */
    private static final String[] THE_WORDS = {"reconcil", "multilag", "multi-lag"};

    // ------------------------------------------------------------- by name

    /** Nothing on the fingerprint path takes a reconciliation parameter or offers one. */
    @Test
    public void noMethodInTheMeasurementNamesItOrTakesIt() {
        Class<?>[] onThePath = {Estimators.class, Estimator.class, PhaseCorrelation.class,
                PyramidSsd.class, Estimators.Result.class, Estimators.PairEstimate.class};
        for (int i = 0; i < onThePath.length; i++) {
            for (Method method : onThePath[i].getMethods()) {
                assertNoWordIn(onThePath[i].getName() + "." + method.getName() + "()",
                        method.getName());
                Class<?>[] parameters = method.getParameterTypes();
                for (int p = 0; p < parameters.length; p++) {
                    assertNoWordIn(onThePath[i].getName() + "." + method.getName()
                            + " takes a " + parameters[p].getSimpleName(),
                            parameters[p].getSimpleName());
                }
                assertNoWordIn(onThePath[i].getName() + "." + method.getName() + " returns a "
                        + method.getReturnType().getSimpleName(),
                        method.getReturnType().getSimpleName());
            }
        }
    }

    // ---------------------------------------------------------- by bytecode

    /** And no class in the measurement reaches for one from anywhere else. */
    @Test
    public void noClassInTheMeasurementNamesOne() throws IOException {
        List<String> classes = Bytecode.classesIn("regdrift.diag");
        assertTrue("the scan must be looking at something", classes.size() >= 10);
        for (String className : classes) {
            Bytecode.Pool pool = Bytecode.poolOf(className);
            for (int i = 0; i < THE_WORDS.length; i++) {
                assertFalse(className + " names " + THE_WORDS[i] + " - see defect D5",
                        pool.mentions(THE_WORDS[i]));
                assertFalse(className + " names " + THE_WORDS[i] + " - see defect D5",
                        pool.mentions(capitalised(THE_WORDS[i])));
            }
        }
    }

    // --------------------------------------------------------- by behaviour

    /**
     * The substantive one. A pair measured among others is measured exactly as it would have been
     * alone.
     *
     * <p>This is what reconciliation would break, whatever it was called. Bit-for-bit, because an
     * adjustment small enough to hide inside a tolerance is still an adjustment, and the descriptors
     * downstream are built out of the differences between consecutive steps.
     */
    @Test
    public void aPairMeasuredAmongOthersIsMeasuredExactlyAsItWouldBeAlone() {
        FrameSource recording = walkingRecording();
        int[][] chain = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}};
        Estimators.Result together = measure(recording, chain);

        for (int i = 0; i < chain.length; i++) {
            Estimators.Result alone = measure(recording, new int[][]{chain[i]});
            Estimators.PairEstimate inSet = together.pairs().get(i);
            Estimators.PairEstimate onItsOwn = alone.pairs().get(0);
            assertIdentical("pair " + chain[i][0] + "->" + chain[i][1] + ", phase correlation",
                    inSet.byPhaseCorrelation(), onItsOwn.byPhaseCorrelation());
            assertIdentical("pair " + chain[i][0] + "->" + chain[i][1] + ", pyramid search",
                    inSet.byPyramidSsd(), onItsOwn.byPyramidSsd());
        }
    }

    /**
     * And a pair is measured the same whether or not the frames either side of it were also asked
     * for - the shape a reconciliation would need in order to work at all.
     */
    @Test
    public void addingNeighbouringPairsDoesNotMoveAnExistingOne() {
        FrameSource recording = walkingRecording();
        Estimators.PairEstimate middleAlone = measure(recording, new int[][]{{2, 3}}).pairs().get(0);
        Estimators.PairEstimate middleAmongNeighbours = measure(recording,
                new int[][]{{1, 2}, {2, 3}, {3, 4}}).pairs().get(1);
        assertIdentical("phase correlation", middleAlone.byPhaseCorrelation(),
                middleAmongNeighbours.byPhaseCorrelation());
        assertIdentical("pyramid search", middleAlone.byPyramidSsd(),
                middleAmongNeighbours.byPyramidSsd());
    }

    /**
     * A pair at a longer separation is measured as itself, not derived from the steps between.
     *
     * <p>The sampler asks for bridge pairs across the gaps between windows, so non-consecutive pairs
     * do occur. What must not occur is one being <em>computed</em> from the consecutive ones, which
     * is the other half of what reconciliation does.
     */
    @Test
    public void aPairAcrossAGapIsMeasuredRatherThanAddedUp() {
        FrameSource recording = walkingRecording();
        Estimators.Result steps = measure(recording, new int[][]{{0, 1}, {1, 2}, {0, 2}});
        double byStepsDx = steps.pairs().get(0).byPyramidSsd().dx()
                + steps.pairs().get(1).byPyramidSsd().dx();
        Estimators.PairEstimate bridge = steps.pairs().get(2);
        assertEquals("the bridge is a measurement of its own two frames", 4.0,
                bridge.byPyramidSsd().dx(), 0.10);
        assertEquals("which the two steps happen to agree with, having been measured separately",
                byStepsDx, bridge.byPyramidSsd().dx(), 0.20);
        Estimators.PairEstimate bridgeAlone =
                measure(recording, new int[][]{{0, 2}}).pairs().get(0);
        assertIdentical("and it does not change when the steps are not asked for",
                bridge.byPyramidSsd(), bridgeAlone.byPyramidSsd());
    }

    // ------------------------------------------------------------- machinery

    /** Six frames, each two pixels further along than the last. */
    private static FrameSource walkingRecording() {
        float[][] planes = new float[6][];
        for (int t = 0; t < planes.length; t++) {
            planes[t] = Synth.frame(W, H, 2.0 * t, -1.0 * t);
        }
        return Synth.source(W, H, SCALE, planes);
    }

    private static Estimators.Result measure(FrameSource frames, int[][] pairs) {
        return Estimators.measure(frames, pairs, BOUND, 1, PairScheduler.Progress.NONE,
                Cancellation.never());
    }

    private static void assertIdentical(String what, Estimator.Displacement one,
                                        Estimator.Displacement other) {
        assertEquals(what + ": dx moved when other pairs were present", one, other);
        assertEquals(what + ": dx differs in its last bit",
                Double.doubleToRawLongBits(one.dx()), Double.doubleToRawLongBits(other.dx()));
        assertEquals(what + ": dy differs in its last bit",
                Double.doubleToRawLongBits(one.dy()), Double.doubleToRawLongBits(other.dy()));
    }

    private static void assertNoWordIn(String where, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < THE_WORDS.length; i++) {
            assertFalse(where + " names " + THE_WORDS[i] + ". The fingerprint is a plain chain of"
                            + " independent pair measurements, and reconciliation would erase the"
                            + " distinction the wander descriptor exists to make - see defect D5.",
                    lower.contains(THE_WORDS[i]));
        }
    }

    private static String capitalised(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
