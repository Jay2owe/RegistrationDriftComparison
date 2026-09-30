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
import org.junit.Assume;
import org.junit.Test;
import regdrift.diag.Estimator;
import regdrift.diag.Frames;
import regdrift.diag.PhaseCorrelation;
import regdrift.internal.PairScheduler;
import regdrift.internal.Transform;
import regdrift.score.Arbiter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * How far the sub-pixel shift finder reads a known drift, before and after a
 * change to it. Measures three things on {@link GoldenOutputsTest.Fixture}
 * recordings, whose frames are sampled at exact fractional offsets:
 *
 * <ol>
 *   <li>one frame pair at native resolution;</li>
 *   <li>{@code drift_rate_px} from a diagnosis, at every measurement scale;</li>
 *   <li>the comparison's own-movement chain at native resolution (defect B9).</li>
 * </ol>
 *
 * <p>Not a regression test: skipped unless {@code -Dregdrift.probe=true}. It writes
 * {@code target/subpixel-probe-<label>.txt}.
 */
public class SubpixelProbe {

    private static final double[] RATES = {0.2, 0.3, 0.6, 0.84, 1.5, 1.68, 2.5, 4.0};

    @Test
    public void probe() throws IOException {
        Assume.assumeTrue(Boolean.getBoolean("regdrift.probe"));
        String label = System.getProperty("regdrift.probe.label", "probe");
        StringBuilder out = new StringBuilder();
        out.append("# subpixel probe ").append(label).append('\n');

        out.append("\n## one pair, native, 96 x 96, shift along x\n");
        out.append("true\tread\tratio\n");
        for (double d : new double[]{0.075, 0.21, 0.3, 0.42, 0.6, 0.84, 1.5, 1.68}) {
            GoldenOutputsTest.Fixture f = GoldenOutputsTest.Fixture.drifting("pair", 96, 2, d, 0,
                    0, 0, 0, 16, 1, 1, 32);
            Estimator.Displacement s = new PhaseCorrelation().shift(f.planes[0][0][0],
                    f.planes[0][0][1], 96, 96, 24, Frames.Bin.none());
            out.append(String.format(Locale.ROOT, "%.3f\t%.4f\t%.3f%n", d, s.dx(), s.dx() / d));
        }

        out.append("\n## drift_rate_px, diagnose, 48 frames, direction (0.8, -0.6)\n");
        out.append("side\tbin\ttrue\tread\tratio\n");
        for (int side : new int[]{96, 160, 256, 512}) {
            for (double r : RATES) {
                ImagePlus image = recording(side, 48, r).raw("probe");
                RegDriftResult result = RegDrift.run(RegDriftParameters.builder(image)
                        .mode(Mode.DIAGNOSE).build());
                assertTrue(String.valueOf(result.failure()), result.isSuccess());
                String text = RegDriftTables.cellText(result.diagnosis(), "drift_rate_px", 0);
                double read = text.isEmpty() ? Double.NaN : Double.parseDouble(text);
                out.append(String.format(Locale.ROOT, "%d\t%s\t%.2f\t%.4f\t%.3f%n", side,
                        RegDriftTables.cellText(result.diagnosis(), "measured_at_bin", 0), r,
                        read, read / r));
                image.close();
            }
        }

        out.append("\n## own movement, native, 24 frames, direction (0.8, -0.6)\n");
        out.append("side\ttrue_net\tread_net\tratio\n");
        for (int side : new int[]{128, 256}) {
            for (double r : RATES) {
                ImagePlus image = recording(side, 24, r).raw("probe");
                Arbiter.Recovered own = Arbiter.ownMotion(Frames.of(image, 1, 1,
                        Frames.Bin.none()), 0, PairScheduler.Progress.NONE, Cancellation.never());
                Transform last = own.cumulative()[23];
                double truth = r * 23;
                double read = Math.hypot(last.dx, last.dy);
                out.append(String.format(Locale.ROOT, "%d\t%.2f\t%.3f\t%.3f%n", side, truth, read,
                        read / truth));
                image.close();
            }
        }

        File file = new File("target/subpixel-probe-" + label + ".txt");
        Files.write(file.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(out);
    }

    static GoldenOutputsTest.Fixture recording(int side, int frames, double rate) {
        int margin = (int) Math.ceil(rate * frames) + 8;
        return GoldenOutputsTest.Fixture.drifting("probe", side, frames, 0.8 * rate, -0.6 * rate,
                0, 0, 0, margin, 1, 1, 32);
    }
}
