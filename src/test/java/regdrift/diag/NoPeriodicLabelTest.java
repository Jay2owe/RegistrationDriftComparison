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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T3. There is no periodic motion label, and no routine that would produce one.
 *
 * <h2>What was withdrawn, and why it is a withdrawal rather than a tuning</h2>
 *
 * <p>An earlier survey put a {@code PERIODIC} component on a motion label when a
 * peak in the spectrum of the detrended trace held enough of its power. On a
 * 24-hour baseline it reported dominant periods of exactly 16.00 h and 10.67 h,
 * and on a nine-day baseline exactly 64.0 h and 51.2 h. Those four numbers are the
 * lowest usable bins of the two transforms and nothing else: a record spanning one
 * or two cycles cannot tell a cycle from a bend in the drift, and zero-padding it
 * leaks the trend straight into the low bins.
 *
 * <p>Two guards were added to that survey - a Hann taper, and a floor on how many
 * cycles must complete inside the record - and the peak went on landing in the
 * lowest admitted bin. So the claim was <b>withdrawn</b>. The plugin does not
 * report periodicity, does not carry the routine that measured it, and does not
 * hold the label in reserve behind a flag. This is defect D3.
 *
 * <p>Stating it as a test rather than as a note in a document is the point: the
 * label is easy to want back, and a reader who wants it back has to delete an
 * assertion that explains why it is not there.
 */
public class NoPeriodicLabelTest {

    /** Every spelling of the withdrawn claim, in the form a source file would carry. */
    private static final String[] WITHDRAWN = {"periodic", "periodicity", "oscillat"};

    /** The enum has four members, and none of them is the withdrawn one. */
    @Test
    public void theComponentSetHasNoPeriodicMember() {
        MotionLabel.Component[] components = MotionLabel.Component.values();
        assertEquals(4, components.length);
        List<String> names = new ArrayList<String>();
        for (MotionLabel.Component component : components) {
            String lower = component.name().toLowerCase(Locale.US);
            for (String banned : WITHDRAWN) {
                assertFalse("MotionLabel.Component." + component.name()
                        + " is the label defect D3 withdrew", lower.contains(banned));
            }
            names.add(component.name());
            assertEquals(component.name(), component.word());
        }
        assertEquals("[DRIFT, WALK, JITTER, KNOCK]", names.toString());
    }

    /**
     * No trace produces one either - not a clean sinusoid at any period the
     * sampler could see, and not one buried in drift or in jitter.
     *
     * <p>A sinusoid is described by its drift and its excursion, like every other
     * recording. That description is less specific than "oscillating at 16 hours"
     * and it is the one the measurement supports.
     */
    @Test
    public void noTraceEverProducesAPeriodicLabel() {
        Random rng = new Random(20260813L);
        int examined = 0;
        for (int frames : new int[]{48, 96, 200}) {
            for (double period : new double[]{3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96}) {
                for (double amplitude : new double[]{0.2, 1, 6, 30}) {
                    for (double drift : new double[]{0, 0.05, 0.4}) {
                        for (double noise : new double[]{0, 0.3}) {
                            double[][] positions = new double[frames][2];
                            for (int t = 0; t < frames; t++) {
                                positions[t][0] = amplitude * Math.sin(2 * Math.PI * t / period)
                                        + drift * t + noise * rng.nextGaussian();
                                positions[t][1] = amplitude * Math.cos(2 * Math.PI * t / period)
                                        + noise * rng.nextGaussian();
                            }
                            assertPlainlyLabelled(frames, positions);
                            examined++;
                        }
                    }
                }
            }
        }
        assertTrue("the sweep must actually examine something", examined > 500);
    }

    private static void assertPlainlyLabelled(int frames, double[][] positions) {
        WindowSampler[] samplings = {
                WindowSampler.measured(), WindowSampler.everyConsecutivePair(),
        };
        for (WindowSampler sampler : samplings) {
            WindowSampler.Plan plan = sampler.plan(frames);
            int[][] pairs = plan.pairs();
            List<Estimator.Displacement> trace =
                    new ArrayList<Estimator.Displacement>(pairs.length);
            for (int i = 0; i < pairs.length; i++) {
                trace.add(Estimator.Displacement.of(
                        positions[pairs[i][1]][0] - positions[pairs[i][0]][0],
                        positions[pairs[i][1]][1] - positions[pairs[i][0]][1],
                        Estimator.Status.OK, Frames.Bin.none()));
            }
            MotionLabel label = MotionDescriptors.of(plan, trace).label();
            String rendered = label.render().toLowerCase(Locale.US);
            for (String banned : WITHDRAWN) {
                assertFalse(label.render() + " from " + plan.provenance(),
                        rendered.contains(banned));
            }
            assertFalse(label.render(), rendered.contains("period"));
            assertFalse(label.render(), rendered.contains("osc"));
            for (MotionLabel.Component component : label.components()) {
                assertTrue(component.name(),
                        component == MotionLabel.Component.DRIFT
                                || component == MotionLabel.Component.WALK
                                || component == MotionLabel.Component.JITTER
                                || component == MotionLabel.Component.KNOCK);
            }
        }
    }

    /** No method anywhere in the measurement is named for the withdrawn quantity. */
    @Test
    public void nothingInTheMeasurementMeasuresPeriodicity() throws Exception {
        for (String className : Bytecode.classesIn("regdrift")) {
            Class<?> type = Class.forName(className);
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.US);
                for (String banned : WITHDRAWN) {
                    assertFalse(className + "." + method.getName()
                            + " measures what defect D3 withdrew", name.contains(banned));
                }
            }
        }
    }

    /**
     * The word survives in the shipped source only inside comments, and one of
     * those comments records that the claim was withdrawn.
     *
     * <p>The second half matters as much as the first. A silent absence is
     * indistinguishable from an oversight, and the next person to notice that the
     * traces look rhythmic would add the label back. The comment is the reason
     * they will not.
     */
    @Test
    public void theWordSurvivesOnlyInACommentThatRecordsTheWithdrawal() throws IOException {
        File sources = new File(projectRoot(), "src/main/java");
        assertTrue("the source tree is not where this test expects it: "
                + sources.getAbsolutePath(), sources.isDirectory());
        List<File> files = new ArrayList<File>();
        collect(sources, files);
        assertFalse("no sources found to scan", files.isEmpty());

        TreeSet<String> mentioning = new TreeSet<String>();
        boolean withdrawalRecorded = false;
        for (File file : files) {
            boolean mentions = false;
            boolean records = false;
            int lineNumber = 0;
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    lineNumber++;
                    String lower = line.toLowerCase(Locale.US);
                    if (lower.contains("withdrawn") && isComment(line)) records = true;
                    boolean hit = false;
                    for (String banned : WITHDRAWN) {
                        if (lower.contains(banned)) hit = true;
                    }
                    if (!hit) continue;
                    mentions = true;
                    assertTrue(file.getName() + ":" + lineNumber + " names the label defect D3"
                                    + " withdrew outside a comment: " + line.trim(),
                            isComment(line));
                }
            } finally {
                in.close();
            }
            if (mentions) mentioning.add(file.getName());
            if (mentions && records) withdrawalRecorded = true;
        }
        assertTrue("the withdrawal must be recorded in a comment beside the label it concerns,"
                + " and these are the files that mention it at all: " + mentioning,
                withdrawalRecorded);
    }

    private static boolean isComment(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    private static void collect(File folder, List<File> into) {
        File[] entries = folder.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collect(entry, into);
            } else if (entry.getName().endsWith(".java")) {
                into.add(entry);
            }
        }
    }

    /** The repository root, two folders above {@code target/classes}. */
    private static File projectRoot() {
        return Bytecode.buildOutput().getParentFile().getParentFile();
    }
}
