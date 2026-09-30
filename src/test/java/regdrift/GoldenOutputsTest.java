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
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineFixtures;
import regdrift.autofix.EngineId;
import regdrift.harness.EngineDescriptor;
import regdrift.harness.EngineRunner;
import regdrift.internal.Transform;
import regdrift.score.ControlWarp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The measurements, pinned: every mode over five fixed recordings must produce
 * exactly the tables and the saved tree it produced when these goldens were
 * written.
 *
 * <p>Behavioural tests say a figure is in the right direction. This says it did
 * not move at all. A speed-up, a refactor or a bug fix that changes any number in
 * any table fails here, and the fix is either to find what moved or - for a
 * change that is meant - to regenerate the goldens with
 * {@code -Dregdrift.golden.write=true} in a commit of its own, with a CHANGELOG
 * line saying which numbers moved and why. The switch is never used to make a
 * difference go away.
 *
 * <h2>What is compared</h2>
 *
 * <ul>
 *   <li>The four result tables. Every number cell is written as the hex of
 *       {@link Double#doubleToLongBits}, so a change in the last bit fails; every
 *       word cell is written as it is.</li>
 *   <li>The auto-save tree: each CSV, {@code README.txt} and {@code summary.csv}
 *       as text, and each TIFF as its dimensions and pixel values rather than its
 *       file bytes (a TIFF header carries nothing measured).</li>
 * </ul>
 *
 * <h2>What is excluded, and why</h2>
 *
 * <ul>
 *   <li>{@code cpu_seconds} (Comparison table and CSV): processor time, different
 *       on every run.</li>
 *   <li>{@code run_utc} (summary.csv) and the {@code Written by version ... on}
 *       line of README.txt: the clock.</li>
 *   <li>In the {@code settings} text of summary.csv and the {@code Macro:} line of
 *       README.txt, the save folder is replaced by {@code <root>} and the value of
 *       {@code serial=} by {@code *}: the folder is a temporary one and the serial
 *       and max-worker runs are required to agree on everything else.</li>
 * </ul>
 *
 * <p>{@code expected_seconds} is kept: it is read from the frozen calibration
 * table, not measured here.
 *
 * <h2>The fixtures</h2>
 *
 * <ol>
 *   <li>{@code drift_f32}: 96 x 96 x 12 textured float stack drifting 0.73 / -0.41
 *       px per frame, the construction {@code ModesEndToEndTest} uses.</li>
 *   <li>{@code hyper_c3z2t12}: 3 channels x 2 slices x 12 frames of the same
 *       drift, one texture per channel.</li>
 *   <li>{@code drift_u8} and {@code drift_u16}: fixture 1 at 8 and 16 bits.</li>
 *   <li>{@code knock_256x48}: 256 x 256 x 48 drifting 0.61 / -0.37 px per frame
 *       with a knock of (5.3, -3.1) px at frame 20.</li>
 * </ol>
 *
 * <p>{@code score} rates each fixture against its own partner, made by the
 * invented engine that removes all of the drift. The engines are invented through
 * the {@link RegDrift#bench} seam, as in {@code ModesEndToEndTest}, and the set of
 * engines "installed" is fixed, so nothing here depends on the machine.
 */
public class GoldenOutputsTest {

    /** Where the goldens live, relative to the module. */
    static final String GOLDEN_PATH = "src/test/resources/golden/modes.tsv";

    /** Regenerates the goldens instead of comparing. Never used to hide a difference. */
    static final String WRITE_SWITCH = "regdrift.golden.write";

    /** Timing columns, dropped from tables and CSVs. */
    static final Set<String> EXCLUDED = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("cpu_seconds", "run_utc")));

    private static final Set<EngineId> HERE = EnumSet.of(EngineId.TURBOREG, EngineId.STACKREG,
            EngineId.MULTISTACKREG, EngineId.IMAGE_STABILIZER);

    private static final Map<EngineId, Double> SHARE = shares();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private RegDrift.Bench realBench;
    private Arms arms;

    @Before
    public void putAnInventedComputerInFront() {
        realBench = RegDrift.bench;
        arms = new Arms();
        RegDrift.bench = bench(arms);
    }

    @After
    public void putTheRealComputerBack() {
        RegDrift.bench = realBench;
    }

    @Test
    public void everyModeOnEveryFixtureMatchesItsGolden() throws Exception {
        Map<String, String> serial = digests(true);
        Map<String, String> parallel = digests(false);

        List<String> split = differences(serial, parallel);
        assertTrue("serial and max-worker runs must be bit-identical; they differ at "
                + split, split.isEmpty());

        File golden = new File(System.getProperty("basedir", "."), GOLDEN_PATH);
        if (Boolean.getBoolean(WRITE_SWITCH)) {
            write(golden, serial);
            return;
        }
        assertTrue("No golden at " + golden.getAbsolutePath() + ". Generate it once from an"
                + " unmodified tree with -D" + WRITE_SWITCH + "=true.", golden.isFile());
        Map<String, String> expected = read(golden);
        List<String> moved = differences(expected, serial);
        if (!moved.isEmpty()) {
            fail(moved.size() + " golden digest(s) moved. A measurement changed; find out"
                    + " why before touching the golden. First differences: "
                    + moved.subList(0, Math.min(8, moved.size())));
        }
        assertEquals(expected.size(), serial.size());
    }

    // ------------------------------------------------------------------ runs

    private Map<String, String> digests(boolean serial) throws IOException {
        Map<String, String> out = new TreeMap<String, String>();
        for (Fixture fixture : fixtures()) {
            for (Mode mode : Mode.values()) {
                arms.fixture = fixture;
                File root = folder.newFolder(fixture.name + "-" + mode.macroValue() + "-"
                        + (serial ? "serial" : "max"));
                RegDriftParameters.Builder request = RegDriftParameters
                        .builder(fixture.raw("movie"))
                        .mode(mode)
                        .serial(serial)
                        .hideDisplay(true)
                        .saveRoot(root.getAbsolutePath());
                if (mode == Mode.SCORE) request.compareWith(fixture.partner("registered"));
                RegDriftResult result = RegDrift.run(request.build());

                String key = fixture.name + "\t" + mode.macroValue() + "\t";
                out.put(key + "failure", result.failure() == null ? "none"
                        : result.failure().kind() + ": " + sha(result.failure().message()));
                out.put(key + "diagnosis", sha(canon(result.diagnosis())));
                out.put(key + "recommendation", sha(canon(result.recommendation())));
                out.put(key + "comparison", sha(canon(result.comparison())));
                out.put(key + "frames", sha(canon(result.frames())));
                out.put(key + "verdict", String.valueOf(result.verdict()));
                out.put(key + "registered", result.registered() == null ? "none"
                        : sha(pixels(result.registered())));

                RegDriftAutoSave.Report report = RegDriftAutoSave.save(result);
                out.put(key + "tree", report.isSuccess()
                        ? sha(tree(new File(root, RegDriftAutoSave.TREE_FOLDER), root))
                        : "save failed: " + report.failure().kind());
            }
        }
        return out;
    }

    // ------------------------------------------------------------ canonical text

    /** A table as text: headings minus the excluded ones, then every cell. */
    static String canon(ResultsTable table) {
        if (table == null) return "null";
        StringBuilder text = new StringBuilder();
        List<String> columns = new ArrayList<String>();
        String[] headings = table.getHeadings();
        for (String heading : headings) {
            if (heading == null || heading.isEmpty() || EXCLUDED.contains(heading)) continue;
            columns.add(heading);
        }
        text.append(columns).append('\n');
        for (int row = 0; row < table.size(); row++) {
            for (String column : columns) {
                double value = Double.NaN;
                try {
                    value = table.getValue(column, row);
                } catch (RuntimeException words) {
                    value = Double.NaN;
                }
                if (!Double.isNaN(value)) {
                    text.append(Long.toHexString(Double.doubleToLongBits(value)));
                } else {
                    String words = table.getStringValue(column, row);
                    text.append('"').append(words == null ? "" : words).append('"');
                }
                text.append('|');
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** Every file of a saved tree, in path order, normalised as the javadoc says. */
    static String tree(final File tree, File root) throws IOException {
        List<File> files = new ArrayList<File>();
        collect(tree, files);
        // Path order, ignoring case, on every system. File's own ordering ignores case on Windows
        // and not on Linux, so README.txt moved among the folders and every tree digest differed
        // on the CI runner while every table and pixel matched. This is the Windows order the
        // goldens were written in.
        Collections.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return tree.toURI().relativize(a.toURI()).getPath()
                        .compareToIgnoreCase(tree.toURI().relativize(b.toURI()).getPath());
            }
        });
        StringBuilder text = new StringBuilder();
        for (File file : files) {
            String relative = tree.toURI().relativize(file.toURI()).getPath();
            text.append("## ").append(relative).append('\n');
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".tif") || name.endsWith(".tiff")) {
                ImagePlus image = IJ.openImage(file.getAbsolutePath());
                text.append(image == null ? "unreadable" : sha(pixels(image))).append('\n');
                if (image != null) image.flush();
            } else {
                String content = new String(Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8);
                content = normalise(content, root);
                if (name.endsWith(".csv")) content = dropColumns(content);
                text.append(content).append('\n');
            }
        }
        return text.toString();
    }

    private static void collect(File dir, List<File> into) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collect(child, into);
            else into.add(child);
        }
    }

    /** Line endings, the temporary folder, the clock line and the serial flag. */
    static String normalise(String content, File root) {
        String text = content.replace("\r\n", "\n");
        String path = root.getAbsolutePath();
        text = text.replace(path, "<root>").replace(path.replace('\\', '/'), "<root>");
        text = text.replaceAll("serial=(true|false)", "serial=*");
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("Written by version ")) continue;
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    /** A CSV with the excluded columns taken out. */
    static String dropColumns(String csv) {
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) return csv;
        List<String> header = rows.get(0);
        List<Integer> keep = new ArrayList<Integer>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).replace("﻿", "");
            if (!EXCLUDED.contains(name)) keep.add(Integer.valueOf(i));
        }
        StringBuilder out = new StringBuilder();
        for (List<String> row : rows) {
            for (Integer i : keep) {
                out.append(i.intValue() < row.size() ? row.get(i.intValue()) : "").append('\u001f');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** RFC 4180: commas, quotes doubled inside quotes, newlines inside quotes. */
    static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<List<String>>();
        List<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<String>();
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Dimensions, type and every pixel value of an image. */
    static String pixels(ImagePlus image) {
        StringBuilder text = new StringBuilder();
        text.append(image.getWidth()).append('x').append(image.getHeight()).append('x')
                .append(image.getNChannels()).append('x').append(image.getNSlices()).append('x')
                .append(image.getNFrames()).append(" bits=").append(image.getBitDepth())
                .append('\n');
        ImageStack stack = image.getStack();
        for (int s = 1; s <= stack.getSize(); s++) {
            ImageProcessor ip = stack.getProcessor(s);
            int n = ip.getPixelCount();
            long h = 1125899906842597L;
            for (int i = 0; i < n; i++) {
                h = 31 * h + Float.floatToIntBits(ip.getf(i));
            }
            text.append(Long.toHexString(h)).append('\n');
        }
        return text.toString();
    }

    static String sha(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ----------------------------------------------------------- golden file

    private static List<String> differences(Map<String, String> a, Map<String, String> b) {
        Set<String> keys = new java.util.TreeSet<String>(a.keySet());
        keys.addAll(b.keySet());
        List<String> out = new ArrayList<String>();
        for (String key : keys) {
            String x = a.get(key);
            String y = b.get(key);
            if (x == null ? y != null : !x.equals(y)) {
                out.add(key.replace('\t', '/') + " " + x + " -> " + y);
            }
        }
        return out;
    }

    private static void write(File golden, Map<String, String> digests) throws IOException {
        golden.getParentFile().mkdirs();
        StringBuilder text = new StringBuilder("fixture\tmode\toutput\tdigest\n");
        for (Map.Entry<String, String> entry : digests.entrySet()) {
            text.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
        }
        Files.write(golden.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> read(File golden) throws IOException {
        Map<String, String> out = new TreeMap<String, String>();
        List<String> lines = Files.readAllLines(golden.toPath(), StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty()) continue;
            int last = line.lastIndexOf('\t');
            out.put(line.substring(0, last), line.substring(last + 1));
        }
        return out;
    }

    // -------------------------------------------------------------- fixtures

    static List<Fixture> fixtures() {
        List<Fixture> out = new ArrayList<Fixture>();
        out.add(Fixture.drifting("drift_f32", 96, 12, 0.73, -0.41, 0, 0, 0, 32, 1, 1, 32));
        out.add(Fixture.drifting("hyper_c3z2t12", 96, 12, 0.73, -0.41, 0, 0, 0, 32, 3, 2, 32));
        out.add(Fixture.drifting("drift_u8", 96, 12, 0.73, -0.41, 0, 0, 0, 32, 1, 1, 8));
        out.add(Fixture.drifting("drift_u16", 96, 12, 0.73, -0.41, 0, 0, 0, 32, 1, 1, 16));
        out.add(Fixture.drifting("knock_256x48", 256, 48, 0.61, -0.37, 20, 5.3, -3.1, 64, 1, 1,
                32));
        return out;
    }

    /** One recording, the true movement in it, and how to make it at a bit depth. */
    static final class Fixture {

        final String name;
        final int side;
        final int frames;
        final int channels;
        final int slices;
        final int bits;
        final double[] offX;
        final double[] offY;
        /** [channel][slice][frame] planes, float. */
        final float[][][][] planes;

        private Fixture(String name, int side, int frames, int channels, int slices, int bits,
                        double[] offX, double[] offY, float[][][][] planes) {
            this.name = name;
            this.side = side;
            this.frames = frames;
            this.channels = channels;
            this.slices = slices;
            this.bits = bits;
            this.offX = offX;
            this.offY = offY;
            this.planes = planes;
        }

        static Fixture drifting(String name, int side, int frames, double stepX, double stepY,
                                int knockAt, double knockX, double knockY, int margin,
                                int channels, int slices, int bits) {
            double[] offX = new double[frames];
            double[] offY = new double[frames];
            for (int t = 0; t < frames; t++) {
                offX[t] = stepX * t + (knockAt > 0 && t >= knockAt ? knockX : 0);
                offY[t] = stepY * t + (knockAt > 0 && t >= knockAt ? knockY : 0);
            }
            int field = side + 2 * margin;
            float[][][][] planes = new float[channels][slices][frames][];
            for (int c = 0; c < channels; c++) {
                for (int z = 0; z < slices; z++) {
                    float[] world = texture(field, field, 4242L + 97L * c + 13L * z);
                    for (int t = 0; t < frames; t++) {
                        planes[c][z][t] = sampled(world, field, field, margin + offX[t],
                                margin + offY[t], side);
                    }
                }
            }
            return new Fixture(name, side, frames, channels, slices, bits, offX, offY, planes);
        }

        ImagePlus raw(String title) {
            return build(title, 0.0);
        }

        /** The recording after an engine that took out all of the movement. */
        ImagePlus partner(String title) {
            return build(title, 1.0);
        }

        private ImagePlus build(String title, double share) {
            ImageStack stack = new ImageStack(side, side);
            for (int t = 0; t < frames; t++) {
                for (int z = 0; z < slices; z++) {
                    for (int c = 0; c < channels; c++) {
                        float[] plane = planes[c][z][t].clone();
                        if (share != 0) {
                            plane = ControlWarp.warp(plane, side, side, Transform.translation(
                                    -share * offX[t], -share * offY[t]),
                                    ControlWarp.Interpolation.BILINEAR, 0f);
                        }
                        stack.addSlice("c" + (c + 1) + "z" + (z + 1) + "t" + (t + 1),
                                toDepth(new FloatProcessor(side, side, plane, null), bits));
                    }
                }
            }
            ImagePlus image = new ImagePlus(title, stack);
            if (channels > 1 || slices > 1) {
                image.setDimensions(channels, slices, frames);
                image.setOpenAsHyperStack(true);
            }
            return image;
        }

        /** Which frame a plane of the stack belongs to, in ImageJ's czt order. */
        int frameOf(int plane) {
            return (plane - 1) / (channels * slices);
        }
    }

    /** A fixed, value-preserving mapping to 8 or 16 bits. */
    static ImageProcessor toDepth(FloatProcessor plane, int bits) {
        if (bits == 32) return plane;
        float[] pixels = (float[]) plane.getPixels();
        int n = pixels.length;
        if (bits == 16) {
            short[] out = new short[n];
            for (int i = 0; i < n; i++) {
                out[i] = (short) Math.max(0, Math.min(65535, Math.round(pixels[i] * 20f)));
            }
            return new ij.process.ShortProcessor(plane.getWidth(), plane.getHeight(), out, null);
        }
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Math.max(0, Math.min(255, Math.round((pixels[i] - 300f) * 1.25f)));
        }
        return new ij.process.ByteProcessor(plane.getWidth(), plane.getHeight(), out, null);
    }

    // -------------------------------------------------------------- engines

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

        private Fixture fixture;

        @Override
        public void drive(ImagePlus working, EngineDescriptor engine, String options) {
            double share = SHARE.containsKey(engine.id())
                    ? SHARE.get(engine.id()).doubleValue() : 1.0;
            ImageStack stack = working.getStack();
            int width = working.getWidth();
            int height = working.getHeight();
            int bits = working.getBitDepth();
            for (int slice = 1; slice <= stack.getSize(); slice++) {
                int t = fixture.frameOf(slice);
                Transform undo = Transform.translation(-share * fixture.offX[t],
                        -share * fixture.offY[t]);
                float[] pixels = (float[]) stack.getProcessor(slice).convertToFloat().getPixels();
                float[] moved = ControlWarp.warp(pixels, width, height, undo,
                        ControlWarp.Interpolation.BILINEAR, 0f);
                ImageProcessor back = new FloatProcessor(width, height, moved, null);
                if (bits == 8) back = back.convertToByteProcessor(false);
                else if (bits == 16) back = back.convertToShortProcessor(false);
                stack.setProcessor(back, slice);
            }
        }
    }

    // ------------------------------------------------------------- texture

    /** Fine-grained texture with structure at every scale, from a fixed seed. */
    static float[] texture(int w, int h, long seed) {
        Random random = new Random(seed);
        float[] plane = new float[w * h];
        for (int i = 0; i < plane.length; i++) {
            plane[i] = 400f + 120f * (float) random.nextGaussian();
        }
        for (int pass = 0; pass < 3; pass++) {
            float[] smoothed = new float[plane.length];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double sum = 0;
                    int counted = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                            sum += plane[ny * w + nx];
                            counted++;
                        }
                    }
                    smoothed[y * w + x] = (float) (sum / counted);
                }
            }
            plane = smoothed;
        }
        return plane;
    }

    /** A window of the field, sampled bilinearly at a fractional offset. */
    static float[] sampled(float[] field, int fieldWidth, int fieldHeight, double ox, double oy,
                           int side) {
        float[] out = new float[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                double fx = ox + x;
                double fy = oy + y;
                int x0 = (int) Math.floor(fx);
                int y0 = (int) Math.floor(fy);
                double ax = fx - x0;
                double ay = fy - y0;
                out[y * side + x] = (float) (
                        at(field, fieldWidth, fieldHeight, x0, y0) * (1 - ax) * (1 - ay)
                                + at(field, fieldWidth, fieldHeight, x0 + 1, y0) * ax * (1 - ay)
                                + at(field, fieldWidth, fieldHeight, x0, y0 + 1) * (1 - ax) * ay
                                + at(field, fieldWidth, fieldHeight, x0 + 1, y0 + 1) * ax * ay);
            }
        }
        return out;
    }

    private static double at(float[] field, int w, int h, int x, int y) {
        int cx = Math.max(0, Math.min(w - 1, x));
        int cy = Math.max(0, Math.min(h - 1, y));
        return field[cy * w + cx];
    }
}
