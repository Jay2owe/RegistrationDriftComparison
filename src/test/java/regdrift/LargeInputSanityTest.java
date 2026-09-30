/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.FolderOpener;
import ij.process.ShortProcessor;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * A 2048 x 2048 x 100 16-bit recording, 800 MB of pixels, diagnosed inside the
 * build's 512 MB test heap. The recording is opened as a virtual stack, which is
 * how a recording larger than the heap reaches Fiji at all; the diagnosis reads
 * three windows and their bridges, not every frame.
 *
 * <p>Skipped unless {@code -Dregdrift.large=true}: it writes 800 MB to the
 * temporary folder and takes a minute or more.
 */
public class LargeInputSanityTest {

    private static final int SIDE = 2048;
    private static final int FRAMES = 100;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aRecordingLargerThanTheHeapIsDiagnosedFromAVirtualStack() throws Exception {
        Assume.assumeTrue("set -Dregdrift.large=true to write and diagnose 800 MB",
                Boolean.getBoolean("regdrift.large"));
        File frames = folder.newFolder("large");
        Random random = new Random(20260930L);
        int pad = FRAMES + 8;
        // Texture a few native pixels across, so it survives the diagnosis's binning:
        // white noise one pixel across averages away inside each binned pixel.
        ij.process.FloatProcessor texture = new ij.process.FloatProcessor(SIDE + pad, SIDE + pad);
        float[] noise = (float[]) texture.getPixels();
        for (int i = 0; i < noise.length; i++) noise[i] = random.nextFloat();
        texture.blurGaussian(4.0);
        texture.resetMinAndMax();
        double low = texture.getMin();
        double span = texture.getMax() - low;
        short[] base = new short[noise.length];
        for (int i = 0; i < base.length; i++) {
            base[i] = (short) (1000 + Math.round(3000 * (noise[i] - low) / span));
        }
        texture = null;
        noise = null;
        for (int t = 0; t < FRAMES; t++) {
            ShortProcessor frame = new ShortProcessor(SIDE, SIDE);
            short[] out = (short[]) frame.getPixels();
            int dx = t;
            int dy = t / 2;
            for (int y = 0; y < SIDE; y++) {
                System.arraycopy(base, (y + dy) * (SIDE + pad) + dx, out, y * SIDE, SIDE);
            }
            IJ.saveAsTiff(new ImagePlus("f", frame), new File(frames,
                    String.format(Locale.ROOT, "f%03d.tif", t)).getPath());
        }
        base = null;
        System.gc();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();

        ImagePlus virtual = FolderOpener.open(frames.getPath(), "virtual");
        assertTrue(virtual.getStack().isVirtual());
        long start = System.nanoTime();
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(virtual)
                .mode(Mode.DIAGNOSE).build());
        double seconds = (System.nanoTime() - start) / 1e9;
        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) peak += pool.getPeakUsage().getUsed();
        }
        String said = String.format(Locale.ROOT,
                "large input: %dx%dx%d 16-bit virtual, max heap %d MB, peak heap %d MB,"
                        + " %.1f s, success=%s, drift_rate_px=%s, verdict=%s%n",
                SIDE, SIDE, FRAMES, Runtime.getRuntime().maxMemory() >> 20, peak >> 20, seconds,
                result.isSuccess(), result.isSuccess()
                        ? RegDriftTables.cellText(result.diagnosis(), "drift_rate_px", 0) : "",
                result.isSuccess() ? result.verdict() : result.failure().message());
        if (result.isSuccess()) {
            StringBuilder row = new StringBuilder(said);
            for (String heading : result.diagnosis().getHeadings()) {
                row.append("  ").append(heading).append('=')
                        .append(RegDriftTables.cellText(result.diagnosis(), heading, 0)).append('\n');
            }
            said = row.toString();
        }
        System.out.print(said);
        java.nio.file.Files.write(new File(System.getProperty("regdrift.large.out",
                "target/large-input.txt")).toPath(), said.getBytes("UTF-8"));
        assertTrue(said, result.isSuccess());
    }
}
