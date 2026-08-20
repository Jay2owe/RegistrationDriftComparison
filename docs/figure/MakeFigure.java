/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.Duplicator;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import regdrift.ArmOutcome;
import regdrift.Mode;
import regdrift.Recommendation;
import regdrift.RegDrift;
import regdrift.RegDriftParameters;
import regdrift.RegDriftResult;
import regdrift.harness.ArmStatus;

import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The one figure: the same recording registered by two methods, side by side.
 *
 * <p>Everything drawn here is produced by the plugin itself in this one run. The
 * two registered recordings come from two {@code apply} runs, the percentages and
 * the ranking come from one {@code compare} run over the same recording, and the
 * recommendation block is the plugin's own ranked list. Nothing is copied in from
 * a document.
 *
 * <h2>What the residual map is</h2>
 *
 * <p>One picture per recording, the same size as a frame. Each pixel holds the
 * root-mean-square of that pixel's frame-to-frame change across the whole
 * recording. It is the per-pixel form of the {@code residual_before} and
 * {@code residual_after} columns, which are the same quantity summarised over the
 * frame and converted into an equivalent displacement. Bright means that pixel
 * kept changing; dark means it settled. All three maps are stretched with one
 * pair of limits taken from the raw recording, so a darker panel is a stiller
 * recording rather than a differently scaled picture.
 *
 * <h2>Why every panel is cropped by the same inset</h2>
 *
 * <p>A registration that shifts the content leaves fill at the edge, and fill is
 * perfectly still, which would read as a flawless registration. Every panel is
 * therefore cropped on all four sides by the largest net displacement any arm
 * walked, rounded up, plus two pixels — the same idea as the shared region the
 * arbiter scores inside, computed here from figures the arms themselves report.
 *
 * <h2>How to run it</h2>
 *
 * <pre>
 * bash mvnw clean package -Denforcer.skip=true
 * CP="target/RegistrationDriftComparison-&lt;version&gt;.jar;&lt;ij.jar&gt;"
 * java -Xmx6g -Dplugins.dir=&lt;Fiji.app&gt; -Dij.dir=&lt;Fiji.app&gt; \
 *      -cp "$CP" docs/figure/MakeFigure.java &lt;library&gt; docs/figure
 * </pre>
 *
 * <p>The packaged jar rather than {@code target/classes}, because the two chassis
 * modules are shaded in at package time and are not on the class path before that.
 * <b>Not</b> {@code -Djava.awt.headless=true}: booting ImageJ's command table is
 * what makes another plugin's menu command reachable, and that needs a display,
 * which is why no window is shown rather than no display being used.
 *
 * <p>It needs a Fiji with StackReg and Linear Stack Alignment with SIFT in it and
 * the twelve-recording library. It writes {@code registration-comparison.png} and
 * {@code figure-data.txt} into the output folder and nothing else, shows no
 * window, and reaches no network.
 */
public final class MakeFigure {

    /** The recording the figure is drawn on. */
    private static final String ENTRY = "04_drift";

    /** The channel the engines are handed, and the reason is in the README. */
    private static final int CHANNEL = 1;

    /** The two methods drawn beside each other. Spelled as their authors spell them. */
    private static final String LEFT_ENGINE = "StackReg";
    private static final String RIGHT_ENGINE = "Linear Stack Alignment with SIFT";

    /** Each panel is drawn this wide, whatever the crop came out at. */
    private static final int PANEL = 300;

    /** White between panels and around the edge. */
    private static final int PAD = 22;

    private MakeFigure() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: MakeFigure <library folder> <output folder>");
            System.out.println("  <library folder> holds " + ENTRY + "/original.tif");
            return;
        }
        File library = new File(args[0]);
        File out = new File(args[1]);
        File source = new File(new File(library, ENTRY), "original.tif");
        if (!source.isFile()) {
            System.out.println("no recording at " + source.getAbsolutePath()
                    + ", so there is nothing to draw. The twelve-recording library is not in this"
                    + " repository; point the first argument at it.");
            return;
        }
        if (!out.isDirectory() && !out.mkdirs()) {
            System.out.println("could not write into " + out.getAbsolutePath());
            return;
        }

        // Comparison drives other people's plugins through ImageJ's own command table,
        // so the table has to exist. A plain build JVM has ij.jar and nothing else, and
        // every arm would come back not_installed. NO_SHOW builds it without a window.
        // Errors are redirected into the log first and left redirected, because a Fiji
        // with two plugins claiming one command name says so through a modal dialog, and
        // a modal dialog in an unattended run is a session that never finishes.
        ij.IJ.redirectErrorMessages(true);
        new ij.ImageJ(ij.ImageJ.NO_SHOW);
        ij.IJ.redirectErrorMessages(true);

        List<String> log = new ArrayList<String>();
        log.add("Registration & Drift Comparison " + RegDrift.VERSION + " - figure data");
        log.add("recording: " + source.getAbsolutePath());
        log.add("channel:   " + CHANNEL);
        log.add("Fiji:      " + System.getProperty("plugins.dir"));
        log.add("");

        ImagePlus raw = channelOf(source);
        int frames = raw.getStackSize();
        log.add(String.format(Locale.US, "raw: %d x %d, %d frames", raw.getWidth(),
                raw.getHeight(), frames));

        // ---------------------------------------------------------- the comparison
        System.out.println("compare mode over " + ENTRY + " channel " + CHANNEL + " ...");
        RegDriftResult compare = RegDrift.run(RegDriftParameters.builder(raw)
                .mode(Mode.COMPARE)
                .hideDisplay(true)
                .build());
        if (!compare.isSuccess()) {
            System.out.println("the comparison did not finish: "
                    + (compare.failure() == null ? "" : compare.failure().message()));
            return;
        }
        log.add("");
        log.add("compare mode, every arm it considered:");
        double widestNet = 0;
        Arm left = null;
        Arm right = null;
        for (ArmOutcome arm : compare.arms()) {
            if (arm.status() == ArmStatus.NOT_INSTALLED) {
                log.add(String.format(Locale.US, "  %-34s not installed", arm.engineName()));
                continue;
            }
            log.add(String.format(Locale.US, "  %-34s %-12s %s  rank %s  residual %.2f -> %.2f px"
                            + "  path %.1f px  net %.1f px  cpu %.1f s",
                    arm.engineName(), arm.status().tableValue(),
                    arm.hasFigure()
                            ? String.format(Locale.US, "sd_vs_control %+.2f%%",
                                    arm.sdVsControlPercent())
                            : "no figure          ",
                    arm.rankColumn(), arm.residualBefore(), arm.residualAfter(),
                    arm.pathPx(), arm.netPx(), arm.cpuSeconds()));
            if (arm.hasFigure() && !Double.isNaN(arm.netPx())) {
                widestNet = Math.max(widestNet, arm.netPx());
            }
            if (LEFT_ENGINE.equals(arm.engineName())) left = new Arm(arm);
            if (RIGHT_ENGINE.equals(arm.engineName())) right = new Arm(arm);
        }
        if (left == null || right == null) {
            System.out.println("this Fiji did not run both " + LEFT_ENGINE + " and " + RIGHT_ENGINE
                    + ", so the two-method figure cannot be drawn from it.");
            return;
        }

        // ------------------------------------------------------ the recommendation
        //
        // Four lines, and the last two exist because the honest answer is not one name.
        // The bundled calibration measured one arm and it describes two engines, so the
        // top two share a reason word for word; and the engine the arbiter then put
        // first is not necessarily the one the calibration ranked first, which is the
        // whole reason compare mode is in this plugin.
        List<Recommendation> ranking = compare.ranked();
        log.add("");
        log.add("the recommendation, from the bundled calibration, before any engine ran:");
        for (Recommendation ranked : ranking) {
            log.add(String.format(Locale.US, "  rank %d  %-34s %s [%s]", ranked.rank(),
                    ranked.engine(), ranked.reason(), ranked.calibration().tableValue()));
        }
        List<String> block = new ArrayList<String>();
        if (!ranking.isEmpty()) {
            Recommendation first = ranking.get(0);
            Recommendation second = ranking.size() > 1 ? ranking.get(1) : null;
            boolean sameRow = second != null && first.reason().equals(second.reason());
            block.add("What the plugin recommended, before any engine was run: " + first.engine()
                    + (sameRow ? ", then " + second.engine() : ""));
            block.add(first.reason());
            block.add(sameRow
                    ? "One measured arm describes both, so they carry the identical row and are not"
                            + " separated. Calibration reads " + first.calibration().tableValue()
                            + "."
                    : "Calibration reads " + first.calibration().tableValue() + ".");
        }
        StringBuilder won = new StringBuilder();
        StringBuilder refused = new StringBuilder();
        for (ArmOutcome arm : compare.arms()) {
            if (arm.hasFigure() && arm.rank() == 1) {
                if (won.length() > 0) won.append(" and ");
                won.append(arm.engineName());
            }
            if (arm.status() == ArmStatus.COULD_NOT_DRIVE) {
                if (refused.length() > 0) refused.append(", ");
                refused.append(arm.engineName());
            }
        }
        block.add("On this recording the arbiter then put " + won
                + " first, tied through \"cannot separate\" at 3.0 percentage points.");
        if (refused.length() > 0) {
            block.add("Present in this Fiji and not drivable from here: " + refused + ".");
        }

        // ------------------------------------------------------------- the pictures
        System.out.println("apply mode, " + LEFT_ENGINE + " ...");
        ImagePlus leftStack = applied(source, LEFT_ENGINE, log);
        System.out.println("apply mode, " + RIGHT_ENGINE + " ...");
        ImagePlus rightStack = applied(source, RIGHT_ENGINE, log);
        if (leftStack == null || rightStack == null) {
            System.out.println("an apply run produced no registered recording, so there is no"
                    + " picture to draw.");
            return;
        }

        int inset = (int) Math.ceil(widestNet) + 2;
        int cropW = raw.getWidth() - 2 * inset;
        int cropH = raw.getHeight() - 2 * inset;
        if (cropW < 32 || cropH < 32) {
            System.out.println("the arms walked further than the frame, so every panel would be"
                    + " cropped away.");
            return;
        }
        log.add("");
        log.add(String.format(Locale.US, "every panel cropped by %d px on all four sides"
                        + " (largest net displacement any arm walked, %.1f px, rounded up, plus 2),"
                        + " leaving %d x %d",
                inset, widestNet, cropW, cropH));

        float[][] rawResidual = residualMap(raw, inset, cropW, cropH);
        float[][] leftResidual = residualMap(leftStack, inset, cropW, cropH);
        float[][] rightResidual = residualMap(rightStack, inset, cropW, cropH);
        double[] limits = limits(rawResidual);
        log.add(String.format(Locale.US, "residual maps stretched black-to-white over %.4f to %.4f"
                + " grey levels per frame, taken from the raw map and used for all three",
                limits[0], limits[1]));

        ImageProcessor rawMap = grey(rawResidual, limits);
        ImageProcessor leftMap = grey(leftResidual, limits);
        ImageProcessor rightMap = grey(rightResidual, limits);

        double[] kymoLimits = kymographLimits(raw, inset, cropW, cropH);
        ImageProcessor rawKymo = kymograph(raw, inset, cropW, cropH, kymoLimits);
        ImageProcessor leftKymo = kymograph(leftStack, inset, cropW, cropH, kymoLimits);
        ImageProcessor rightKymo = kymograph(rightStack, inset, cropW, cropH, kymoLimits);

        // --------------------------------------------------------------- the canvas
        ColorProcessor canvas = compose(new ImageProcessor[]{rawMap, leftMap, rightMap},
                new ImageProcessor[]{rawKymo, leftKymo, rightKymo},
                new String[]{"raw", LEFT_ENGINE, RIGHT_ENGINE},
                new String[]{
                        "no registration",
                        String.format(Locale.US, "sd_vs_control %+.2f%%   rank %s",
                                left.sdVsControl, left.rank),
                        String.format(Locale.US, "sd_vs_control %+.2f%%   rank %s",
                                right.sdVsControl, right.rank)},
                new String[]{
                        String.format(Locale.US, "residual %.2f px frame to frame",
                                left.residualBefore),
                        String.format(Locale.US, "residual %.2f px, %.1f CPU s",
                                left.residualAfter, left.cpuSeconds),
                        String.format(Locale.US, "residual %.2f px, %.1f CPU s",
                                right.residualAfter, right.cpuSeconds)},
                ENTRY + ", channel " + CHANNEL + ", " + frames + " frames, "
                        + raw.getWidth() + " x " + raw.getHeight(),
                block, inset, cropW, cropH, frames);

        ImagePlus figure = new ImagePlus("Registration and Drift Comparison figure", canvas);
        File png = new File(out, "registration-comparison.png");
        IJ.saveAs(figure, "PNG", png.getAbsolutePath());
        log.add("");
        log.add("wrote " + png.getName() + ", " + canvas.getWidth() + " x " + canvas.getHeight());

        File data = new File(out, "figure-data.txt");
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(data), StandardCharsets.UTF_8));
        try {
            for (String line : log) writer.println(line);
        } finally {
            writer.close();
        }
        for (String line : log) System.out.println(line);
        System.out.println();
        System.out.println("wrote " + png.getAbsolutePath());
        System.out.println("wrote " + data.getAbsolutePath());

        raw.close();
        leftStack.close();
        rightStack.close();
    }

    // ------------------------------------------------------------------ the runs

    /** Channel {@link #CHANNEL} of the recording, as a plain single-channel stack. */
    private static ImagePlus channelOf(File source) {
        ImagePlus whole = IJ.openImage(source.getAbsolutePath());
        try {
            ImagePlus one = new Duplicator().run(whole, CHANNEL, CHANNEL, 1, whole.getNSlices(),
                    1, whole.getNFrames());
            one.setTitle(ENTRY + " channel " + CHANNEL);
            return one;
        } finally {
            whole.close();
        }
    }

    /** One engine driven over the recording, and the recording it handed back. */
    private static ImagePlus applied(File source, String engine, List<String> log) {
        ImagePlus input = channelOf(source);
        try {
            RegDriftResult result = RegDrift.run(RegDriftParameters.builder(input)
                    .mode(Mode.APPLY)
                    .applyEngine(engine)
                    .hideDisplay(true)
                    .build());
            if (!result.isSuccess()) {
                log.add("apply " + engine + " did not finish: "
                        + (result.failure() == null ? "" : result.failure().message()));
                return null;
            }
            for (ArmOutcome arm : result.arms()) {
                if (!engine.equals(arm.engineName())) continue;
                log.add(String.format(Locale.US, "apply %s: %s, sd_vs_control %s, %.1f CPU s",
                        engine, arm.status().tableValue(),
                        arm.hasFigure()
                                ? String.format(Locale.US, "%+.2f%%", arm.sdVsControlPercent())
                                : "none",
                        arm.cpuSeconds()));
            }
            return result.registered();
        } finally {
            input.close();
        }
    }

    // ------------------------------------------------------------- the arithmetic

    /**
     * Root-mean-square frame-to-frame change, per pixel, inside the crop.
     *
     * <p>The per-pixel form of {@code residual_before} and {@code residual_after}:
     * those two columns are this quantity summed over the frame and turned into an
     * equivalent displacement, and this is the same difference before the summing.
     */
    private static float[][] residualMap(ImagePlus imp, int inset, int w, int h) {
        ImageStack stack = imp.getStack();
        int n = stack.getSize();
        int width = imp.getWidth();
        double[] sum = new double[w * h];
        int pairs = 0;
        float[] previous = null;
        for (int t = 1; t <= n; t++) {
            float[] plane = plane(stack, t, width, imp.getHeight());
            if (previous != null) {
                for (int y = 0; y < h; y++) {
                    int rowAt = (y + inset) * width + inset;
                    int outAt = y * w;
                    for (int x = 0; x < w; x++) {
                        double d = plane[rowAt + x] - previous[rowAt + x];
                        sum[outAt + x] += d * d;
                    }
                }
                pairs++;
            }
            previous = plane;
        }
        float[][] map = new float[h][w];
        double scale = pairs > 0 ? 1.0 / pairs : 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                map[y][x] = (float) Math.sqrt(sum[y * w + x] * scale);
            }
        }
        return map;
    }

    /** One frame as floats, whatever the stack's bit depth is. */
    private static float[] plane(ImageStack stack, int t, int width, int height) {
        ImageProcessor ip = stack.getProcessor(t);
        float[] out = new float[width * height];
        for (int i = 0; i < out.length; i++) out[i] = ip.getf(i);
        return out;
    }

    /** Black and white pinned to the 0.5th and 99.5th percentile of a map. */
    private static double[] limits(float[][] map) {
        int total = 0;
        for (int y = 0; y < map.length; y++) total += map[y].length;
        float[] all = new float[total];
        int at = 0;
        for (int y = 0; y < map.length; y++) {
            for (int x = 0; x < map[y].length; x++) {
                float v = map[y][x];
                if (Float.isNaN(v)) continue;
                all[at++] = v;
            }
        }
        if (at == 0) return new double[]{0, 1};
        float[] sorted = Arrays.copyOf(all, at);
        Arrays.sort(sorted);
        double low = sorted[percentile(sorted.length, 0.5)];
        double high = sorted[percentile(sorted.length, 99.5)];
        return new double[]{low, high > low ? high : low + 1};
    }

    private static int percentile(int length, double p) {
        int k = (int) Math.round(p / 100.0 * (length - 1));
        return Math.max(0, Math.min(length - 1, k));
    }

    /** A map into an 8-bit picture, with a mild gamma so faint structure survives print. */
    private static ImageProcessor grey(float[][] map, double[] limits) {
        int h = map.length;
        int w = map[0].length;
        ByteProcessor ip = new ByteProcessor(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double f = (map[y][x] - limits[0]) / Math.max(1e-9, limits[1] - limits[0]);
                f = Math.pow(Math.max(0, Math.min(1, f)), 0.7);
                ip.set(x, y, (int) Math.round(255 * f));
            }
        }
        return ip;
    }

    /**
     * One row of the picture per frame, stacked down the page.
     *
     * <p>A feature that stays put draws a straight vertical stripe; a feature that
     * drifts draws a slanted one. It is the same panel {@code regdrift.score.Kymograph}
     * builds, drawn here so the figure can put three of them under one another.
     */
    private static ImageProcessor kymograph(ImagePlus imp, int inset, int w, int h,
                                            double[] limits) {
        ImageStack stack = imp.getStack();
        int n = stack.getSize();
        int width = imp.getWidth();
        int row = inset + h / 2;
        ByteProcessor ip = new ByteProcessor(w, n);
        for (int t = 1; t <= n; t++) {
            ImageProcessor frame = stack.getProcessor(t);
            for (int x = 0; x < w; x++) {
                double v = frame.getf(inset + x, row);
                double f = (v - limits[0]) / Math.max(1e-9, limits[1] - limits[0]);
                f = Math.pow(Math.max(0, Math.min(1, f)), 0.7);
                ip.set(x, t - 1, (int) Math.round(255 * f));
            }
        }
        return ip;
    }

    /** The brightness limits all three kymographs share, taken from the raw recording. */
    private static double[] kymographLimits(ImagePlus imp, int inset, int w, int h) {
        ImageStack stack = imp.getStack();
        int n = stack.getSize();
        int row = inset + h / 2;
        float[][] rows = new float[n][w];
        for (int t = 1; t <= n; t++) {
            ImageProcessor frame = stack.getProcessor(t);
            for (int x = 0; x < w; x++) rows[t - 1][x] = frame.getf(inset + x, row);
        }
        return limits(rows);
    }

    // ------------------------------------------------------------------ the page

    private static ColorProcessor compose(ImageProcessor[] maps, ImageProcessor[] kymos,
                                          String[] titles, String[] scores, String[] residuals,
                                          String recordingLine, List<String> recommendation,
                                          int inset, int cropW, int cropH, int frames) {
        int columns = maps.length;
        int mapH = Math.max(1, PANEL * maps[0].getHeight() / maps[0].getWidth());
        int kymoH = Math.max(48, 2 * frames);

        Font heading = new Font("SansSerif", Font.BOLD, 17);
        Font strong = new Font("SansSerif", Font.BOLD, 15);
        Font plain = new Font("SansSerif", Font.PLAIN, 13);
        Font small = new Font("SansSerif", Font.PLAIN, 12);

        int width = columns * PANEL + (columns + 1) * PAD;
        int y = 0;
        int headerTop = y + 12;
        y = headerTop + 24 + 20 + 16;          // title line, recording line, gap
        int titleTop = y;
        y += 22;                                // per-column engine name
        int mapTop = y;
        y += mapH + 8;
        int scoreTop = y;
        y += 22 + 20 + 14;                      // score line, residual line, gap
        int kymoLabelTop = y;
        y += 18;
        int kymoTop = y;
        y += kymoH + 8;
        int kymoNoteTop = y;
        y += 20 + 18;
        int recommendTop = y;
        int recommendHeight = 14 + 22 * recommendation.size();
        y += recommendHeight + 24;
        int footTop = y;
        y += 18 + 18 + 18 + 14;
        int height = y;

        ColorProcessor page = new ColorProcessor(width, height);
        page.setColor(Color.WHITE);
        page.fill();
        page.setAntialiasedText(true);

        page.setColor(Color.BLACK);
        page.setFont(heading);
        page.drawString("The same recording registered by two methods, scored the same way",
                PAD, headerTop + 20);
        page.setFont(plain);
        page.setColor(new Color(70, 70, 70));
        page.drawString(recordingLine + "   -   every panel cropped by " + inset
                + " px on all four sides, leaving " + cropW + " x " + cropH,
                PAD, headerTop + 42);

        for (int c = 0; c < columns; c++) {
            int x = PAD + c * (PANEL + PAD);
            page.setColor(Color.BLACK);
            page.setFont(strong);
            page.drawString(titles[c], x, titleTop + 17);

            ImageProcessor map = maps[c].resize(PANEL, mapH);
            page.copyBits(map.convertToRGB(), x, mapTop, ij.process.Blitter.COPY);
            page.setColor(new Color(150, 150, 150));
            page.drawRect(x - 1, mapTop - 1, PANEL + 2, mapH + 2);

            page.setColor(Color.BLACK);
            page.setFont(strong);
            page.drawString(scores[c], x, scoreTop + 16);
            page.setFont(plain);
            page.setColor(new Color(70, 70, 70));
            page.drawString(residuals[c], x, scoreTop + 36);

            ImageProcessor kymo = kymos[c].resize(PANEL, kymoH);
            page.copyBits(kymo.convertToRGB(), x, kymoTop, ij.process.Blitter.COPY);
            page.setColor(new Color(150, 150, 150));
            page.drawRect(x - 1, kymoTop - 1, PANEL + 2, kymoH + 2);
        }

        page.setColor(new Color(70, 70, 70));
        page.setFont(small);
        page.drawString("Above: residual map - each pixel is the root-mean-square of its"
                + " frame-to-frame change, all three stretched with one pair of limits taken from"
                + " the raw recording.", PAD, kymoLabelTop + 13);
        page.drawString("Below: one row of the picture per frame, time running downwards. A"
                + " feature that stays put draws a straight stripe; a feature that drifts draws a"
                + " slanted one.", PAD, kymoNoteTop + 14);

        page.setColor(new Color(232, 238, 246));
        page.setRoi(PAD - 10, recommendTop - 8, width - 2 * PAD + 20, recommendHeight);
        page.fill();
        page.resetRoi();
        for (int i = 0; i < recommendation.size(); i++) {
            if (i == 0) {
                page.setColor(Color.BLACK);
                page.setFont(strong);
            } else {
                page.setColor(new Color(50, 50, 50));
                page.setFont(plain);
            }
            page.drawString(recommendation.get(i), PAD, recommendTop + 14 + 22 * i);
        }

        page.setColor(new Color(110, 110, 110));
        page.setFont(small);
        page.drawString("sd_vs_control is the change in temporal standard deviation against a"
                + " control resampled by the same fractional shift, so interpolation blur cannot"
                + " flatter a method.", PAD, footTop + 12);
        page.drawString("Negative is stiller. Two arms within 3.0 percentage points of each other"
                + " share a rank through \"cannot separate\" rather than being ordered.",
                PAD, footTop + 30);
        page.drawString("Registration & Drift Comparison " + RegDrift.VERSION
                + " - reproduce with docs/figure/MakeFigure.java. Every number here is what one"
                + " arbiter measured on one recording on one machine.", PAD, footTop + 48);
        return page;
    }

    /** The few figures the page shows for one arm, taken once. */
    private static final class Arm {

        private final double sdVsControl;
        private final double residualBefore;
        private final double residualAfter;
        private final double cpuSeconds;
        private final String rank;

        private Arm(ArmOutcome arm) {
            this.sdVsControl = arm.sdVsControlPercent();
            this.residualBefore = arm.residualBefore();
            this.residualAfter = arm.residualAfter();
            this.cpuSeconds = arm.cpuSeconds();
            this.rank = arm.rankColumn() + (arm.sharesItsRank()
                    ? " (cannot separate from rank " + arm.cannotSeparateFromRank() + ")" : "");
        }
    }
}
