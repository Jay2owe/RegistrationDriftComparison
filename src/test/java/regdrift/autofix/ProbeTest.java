/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.autofix;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import sc.fiji.autofix.core.DependencySpec;
import sc.fiji.autofix.core.DependencyStatus;
import sc.fiji.autofix.core.ProbeContext;
import sc.fiji.autofix.core.Probes;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Installed, absent, and the wrong version - told apart against folders this
 * test builds, on a class path that resolves nothing.
 *
 * <h2>Why the class path is emptied</h2>
 *
 * <p>Each engine is looked for in two ways: is its class loadable, and are its
 * files where they should be. A test about file versions run on an ordinary
 * class path could be answered by whichever half happened to succeed, and would
 * then pass while the file half was broken. Every context below therefore
 * carries a class loader that resolves nothing, so what is being asserted is the
 * file half and it is asserted alone.
 *
 * <h2>The three answers, and why the third one matters</h2>
 *
 * <p>"Absent" and "the wrong version is here" call for different things from a
 * person: one is an install, the other is a version that has to be set aside
 * first. A layer that reported both as "not installed" would send somebody to
 * install something they already have, watch it appear to do nothing, and leave
 * them no wiser. The detail line therefore names the file that was found as well
 * as the one that was wanted.
 */
public class ProbeTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /**
     * The engines found on the reference Fiji this catalogue was written
     * against, on 2026-08-13. Seven were present; three were not.
     */
    private static final List<EngineId> INSTALLED_ON_THE_REFERENCE_FIJI = Arrays.asList(
            EngineId.TURBOREG,
            EngineId.STACKREG,
            EngineId.MULTISTACKREG,
            EngineId.CORRECT_3D_DRIFT,
            EngineId.DESCRIPTOR_BASED_REGISTRATION,
            EngineId.REGISTER_VIRTUAL_STACK_SLICES,
            EngineId.LINEAR_STACK_ALIGNMENT_WITH_SIFT);

    /** The three that were not, and are still the three a fresh Fiji lacks. */
    private static final List<EngineId> ABSENT_FROM_THE_REFERENCE_FIJI = Arrays.asList(
            EngineId.IMAGE_STABILIZER,
            EngineId.FAST_4D_REG,
            EngineId.NANOJ_CORE);

    /**
     * The files those seven were found as, exactly as they are named on disk.
     *
     * <p>Written out rather than read back from the catalogue, so that a typo in
     * a filename shows up here as a difference instead of being asserted against
     * itself. Correct 3D drift is in the other folder because it is a script
     * carried in an archive rather than a compiled plugin, which is also why it
     * was missed by a search that looked in the plugins folder alone.
     */
    private static final List<String> FILES_ON_THE_REFERENCE_FIJI = Arrays.asList(
            "plugins/TurboReg_-2.0.1.jar",
            "plugins/StackReg_-2.0.1.jar",
            "plugins/MultiStackRegistration_-1.46.5.jar",
            "jars/Correct_3D_Drift-1.0.7.jar",
            "plugins/Descriptor_based_registration-2.1.8.jar",
            "plugins/register_virtual_stack_slices-3.0.8.jar",
            "plugins/mpicbg_-1.6.0.jar");

    private ProxySelector originalProxySelector;

    @After
    public void restoreProxySelector() {
        if (originalProxySelector != null) {
            ProxySelector.setDefault(originalProxySelector);
            originalProxySelector = null;
        }
    }

    // ------------------------------------------------------- the three answers

    @Test
    public void anEngineWhoseFilesAreAllThereReadsAsPresent() throws IOException {
        File fiji = fijiWith(EngineId.TURBOREG);

        DependencyStatus status = filesOf(EngineId.TURBOREG, fiji);

        assertTrue("TurboReg's own jar is sitting there: " + status.getDetailMessage(),
                status.isPresent());
    }

    @Test
    public void anEngineWithNoFilesReadsAsMissingAndNamesWhatItWanted() throws IOException {
        File fiji = EngineFixtures.emptyFiji(temp.getRoot(), "Fiji.app");

        DependencyStatus status = filesOf(EngineId.TURBOREG, fiji);

        assertTrue(status.getDetailMessage(), status.isMissing());
        assertTrue("the line has to name the file that was wanted: "
                        + status.getDetailMessage(),
                status.getDetailMessage().contains(pinnedFileOf(EngineId.TURBOREG)));
    }

    @Test
    public void adifferentVersionReadsAsMissingAndNamesBothFiles() throws IOException {
        File fiji = EngineFixtures.emptyFiji(temp.getRoot(), "Fiji.app");
        DependencySpec.Artifact pinned = firstArtifactOf(EngineId.TURBOREG);
        String older = pinned.getMatchPrefix() + "1.0.0.jar";
        EngineFixtures.writeFile(fiji, pinned.getFolder() + "/" + older, 2048);

        DependencyStatus status = filesOf(EngineId.TURBOREG, fiji);

        assertTrue(status.getDetailMessage(), status.isMissing());
        assertTrue("the line has to name the version that is here: "
                + status.getDetailMessage(), status.getDetailMessage().contains(older));
        assertTrue("and the version that is wanted: " + status.getDetailMessage(),
                status.getDetailMessage().contains(pinned.getExpectedFile()));
        assertFalse("a wrong version is a different situation from nothing at all",
                status.getDetailMessage().contains("MISSING"));
    }

    /**
     * An engine that came with Fiji is accepted at whatever version this Fiji
     * carries. Pinning one would report a perfectly good Fiji as broken every
     * time its own maintainers shipped an update.
     */
    @Test
    public void anEngineThatShipsWithFijiIsAcceptedAtAnyVersion() throws IOException {
        File fiji = EngineFixtures.emptyFiji(temp.getRoot(), "Fiji.app");
        DependencySpec.Artifact pinned = firstArtifactOf(EngineId.CORRECT_3D_DRIFT);
        EngineFixtures.writeFile(fiji,
                pinned.getFolder() + "/" + pinned.getMatchPrefix() + "9.9.9.jar", 2048);

        DependencyStatus status = filesOf(EngineId.CORRECT_3D_DRIFT, fiji);

        assertTrue("a later Correct 3D drift is still Correct 3D drift: "
                + status.getDetailMessage(), status.isPresent());
    }

    // ---------------------------------------------------- the engines that pair

    /**
     * StackReg drives TurboReg while it runs. Reporting it as present with
     * TurboReg absent gives a run that fails much later with a message about a
     * class nobody asked for, so the pair is looked for together and the line
     * names the half that is missing.
     */
    @Test
    public void stackRegIsNotPresentWithoutTurboReg() throws IOException {
        File fiji = EngineFixtures.emptyFiji(temp.getRoot(), "Fiji.app");
        DependencySpec.Artifact stackReg = firstArtifactOf(EngineId.STACKREG);
        EngineFixtures.writeFile(fiji,
                stackReg.getFolder() + "/" + stackReg.getExpectedFile(), 2048);

        DependencyStatus status = filesOf(EngineId.STACKREG, fiji);

        assertTrue("its own jar is here, and the engine it drives is not: "
                + status.getDetailMessage(), status.isMissing());
        assertTrue("the line has to name TurboReg: " + status.getDetailMessage(),
                status.getDetailMessage().contains(pinnedFileOf(EngineId.TURBOREG)));
    }

    @Test
    public void multiStackRegIsNotPresentWithoutTurboRegEither() throws IOException {
        File fiji = EngineFixtures.emptyFiji(temp.getRoot(), "Fiji.app");
        DependencySpec.Artifact own = firstArtifactOf(EngineId.MULTISTACKREG);
        EngineFixtures.writeFile(fiji, own.getFolder() + "/" + own.getExpectedFile(), 2048);

        DependencyStatus status = filesOf(EngineId.MULTISTACKREG, fiji);

        assertTrue(status.getDetailMessage(), status.isMissing());
        assertTrue("the line has to name TurboReg: " + status.getDetailMessage(),
                status.getDetailMessage().contains(pinnedFileOf(EngineId.TURBOREG)));
    }

    @Test
    public void bothOfThemReadAsPresentOnceTurboRegIsThere() throws IOException {
        File fiji = fijiWith(EngineId.STACKREG, EngineId.MULTISTACKREG, EngineId.TURBOREG);

        assertTrue(filesOf(EngineId.STACKREG, fiji).getDetailMessage(),
                filesOf(EngineId.STACKREG, fiji).isPresent());
        assertTrue(filesOf(EngineId.MULTISTACKREG, fiji).getDetailMessage(),
                filesOf(EngineId.MULTISTACKREG, fiji).isPresent());
    }

    // ------------------------------------------------- the whole catalogue

    /**
     * The catalogue against a folder holding what the reference Fiji held:
     * seven engines found, three absent. This is the count the build was written
     * against, asserted here so that a later change to a filename or a folder
     * shows up as a difference rather than as a row quietly reading "missing".
     */
    @Test
    public void theReferenceFijiReadsAsSevenPresentAndThreeAbsent() throws IOException {
        File fiji = fijiWith(INSTALLED_ON_THE_REFERENCE_FIJI.toArray(new EngineId[0]));

        List<EngineId> present = new ArrayList<EngineId>();
        List<EngineId> absent = new ArrayList<EngineId>();
        for (EngineId engine : EngineId.values()) {
            (filesOf(engine, fiji).isPresent() ? present : absent).add(engine);
        }

        assertEquals(INSTALLED_ON_THE_REFERENCE_FIJI, present);
        assertEquals(ABSENT_FROM_THE_REFERENCE_FIJI, absent);
    }

    /**
     * The seven names, exactly as they are on the reference Fiji. Every one of
     * those files has to be one this catalogue looks for, in the folder it looks
     * in; a probe that looked for the right name in the wrong folder would
     * report a present engine as absent, which is how Correct 3D drift came to
     * be reported as absent by an earlier search of the plugins folder.
     */
    @Test
    public void everyFileOnTheReferenceFijiIsOneTheCatalogueLooksFor() {
        List<String> wanted = new ArrayList<String>();
        for (DependencySpec spec : EngineRegistry.specs()) {
            for (DependencySpec.Artifact artifact : spec.getArtifacts()) {
                String path = artifact.getFolder() + "/" + artifact.getExpectedFile();
                if (!wanted.contains(path)) wanted.add(path);
            }
        }
        for (String found : FILES_ON_THE_REFERENCE_FIJI) {
            assertTrue("the catalogue does not look for " + found + "; it looks for " + wanted,
                    wanted.contains(found));
        }
    }

    /**
     * Image Stabilizer is published as a loose compiled file with no version in
     * its name, so there is nothing for this layer to check a download against
     * and it is not repairable from in here. It is looked for by its menu
     * command first, because its author says it may be put in a folder inside
     * plugins/ and the command table is the answer that holds wherever it went.
     */
    @Test
    public void imageStabilizerIsLookedForWithNoVersionAndIsNotRepairable() {
        DependencySpec spec = EngineRegistry.specFor(EngineId.IMAGE_STABILIZER);
        assertFalse("it is not repairable from in here", spec.isFixableInApp());
        assertEquals("there is one loose file to look for", 1, spec.getArtifacts().size());
        DependencySpec.Artifact file = spec.getArtifacts().get(0);
        assertTrue("and no address to fetch it from", file.getDownloadUrl().isEmpty());
        assertTrue("and no digest to check it against", file.getExpectedSha1().isEmpty());
        assertFalse("and no version in the name to pin",
                file.getExpectedFile().matches(".*\\d.*"));
        assertTrue("its menu command is what is asked first",
                EngineRegistry.allProbedCommands()
                        .contains(EngineRegistry.IMAGE_STABILIZER_COMMAND));
    }

    // ------------------------------------------------------- and no network

    /**
     * Reading which engines are here opens no connection.
     *
     * <p>Asserted by putting a proxy chooser in front of the whole Java process
     * and reading every engine in the catalogue: any attempt to open an http or
     * https connection consults it, so a probe that reached out would be
     * recorded. This is the promise that a dialog can be opened on a machine
     * with no network at all and behave exactly as it does on one with.
     */
    @Test
    public void readingWhichEnginesArePresentOpensNoConnection() throws IOException {
        final List<String> attempts = new ArrayList<String>();
        originalProxySelector = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                attempts.add(String.valueOf(uri));
                return Collections.singletonList(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                attempts.add(String.valueOf(uri));
            }
        });

        ProxySelector.getDefault().select(URI.create("https://sites.imagej.net/"));
        assertEquals("the recorder has to see a lookup that really happens, or everything"
                + " below is decoration", 1, attempts.size());
        attempts.clear();

        File fiji = fijiWith(INSTALLED_ON_THE_REFERENCE_FIJI.toArray(new EngineId[0]));
        ProbeContext context = EngineFixtures.contextOver(fiji);
        for (DependencySpec spec : EngineRegistry.specs()) {
            spec.probe(context);
        }
        EngineFixtures.serviceWherePresent(EngineId.TURBOREG).rows();

        assertEquals("reading which engines are here reached off this machine",
                Collections.<String>emptyList(), attempts);
    }

    /**
     * Neither the catalogue nor the panel names anything that can open a
     * connection. The single place this plugin reaches a network is inside the
     * shared machinery's download step, which a button calls and nothing else
     * does.
     */
    @Test
    public void neitherTheCatalogueNorThePanelCanOpenAConnection() throws IOException {
        for (String folder : Arrays.asList("regdrift/autofix", "regdrift/ui")) {
            for (File source : javaFilesUnder("src/main/java/" + folder)) {
                assertFalse(source.getName() + " names the Java networking package, and drawing"
                                + " the Engines section must not be able to reach a network",
                        read(source).contains("java.net"));
            }
        }
    }

    // ---------------------------------------------------------- the fixtures

    /** A Fiji.app holding every file the named engines are looked for as. */
    private File fijiWith(EngineId... engines) throws IOException {
        File fiji = EngineFixtures.emptyFiji(temp.getRoot(), "Fiji.app");
        for (EngineId engine : engines) {
            for (DependencySpec.Artifact artifact : EngineRegistry.specFor(engine).getArtifacts()) {
                EngineFixtures.writeFile(fiji,
                        artifact.getFolder() + "/" + artifact.getExpectedFile(), 2048);
            }
        }
        return fiji;
    }

    /** What the file half of an engine's probe says about a folder. */
    private static DependencyStatus filesOf(EngineId engine, File fiji) {
        DependencySpec spec = EngineRegistry.specFor(engine);
        return Probes.artifactProbe(spec.getArtifacts(), spec.getArtifactIgnorePrefixes())
                .probe(EngineFixtures.contextOver(fiji));
    }

    private static DependencySpec.Artifact firstArtifactOf(EngineId engine) {
        return EngineRegistry.specFor(engine).getArtifacts().get(0);
    }

    private static String pinnedFileOf(EngineId engine) {
        return firstArtifactOf(engine).getExpectedFile();
    }

    private static List<File> javaFilesUnder(String relativePath) {
        File folder = new File(projectRoot(), relativePath);
        assertTrue("this test reads source from " + folder.getAbsolutePath()
                + ", and it is not there", folder.isDirectory());
        List<File> found = new ArrayList<File>();
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".java")) found.add(file);
            }
        }
        assertFalse("no source files under " + folder.getAbsolutePath(), found.isEmpty());
        return found;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File projectRoot() {
        try {
            File output = new File(ProbeTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return output.getParentFile().getParentFile();
        } catch (java.net.URISyntaxException unreadable) {
            throw new AssertionError("could not find the source tree: " + unreadable);
        }
    }
}
