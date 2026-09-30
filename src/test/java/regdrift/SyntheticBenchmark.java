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
import ij.VirtualStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineFixtures;
import regdrift.autofix.EngineId;
import regdrift.harness.EngineDescriptor;
import regdrift.harness.EngineRunner;
import regdrift.internal.Transform;
import regdrift.score.ControlWarp;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * How long each mode takes on synthetic recordings of a realistic size, and the
 * digest of every table it produced, so a speed change can be shown to leave
 * every number alone.
 *
 * <p>Skipped unless {@code -Dregdrift.bench=true}. Each case is run once to warm
 * up and then five times; the report gives the median wall and process CPU
 * seconds. {@code -Dregdrift.bench.label} names the report written to
 * {@code target/bench-<label>.txt}. The whole run stops starting new repeats
 * after {@code -Dregdrift.bench.minutes} (default 18) and marks what it did not
 * finish as DNF.
 *
 * <p>The 1024 x 1024 x 500 recording is a virtual stack that makes each frame
 * when asked, the way a recording larger than the heap reaches Fiji, so the
 * build's 512 MB test heap is enough. Every recording drifts by whole pixels,
 * which keeps making a frame cheap next to measuring it.
 */
public class SyntheticBenchmark {

    private static final int REPEATS = 5;

    private static final Set<EngineId> HERE = EnumSet.of(EngineId.TURBOREG, EngineId.STACKREG,
            EngineId.MULTISTACKREG, EngineId.IMAGE_STABILIZER);

    private RegDrift.Bench realBench;
    private Arms arms;

    @Before
    public void inventEngines() {
        realBench = RegDrift.bench;
        arms = new Arms();
        RegDrift.bench = bench(arms);
    }

    @After
    public void restore() {
        RegDrift.bench = realBench;
    }

    @Test
    public void timeEveryCase() throws Exception {
        Assume.assumeTrue("set -Dregdrift.bench=true to run the benchmark",
                Boolean.getBoolean("regdrift.bench"));
        String label = System.getProperty("regdrift.bench.label", "run");
        long deadline = System.nanoTime()
                + (long) (Double.parseDouble(System.getProperty("regdrift.bench.minutes", "18"))
                * 60e9);
        String only = System.getProperty("regdrift.bench.case", "");

        List<Case> cases = new ArrayList<Case>();
        cases.add(new Case("diagnose_recommend 768x768x48", Mode.DIAGNOSE_AND_RECOMMEND,
                Drifting.of(768, 48, 0.6, 0.35, false), null));
        cases.add(new Case("diagnose_recommend 1024x1024x500 virtual", Mode.DIAGNOSE_AND_RECOMMEND,
                Drifting.of(1024, 500, 0.5, 0.3, true), null));
        Drifting mid = Drifting.of(512, 48, 0.6, 0.35, false);
        cases.add(new Case("score 512x512x48", Mode.SCORE, mid, mid.still()));
        cases.add(new Case("compare 512x512x48 three invented arms", Mode.COMPARE, mid, null));

        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.ROOT, "# SyntheticBenchmark %s - java %s, %d processors,"
                        + " max heap %d MB, median of %d after 1 warm-up%n", label,
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() >> 20, REPEATS));
        report.append("case\twall_median_s\tcpu_median_s\twall_all_s\tcpu_all_s\tdigest\n");
        for (Case c : cases) {
            if (!only.isEmpty() && !c.name.startsWith(only)) continue;
            report.append(c.run(deadline)).append('\n');
            System.out.print(report);
        }
        File out = new File(System.getProperty("regdrift.bench.out",
                "target/bench-" + label + ".txt"));
        Files.write(out.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("benchmark report: " + out.getAbsolutePath());
    }

    // ---------------------------------------------------------------- cases

    private final class Case {
        final String name;
        final Mode mode;
        final Drifting recording;
        final Drifting partner;

        Case(String name, Mode mode, Drifting recording, Drifting partner) {
            this.name = name;
            this.mode = mode;
            this.recording = recording;
            this.partner = partner;
        }

        String run(long deadline) {
            String digest = null;
            List<Double> walls = new ArrayList<Double>();
            List<Double> cpus = new ArrayList<Double>();
            for (int i = 0; i <= REPEATS; i++) {
                if (System.nanoTime() > deadline) {
                    return name + "\tDNF\tDNF\t" + walls + "\t" + cpus + "\t"
                            + (digest == null ? "none" : digest);
                }
                arms.recording = recording;
                RegDriftParameters.Builder request = RegDriftParameters
                        .builder(recording.image("movie"))
                        .mode(mode)
                        .hideDisplay(true);
                if (partner != null) request.compareWith(partner.image("registered"));
                RegDriftParameters parameters = request.build();
                System.gc();
                long cpu0 = processCpuNanos();
                long wall0 = System.nanoTime();
                RegDriftResult result = RegDrift.run(parameters);
                double wall = (System.nanoTime() - wall0) / 1e9;
                double cpu = (processCpuNanos() - cpu0) / 1e9;
                String now = digestOf(result);
                if (digest != null && !digest.equals(now)) {
                    throw new AssertionError(name + ": two runs gave different tables");
                }
                digest = now;
                if (i > 0) {
                    walls.add(wall);
                    cpus.add(cpu);
                }
            }
            return String.format(Locale.ROOT, "%s\t%.2f\t%.2f\t%s\t%s\t%s", name, median(walls),
                    median(cpus), rounded(walls), rounded(cpus), digest);
        }
    }

    /** Every table, the verdict and the registered stack, without the timing columns. */
    static String digestOf(RegDriftResult result) {
        StringBuilder all = new StringBuilder();
        all.append(result.failure() == null ? "none"
                : result.failure().kind() + ": " + result.failure().message()).append('\n');
        all.append(GoldenOutputsTest.canon(result.diagnosis()));
        all.append(GoldenOutputsTest.canon(result.recommendation()));
        all.append(GoldenOutputsTest.canon(result.comparison()));
        all.append(GoldenOutputsTest.canon(result.frames()));
        all.append(result.verdict()).append('\n');
        all.append(result.registered() == null ? "none"
                : GoldenOutputsTest.pixels(result.registered()));
        return GoldenOutputsTest.sha(all.toString()).substring(0, 16);
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
        }
        return 0L;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n == 0) return Double.NaN;
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static String rounded(List<Double> values) {
        StringBuilder out = new StringBuilder("[");
        for (Double v : values) {
            if (out.length() > 1) out.append(' ');
            out.append(String.format(Locale.ROOT, "%.2f", v));
        }
        return out.append(']').toString();
    }

    // ------------------------------------------------------------ recordings

    /** A 16-bit recording that drifts by whole pixels across a fixed texture. */
    static final class Drifting {
        final int side;
        final int frames;
        final double stepX;
        final double stepY;
        final boolean virtual;
        final int pad;
        final short[] base;

        private Drifting(int side, int frames, double stepX, double stepY, boolean virtual,
                         int pad, short[] base) {
            this.side = side;
            this.frames = frames;
            this.stepX = stepX;
            this.stepY = stepY;
            this.virtual = virtual;
            this.pad = pad;
            this.base = base;
        }

        static Drifting of(int side, int frames, double stepX, double stepY, boolean virtual) {
            int pad = (int) Math.ceil(Math.max(Math.abs(stepX), Math.abs(stepY)) * frames) + 4;
            int field = side + pad;
            Random random = new Random(20261001L + side * 31L + frames);
            FloatProcessor texture = new FloatProcessor(field, field);
            float[] noise = (float[]) texture.getPixels();
            for (int i = 0; i < noise.length; i++) noise[i] = random.nextFloat();
            texture.blurGaussian(2.0);
            texture.resetMinAndMax();
            double low = texture.getMin();
            double span = texture.getMax() - low;
            short[] base = new short[noise.length];
            for (int i = 0; i < base.length; i++) {
                base[i] = (short) (1000 + Math.round(3000 * (noise[i] - low) / span));
            }
            return new Drifting(side, frames, stepX, stepY, virtual, pad, base);
        }

        /** The same texture with the movement taken out: what a perfect engine gives. */
        Drifting still() {
            return new Drifting(side, frames, 0, 0, virtual, pad, base);
        }

        int dx(int t) {
            return (int) Math.round(stepX * t);
        }

        int dy(int t) {
            return (int) Math.round(stepY * t);
        }

        ShortProcessor frame(int t) {
            ShortProcessor frame = new ShortProcessor(side, side);
            short[] out = (short[]) frame.getPixels();
            int field = side + pad;
            int ox = dx(t);
            int oy = dy(t);
            for (int y = 0; y < side; y++) {
                System.arraycopy(base, (y + oy) * field + ox, out, y * side, side);
            }
            return frame;
        }

        ImagePlus image(String title) {
            if (virtual) return new ImagePlus(title, new OnDemand(this));
            ImageStack stack = new ImageStack(side, side);
            for (int t = 0; t < frames; t++) stack.addSlice("t" + (t + 1), frame(t));
            return new ImagePlus(title, stack);
        }
    }

    /** A virtual stack whose frames are made when read. */
    static final class OnDemand extends VirtualStack {
        private final Drifting recording;

        OnDemand(Drifting recording) {
            super(recording.side, recording.side, null, null);
            this.recording = recording;
            setBitDepth(16);
        }

        @Override public int getSize() { return recording.frames; }
        @Override public ImageProcessor getProcessor(int n) { return recording.frame(n - 1); }
        @Override public Object getPixels(int n) { return recording.frame(n - 1).getPixels(); }
        @Override public void setPixels(Object pixels, int n) { }
        @Override public String getSliceLabel(int n) { return "t" + n; }
        @Override public String getShortSliceLabel(int n) { return "t" + n; }
    }

    // -------------------------------------------------------------- engines

    private static final Map<EngineId, Double> SHARE = shares();

    private static Map<EngineId, Double> shares() {
        Map<EngineId, Double> shares = new EnumMap<EngineId, Double>(EngineId.class);
        shares.put(EngineId.STACKREG, Double.valueOf(1.0));
        shares.put(EngineId.MULTISTACKREG, Double.valueOf(1.0));
        shares.put(EngineId.IMAGE_STABILIZER, Double.valueOf(0.5));
        return shares;
    }

    private static RegDrift.Bench bench(Arms driver) {
        final AutofixService catalogue = EngineFixtures.serviceWherePresent(
                HERE.toArray(new EngineId[0]));
        final EngineRunner runner = new EngineRunner(new EngineRunner.Presence() {
            @Override
            public boolean installed(EngineId engine) {
                return HERE.contains(engine);
            }
        }, driver);
        return new RegDrift.Bench() {
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

    /** Invented engines: each removes a stated share of the true movement, frame by frame. */
    private static final class Arms implements EngineRunner.Driver {
        private Drifting recording;

        @Override
        public void drive(ImagePlus working, EngineDescriptor engine, String options) {
            double share = SHARE.containsKey(engine.id())
                    ? SHARE.get(engine.id()).doubleValue() : 1.0;
            ImageStack stack = working.getStack();
            int width = working.getWidth();
            int height = working.getHeight();
            for (int slice = 1; slice <= stack.getSize(); slice++) {
                int t = slice - 1;
                Transform undo = Transform.translation(-share * recording.dx(t),
                        -share * recording.dy(t));
                float[] pixels = (float[]) stack.getProcessor(slice).convertToFloat().getPixels();
                float[] moved = ControlWarp.warp(pixels, width, height, undo,
                        ControlWarp.Interpolation.BILINEAR, 0f);
                stack.setProcessor(new FloatProcessor(width, height, moved, null)
                        .convertToShortProcessor(false), slice);
            }
        }
    }
}
