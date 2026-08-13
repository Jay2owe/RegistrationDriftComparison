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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T2. What a displacement trace is turned into: a drift rate, an excursion, step
 * statistics, knock presence, a compound label and a severity.
 *
 * <p>Every fixture here is a trace rather than an image. The estimators are
 * stage 07's and are tested there; what is under test here is the arithmetic that
 * turns their answers into a description, and building it from images would make
 * these assertions partly about the estimators.
 */
public class MotionDescriptorsTest {

    // ------------------------------------------------------ the four motions

    /**
     * A field carried steadily in one direction: all drift, nothing else.
     *
     * <p>{@code wander} is zero because there is nothing left once the line is
     * removed, and the drift figure is the ramp itself.
     */
    @Test
    public void aPureRampIsAllDriftAndNoWander() {
        WindowSampler.Plan plan = WindowSampler.measured().plan(48);
        MotionDescriptors d = describe(plan, ramp(48, 0.5, 0));

        assertEquals(0.5, d.driftRatePx(), 1e-9);
        assertEquals(0.5 * 47, d.driftPx(), 1e-9);
        assertEquals(0.0, d.wander(), 1e-9);
        assertEquals(0.0, d.residualRmsPx(), 1e-9);
        assertEquals(0.0, d.stepMaxPx(), 1e-9);
        assertFalse(d.knockPresent());
        assertEquals("DRIFT", d.label().render());
        assertEquals(MotionLabel.Component.DRIFT, d.label().dominant());
        assertEquals(MotionDescriptors.Severity.SEVERE, d.severity());

        for (double rate : d.windowRatesPx()) assertEquals(0.5, rate, 1e-9);
    }

    /**
     * A field shaking about a fixed point: each position independent of the last.
     *
     * <p>{@code wander} is the residual excursion over the step size, and for
     * independent positions that ratio is {@code 1/sqrt(2)} by construction, which
     * is where the 0.7 in the contract comes from.
     */
    @Test
    public void whiteJitterHasAWanderOfAboutSevenTenths() {
        WindowSampler.Plan plan = WindowSampler.everyConsecutivePair().plan(200);
        MotionDescriptors d = describe(plan, whiteJitter(200, 1.0, 90210L));

        assertEquals(0.707, d.wander(), 0.05);
        assertTrue(d.provenance(), d.driftPx() < 1.0);
        assertEquals("JITTER", d.label().render());
        assertEquals(MotionLabel.Component.JITTER, d.label().dominant());
        assertFalse(d.knockPresent());
    }

    /**
     * A field with no destination, each step taken from wherever the last one left
     * it. The excursion grows with time, so {@code wander} is several.
     *
     * <p><b>And it is several only over a long enough stretch.</b> The same walk
     * measured over a twelve-frame window reads far lower, because twelve frames
     * of a random walk genuinely do look like jitter. That is a property of the
     * sampler's window length rather than of this arithmetic, and it is asserted
     * here so that nobody rediscovers it as a defect.
     */
    @Test
    public void aRandomWalkWandersSeveralTimesItsStep() {
        double[][] walk = randomWalk(200, 1.0, 4242L);
        MotionDescriptors full =
                describe(WindowSampler.everyConsecutivePair().plan(200), walk);

        assertTrue(full.provenance(), full.wander() > 2.5);
        assertTrue(full.label().has(MotionLabel.Component.WALK));

        MotionDescriptors windowed = describe(WindowSampler.measured().plan(200), walk);
        assertTrue("a twelve-frame window sees far less of a walk's excursion: "
                        + windowed.wander() + " against " + full.wander(),
                windowed.wander() < full.wander());
    }

    /**
     * One large step among small ones. The knock is reported as present, the step
     * itself is the largest step, and the drift figure is not dragged upward by it.
     *
     * <p>The drift figure survives because it is the <b>median</b> of the three
     * windows' rates. A single window bent by a knock fits a line with a slope of
     * about 1.9 px a frame through a recording that is not drifting at all; two
     * clean windows outvote it. One line fitted across the whole recording would
     * have reported that 1.9.
     */
    @Test
    public void oneInjectedJumpIsAKnockAndDoesNotBecomeDrift() {
        WindowSampler.Plan plan = WindowSampler.measured().plan(48);
        double[][] positions = whiteJitter(48, 0.2, 13L);
        for (int t = 24; t < 48; t++) positions[t][0] += 15;      // a 15 px jump, inside window 1

        MotionDescriptors d = describe(plan, positions);

        assertTrue(d.provenance(), d.knockPresent());
        assertTrue(d.label().has(MotionLabel.Component.KNOCK));
        assertTrue("the largest step is the jump, less the line the window fitted through it: "
                + d.stepMaxPx(), d.stepMaxPx() > 12 && d.stepMaxPx() < 15);
        assertTrue("the jump must not read as drift: " + d.driftPx(), d.driftPx() < 3);
        assertFalse(d.label().render(), d.label().has(MotionLabel.Component.DRIFT));
        assertTrue(d.stepMaxPx() > d.knockThresholdPx());

        // The window the jump landed in fits a steep line; the other two do not.
        double[] rates = d.windowRatesPx();
        assertTrue("window 1 holds the jump: " + rates[1], rates[1] > 1.5);
        assertTrue(rates[0] < 0.2);
        assertTrue(rates[2] < 0.2);
    }

    /**
     * A sinusoid is described by its drift and its excursion, like anything else.
     *
     * <p>There is no periodic component to reach for - see
     * {@link NoPeriodicLabelTest} and defect D3.
     */
    @Test
    public void aSinusoidIsLabelledByItsDriftAndItsWander() {
        WindowSampler.Plan plan = WindowSampler.everyConsecutivePair().plan(96);
        MotionDescriptors d = describe(plan, sinusoid(96, 6.0, 24.0));

        assertTrue(d.measured());
        assertTrue(d.label().render(), d.label().measured());
        for (MotionLabel.Component component : d.label().components()) {
            assertTrue(component.name(), EnumSet.allOf(MotionLabel.Component.class)
                    .contains(component));
        }
        assertFalse(d.label().render(), d.label().render().contains("PERIOD"));
        assertFalse(d.label().render(), d.label().render().contains("OSC"));
    }

    // ------------------------------------------------------------- the gaps

    /**
     * <b>A movement inside a gap is reported as a bridge and nowhere else.</b>
     *
     * <p>Two traces holding the very same within-window displacements, one of
     * which also moves 25 px across the first gap. Every within-window number is
     * bit identical between them. The movement appears in {@code bridge_max_px}
     * and {@code bridge_span}, and it is why one of the two reports a knock.
     *
     * <p>A bridge spans seven frames here. Folding it into the step statistics
     * would make it look like one enormous single-frame step, which is precisely
     * the thing the sampler is not able to see.
     */
    @Test
    public void aMovementInsideAGapNeverReachesTheStepStatistics() {
        WindowSampler.Plan plan = WindowSampler.measured().plan(48);
        List<Estimator.Displacement> quiet = trace(plan, whiteJitter(48, 0.3, 55L));
        List<Estimator.Displacement> jumped =
                new ArrayList<Estimator.Displacement>(quiet);
        int firstGap = plan.bridgePairIndex(0);
        jumped.set(firstGap, Estimator.Displacement.of(
                quiet.get(firstGap).dx() + 25, quiet.get(firstGap).dy(),
                Estimator.Status.OK, Frames.Bin.none()));

        MotionDescriptors without = MotionDescriptors.of(plan, quiet);
        MotionDescriptors with = MotionDescriptors.of(plan, jumped);

        assertSameBits("wander", without.wander(), with.wander());
        assertSameBits("step_rms_px", without.stepRmsPx(), with.stepRmsPx());
        assertSameBits("step_max_px", without.stepMaxPx(), with.stepMaxPx());
        assertSameBits("drift_rate_px", without.driftRatePx(), with.driftRatePx());
        assertSameBits("residual", without.residualRmsPx(), with.residualRmsPx());

        assertTrue(with.bridgeMaxPx() > 24);
        assertTrue(without.bridgeMaxPx() < 2);
        assertEquals("11 and 18, numbered as the stack numbers them", "12-19", with.bridgeSpan());
        assertTrue(with.provenance(), with.knockPresent());
        assertFalse(without.provenance(), without.knockPresent());
        assertTrue(with.bridgeExcessPx() > 24);
    }

    /** No gaps to span means no bridge reading, not a bridge reading of zero. */
    @Test
    public void aPlanWithNoGapsReportsNoBridgeRatherThanZero() {
        MotionDescriptors d = describe(WindowSampler.everyConsecutivePair().plan(48),
                whiteJitter(48, 0.4, 7L));
        assertTrue(Double.isNaN(d.bridgeMaxPx()));
        assertTrue(Double.isNaN(d.bridgeExcessPx()));
        assertEquals("", d.bridgeSpan());
    }

    // ------------------------------------------------------ knocks, and D13

    /**
     * <b>Knock presence is stable across samplings; the threshold it is judged
     * against is not.</b>
     *
     * <p>This is defect D13 in one test. The rule is "a step larger than three
     * pixels, or six times the typical step", and the typical step is computed
     * over whichever pairs were sampled. Five different samplings of the same
     * recording are given here, they agree on presence, and they do not agree on
     * the threshold - which is why a count of steps clearing that threshold was
     * never a number to act on, and why there is none to read.
     */
    @Test
    public void knockPresenceSurvivesResamplingAndTheThresholdDoesNot() {
        double[][] positions = whiteJitter(48, 0.25, 606L);
        for (int t = 3; t < 48; t++) positions[t][0] += 12;       // inside every configuration
        for (int t = 26; t < 48; t++) positions[t][1] += 9;       // inside some of them
        for (int t = 41; t < 48; t++) positions[t][0] += 14;      // and this one too

        WindowSampler[] samplings = {
                WindowSampler.of(2, 8), WindowSampler.of(3, 8), WindowSampler.of(3, 12),
                WindowSampler.of(4, 12), WindowSampler.everyConsecutivePair(),
        };
        Set<Double> thresholds = new LinkedHashSet<Double>();
        for (WindowSampler sampler : samplings) {
            MotionDescriptors d = describe(sampler.plan(48), positions);
            assertTrue(sampler + " missed the knock: " + d.provenance(), d.knockPresent());
            thresholds.add(Double.valueOf(d.knockThresholdPx()));
        }
        assertTrue("the threshold is a property of the sample, and these samples differ: "
                + thresholds, thresholds.size() > 1);
    }

    /**
     * There is no way to ask how many knocks there were, anywhere in the plugin.
     *
     * <p>Read off the compiled classes rather than the source, so a method written
     * without the word {@code knock} in an import cannot slip past.
     *
     * <p>A knock accessor may return a boolean, because presence is stable, or a
     * measurement in pixels, because the largest step and the threshold it was
     * judged against are both real quantities. What it may not return is a whole
     * number, because the only whole number there is to return is a count. Defect
     * D13.
     */
    @Test
    public void nothingInThePluginReturnsANumberOfKnocks() throws Exception {
        for (String className : Bytecode.classesIn("regdrift")) {
            Class<?> type = Class.forName(className);
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(java.util.Locale.US);
                if (!name.contains("knock")) continue;
                assertFalse(className + "." + method.getName()
                                + " is named for a count of knocks. See defect D13.",
                        name.contains("count") || name.equals("knocks"));
                Class<?> returns = method.getReturnType();
                assertFalse(className + "." + method.getName() + " returns " + returns
                                + ", and the only whole number of knocks there is to return is a"
                                + " count of them. Presence is a boolean. See defect D13.",
                        returns == int.class || returns == long.class || returns == short.class
                                || returns == byte.class
                                || Integer.class.isAssignableFrom(returns)
                                || Long.class.isAssignableFrom(returns));
            }
        }
    }

    // ------------------------------------------------------- label and rank

    /**
     * The label is a set, and dominance is a separate reading that is allowed to
     * say it does not know.
     *
     * <p>Sweeping the drift rate through the threshold the old ordered label
     * turned on: the set stays {@code DRIFT+JITTER} across the sweep, and the
     * dominance reading moves from jitter, through {@code unclear}, to drift. The
     * ordered label used to flip between the two ends with nothing in between,
     * which is what made it move under resampling.
     */
    @Test
    public void dominanceIsReportedSeparatelyAndSaysUnclearNearTheThreshold() {
        List<String> words = new ArrayList<String>();
        for (int step = 0; step <= 30; step++) {
            double rate = 0.005 + step * 0.005;
            double[][] positions = whiteJitter(48, 0.5, 31337L);
            for (int t = 0; t < 48; t++) positions[t][0] += rate * t;
            MotionDescriptors d = describe(WindowSampler.everyConsecutivePair().plan(48),
                    positions);
            words.add(d.label().dominantWord());
        }
        int firstJitter = words.indexOf("JITTER");
        int firstUnclear = words.indexOf(MotionLabel.UNCLEAR);
        int firstDrift = words.indexOf("DRIFT");
        assertTrue(words.toString(), firstJitter == 0);
        assertTrue("there must be a band that declines to choose: " + words,
                firstUnclear > firstJitter);
        assertTrue("and drift must win beyond it: " + words, firstDrift > firstUnclear);
    }

    /** The component set is unordered, and renders one way whatever order it was built in. */
    @Test
    public void aLabelIsASetAndRendersInOneFixedOrder() {
        MotionLabel one = MotionLabel.of(EnumSet.of(MotionLabel.Component.JITTER,
                MotionLabel.Component.DRIFT), MotionLabel.Component.DRIFT);
        MotionLabel other = MotionLabel.of(EnumSet.of(MotionLabel.Component.DRIFT,
                MotionLabel.Component.JITTER), MotionLabel.Component.JITTER);

        assertEquals("DRIFT+JITTER", one.render());
        assertEquals("DRIFT+JITTER", other.render());
        assertEquals("two labels over the same components are the same label", one, other);
        assertEquals(one.hashCode(), other.hashCode());
        assertNotEquals(one.dominantWord(), other.dominantWord());

        assertEquals("DRIFT+WALK+KNOCK", MotionLabel.of(EnumSet.of(MotionLabel.Component.KNOCK,
                MotionLabel.Component.WALK, MotionLabel.Component.DRIFT), null).render());
        assertEquals(MotionLabel.UNCLEAR, MotionLabel.of(
                EnumSet.of(MotionLabel.Component.DRIFT, MotionLabel.Component.JITTER), null)
                .dominantWord());
        assertEquals("JITTER", MotionLabel.of(MotionLabel.Component.JITTER).render());
    }

    /** A knock is an event, not a share of the movement, so it never dominates. */
    @Test
    public void aKnockIsNeverTheDominantComponent() {
        try {
            MotionLabel.of(EnumSet.of(MotionLabel.Component.KNOCK,
                    MotionLabel.Component.JITTER), MotionLabel.Component.KNOCK);
            fail("a knock cannot be the dominant component");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("D13"));
        }
        try {
            MotionLabel.of(EnumSet.of(MotionLabel.Component.JITTER),
                    MotionLabel.Component.DRIFT);
            fail("a component that was not found cannot dominate");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not one of"));
        }
        assertNull(MotionLabel.of(MotionLabel.Component.KNOCK).dominant());
    }

    /** Severity comes from the larger of the drift and the largest step. */
    @Test
    public void severityBandsFollowTheLargerOfDriftAndTheBiggestStep() {
        assertEquals(MotionDescriptors.Severity.MILD, severityOfRamp(1.0));
        assertEquals(MotionDescriptors.Severity.MODERATE, severityOfRamp(5.0));
        assertEquals(MotionDescriptors.Severity.SEVERE, severityOfRamp(20.0));
        assertEquals(MotionDescriptors.Severity.EXTREME, severityOfRamp(100.0));
    }

    // -------------------------------------------------- refusals and empties

    /** A recording nothing could be read from is said so, not described as still. */
    @Test
    public void aRecordingNothingCouldBeReadFromIsNotDescribedAsStill() {
        WindowSampler.Plan plan = WindowSampler.measured().plan(48);
        List<Estimator.Displacement> refused =
                new ArrayList<Estimator.Displacement>(plan.measuredPairs());
        for (int i = 0; i < plan.measuredPairs(); i++) {
            refused.add(Estimator.Displacement.identity(
                    Estimator.Status.NO_STRUCTURE, Frames.Bin.none()));
        }
        MotionDescriptors d = MotionDescriptors.of(plan, refused);

        assertFalse(d.measured());
        assertEquals(0, d.measuredSteps());
        assertEquals(plan.measuredPairs(), d.refusedPairs());
        assertEquals(MotionLabel.NOT_MEASURED, d.label().render());
        assertFalse(d.label().measured());
        assertEquals(MotionDescriptors.Severity.NOT_MEASURED, d.severity());
        assertTrue(Double.isNaN(d.driftRatePx()));
        assertTrue(Double.isNaN(d.wander()));
        assertTrue(Double.isNaN(d.stepRmsPx()));
        assertFalse(d.knockPresent());
        assertTrue(d.provenance(), d.provenance().contains("could be read"));
    }

    /** One refused pair is left out of the statistics rather than counted as a still step. */
    @Test
    public void aRefusedPairIsLeftOutRatherThanCountedAsZero() {
        WindowSampler.Plan plan = WindowSampler.everyConsecutivePair().plan(12);
        List<Estimator.Displacement> steps = trace(plan, ramp(12, 0, 0));
        for (int i = 0; i < steps.size(); i++) {
            steps.set(i, Estimator.Displacement.of(1.0, 0, Estimator.Status.OK,
                    Frames.Bin.none()));
        }
        steps.set(4, Estimator.Displacement.identity(Estimator.Status.NO_STRUCTURE,
                Frames.Bin.none()));

        MotionDescriptors d = MotionDescriptors.of(plan, steps);
        assertEquals(1, d.refusedPairs());
        assertEquals(steps.size() - 1, d.measuredSteps());
        assertTrue(d.measured());
    }

    /** Displacements measured at two scales are not describable together. */
    @Test
    public void twoScalesCannotBeDescribedTogether() {
        WindowSampler.Plan plan = WindowSampler.everyConsecutivePair().plan(4);
        List<Estimator.Displacement> steps = new ArrayList<Estimator.Displacement>();
        steps.add(Estimator.Displacement.of(1, 0, Estimator.Status.OK, Frames.Bin.none()));
        steps.add(Estimator.Displacement.of(1, 0, Estimator.Status.OK, Frames.Bin.factor(2)));
        steps.add(Estimator.Displacement.of(1, 0, Estimator.Status.OK, Frames.Bin.none()));
        try {
            MotionDescriptors.of(plan, steps);
            fail("two pixel sizes in one description");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("D12"));
        }
    }

    /** The list has to match the plan it claims to describe. */
    @Test
    public void aTraceThatDoesNotMatchThePlanIsRefused() {
        WindowSampler.Plan plan = WindowSampler.measured().plan(48);
        try {
            MotionDescriptors.of(plan, trace(WindowSampler.measured().plan(60),
                    ramp(60, 0.1, 0)).subList(0, 34));
            fail("34 displacements for a 35 pair plan");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("35"));
        }
    }

    // ---------------------------------------------------------- the fixtures

    private static MotionDescriptors describe(WindowSampler.Plan plan, double[][] positions) {
        return MotionDescriptors.of(plan, trace(plan, positions));
    }

    /**
     * The displacements a perfect estimator would report for a recording whose
     * frames sit at these positions. A bridge is the movement across the whole
     * gap, which is what an estimator measuring those two frames would see.
     */
    private static List<Estimator.Displacement> trace(WindowSampler.Plan plan,
                                                      double[][] positions) {
        int[][] pairs = plan.pairs();
        List<Estimator.Displacement> out =
                new ArrayList<Estimator.Displacement>(pairs.length);
        for (int i = 0; i < pairs.length; i++) {
            out.add(Estimator.Displacement.of(
                    positions[pairs[i][1]][0] - positions[pairs[i][0]][0],
                    positions[pairs[i][1]][1] - positions[pairs[i][0]][1],
                    Estimator.Status.OK, Frames.Bin.none()));
        }
        return out;
    }

    private static double[][] ramp(int frames, double perFrameX, double perFrameY) {
        double[][] out = new double[frames][2];
        for (int t = 0; t < frames; t++) {
            out[t][0] = perFrameX * t;
            out[t][1] = perFrameY * t;
        }
        return out;
    }

    /** Positions independent of one another: the field shakes about a fixed point. */
    private static double[][] whiteJitter(int frames, double sd, long seed) {
        Random rng = new Random(seed);
        double[][] out = new double[frames][2];
        for (int t = 0; t < frames; t++) {
            out[t][0] = sd * rng.nextGaussian();
            out[t][1] = sd * rng.nextGaussian();
        }
        return out;
    }

    /** Each position taken from where the last one left off: the excursion accumulates. */
    private static double[][] randomWalk(int frames, double sd, long seed) {
        Random rng = new Random(seed);
        double[][] out = new double[frames][2];
        for (int t = 1; t < frames; t++) {
            out[t][0] = out[t - 1][0] + sd * rng.nextGaussian();
            out[t][1] = out[t - 1][1] + sd * rng.nextGaussian();
        }
        return out;
    }

    private static double[][] sinusoid(int frames, double amplitude, double period) {
        double[][] out = new double[frames][2];
        for (int t = 0; t < frames; t++) {
            out[t][0] = amplitude * Math.sin(2 * Math.PI * t / period);
            out[t][1] = amplitude * Math.cos(2 * Math.PI * t / period);
        }
        return out;
    }

    private static MotionDescriptors.Severity severityOfRamp(double totalPx) {
        return describe(WindowSampler.everyConsecutivePair().plan(48),
                ramp(48, totalPx / 47.0, 0)).severity();
    }

    private static void assertSameBits(String what, double expected, double actual) {
        assertEquals(what + " changed when only a gap did: " + expected + " against " + actual,
                Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }
}
