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
import regdrift.advise.Recipe;
import regdrift.autofix.EngineId;
import regdrift.autofix.EngineRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The line somebody copies out of the recommendation and the command an arm
 * really runs have to be the same command.
 *
 * <h2>Why this is worth a test of its own</h2>
 *
 * <p>The plugin makes a specific promise: the arm it ran for you is the thing
 * this macro line does. If those two drift apart, somebody pastes the line, gets
 * a different result from the one in the table, and has no way of telling which
 * of the two was wrong. Nothing in the compiler notices - both are strings, both
 * still compile, and both still run.
 *
 * <p>StackReg is the one to watch. Its entry class ends in an underscore, ImageJ
 * turns each underscore in a class name into a space when it builds the menu, so
 * its command ends in a space - and a line that dropped that space would find no
 * command at all. It has been dropped by hand before.
 */
public class RecipeMatchesTheHarnessTest {

    /** Every engine with an arm: the recipe quotes the command the arm runs. */
    @Test
    public void everyArmsCommandIsTheCommandTheRecipeQuotes() {
        for (EngineDescriptor descriptor : EngineDescriptor.drivable()) {
            String quoted = "\"" + descriptor.command() + "\"";
            assertTrue(descriptor.displayName() + ": the copyable line must run the same command"
                            + " the arm runs. The arm runs " + quoted + " and the line is "
                            + Recipe.forEngine(descriptor.id()).macroLine(),
                    Recipe.forEngine(descriptor.id()).macroLine().contains(quoted));
        }
    }

    /** The trailing space in StackReg's command survives in both places. */
    @Test
    public void stackRegKeepsItsTrailingSpaceEverywhere() {
        EngineDescriptor stackReg = EngineDescriptor.forEngine(EngineId.STACKREG);
        assertEquals("ImageJ turns the underscore in StackReg_ into a space, so the command ends"
                + " in one and a line without it finds nothing", "StackReg ", stackReg.command());
        assertTrue("the copyable line must carry it too: "
                        + Recipe.forEngine(EngineId.STACKREG).macroLine(),
                Recipe.forEngine(EngineId.STACKREG).macroLine().contains("\"StackReg \""));
    }

    /**
     * The commands the catalogue detects an engine by are the commands the arm
     * drives it with.
     *
     * <p>One spelling asked twice: a command wrong in the catalogue reports an
     * engine as absent on a computer that has it, and the same command wrong in
     * the harness fails to drive an engine the catalogue has already said is
     * there.
     */
    @Test
    public void theEnginesFoundByCommandAreDrivenByTheSameCommand() {
        assertEquals(EngineRegistry.CORRECT_3D_DRIFT_COMMAND,
                EngineDescriptor.forEngine(EngineId.CORRECT_3D_DRIFT).command());
        assertEquals(EngineRegistry.SIFT_COMMAND,
                EngineDescriptor.forEngine(EngineId.LINEAR_STACK_ALIGNMENT_WITH_SIFT).command());
        assertEquals(EngineRegistry.IMAGE_STABILIZER_COMMAND,
                EngineDescriptor.forEngine(EngineId.IMAGE_STABILIZER).command());
    }

    /**
     * An engine this build drives no arm for still gets a recipe, because a
     * person can run it themselves from its own menu entry - and one whose menu
     * entry has not been read says so rather than printing a plausible path.
     */
    @Test
    public void anEngineWithNoArmStillTellsSomebodyWhereToFindIt() {
        for (EngineDescriptor descriptor : EngineDescriptor.all()) {
            if (descriptor.isDrivable()) continue;
            String path = Recipe.forEngine(descriptor.id()).menuPath();
            assertFalse(descriptor.displayName() + " has no arm and no menu path either",
                    path.trim().isEmpty());
        }
        assertEquals("NanoJ-Core's entry has not been read, and says so rather than guessing",
                Recipe.MENU_PATH_NOT_READ, Recipe.forEngine(EngineId.NANOJ_CORE).menuPath());
    }
}
