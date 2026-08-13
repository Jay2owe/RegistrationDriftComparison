/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.autofix;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import regdrift.ui.RegDriftDialog;
import sc.fiji.autofix.core.AbstractJarDependencyFixer;
import sc.fiji.autofix.core.DependencyFixResult;
import sc.fiji.autofix.core.DependencyServiceCore;
import sc.fiji.autofix.core.DependencySpec;
import sc.fiji.autofix.core.DependencyStatus;
import sc.fiji.autofix.core.ProbeContext;
import sc.fiji.autofix.core.Probes;
import sc.fiji.autofix.core.ProgressCallback;
import sc.fiji.autofix.core.Sizes;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T17. The rules the engine catalogue has to satisfy before anybody is shown a
 * button, and the two failures a download has to survive.
 *
 * <h2>What a spec has to have</h2>
 *
 * <p>Three of these are the difference between a helpful button and a harmful
 * one. A spec with no way to detect its engine reports the same thing forever. A
 * spec with neither a repair nor a stated reason leaves a row that says an
 * engine is absent and offers nothing at all. A spec offering a download with no
 * size lets somebody start a seven-megabyte fetch believing it is small.
 *
 * <h2>What a download has to survive</h2>
 *
 * <p>Two failures, both tested here against a fetch from a local file so that no
 * test needs a network.
 *
 * <ul>
 *   <li><b>The file is not what it should be.</b> The digest is checked before
 *       the file is put in place, so a mismatch leaves nothing behind at all -
 *       not the file, not the part-written copy - and says which digest was
 *       wanted and which arrived.</li>
 *   <li><b>The same repair is run again.</b> A person whose download failed
 *       presses the button again; that is the first thing anybody does. The
 *       second attempt has to leave the folder exactly as the first did, with no
 *       part-written file and no second copy of an older version renamed out of
 *       the way.</li>
 * </ul>
 */
public class AutofixSpecTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /** Where the catalogue's own text lives, for the wording checks. */
    private static final String CATALOGUE_SOURCE =
            "src/main/java/regdrift/autofix/EngineRegistry.java";

    /** The whole plugin, for the rule about the ImageJ updater. */
    private static final String SOURCE_ROOT = "src/main/java";

    /** Assembled in pieces so this file does not fail the check it performs. */
    private static final String[] FORBIDDEN = {
            "accu" + "racy", "b" + "est", "opti" + "mal", "on" + "ly",
    };

    // ------------------------------------------------------- the spec itself

    @Test
    public void everyEngineHasExactlyOneEntryAndTheOrderIsTheEnumsOrder() {
        List<DependencySpec> specs = EngineRegistry.specs();
        assertEquals("every engine gets one entry and no engine gets two",
                EngineId.values().length, specs.size());
        for (int i = 0; i < specs.size(); i++) {
            assertEquals("the catalogue is shown in the order the engines are declared",
                    EngineId.values()[i], specs.get(i).getId());
        }
    }

    @Test
    public void everySpecHasAProbe() {
        ProbeContext nothing = EngineFixtures.contextOver(null);
        for (DependencySpec spec : EngineRegistry.specs()) {
            assertNotNull(spec.getDisplayName() + " has no probe object at all",
                    spec.getProbe());
            DependencyStatus status = spec.probe(nothing);
            assertNotNull(spec.getDisplayName() + " probed to nothing", status);
            assertFalse(spec.getDisplayName() + " is carrying the placeholder probe, which reports"
                            + " the same thing forever: " + status.getDetailMessage(),
                    status.getDetailMessage().contains("No dependency probe configured"));
        }
    }

    @Test
    public void everySpecHasEitherARepairOrAStatedReasonWhyNot() {
        for (DependencySpec spec : EngineRegistry.specs()) {
            boolean repairable = spec.isFixableInApp()
                    && AutofixService.defaultFixers().containsKey(spec.getId());
            boolean explained = !spec.getNonFixableReason().trim().isEmpty();
            assertTrue(spec.getDisplayName() + " offers no repair and gives no reason, so its row"
                    + " would say an engine is absent and leave a person with nothing to do",
                    repairable || explained);
            if (!spec.isFixableInApp()) {
                assertTrue(spec.getDisplayName() + "'s reason has to name a page or a menu item"
                                + " somebody can act on: " + spec.getNonFixableReason(),
                        spec.getNonFixableReason().contains("http")
                                || spec.getNonFixableReason().contains("Update..."));
            }
        }
    }

    @Test
    public void noRepairableSpecHidesWhatItWouldDownload() {
        for (DependencySpec spec : EngineRegistry.specs()) {
            if (!spec.isFixableInApp()) continue;
            long size = spec.getApproxDownloadSizeBytes();
            assertTrue(spec.getDisplayName() + " offers a download with no size stated", size > 0L);
            String caption = spec.formatButtonLabel(DependencyStatus.missing("absent"));
            assertNotNull(spec.getDisplayName() + " offers a repair with no button caption",
                    caption);
            assertTrue("the size a person reads before pressing has to be in the caption: "
                            + caption,
                    caption.contains(Sizes.formatApproxSize(size)));
        }
    }

    /**
     * A repairable spec fetches files by address and checks each one against a
     * digest measured from that address. Either missing turns the repair into an
     * invitation to install whatever happens to be served, which is worse than
     * no button.
     */
    @Test
    public void everyFileARepairWouldFetchHasAnAddressAndADigest() {
        for (DependencySpec spec : EngineRegistry.specs()) {
            if (!spec.isFixableInApp()) continue;
            assertFalse(spec.getDisplayName() + " is repairable and names no files",
                    spec.getArtifacts().isEmpty());
            for (DependencySpec.Artifact artifact : spec.getArtifacts()) {
                String what = spec.getDisplayName() + " / " + artifact.getExpectedFile();
                assertTrue(what + " has no download address", !artifact.getDownloadUrl().isEmpty());
                assertTrue(what + " is fetched over something other than https: "
                                + artifact.getDownloadUrl(),
                        artifact.getDownloadUrl().startsWith("https://"));
                assertEquals(what + " has no measured digest, or one of the wrong length",
                        40, artifact.getExpectedSha1().length());
                assertTrue(what + "'s digest is not hexadecimal: " + artifact.getExpectedSha1(),
                        artifact.getExpectedSha1().matches("[0-9a-f]{40}"));
            }
        }
    }

    /**
     * An engine that arrived with Fiji gets no button. A button that fetched it
     * would fetch a piece of Fiji, and one that did nothing would be worse than
     * no button at all.
     */
    @Test
    public void enginesThatShipWithFijiOfferNoRepair() {
        List<EngineId> bundled = Arrays.asList(
                EngineId.CORRECT_3D_DRIFT, EngineId.DESCRIPTOR_BASED_REGISTRATION,
                EngineId.REGISTER_VIRTUAL_STACK_SLICES,
                EngineId.LINEAR_STACK_ALIGNMENT_WITH_SIFT);
        for (EngineId engine : bundled) {
            DependencySpec spec = EngineRegistry.specFor(engine);
            assertFalse(spec.getDisplayName() + " ships with Fiji and must offer no repair",
                    spec.isFixableInApp());
            assertFalse(spec.getDisplayName() + " ships with Fiji and must have no repair path",
                    AutofixService.defaultFixers().containsKey(engine));
            assertTrue(spec.getDisplayName() + "'s reason has to say that it came with Fiji",
                    spec.getNonFixableReason().contains("part of the Fiji download"));
        }
    }

    /**
     * The terms of use were read off a page, on a stated day, for every engine.
     * An engine whose terms nobody checked gets no button: not having checked is
     * a reason to send somebody to a page, never a reason to try.
     */
    @Test
    public void everyEngineRecordsItsTermsAndTheDayTheyWereRead() {
        for (DependencySpec spec : EngineRegistry.specs()) {
            String license = spec.attribute("license", String.class);
            assertNotNull(spec.getDisplayName() + " records no terms of use", license);
            assertFalse(spec.getDisplayName() + " records empty terms of use", license.isEmpty());
            assertEquals(spec.getDisplayName() + " does not say when its terms were read",
                    EngineRegistry.CHECKED_ON,
                    spec.attribute("licenseCheckedOn", String.class));
            assertNotNull(spec.getDisplayName() + " names no page to read about it",
                    spec.attribute("page", String.class));
        }
    }

    /**
     * The engines this build fetches, and the terms that let it. Written out as
     * a list rather than derived, so that making an engine repairable is a
     * decision somebody has to record here as well as in the catalogue.
     */
    @Test
    public void theEnginesThisBuildFetchesAreTheOnesWhoseTermsSaySoPlainly() {
        Set<EngineId> repairable = new HashSet<EngineId>();
        for (DependencySpec spec : EngineRegistry.specs()) {
            if (spec.isFixableInApp()) repairable.add((EngineId) spec.getId());
        }
        assertEquals(new HashSet<EngineId>(Arrays.asList(
                        EngineId.TURBOREG, EngineId.STACKREG,
                        EngineId.FAST_4D_REG, EngineId.NANOJ_CORE)),
                repairable);

        for (EngineId engine : repairable) {
            String license = EngineRegistry.specFor(engine).attribute("license", String.class);
            assertTrue(EngineRegistry.displayName(engine) + " is fetched under terms that do not"
                            + " plainly permit it: " + license,
                    license.contains("General Public License") || license.contains("MIT"));
        }
    }

    /**
     * StackReg and MultiStackReg both call TurboReg while they run. Reporting
     * either as present with TurboReg absent produces a run that fails later
     * with a message about a class nobody asked for, so both are detected
     * together with it and both say so.
     */
    @Test
    public void theEnginesThatDriveTurboRegAreCheckedTogetherWithIt() {
        for (EngineId engine : Arrays.asList(EngineId.STACKREG, EngineId.MULTISTACKREG)) {
            DependencySpec spec = EngineRegistry.specFor(engine);
            assertEquals(spec.getDisplayName() + " does not record that it needs TurboReg",
                    EngineId.TURBOREG.name(), spec.attribute("requires", String.class));

            List<String> files = new ArrayList<String>();
            for (DependencySpec.Artifact artifact : spec.getArtifacts()) {
                files.add(artifact.getExpectedFile());
            }
            assertTrue(spec.getDisplayName() + " is detected without looking for TurboReg: "
                    + files, files.contains(turboRegFile()));
        }
    }

    @Test
    public void aNameSomebodyTypesFindsTheEngineItNames() {
        for (EngineId engine : EngineId.values()) {
            String name = EngineRegistry.displayName(engine);
            assertEquals(engine, EngineRegistry.byDisplayName(name));
            assertEquals("a name typed in the wrong case still names the engine",
                    engine, EngineRegistry.byDisplayName(name.toUpperCase(Locale.ROOT)));
            assertEquals(engine, EngineRegistry.byDisplayName("  " + name + " "));
        }
        assertNull(EngineRegistry.byDisplayName("something nobody wrote"));
        assertNull(EngineRegistry.byDisplayName(""));
        assertNull(EngineRegistry.byDisplayName(null));
    }

    @Test
    public void theCatalogueNamesTheTwoThingsARunDoesWithAnEngine() {
        for (DependencySpec spec : EngineRegistry.specs()) {
            assertEquals(spec.getDisplayName() + " names features the dialog does not offer",
                    Arrays.asList(RegDriftDialog.LABEL_COMPARE, RegDriftDialog.LABEL_APPLY),
                    spec.getAffectedFeatures());
        }
    }

    /**
     * A third-party class is found by its name at run time, and shading rewrites
     * any text that looks like a package this build moves. A class name that
     * resembled one would be quietly rewritten and the lookup would fail against
     * a class that is sitting there.
     */
    @Test
    public void noProbedClassNameLooksLikeAPackageThisBuildMoves() {
        for (String className : EngineRegistry.allProbedClassNames()) {
            assertFalse(className + " starts with a package this build relocates",
                    className.startsWith("sc.fiji.autofix")
                            || className.startsWith("sc.fiji.oc3d")
                            || className.startsWith("regdrift."));
        }
    }

    // ----------------------------------------------------------- the wording

    @Test
    public void nothingTheCatalogueSaysUsesAWordTheHouseRulesForbid() throws IOException {
        for (String literal : stringLiteralsIn(read(new File(projectRoot(), CATALOGUE_SOURCE)))) {
            String text = literal.toLowerCase(Locale.ROOT);
            for (String word : FORBIDDEN) {
                assertFalse("the catalogue says '" + word + "' in: " + literal,
                        Pattern.compile("\\b" + word + "\\b").matcher(text).find());
            }
        }
    }

    /**
     * Reaching for ImageJ's own updater is the obvious way to make this layer
     * shorter, and it would drag a large stack of libraries into a jar whose
     * whole point is that it has none.
     */
    @Test
    public void nothingInThisPluginReachesForTheImageJUpdater() throws IOException {
        String updater = "net.imagej" + ".updater";
        for (File source : javaFilesUnder(SOURCE_ROOT)) {
            assertFalse(source.getName() + " references the ImageJ updater",
                    read(source).contains(updater));
        }
        assertFalse("and this test may not be the thing a search for it finds",
                read(new File(projectRoot(), "src/test/java/regdrift/autofix/AutofixSpecTest.java"))
                        .contains(updater));
    }

    // --------------------------------------------------- a download that fails

    /**
     * A file whose digest does not match is never put in place, and the
     * part-written copy goes with it. The message names both digests, because
     * "it did not work" leaves somebody with nothing to search for.
     */
    @Test
    public void aWrongDigestAbortsTheRepairAndLeavesNothingBehind() throws Exception {
        File fiji = EngineFixtures.emptyFiji(temp.getRoot(), "Fiji.app");
        File served = EngineFixtures.writeFile(temp.getRoot(), "served/engine.jar", 2048);
        String wrongDigest = "0000000000000000000000000000000000000000";
        DependencySpec spec = pinnedTo(served, wrongDigest);

        DependencyFixResult result = new LocalFixer(fiji)
                .apply(spec, DependencyServiceCore.DialogAction.AUTO_FIX, ProgressCallback.NONE);

        assertTrue("a repair that fetched the wrong file must not report success",
                result.wasAttempted() && !result.isSuccess());
        String message = result.getMessage();
        assertTrue("the message has to say what went wrong: " + message,
                message.contains("SHA-1 mismatch"));
        assertTrue("the message has to name the digest that was wanted: " + message,
                message.contains(wrongDigest));
        assertTrue("the message has to name the digest that arrived: " + message,
                message.contains(sha1Of(served)));

        List<String> left = enginesJarsIn(fiji);
        assertEquals("a failed repair must leave nothing behind at all: " + left,
                Collections.<String>emptyList(), left);
    }

    /**
     * The same button pressed twice after a failure leaves the same folder. In
     * particular the older version that was renamed out of the way the first
     * time is not renamed a second time, which would leave two copies of it
     * under two dated names.
     */
    @Test
    public void repeatingAFailedRepairLeavesTheSameFolderAsTheFirstAttempt() throws Exception {
        File fiji = EngineFixtures.emptyFiji(temp.getRoot(), "Fiji.app");
        EngineFixtures.writeFile(fiji, "plugins/Engine_-1.0.0.jar", 2048);
        File served = EngineFixtures.writeFile(temp.getRoot(), "served/engine.jar", 2048);
        DependencySpec spec = pinnedTo(served, "0000000000000000000000000000000000000000");
        AbstractJarDependencyFixer fixer = new LocalFixer(fiji);

        fixer.apply(spec, DependencyServiceCore.DialogAction.AUTO_FIX, ProgressCallback.NONE);
        List<String> afterFirst = enginesJarsIn(fiji);
        DependencyFixResult second = fixer.apply(spec,
                DependencyServiceCore.DialogAction.AUTO_FIX, ProgressCallback.NONE);
        List<String> afterSecond = enginesJarsIn(fiji);

        assertEquals("pressing the button again must leave the folder as it was: "
                + afterFirst + " then " + afterSecond, afterFirst, afterSecond);
        assertEquals("the older version is set aside once, under one dated name",
                1, countMatching(afterSecond, ".disabled-"));
        assertEquals("and it is set aside, never deleted",
                Collections.<String>emptyList(), namesEnding(afterSecond, ".download"));
        assertFalse("the second attempt must not claim success either", second.isSuccess());
    }

    /**
     * The same repair against a file whose digest does match installs it, which
     * is what makes the two failures above failures rather than the only
     * behavior this code has.
     */
    @Test
    public void aMatchingDigestInstallsTheFileAndSetsTheOldVersionAside() throws Exception {
        File fiji = EngineFixtures.emptyFiji(temp.getRoot(), "Fiji.app");
        EngineFixtures.writeFile(fiji, "plugins/Engine_-1.0.0.jar", 2048);
        File served = EngineFixtures.writeFile(temp.getRoot(), "served/engine.jar", 2048);
        DependencySpec spec = pinnedTo(served, sha1Of(served));

        new LocalFixer(fiji).apply(spec,
                DependencyServiceCore.DialogAction.AUTO_FIX, ProgressCallback.NONE);

        List<String> left = enginesJarsIn(fiji);
        assertTrue("the pinned version has to arrive: " + left,
                left.contains("Engine_-2.0.0.jar"));
        assertEquals("the older version is set aside, never deleted: " + left,
                1, countMatching(left, "Engine_-1.0.0.jar.disabled-"));
        assertEquals("and nothing part-written is left: " + left,
                Collections.<String>emptyList(), namesEnding(left, ".download"));
    }

    // ---------------------------------------------------------- the fixtures

    /** A spec that fetches one file from a local address, under a stated digest. */
    private static DependencySpec pinnedTo(File served, String digest) throws IOException {
        DependencySpec.Artifact artifact = new DependencySpec.Artifact(
                "Engine", "Engine_-2.0.0.jar", "Engine_-", "plugins",
                served.toURI().toURL().toString(), digest, false);
        List<DependencySpec.Artifact> artifacts = Collections.singletonList(artifact);
        return DependencySpec.builder(EngineId.TURBOREG, "Engine")
                .probe(Probes.artifactProbe(artifacts, Collections.<String>emptyList()))
                .artifacts(artifacts)
                .approxDownloadSizeBytes(2048L)
                .restartRequired(true)
                .fixableInApp(true)
                .fixButtonLabelTemplate("Install Engine%s")
                .build();
    }

    /** The ordinary jar repair, pointed at a temporary folder rather than Fiji. */
    private static final class LocalFixer extends AbstractJarDependencyFixer {

        private final File fijiDir;

        LocalFixer(File fijiDir) {
            super(AutofixService.PRODUCT, 1, "Engine is in place.", "Engine install finished.");
            this.fijiDir = fijiDir;
        }

        @Override
        protected File getFijiDir() {
            return fijiDir;
        }
    }

    /** Everything in the plugins folder that came from the repair under test. */
    private static List<String> enginesJarsIn(File fiji) {
        List<String> found = new ArrayList<String>();
        for (String name : EngineFixtures.listing(new File(fiji, "plugins"))) {
            if (name.startsWith("Engine_")) found.add(name);
        }
        return found;
    }

    private static int countMatching(List<String> names, String fragment) {
        int count = 0;
        for (String name : names) {
            if (name.contains(fragment)) count++;
        }
        return count;
    }

    private static List<String> namesEnding(List<String> names, String suffix) {
        List<String> found = new ArrayList<String>();
        for (String name : names) {
            if (name.endsWith(suffix)) found.add(name);
        }
        return found;
    }

    private static String turboRegFile() {
        return EngineRegistry.specFor(EngineId.TURBOREG)
                .getArtifacts().get(0).getExpectedFile();
    }

    private static String sha1Of(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
        byte[] bytes = digest.digest(Files.readAllBytes(file.toPath()));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xff);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- reading

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
        assertTrue("expected a source file at " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static List<String> stringLiteralsIn(String source) {
        List<String> literals = new ArrayList<String>();
        Matcher matcher = Pattern.compile("\"(([^\"\\\\\\n]|\\\\.)*)\"").matcher(source);
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        assertFalse("the literal scan found nothing, so it is asserting over nothing",
                literals.isEmpty());
        return literals;
    }

    private static File projectRoot() {
        try {
            File output = new File(AutofixSpecTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return output.getParentFile().getParentFile();
        } catch (java.net.URISyntaxException unreadable) {
            throw new AssertionError("could not find the source tree: " + unreadable);
        }
    }
}
