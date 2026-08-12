/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import org.junit.Test;
import regdrift.CompareRegistration_;
import regdrift.RegDriftAutoSave;
import regdrift.RegDriftEntry;
import regdrift.RegDriftMacroOptions;
import regdrift.RegistrationDiagnostics_;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The words this plugin is allowed to use, checked against its own source.
 *
 * <p>This build writes more text a person reads than every stage before it put
 * together, and four words are forbidden across all of it. Three of them -
 * the superlative, its formal twin, and the word that says something is the
 * single option there is - claim a certainty that a plugin ranking somebody
 * else's registration engines on one recording does not have. The fourth names a
 * quantity nobody measured: the true registration of a real recording is
 * unknown, so what is reported is residual mismatch and a standard deviation
 * against a control, and a label claiming otherwise would be a lie with a number
 * beside it.
 *
 * <p>It is asserted here rather than left to a grep somebody remembers to run,
 * because the stage that writes the results view will write another few hundred
 * strings and nobody will re-read this rule before doing it. The forbidden words
 * are spelled here in pieces so that this test does not fail over its own
 * source.
 *
 * <p>The engine names are checked at the same time. A recommendation that
 * misspells the tool it is recommending does not read as a measurement, and
 * those strings are also what somebody types into a search box.
 */
public class DialogWordingTest {

    /**
     * The four forbidden words, assembled from pieces.
     *
     * <p>Written this way on purpose: a test that names them outright would put
     * them in a file the same rule covers, and the next person to widen the scan
     * would have to work out which occurrence was allowed.
     */
    private static final String[] FORBIDDEN = {
            "accu" + "racy",
            "b" + "est",
            "opti" + "mal",
            "on" + "ly",
    };

    /** Where the strings a person reads are written. */
    private static final String UI_PACKAGE = "src/main/java/regdrift/ui";

    /** The whole plugin, for the rules that are not about the dialogs alone. */
    private static final String SOURCE_ROOT = "src/main/java/regdrift";

    /** The single place an ampersand is allowed to appear. */
    private static final String DISPLAY_NAME = "Registration & Drift Comparison";

    @Test
    public void noStringAnybodyReadsUsesAWordTheHouseRulesForbid() throws IOException {
        for (File source : javaFilesUnder(UI_PACKAGE)) {
            String text = read(source).toLowerCase();
            for (String word : FORBIDDEN) {
                assertFalse(source.getName() + " uses a word the house rules forbid: '" + word
                                + "'. See this test's javadoc for why, and reword it.",
                        Pattern.compile("\\b" + word + "\\b").matcher(text).find());
            }
        }
    }

    @Test
    public void theScanIsLookingAtTheFilesItThinksItIs() throws IOException {
        List<File> found = javaFilesUnder(UI_PACKAGE);
        List<String> names = new ArrayList<String>();
        for (File file : found) names.add(file.getName());

        assertTrue("the dialogs have to be among the files scanned: " + names,
                names.contains("CompareDialog.java")
                        && names.contains("DiagnosticsDialog.java")
                        && names.contains("RegDriftDialog.java")
                        && names.contains("EnginesPlaceholder.java"));

        String text = read(found.get(0)).toLowerCase();
        assertTrue("the scan must be able to find a word that is really there",
                Pattern.compile("\\bthe\\b").matcher(text).find());
    }

    @Test
    public void everyEngineIsSpelledTheWayItsOwnAuthorsSpellIt() throws IOException {
        List<String> misspellings = Arrays.asList(
                "Stackreg", "StackReg_", "Turboreg", "TurboReg_",
                "Correct 3D Drift", "correct 3d drift", "Fast4dreg", "Fast4DREG",
                "Linear stack alignment with SIFT", "Linear Stack Alignment With SIFT",
                "Image stabilizer", "Image Stabiliser");

        for (File source : javaFilesUnder(SOURCE_ROOT)) {
            String text = read(source);
            for (String wrong : misspellings) {
                assertFalse(source.getName() + " spells an engine '" + wrong + "'. The name its"
                        + " own authors use is what a recommendation has to carry, and it is also"
                        + " what somebody searches for.", text.contains(wrong));
            }
        }
    }

    /**
     * The ampersand belongs to the display name and to nothing else - not a menu
     * entry, not a macro call, not a file written to disk. A shell, a macro
     * parser or a build path meets one and behaves differently.
     */
    @Test
    public void theAmpersandAppearsInTheDisplayNameAndNowhereElse() throws IOException {
        for (File source : javaFilesUnder(SOURCE_ROOT)) {
            for (String literal : stringLiteralsIn(read(source))) {
                if (literal.indexOf('&') < 0) continue;
                assertTrue(source.getName() + " has an ampersand in a string that is not the"
                                + " display name: \"" + literal + "\"",
                        literal.contains(DISPLAY_NAME));
            }
        }
    }

    @Test
    public void nothingAMacroOrTheFilesystemSeesCarriesAnAmpersand() {
        assertFalse(CompareRegistration_.COMMAND.contains("&"));
        assertFalse(RegistrationDiagnostics_.COMMAND.contains("&"));
        assertFalse(RegDriftAutoSave.TREE_FOLDER.contains("&"));
        for (String folder : RegDriftAutoSave.FOLDERS) {
            assertFalse(folder, folder.contains("&"));
        }
        for (String option : RegDriftMacroOptions.allOptionNames()) {
            assertFalse(option, option.contains("&"));
        }
        assertTrue("and the display name is where it does live",
                RegDriftEntry.DISPLAY_NAME.contains("&"));
    }

    /**
     * US English, with one exception that is not a slip: {@code localisability}
     * is a column name fixed by the contract and already released, so the words
     * checked here are ones no column carries.
     */
    @Test
    public void theDialogsAreWrittenInUsEnglish() throws IOException {
        List<String> british = Arrays.asList(
                "colour", "behaviour", "centre", "analyse", "licence", "grey",
                "stabiliser", "normalise", "visualise");

        for (File source : javaFilesUnder(UI_PACKAGE)) {
            String text = read(source).toLowerCase();
            for (String word : british) {
                assertFalse(source.getName() + " uses the British spelling '" + word + "'",
                        Pattern.compile("\\b" + word).matcher(text).find());
            }
        }
    }

    // ---------------------------------------------------------------- reading

    /** Every {@code .java} file under a folder of the repository. */
    private static List<File> javaFilesUnder(String relativePath) {
        File folder = new File(projectRoot(), relativePath);
        assertTrue("this test reads source from " + folder.getAbsolutePath()
                + ", and it is not there", folder.isDirectory());
        List<File> found = new ArrayList<File>();
        collect(folder, found);
        assertFalse("no source files under " + folder.getAbsolutePath(), found.isEmpty());
        return found;
    }

    private static void collect(File folder, List<File> into) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                collect(file, into);
            } else if (file.getName().endsWith(".java")) {
                into.add(file);
            }
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Every double-quoted literal in a source file.
     *
     * <p>Escaped quotes and escaped backslashes are honoured, so a literal
     * holding a quotation mark does not swallow the rest of the file. Comments
     * are left in: an ampersand in a comment is worth knowing about too, and a
     * comment is not where one belongs either.
     */
    private static List<String> stringLiteralsIn(String source) {
        List<String> literals = new ArrayList<String>();
        Matcher matcher = Pattern.compile("\"(([^\"\\\\\\n]|\\\\.)*)\"").matcher(source);
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }

    /** The repository root, found from where the compiled classes sit. */
    private static File projectRoot() {
        try {
            File output = new File(DialogWordingTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return output.getParentFile().getParentFile();
        } catch (java.net.URISyntaxException unreadable) {
            throw new AssertionError("could not find the source tree: " + unreadable);
        }
    }
}
