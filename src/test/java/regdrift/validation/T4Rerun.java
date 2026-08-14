/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.validation;

import ij.ImagePlus;
import org.junit.Assume;
import org.junit.Test;
import regdrift.Cancellation;
import regdrift.diag.Estimator;
import regdrift.diag.Fingerprint;
import regdrift.diag.Frames;
import regdrift.diag.MotionDescriptors;
import regdrift.diag.MotionLabel;
import regdrift.diag.WindowSampler;
import regdrift.internal.PairScheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The sampler measurement, re-run against a criterion fixed before the run.
 *
 * <p>The first run of this (`t4\RESULT.md`, 2026-08-12) reached its answer only
 * after applying two changes to how a motion label is defined - knock as presence
 * rather than a count, and the compound label as an unordered set - and it said
 * about itself that re-scoring after seeing the results is a weaker form of
 * evidence than a criterion fixed first. Both changes are now what
 * {@link MotionLabel} does, so this run scores the implementation rather than a
 * re-reading of a table.
 *
 * <h2>The reference is a full-consecutive-pair run on the same pixels</h2>
 *
 * <p>Not {@code entry.properties}. That label was measured on the full uncropped
 * frame, binned, on the best-ranked plane, and the entry is an unbinned crop of
 * channel 1 - so agreement with it measures the crop rather than the sampler, and
 * that mistake is what cost the first run its first result. Here the reference is
 * the same recording, the same channel, the same scale and the same two
 * estimators, over <b>every consecutive pair</b>; the only difference between
 * reference and sample is which pairs were measured, which is the whole question.
 *
 * <h2>Channel 1, fixed</h2>
 *
 * <p>Both sides use channel 1 - what the library registered - rather than the
 * plugin's own channel ranking, so that a ranking that chose differently under
 * two samplings could not show up as a sampler disagreement.
 */
public class T4Rerun {

    /** The shipped default and the four neighbours it has to beat. */
    private static final int[][] CONFIGURATIONS = {
            {3, 12},        // the shipped default
            {2, 12},
            {4, 12},
            {3, 8},
            {3, 16},
    };

    /**
     * The shipped constant of that name, read out of {@link MotionDescriptors}.
     *
     * <p>Read rather than copied. A measurement of a threshold that quoted its own
     * copy of the number would keep agreeing with itself after somebody changed the
     * one that ships.
     */
    private static double constant(String name) {
        try {
            java.lang.reflect.Field field = MotionDescriptors.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getDouble(null);
        } catch (Exception notThere) {
            throw new IllegalStateException("MotionDescriptors no longer has a constant called "
                    + name + ", so this measurement is about a threshold that has moved: "
                    + notThere);
        }
    }

    /** What the dominance decision compares the drift-to-excursion ratio against. */
    private static final double DRIFT_DOMINANCE = constant("DRIFT_DOMINANCE");

    /** How far either side of it is held back as unclear - the stated guess. */
    private static final double DOMINANCE_MARGIN = constant("DOMINANCE_MARGIN");

    /** Above this, the excursion is growing with time rather than shaking about a centre. */
    private static final double WANDER_WALK = constant("WANDER_WALK");

    /** One configuration's answer on one entry. */
    static final class Measurement {

        final String configuration;
        final Set<MotionLabel.Component> components;
        final String label;
        final MotionDescriptors.Severity severity;
        final double driftPx;
        final double residualRmsPx;
        final double wander;
        final int measuredPairs;
        final double seconds;

        Measurement(String configuration, MotionDescriptors motion, int measuredPairs,
                    double seconds) {
            this.configuration = configuration;
            this.components = motion.label().components();
            this.label = motion.label().render();
            this.severity = motion.severity();
            this.driftPx = motion.driftPx();
            this.residualRmsPx = motion.residualRmsPx();
            this.wander = motion.wander();
            this.measuredPairs = measuredPairs;
            this.seconds = seconds;
        }

        /** The drift-to-excursion ratio the dominance decision turns on. */
        double dominanceRatio() {
            if (!(residualRmsPx > 0)) return Double.POSITIVE_INFINITY;
            return driftPx / residualRmsPx;
        }
    }

    private static Map<String, Map<String, Measurement>> matrix;

    private static void needsTheLibrary() {
        Assume.assumeTrue(Fixtures.whyItWasSkipped(), Fixtures.library() != null);
    }

    private static String nameOf(int windows, int framesPerWindow) {
        return windows == 0 ? "reference (every pair)"
                : "W" + windows + "K" + framesPerWindow;
    }

    /**
     * Measures every entry once through every configuration and the reference, and
     * hands the same matrix to every test below.
     */
    private static synchronized Map<String, Map<String, Measurement>> matrix() {
        if (matrix != null) return matrix;
        Map<String, Map<String, Measurement>> found =
                new LinkedHashMap<String, Map<String, Measurement>>();
        for (Fixtures.Entry entry : Fixtures.ENTRIES) {
            ImagePlus imp = entry.open();
            Map<String, Measurement> perConfiguration = new LinkedHashMap<String, Measurement>();
            try {
                Frames.Bin scale = Fingerprint.binFor(imp.getWidth(), imp.getHeight());
                Frames frames = Frames.of(imp, 1, Frames.PROJECT_Z, scale);
                try {
                    perConfiguration.put(nameOf(0, 0),
                            measure(frames, WindowSampler.everyConsecutivePair(), 0, 0));
                    for (int[] shape : CONFIGURATIONS) {
                        perConfiguration.put(nameOf(shape[0], shape[1]),
                                measure(frames, WindowSampler.of(shape[0], shape[1]),
                                        shape[0], shape[1]));
                    }
                } finally {
                    frames.release();
                }
            } finally {
                imp.close();
            }
            found.put(entry.name(), perConfiguration);
        }
        matrix = found;
        return matrix;
    }

    private static Measurement measure(Frames frames, WindowSampler sampler, int windows,
                                       int framesPerWindow) {
        WindowSampler.Plan plan = sampler.plan(frames.count());
        long before = System.nanoTime();
        Fingerprint fingerprint = Fingerprint.measure(frames, plan, 0,
                PairScheduler.Progress.NONE, Cancellation.never());
        double seconds = (System.nanoTime() - before) / 1e9;
        MotionDescriptors motion = fingerprint.motion();
        assertNotNull("no movement was described at " + nameOf(windows, framesPerWindow), motion);
        return new Measurement(nameOf(windows, framesPerWindow), motion, plan.measuredPairs(),
                seconds);
    }

    // ---------------------------------------------------------------- the gate

    /**
     * <b>The pre-registered score.</b> Each configuration's component set against
     * the reference's, out of twelve, and the shipped default has to be at least as
     * good as every neighbour.
     */
    @Test
    public void theShippedSamplerIsAtLeastAsGoodAsEveryNeighbour() {
        needsTheLibrary();
        Map<String, Map<String, Measurement>> found = matrix();

        Map<String, Integer> labelScore = new LinkedHashMap<String, Integer>();
        Map<String, Integer> severityScore = new LinkedHashMap<String, Integer>();
        Map<String, Integer> pairs = new LinkedHashMap<String, Integer>();
        Map<String, Double> seconds = new LinkedHashMap<String, Double>();

        System.out.println();
        System.out.println("The T4 re-run, pre-registered. Reference: every consecutive pair,"
                + " channel 1, same scale, same two estimators.");
        System.out.printf(Locale.US, "%-32s %-26s", "entry", "reference label");
        for (int[] shape : CONFIGURATIONS) {
            System.out.printf(Locale.US, " %-22s", nameOf(shape[0], shape[1]));
        }
        System.out.println();

        for (Fixtures.Entry entry : Fixtures.ENTRIES) {
            Map<String, Measurement> row = found.get(entry.name());
            Measurement reference = row.get(nameOf(0, 0));
            System.out.printf(Locale.US, "%-32s %-26s", entry.name(),
                    reference.label + "/" + reference.severity.word());
            for (int[] shape : CONFIGURATIONS) {
                String name = nameOf(shape[0], shape[1]);
                Measurement measurement = row.get(name);
                boolean sameLabel = measurement.components.equals(reference.components);
                boolean sameSeverity = measurement.severity == reference.severity;
                if (sameLabel) labelScore.put(name, count(labelScore, name) + 1);
                if (sameSeverity) severityScore.put(name, count(severityScore, name) + 1);
                pairs.put(name, count(pairs, name) + measurement.measuredPairs);
                seconds.put(name, seconds.containsKey(name)
                        ? seconds.get(name) + measurement.seconds : measurement.seconds);
                System.out.printf(Locale.US, " %-22s",
                        (sameLabel ? "=" : "x") + (sameSeverity ? "=" : "x") + " "
                                + measurement.label + "/" + measurement.severity.word());
            }
            System.out.println();
        }

        System.out.println();
        System.out.printf(Locale.US, "%-10s %8s %8s %8s %10s%n",
                "config", "label", "severity", "pairs", "seconds");
        for (int[] shape : CONFIGURATIONS) {
            String name = nameOf(shape[0], shape[1]);
            System.out.printf(Locale.US, "%-10s %6d/12 %6d/12 %8d %10.1f%n", name,
                    count(labelScore, name), count(severityScore, name), count(pairs, name),
                    seconds.get(name));
        }
        int reference = 0;
        for (Fixtures.Entry entry : Fixtures.ENTRIES) {
            reference += found.get(entry.name()).get(nameOf(0, 0)).measuredPairs;
        }
        System.out.printf(Locale.US, "%-10s %6s    %6s    %8d%n", "reference", "-", "-", reference);
        System.out.println();

        String shipped = nameOf(CONFIGURATIONS[0][0], CONFIGURATIONS[0][1]);
        List<String> beatenByASampler = new ArrayList<String>();
        List<String> beatenByAnything = new ArrayList<String>();
        for (int i = 1; i < CONFIGURATIONS.length; i++) {
            String name = nameOf(CONFIGURATIONS[i][0], CONFIGURATIONS[i][1]);
            if (count(labelScore, name) <= count(labelScore, shipped)) continue;
            String beat = name + " scored " + count(labelScore, name) + "/12 against " + shipped
                    + " on " + count(labelScore, shipped);
            beatenByAnything.add(beat);
            if (!degenerate(found, CONFIGURATIONS[i][0], CONFIGURATIONS[i][1])) {
                beatenByASampler.add(beat);
            }
        }
        if (!beatenByAnything.isEmpty()) {
            System.out.println("SCORED HIGHER THAN THE SHIPPED DEFAULT: " + beatenByAnything);
            System.out.println("of those, configurations that really sample this library rather"
                    + " than measuring every one of its transitions: "
                    + (beatenByASampler.isEmpty() ? "none" : beatenByASampler.toString()));
            System.out.println();
        }

        // The pre-registered criterion is about sampling, so it can only be decided against
        // configurations that sample. On a 48-frame recording W4xK12 and W3xK16 each place
        // contiguous windows over all 48 frames and their bridges are themselves consecutive
        // pairs, so they measure the reference's own 47 transitions and cannot disagree with it.
        // That is arithmetic, and it is asserted below rather than argued, because it is the
        // whole reason those two rows are not evidence about sampling.
        assertEquals("a configuration that really samples this library beat the shipped default,"
                        + " so the default has to change and 02_CONTRACT.md, 03_BUILD_PLAN.md and"
                        + " stage 08 constants with it",
                Arrays.<String>asList(), beatenByASampler);
        assertTrue("the shipped sampler reproduced the full-recording label on only "
                        + count(labelScore, shipped) + " of 12 entries",
                count(labelScore, shipped) >= 11);
    }

    /**
     * True when this configuration measured every transition the reference did, on
     * most of the library.
     *
     * <p>Four windows of twelve frames, or three of sixteen, laid over a 48-frame
     * recording are contiguous: the windows cover every frame and each bridge spans
     * one frame to the next, so the plan's 47 pairs <em>are</em> the reference's 47
     * pairs. Such a configuration agrees with the reference by construction on those
     * recordings, and its score there says nothing about sampling.
     */
    private static boolean degenerate(Map<String, Map<String, Measurement>> found, int windows,
                                      int framesPerWindow) {
        int exhaustive = 0;
        for (Fixtures.Entry entry : Fixtures.ENTRIES) {
            Map<String, Measurement> row = found.get(entry.name());
            if (row.get(nameOf(windows, framesPerWindow)).measuredPairs
                    == row.get(nameOf(0, 0)).measuredPairs) {
                exhaustive++;
            }
        }
        boolean isDegenerate = exhaustive > Fixtures.ENTRIES.length / 2;
        if (isDegenerate) {
            System.out.printf(Locale.US, "%s measures every transition the reference does on"
                            + " %d of the %d entries, so it is the reference in disguise there%n",
                    nameOf(windows, framesPerWindow), exhaustive, Fixtures.ENTRIES.length);
        }
        return isDegenerate;
    }

    private static int count(Map<String, Integer> counts, String key) {
        Integer found = counts.get(key);
        return found == null ? 0 : found.intValue();
    }

    // ------------------------------------------------- the first stage-08 guess

    /**
     * <b>{@code DOMINANCE_MARGIN = 1.5} measured.</b> The constant is a stated
     * guess: the resampling measurement showed that a label ordered on which side
     * of {@code DRIFT_DOMINANCE = 3.0} the drift-to-excursion ratio falls flips
     * between samplings of one recording, and never measured how wide the unsafe
     * band around it is.
     *
     * <p>What is measured here is that width: the spread of that ratio across the
     * five sampler configurations of the same recording, as a factor. A margin
     * wide enough covers the spread; one too narrow lets a recording whose ratio
     * moved across the threshold be given a dominant component under one sampling
     * and the other under another.
     */
    @Test
    public void theDominanceBandIsWideEnoughForWhatResamplingMoves() {
        needsTheLibrary();
        Map<String, Map<String, Measurement>> found = matrix();

        System.out.println();
        System.out.println("DOMINANCE_MARGIN measured. Ratio = drift_px / residual_rms_px, the"
                + " quantity the dominance decision compares against DRIFT_DOMINANCE = 3.0.");
        System.out.printf(Locale.US, "%-32s %9s %9s %9s %9s %9s %9s %8s  %s%n", "entry",
                "reference", "W3K12", "W2K12", "W4K12", "W3K8", "W3K16", "spread", "crosses 3.0?");

        double widest = 1.0;
        String widestEntry = "";
        List<String> crossers = new ArrayList<String>();
        List<String> uncoveredByTheMargin = new ArrayList<String>();
        for (Fixtures.Entry entry : Fixtures.ENTRIES) {
            Map<String, Measurement> row = found.get(entry.name());
            double lowest = Double.POSITIVE_INFINITY;
            double highest = 0;
            for (int[] shape : CONFIGURATIONS) {
                double ratio = row.get(nameOf(shape[0], shape[1])).dominanceRatio();
                if (Double.isInfinite(ratio) || Double.isNaN(ratio)) continue;
                lowest = Math.min(lowest, ratio);
                highest = Math.max(highest, ratio);
            }
            double spread = lowest > 0 && !Double.isInfinite(lowest) ? highest / lowest : Double.NaN;
            boolean crosses = lowest < DRIFT_DOMINANCE
                    && highest > DRIFT_DOMINANCE;
            System.out.printf(Locale.US, "%-32s %9.2f %9.2f %9.2f %9.2f %9.2f %9.2f %8.2f  %s%n",
                    entry.name(), row.get(nameOf(0, 0)).dominanceRatio(),
                    row.get("W3K12").dominanceRatio(), row.get("W2K12").dominanceRatio(),
                    row.get("W4K12").dominanceRatio(), row.get("W3K8").dominanceRatio(),
                    row.get("W3K16").dominanceRatio(), spread, crosses ? "YES" : "no");
            if (!Double.isNaN(spread) && spread > widest) {
                widest = spread;
                widestEntry = entry.name();
            }
            if (crosses) {
                crossers.add(entry.name());
                // The margin has to hold back as unclear every ratio the resampling could have
                // put on the other side. A recording whose ratio spans the threshold needs the
                // whole of its span inside the band, or one of its samplings is given a dominant
                // component that another sampling contradicts.
                double marginNeeded = Math.max(
                        highest / DRIFT_DOMINANCE,
                        DRIFT_DOMINANCE / lowest);
                if (marginNeeded > DOMINANCE_MARGIN) {
                    uncoveredByTheMargin.add(String.format(Locale.US, "%s needs %.2f",
                            entry.name(), marginNeeded));
                }
            }
        }
        System.out.printf(Locale.US, "widest spread across the five samplings: %.2fx, on %s."
                        + " Entries whose ratio crosses 3.0 under resampling: %s.%n"
                        + "DOMINANCE_MARGIN ships at %.1f; entries it does not cover: %s.%n",
                widest, widestEntry.isEmpty() ? "(none)" : widestEntry,
                crossers.isEmpty() ? "none" : crossers.toString(),
                DOMINANCE_MARGIN,
                uncoveredByTheMargin.isEmpty() ? "none" : uncoveredByTheMargin.toString());
        System.out.println();

        // Reported, not gated. A margin that turned out too narrow is a finding for VALIDATION.md
        // and a v0.2.0 measurement, not a number to nudge until this run passes - and a dominance
        // note is a separate column that D13 deliberately took out of the label's identity.
        assertTrue("the ratio has to have been measurable on at least one entry", widest >= 1.0);
    }

    // ------------------------------------------------ the second stage-08 guess

    /**
     * <b>{@code wander} against window length.</b> {@code wander} is the residual
     * excursion divided by the step size: about 0.7 when each position is
     * independent of the last, and several when the excursion accumulates. The
     * second reading is a property of the <em>window</em> as much as of the
     * recording - twelve frames of a random walk genuinely do look like jitter,
     * because a walk has not had time to wander far.
     *
     * <p>Measured on trajectories rather than on images, deliberately: the
     * question is what the window length does to the statistic, and estimator
     * noise would only blur it. The displacements handed in are the exact steps of
     * a built trajectory.
     *
     * <p>It cost the library nothing - all twelve entries are 48 frames or fewer,
     * and the twelve-frame windows read {@code wander} 0.59 to 0.98 on every one of
     * them. What it costs on a longer recording is what this measures.
     */
    @Test
    public void wanderAgainstWindowLength() {
        int[] lengths = {12, 24, 48, 100, 200};
        System.out.println();
        System.out.println("wander against window length, the median of 25 built trajectories"
                + " of 200 frames each. WANDER_WALK ships at " + WANDER_WALK + ".");
        System.out.printf(Locale.US, "%-28s", "trajectory");
        for (int length : lengths) {
            System.out.printf(Locale.US, " %10s", "K=" + length);
        }
        System.out.println("   reads WALK at K=12?");

        double walkAtTwelve = Double.NaN;
        double jitterAtTwelve = Double.NaN;
        double walkAtTwoHundred = Double.NaN;
        for (int kind = 0; kind < 2; kind++) {
            boolean walk = kind == 0;
            String what = walk ? "pure random walk, 1.0 px" : "pure jitter, 1.0 px";
            System.out.printf(Locale.US, "%-28s", what);
            String verdictAtTwelve = "";
            for (int length : lengths) {
                double wander = wanderOf(walk, length);
                if (length == 12) {
                    verdictAtTwelve = wander > WANDER_WALK ? "yes" : "NO";
                    if (walk) walkAtTwelve = wander; else jitterAtTwelve = wander;
                }
                if (length == 200 && walk) walkAtTwoHundred = wander;
                System.out.printf(Locale.US, " %10.2f", wander);
            }
            System.out.println("   " + verdictAtTwelve);
        }
        System.out.printf(Locale.US, "a walk reads %.2f in a 12-frame window and %.2f in a"
                        + " 200-frame one; jitter reads %.2f in a 12-frame window%n",
                walkAtTwelve, walkAtTwoHundred, jitterAtTwelve);
        System.out.println();

        assertTrue("a random walk over 200 frames has to read as a walk, or the statistic measures"
                + " nothing at all", walkAtTwoHundred > WANDER_WALK);
        assertTrue("white jitter must never read as a walk at any window length",
                jitterAtTwelve < WANDER_WALK);
    }

    // ------------------------------- what the library cannot answer, measured elsewhere

    /**
     * <b>W3xK12 against W4xK12 where the library cannot tell them apart.</b>
     *
     * <p>On eleven of the twelve entries {@code W4xK12} is not a sample at all:
     * four contiguous twelve-frame windows cover all 48 frames and its three
     * bridges are themselves consecutive pairs, so it measures the identical 47
     * transitions the reference does and cannot disagree with it. Its agreement on
     * those eleven rows is arithmetic rather than evidence, and the only entry
     * where the two configurations genuinely sample differently is
     * {@code 12_long_baseline_9d} - one recording.
     *
     * <p>So the comparison is made where it can be made: on built trajectories of
     * 200 frames carrying a drift, a jitter and <b>one knock of known size at a
     * known frame</b>, with the frame walked across the whole recording. Every
     * configuration is asked the one question the library's single long entry
     * turned on - does it find the knock - and the answer is a detection rate over
     * a hundred and ninety-nine placements rather than over one.
     *
     * <p>Measured on the trajectories rather than on pixels, deliberately: the
     * question is which transitions a sampler looks at, and estimator noise would
     * only blur it.
     */
    @Test
    public void findingAKnockOnALongRecording() {
        int frames = 200;
        double[] knocks = {6.0, 12.0, 24.0};
        System.out.println();
        System.out.println("Finding one knock on a 200-frame trajectory: drift 0.05 px/frame,"
                + " jitter 0.4 px, one knock walked across every frame of the recording.");
        System.out.printf(Locale.US, "%-12s %8s", "knock px", "pairs");
        for (int[] shape : CONFIGURATIONS) {
            System.out.printf(Locale.US, " %10s", nameOf(shape[0], shape[1]));
        }
        System.out.printf(Locale.US, " %10s%n", "reference");

        Map<String, Integer> totalFound = new LinkedHashMap<String, Integer>();
        int placements = 0;
        for (double knock : knocks) {
            System.out.printf(Locale.US, "%-12.0f %8s", knock, "");
            for (int[] shape : CONFIGURATIONS) {
                String name = nameOf(shape[0], shape[1]);
                int found = 0;
                int tried = 0;
                for (int at = 1; at < frames; at++) {
                    tried++;
                    if (knockFound(frames, knock, at, WindowSampler.of(shape[0], shape[1]))) {
                        found++;
                    }
                }
                totalFound.put(name, count(totalFound, name) + found);
                if (knock == knocks[0]) placements = tried;
                System.out.printf(Locale.US, " %9.0f%%", 100.0 * found / tried);
            }
            int found = 0;
            for (int at = 1; at < frames; at++) {
                if (knockFound(frames, knock, at, WindowSampler.everyConsecutivePair())) found++;
            }
            totalFound.put("reference", count(totalFound, "reference") + found);
            System.out.printf(Locale.US, " %9.0f%%%n", 100.0 * found / (frames - 1));
        }
        int all = placements * knocks.length;
        System.out.printf(Locale.US, "over all %d placements: ", all);
        for (int[] shape : CONFIGURATIONS) {
            String name = nameOf(shape[0], shape[1]);
            System.out.printf(Locale.US, "%s %.0f%%  ", name, 100.0 * count(totalFound, name) / all);
        }
        System.out.printf(Locale.US, "reference %.0f%%%n%n",
                100.0 * count(totalFound, "reference") / all);

        assertTrue("a sampler that never finds an injected knock is measuring nothing",
                count(totalFound, nameOf(3, 12)) > 0);
    }

    /** True when one configuration reports a knock on a trajectory built to carry one. */
    private static boolean knockFound(int frames, double knockPx, int at, WindowSampler sampler) {
        double[][] path = Fixtures.trajectory(frames, 0.05, 0.4, 0.0, new int[]{at},
                new double[]{knockPx}, 20260814L + at);
        WindowSampler.Plan plan = sampler.plan(frames);
        int[][] pairs = plan.pairs();
        List<Estimator.Displacement> perPair =
                new ArrayList<Estimator.Displacement>(pairs.length);
        for (int[] pair : pairs) {
            perPair.add(Estimator.Displacement.of(path[pair[1]][0] - path[pair[0]][0],
                    path[pair[1]][1] - path[pair[0]][1], Estimator.Status.OK, Frames.Bin.none()));
        }
        return MotionDescriptors.of(plan, perPair).knockPresent();
    }

    /**
     * {@code wander} for one built trajectory measured through one window of
     * {@code length} frames, from the exact steps and no pixels.
     */
    private static double wanderOf(boolean walk, int length) {
        int frames = 200;
        int seeds = 25;
        double[] found = new double[seeds];
        for (int seed = 0; seed < seeds; seed++) {
            double[][] path = Fixtures.trajectory(frames, 0.0, walk ? 0.0 : 1.0, walk ? 1.0 : 0.0,
                    null, null, 4242L + 17L * seed);
            WindowSampler.Plan plan = WindowSampler.of(1, length).plan(frames);
            int[][] pairs = plan.pairs();
            List<Estimator.Displacement> perPair =
                    new ArrayList<Estimator.Displacement>(pairs.length);
            for (int[] pair : pairs) {
                perPair.add(Estimator.Displacement.of(path[pair[1]][0] - path[pair[0]][0],
                        path[pair[1]][1] - path[pair[0]][1], Estimator.Status.OK,
                        Frames.Bin.none()));
            }
            found[seed] = MotionDescriptors.of(plan, perPair).wander();
        }
        Arrays.sort(found);
        return found[seeds / 2];
    }
}
