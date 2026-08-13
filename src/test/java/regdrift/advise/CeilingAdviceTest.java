/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.advise;

import org.junit.Test;
import regdrift.RegDriftMacroOptions;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T13, defect D4. That no code path anywhere switches an intensity ceiling on,
 * that the advice never travels without its warning, and that the numbers the
 * warning rests on are the numbers in the bundled files.
 *
 * <h2>Why this test is larger than the class it tests</h2>
 *
 * <p>The class is forty lines of string formatting. What it is guarding against
 * is a change somebody will make in good faith: a 90th-percentile per-frame
 * ceiling improves the benchmark's worst column by a factor of 86 and runs 35%
 * faster, and both of those are recomputed below from the bundled files, so
 * anybody who checks will find that they are true. The reason it must not become
 * a setting is a failure the benchmark <em>cannot see</em> - all three of its
 * seeds are phase contrast, and on a fluorescence recording of cell bodies the
 * same cut removes the sample rather than the artefact.
 *
 * <p>A comment saying that would be read once. So it is asserted from the
 * compiled classes instead, across every package this plugin ships, and the
 * build is what a later contributor argues with.
 */
public class CeilingAdviceTest {

    /** Method names that would mean somebody had turned the advice into a control. */
    private static final List<String> FORBIDDEN_METHODS = Collections.unmodifiableList(
            Arrays.asList("setCeiling", "applyCeiling", "enableCeiling", "setSaturationMax",
                    "setSaturationPercentile", "applyIntensityCeiling", "ceilingPercentile"));

    /** The packages the ban covers: all of them. */
    private static final List<String> EVERY_PACKAGE = Collections.unmodifiableList(
            Arrays.asList("regdrift"));

    // --------------------------------------------------- there is no such setting

    /**
     * Exit gate 5. Nothing this plugin compiles has a method that would switch a
     * ceiling on, and the scan is over compiled classes rather than over source,
     * so a name assembled at run time is caught too.
     */
    @Test
    public void noCodePathEnablesACeiling() throws Exception {
        List<String> scanned = new ArrayList<String>();
        for (String pkg : EVERY_PACKAGE) {
            for (String className : classesIn(pkg)) {
                scanned.add(className);
                Class<?> loaded = Class.forName(className, false,
                        CeilingAdviceTest.class.getClassLoader());
                for (Method method : loaded.getDeclaredMethods()) {
                    for (String forbidden : FORBIDDEN_METHODS) {
                        assertFalse(className + " has a method called " + method.getName()
                                        + ". An intensity ceiling is advice and stays advice: on a"
                                        + " recording whose sample is itself the brightest thing"
                                        + " present, the same cut removes the sample, and the"
                                        + " calibration behind it is three phase-contrast frames"
                                        + " that cannot see that failure. See defect D4.",
                                method.getName().equalsIgnoreCase(forbidden));
                    }
                }
            }
        }
        assertTrue("the scan has to be looking at the whole plugin, and saw " + scanned.size()
                + " classes", scanned.size() > 50);
    }

    /**
     * The one macro option about ceilings is a switch for the <em>advice</em>,
     * and there is no option anywhere that carries a percentile.
     */
    @Test
    public void noMacroOptionCarriesACeilingValue() {
        List<String> options = RegDriftMacroOptions.allOptionNames();
        assertTrue("the advice switch is still there", options.contains("advise_ceiling"));
        for (String option : options) {
            String lower = option.toLowerCase(Locale.ROOT);
            assertFalse("macro option '" + option + "' looks like a ceiling value. Advice never"
                            + " becomes a setting - defect D4.",
                    lower.contains("percentile") || lower.contains("saturation")
                            || lower.contains("ceiling_at") || lower.contains("intensity_max"));
        }
    }

    /**
     * {@code bright_fraction} is turned into a sentence and never into a value
     * something acts on.
     *
     * <p>The shape this looks for is the one the defect describes: a variable
     * named after a ceiling, a percentile or a saturation limit, assigned from
     * the bright share. That is the single line somebody would write on the way
     * to switching the thing on, and it is the line that must not exist
     * anywhere.
     */
    @Test
    public void brightFractionNeverBecomesASetting() throws IOException {
        Pattern becomesASetting = Pattern.compile(
                "(?i)\\b(ceiling|percentile|saturationMax|saturation_max|cutAt)\\w*\\s*=\\s*"
                        + "[^;\\n]*\\bbrightFraction\\b");
        int read = 0;
        for (File source : everyJavaSource()) {
            String text = read(source);
            if (text.contains("brightFraction") || text.contains("bright_fraction")) read++;
            assertFalse(source.getName() + " turns the bright share into a ceiling. It becomes a"
                            + " sentence and nothing else, because on a recording whose sample is"
                            + " itself the brightest thing present the same cut removes the"
                            + " sample - defect D4.",
                    becomesASetting.matcher(text).find());
        }
        assertTrue("nothing mentions the bright share at all, so this test guards nothing",
                read > 0);

        // The half of the plugin that will drive somebody else's engine is where a ceiling would
        // actually be applied, so it does not get to see the number at all.
        for (File source : everyJavaSource()) {
            if (!source.getAbsolutePath().replace('\\', '/').contains("/regdrift/harness/")) {
                continue;
            }
            String text = read(source);
            assertFalse(source.getName() + " reads the bright share. The half of this plugin that"
                            + " drives an engine is where a ceiling would be applied, and it is"
                            + " deliberately not given the number - defect D4.",
                    text.contains("brightFraction") || text.contains("bright_fraction"));
        }
    }

    // ------------------------------------------- the advice carries its warning

    /**
     * Exit gate 5, and the shape the stage file asked for: the sentence says what
     * would be excluded, and the warning is in the same string.
     */
    @Test
    public void theAdviceSaysWhatWouldGoAndWarnsInTheSameBreath() {
        String advice = CeilingAdvice.text(0.10);
        assertTrue(advice, advice.contains("would exclude"));
        assertTrue(advice, advice.contains(CeilingAdvice.CONTRAINDICATION));
        assertTrue("it names the share it measured: " + advice, advice.contains("10%"));
        assertTrue("and the percentile that would reach it: " + advice,
                advice.contains("90th percentile"));
    }

    /** Every value produces a sentence, and every sentence carries the warning. */
    @Test
    public void thereIsNoValueAtWhichTheWarningIsLeftOff() {
        double[] shares = {Double.NaN, 0.0, 1e-9, 0.0001, 0.0048, 0.005, 0.01, 0.02, 0.05, 0.10,
                0.25, 0.5, 1.0};
        for (double share : shares) {
            String advice = CeilingAdvice.text(share);
            assertFalse("bright_fraction " + share + " produced nothing", advice.trim().isEmpty());
            assertTrue("bright_fraction " + share + " lost the warning: " + advice,
                    advice.contains(CeilingAdvice.CONTRAINDICATION));
        }
    }

    /** The warning names all three ways the cut goes wrong, in the words D4 uses. */
    @Test
    public void theWarningNamesEveryWayTheCutGoesWrong() {
        String warning = CeilingAdvice.CONTRAINDICATION;
        assertTrue("it says there is no setting: " + warning,
                warning.contains("nothing in this plugin switches an intensity ceiling on"));
        assertTrue("it names the modality that inverts: " + warning,
                warning.contains("fluorescence"));
        assertTrue("it says the calibration cannot see that failure: " + warning,
                warning.contains("cannot see that failure"));
        assertTrue("and it names the shallow cuts: " + warning,
                warning.contains("99th or 99.5th percentile"));
    }

    /**
     * A bright structure too small for any measured cut to reach is told so,
     * rather than being handed a 99.5th-percentile ceiling that the sweep
     * measured as no improvement at all.
     */
    @Test
    public void aStructureTooSmallForAnyMeasuredCutIsToldSo() {
        String advice = CeilingAdvice.text(0.0048);
        assertTrue(advice, advice.contains("no improvement on cutting nothing at all"));
        assertFalse("and it is not offered as something to do", advice.contains("would exclude"));
        assertFalse("nor is it worth a line of its own",
                CeilingAdvice.worthShowing(0.0048));
        assertTrue("while a structure a measured cut does reach is",
                CeilingAdvice.worthShowing(0.10));
    }

    @Test
    public void nothingStandingOutSaysNothingStandsOut() {
        String advice = CeilingAdvice.text(0.0);
        assertTrue(advice, advice.contains("nothing an intensity ceiling would exclude"));
        assertTrue("an unmeasured share says that instead: " + CeilingAdvice.text(Double.NaN),
                CeilingAdvice.text(Double.NaN).contains("could not be measured"));
    }

    // ------------------------------------- and the numbers it rests on are real

    /**
     * The two claims the warning is built on, recomputed from the bundled files
     * rather than quoted.
     *
     * <p>One: at the 90th percentile the cut reaches the artefact and the error
     * collapses. Two: at the 99th and the 99.5th it does not reach it, and the
     * result is no improvement on cutting nothing - which is why "cut a bit, gain
     * a bit" is the wrong model and why there is no partial credit.
     */
    @Test
    public void theSweepBehindTheWarningIsInTheBundledFiles() {
        double cutNothing = meanErrorOf(CalibrationTable.BENCHMARK_FILE, "none");
        double at995 = meanErrorOf(CalibrationTable.SATURATION_FILE, "99.5");
        double at99 = meanErrorOf(CalibrationTable.SATURATION_FILE, "99.0");
        double at95 = meanErrorOf(CalibrationTable.SATURATION_FILE, "95.0");
        double at90 = meanErrorOf(CalibrationTable.SATURATION_FILE, "90.0");

        assertTrue("the unbanded arm has to be the bad one: " + cutNothing, cutNothing > 4.0);
        assertTrue("a cut that reaches the artefact collapses it: " + at90, at90 < 0.1);
        assertTrue("by a factor of tens: " + cutNothing / at90, cutNothing / at90 > 50);

        assertTrue("the 99th percentile is no improvement on cutting nothing: "
                + at99 + " against " + cutNothing, at99 >= 0.9 * cutNothing);
        assertTrue("nor is the 99.5th: " + at995 + " against " + cutNothing,
                at995 >= 0.9 * cutNothing);
        assertTrue("and half the artefact is still an order of magnitude short: " + at95,
                at95 > 10 * at90);
    }

    /**
     * And it is faster, which is the other half of why somebody would switch it
     * on. Recomputed, so the claim in the class note cannot drift from the file.
     */
    @Test
    public void theCutIsAlsoFasterAndThatIsMeasuredToo() {
        double unbanded = meanCostOf(CalibrationTable.BENCHMARK_FILE, "none");
        double at90 = meanCostOf(CalibrationTable.SATURATION_FILE, "90.0");
        assertTrue("both arms have to carry a CPU figure: " + unbanded + ", " + at90,
                unbanded > 0 && at90 > 0);
        double faster = 1 - at90 / unbanded;
        assertTrue("the measured saving is about a third: " + faster, faster > 0.30);
        assertTrue("and it is a saving, not a cost: " + faster, faster < 0.45);
    }

    // ---------------------------------------------------------------- machinery

    /**
     * The mean median error over the three seeds for the benchmark's own
     * estimator on the block condition, at one ceiling.
     *
     * <p>Read through the bundled table, which is the same evidence the shipped
     * plugin reads. The arm named here is not an installable engine and is never
     * recommended; it is the arm the ceiling sweep was run on, and it is the only
     * place in this plugin that reads one.
     */
    private static double meanErrorOf(String file, String ceiling) {
        List<CalibrationTable.Row> rows = sweepRows(file, ceiling);
        assertEquals(file + " at " + ceiling + " has to be three seeds", 3, rows.size());
        double total = 0;
        for (CalibrationTable.Row row : rows) total += row.medianErrPx();
        return total / rows.size();
    }

    private static double meanCostOf(String file, String ceiling) {
        List<CalibrationTable.Row> rows = sweepRows(file, ceiling);
        assertEquals(file + " at " + ceiling + " has to be three seeds", 3, rows.size());
        double total = 0;
        for (CalibrationTable.Row row : rows) total += row.cpuMsPerPair();
        return total / rows.size();
    }

    private static List<CalibrationTable.Row> sweepRows(String file, String ceiling) {
        List<CalibrationTable.Row> found = new ArrayList<CalibrationTable.Row>();
        for (CalibrationTable.Row row : CalibrationTable.bundled().rowsFrom(file)) {
            if (row.condition() != CalibrationTable.Condition.CHANGE_BLOCKS) continue;
            if (!"RCC".equals(row.reconciliation())) continue;
            if (!row.estimator().endsWith("Tukey+grad")) continue;
            if (!ceiling.equals(row.saturation())) continue;
            found.add(row);
        }
        return found;
    }

    private static List<String> classesIn(String packageName) {
        File output = buildOutput();
        File folder = new File(output, packageName.replace('.', '/'));
        List<String> found = new ArrayList<String>();
        collect(output, folder, found);
        Collections.sort(found);
        assertFalse("no compiled classes under " + folder.getAbsolutePath(), found.isEmpty());
        return found;
    }

    private static void collect(File output, File folder, List<String> into) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                collect(output, file, into);
            } else if (file.getName().endsWith(".class")) {
                String path = output.toURI().relativize(file.toURI()).getPath();
                into.add(path.substring(0, path.length() - ".class".length()).replace('/', '.'));
            }
        }
    }

    private static File buildOutput() {
        try {
            File output = new File(CeilingAdvice.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            assertTrue("this test reads compiled classes from a folder, and found "
                    + output.getAbsolutePath(), output.isDirectory());
            return output;
        } catch (java.net.URISyntaxException unreadable) {
            throw new AssertionError("could not find the compiled classes: " + unreadable);
        }
    }

    /** Every source file this plugin ships, so the ban above covers all of them. */
    private static List<File> everyJavaSource() {
        File root = new File(projectRoot(), "src/main/java/regdrift");
        assertTrue("this test reads source from " + root.getAbsolutePath()
                + ", and it is not there", root.isDirectory());
        List<File> found = new ArrayList<File>();
        collectSources(root, found);
        assertFalse("no source files under " + root.getAbsolutePath(), found.isEmpty());
        return found;
    }

    private static void collectSources(File folder, List<File> into) {
        File[] files = folder.listFiles();
        assertNotNull(folder.getAbsolutePath(), files);
        for (File file : files) {
            if (file.isDirectory()) {
                collectSources(file, into);
            } else if (file.getName().endsWith(".java")) {
                into.add(file);
            }
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File projectRoot() {
        try {
            File output = new File(CeilingAdviceTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return output.getParentFile().getParentFile();
        } catch (java.net.URISyntaxException unreadable) {
            throw new AssertionError("could not find the source tree: " + unreadable);
        }
    }
}
