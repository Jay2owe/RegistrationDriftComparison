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
import regdrift.Mode;
import regdrift.RegDrift;
import regdrift.RegDriftParameters;
import regdrift.RegDriftResult;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * T18 - what a fingerprint costs, reported and never asserted.
 *
 * <p>The claim under test is the whole reason the sampler exists: <b>the cost of a
 * fingerprint does not grow with the length of the recording</b>, because three
 * windows of twelve frames and two bridges are 35 frame pairs whether the
 * recording is 48 frames long or 500. On a 48-frame recording that saves 26% of
 * the pairs; on a 500-frame recording it would save 93%, and until this fixture
 * ran that second figure was an extrapolation from one 108-frame entry.
 *
 * <p><b>Nothing here asserts a speed.</b> A build machine's timings are not
 * evidence about anybody's laptop, and a test that failed when a shared runner was
 * busy would be measuring the runner. What is asserted is only that the runs
 * finished and produced a fingerprint; the seconds are printed, and
 * {@code VALIDATION.md} carries the machine they were measured on.
 *
 * <p>Process CPU time, not wall-clock, because the measurement is parallel and
 * defect D6 says every timing this plugin reports is processor time. Wall-clock is
 * printed beside it so the two can be told apart, and because the difference is
 * what a worker count buys.
 */
public class BenchmarkFixture {

    /** A run whose figures are being reported. */
    private static final class Timing {

        final String what;
        final double cpuSeconds;
        final double wallSeconds;
        final int frames;
        final int side;

        Timing(String what, double cpuSeconds, double wallSeconds, int frames, int side) {
            this.what = what;
            this.cpuSeconds = cpuSeconds;
            this.wallSeconds = wallSeconds;
            this.frames = frames;
            this.side = side;
        }
    }

    /** Process CPU nanoseconds, or -1 where this JVM will not say. */
    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime();
        }
        return -1L;
    }

    /**
     * One whole {@code RegDrift.run} in diagnose mode, timed on the processor clock.
     *
     * <p>Run twice and the second one reported: the first pass is the JIT compiling
     * the estimators, and reporting that would be reporting the compiler.
     */
    private static Timing timed(String what, ImagePlus imp, int side) {
        RegDriftParameters parameters = RegDriftParameters.builder(imp)
                .mode(Mode.DIAGNOSE).hideDisplay(true).build();
        RegDriftResult warm = RegDrift.run(parameters);
        assertTrue(what + " did not finish: "
                        + (warm.failure() == null ? "" : warm.failure().message()),
                warm.isSuccess());

        long cpuBefore = processCpuNanos();
        long wallBefore = System.nanoTime();
        RegDriftResult result = RegDrift.run(parameters);
        long wallNanos = System.nanoTime() - wallBefore;
        long cpuNanos = processCpuNanos() - cpuBefore;
        assertTrue(what + " did not finish on the measured pass", result.isSuccess());

        return new Timing(what, cpuNanos < 0 ? Double.NaN : cpuNanos / 1e9, wallNanos / 1e9,
                RegDrift.frameCount(imp), side);
    }

    private static void report(List<Timing> timings) {
        System.out.println();
        System.out.println("T18 - what a fingerprint costs. REPORTED, never asserted: a build"
                + " machine's timings are not evidence about a user's machine.");
        System.out.printf(Locale.US, "%-46s %7s %7s %10s %10s%n",
                "recording", "frames", "side", "CPU s", "wall s");
        for (Timing timing : timings) {
            System.out.printf(Locale.US, "%-46s %7d %7d %10.2f %10.2f%n",
                    timing.what, timing.frames, timing.side, timing.cpuSeconds,
                    timing.wallSeconds);
        }
        System.out.println();
    }

    /**
     * The two sizes the test plan names, plus the real recording that anchors the
     * first of them to something that came out of a microscope.
     */
    @Test
    public void whatAFingerprintCosts() {
        Assume.assumeTrue(Fixtures.needsHeap(3000), Fixtures.heapMb() >= 3000);
        List<Timing> timings = new ArrayList<Timing>();

        if (Fixtures.library() != null) {
            ImagePlus real = Fixtures.entry("08_knock_severe").open();
            try {
                timings.add(timed("08_knock_severe, real, 3 channels", real, real.getWidth()));
            } finally {
                real.close();
            }
        } else {
            System.out.println("the real 768x768 recording was skipped: "
                    + Fixtures.whyItWasSkipped());
        }

        ImagePlus small = Fixtures.syntheticTrace(48, 768, 0.4, 0.6, 0.0,
                new int[]{20}, new double[]{9.0}, 5L);
        try {
            timings.add(timed("synthetic, T=48, 768 squared", small, 768));
        } finally {
            small.close();
        }

        ImagePlus large = Fixtures.syntheticTrace(500, 1024, 0.4, 0.6, 0.0,
                new int[]{120, 300}, new double[]{9.0, 14.0}, 5L);
        try {
            timings.add(timed("synthetic, T=500, 1024 squared", large, 1024));
        } finally {
            large.close();
        }

        report(timings);

        Timing shortOne = null;
        Timing longOne = null;
        for (Timing timing : timings) {
            if (timing.frames == 48 && timing.side == 768) shortOne = timing;
            if (timing.frames == 500) longOne = timing;
        }
        if (shortOne != null && longOne != null) {
            System.out.printf(Locale.US, "ten times the frames and 1.8 times the pixels per frame:"
                            + " %.2f s CPU against %.2f s. Scaled to the same frame size that is"
                            + " %.2f s against %.2f s, which is what 'constant in the length of"
                            + " the recording' means and what a build machine can say about it.%n%n",
                    shortOne.cpuSeconds, longOne.cpuSeconds,
                    shortOne.cpuSeconds, longOne.cpuSeconds * (768.0 * 768.0) / (1024.0 * 1024.0));
        }

        // The only thing asserted, and deliberately: that both sizes produced a
        // measurement at all. CI never asserts a wall-clock speedup.
        assertTrue("no timing was produced", timings.size() >= 2);
    }
}
