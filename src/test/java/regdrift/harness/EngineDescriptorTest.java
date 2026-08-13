/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.harness;

import org.junit.Test;
import regdrift.autofix.EngineId;
import regdrift.autofix.EngineRegistry;
import sc.fiji.autofix.core.DependencySpec;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The driving half of the catalogue: one entry per engine, keyed by the same
 * constant the detecting half uses, and no second list of engines anywhere.
 *
 * <h2>The failure this class of test exists to catch</h2>
 *
 * <p>Two lists of the same ten things drift apart. It takes one engine added to
 * one of them, and then a plugin that is detected and cannot be driven, or
 * driven and reported absent. So there is one list - {@link EngineId} - and both
 * halves are asserted against it here rather than against each other.
 */
public class EngineDescriptorTest {

    /** Every catalogue constant has exactly one way to drive it recorded. */
    @Test
    public void everyEngineInTheCatalogueHasOne() {
        for (EngineId engine : EngineId.values()) {
            EngineDescriptor descriptor = EngineDescriptor.forEngine(engine);
            assertNotNull("no way to drive " + engine + " is recorded", descriptor);
            assertEquals("a descriptor must answer for the engine it was asked about",
                    engine, descriptor.id());
        }
        assertEquals("the driving half and the detecting half must list the same engines",
                EngineId.values().length, EngineDescriptor.all().size());
    }

    /** The two halves are in catalogue order, and they name the same engines. */
    @Test
    public void bothHalvesAgreeOnWhichEnginesThereAre() {
        List<EngineId> catalogued = new ArrayList<EngineId>();
        for (DependencySpec spec : EngineRegistry.specs()) catalogued.add((EngineId) spec.getId());

        List<EngineId> drivable = new ArrayList<EngineId>();
        for (EngineDescriptor descriptor : EngineDescriptor.all()) drivable.add(descriptor.id());

        assertEquals("one catalogue, in one order, read by both halves", catalogued, drivable);
    }

    /** Names, versions and files come from the catalogue, never restated here. */
    @Test
    public void everyNameAndVersionIsReadBackFromTheCatalogue() {
        for (EngineDescriptor descriptor : EngineDescriptor.all()) {
            assertEquals("the display name must be the catalogue's",
                    EngineRegistry.displayName(descriptor.id()), descriptor.displayName());
            Object version = EngineRegistry.specFor(descriptor.id()).getAttributes().get("version");
            assertEquals("the measured version must be the catalogue's",
                    version == null ? "" : version.toString(), descriptor.measuredVersion());
        }
    }

    /**
     * An engine handed no arguments stops and waits for somebody who is not
     * there, which inside a comparison is a frozen session.
     *
     * <p>ImageJ decides whether a plugin's own question window appears by
     * looking at whether arguments were supplied at all, so this is not a
     * nicety: it is the whole of what keeps an arm from hanging.
     */
    @Test
    public void everyDrivableEngineCarriesArgumentsAndAnEntry() {
        assertFalse("this build has to drive something", EngineDescriptor.drivable().isEmpty());
        for (EngineDescriptor descriptor : EngineDescriptor.drivable()) {
            assertFalse(descriptor.id() + " is drivable with no menu entry",
                    descriptor.command().trim().isEmpty());
            assertFalse(descriptor.id() + " is drivable with no arguments, and an engine handed"
                            + " none stops and waits for somebody",
                    descriptor.optionsTemplate().trim().isEmpty());
            assertFalse(descriptor.id() + " must have a real title written in, not the token",
                    descriptor.options("a working title").contains(EngineDescriptor.TITLE_TOKEN));
            assertTrue(descriptor.id() + " must say nothing about why it cannot be driven",
                    descriptor.notDrivableReason().isEmpty());
        }
    }

    /** An engine with no arm says why in a finished sentence, not a shrug. */
    @Test
    public void everyEngineWithoutAnArmSaysWhy() {
        for (EngineDescriptor descriptor : EngineDescriptor.all()) {
            if (descriptor.isDrivable()) continue;
            String why = descriptor.notDrivableReason();
            assertFalse(descriptor.id() + " has no arm and no reason given", why.trim().isEmpty());
            assertTrue(descriptor.id() + "'s reason must be a sentence: " + why, why.endsWith("."));
            assertTrue(descriptor.id() + "'s reason must name the engine: " + why,
                    why.contains(descriptor.displayName()));
        }
    }

    /**
     * The engines that call another engine while they run say so, and the one
     * they call is the one that is really needed.
     */
    @Test
    public void theEnginesThatNeedTurboRegSayThatTheyDo() {
        assertEquals("StackReg drives TurboReg for every frame",
                java.util.Arrays.asList(EngineId.TURBOREG),
                EngineDescriptor.forEngine(EngineId.STACKREG).requires());
        assertEquals("MultiStackReg drives TurboReg too",
                java.util.Arrays.asList(EngineId.TURBOREG),
                EngineDescriptor.forEngine(EngineId.MULTISTACKREG).requires());
        for (EngineDescriptor descriptor : EngineDescriptor.all()) {
            assertFalse(descriptor.id() + " must not require itself",
                    descriptor.requires().contains(descriptor.id()));
        }
    }

    /**
     * What was measured about driving an engine twice, and what was not.
     *
     * <p>Measured on 2026-08-13 in one Fiji session: every engine this build
     * drives left an empty thread group, so none is drive-once-per-session. The
     * three nobody has an install of are marked not-yet-measured, which is a
     * different statement from safe, and this asserts the difference is kept.
     */
    @Test
    public void theDriveOnceAnswersAreMeasuredAndTheUnmeasuredOnesSayThatTheyAre() {
        for (EngineDescriptor descriptor : EngineDescriptor.all()) {
            assertFalse("no engine measured on 2026-08-13 left a thread running, so none may be"
                            + " marked drive-once here. The runner marks one for the rest of the"
                            + " session if an arm really does leave a thread behind.",
                    descriptor.driveOncePerSession());
        }
        for (EngineId unmeasured : new EngineId[]{EngineId.IMAGE_STABILIZER, EngineId.FAST_4D_REG,
                EngineId.NANOJ_CORE}) {
            String note = EngineDescriptor.forEngine(unmeasured).driveNote();
            assertTrue(unmeasured + " is not installed on any computer this was measured on, so"
                            + " its note must say the question is open rather than answered: "
                            + note, note.contains("Nobody has yet driven this engine twice"));
        }
        for (EngineId measured : new EngineId[]{EngineId.STACKREG, EngineId.MULTISTACKREG,
                EngineId.CORRECT_3D_DRIFT, EngineId.LINEAR_STACK_ALIGNMENT_WITH_SIFT}) {
            assertTrue(measured + " was measured, so it must not carry the unmeasured note",
                    EngineDescriptor.forEngine(measured).driveNote().isEmpty());
        }
    }

    /**
     * Third-party command strings must stay well away from anything the shade
     * plugin rewrites, because relocation rewrites string constants that look
     * like a package being moved as well as compiled references.
     */
    @Test
    public void noEngineCommandLooksLikeAPackageThatGetsRelocated() {
        for (EngineDescriptor descriptor : EngineDescriptor.all()) {
            String text = descriptor.command() + " " + descriptor.optionsTemplate();
            assertFalse(descriptor.id() + " names something the shade plugin would rewrite: "
                            + text,
                    text.contains("sc.fiji.oc3d.core") || text.contains("sc.fiji.autofix.core"));
        }
    }

    /** An engine nobody added a descriptor for fails loudly rather than silently. */
    @Test(expected = IllegalStateException.class)
    public void anEngineWithNoDescriptorIsNotSilentlyAbsent() {
        EngineDescriptor.forEngine(null);
    }
}
