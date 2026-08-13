/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Test;
import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineFixtures;
import regdrift.autofix.EngineId;
import regdrift.autofix.EngineRegistry;
import regdrift.Cancellation;
import regdrift.harness.EngineDescriptor;
import regdrift.harness.EngineRunner;
import sc.fiji.autofix.core.DependencyFixResult;
import sc.fiji.autofix.core.DependencyServiceCore;
import sc.fiji.autofix.core.Sizes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the Engines section puts in front of somebody, built and read without a
 * window being put on a screen.
 *
 * <p>Every answer here is driven from a fixed set of engine states rather than
 * from whatever this computer has installed, so the assertions say the same
 * thing on every machine.
 *
 * <p>What is left for a person at a real Fiji: that the section is legible, that
 * a button is where the eye expects it, and that pressing one fetches a file and
 * reports what it did. This test settles what the section <em>says</em>.
 */
public class EnginePanelTest {

    /** An engine that is fetched from here, and an engine that is not. */
    private static final EngineId FETCHABLE = EngineId.STACKREG;
    private static final EngineId NOT_FETCHABLE = EngineId.MULTISTACKREG;
    private static final EngineId SHIPS_WITH_FIJI = EngineId.CORRECT_3D_DRIFT;

    /**
     * The threads the fixture below starts on purpose. This test class releases
     * them; the plugin never does, because reporting a thread and leaving it
     * alone is the behavior being described here.
     */
    private static final List<Thread> LEFT_RUNNING = new ArrayList<Thread>();

    @After
    public void releaseTheFixtureThreads() {
        for (Thread thread : LEFT_RUNNING) thread.interrupt();
        LEFT_RUNNING.clear();
    }

    // ------------------------------------------------------------- the shape

    @Test
    public void theSectionDrawsOneLinePerEngineInCatalogueOrder() {
        EnginePanel panel = new EnginePanel(EngineFixtures.serviceWherePresent());
        panel.render(new DialogForm("test"));

        assertEquals(Arrays.asList(EngineId.values()), panel.engines());
    }

    @Test
    public void theSectionCarriesItsHeadingAndItsTwoColumnTitles() {
        DialogForm form = new DialogForm("test");
        new EnginePanel(EngineFixtures.serviceWherePresent()).render(form);

        assertTrue(form.headers().contains(EnginePanel.HEADING));
        assertFalse(EnginePanel.NAME_HEADING.isEmpty());
        assertFalse(EnginePanel.STATUS_HEADING.isEmpty());
    }

    @Test
    public void aPresentEngineAndAnAbsentOneDoNotReadTheSame() {
        EnginePanel panel = new EnginePanel(EngineFixtures.serviceWherePresent(FETCHABLE));
        panel.render(new DialogForm("test"));

        List<String> shown = panel.statuses();
        String forPresent = shown.get(indexOf(FETCHABLE));
        String forAbsent = shown.get(indexOf(NOT_FETCHABLE));

        assertFalse("an engine that is here and one that is not have to read differently",
                forPresent.equals(forAbsent));
        assertFalse(forPresent.isEmpty());
        assertFalse(forAbsent.isEmpty());
    }

    // ------------------------------------------------------------ the buttons

    @Test
    public void anAbsentEngineThisBuildFetchesOffersAButtonCarryingItsSize() {
        DependencyServiceCore.DialogRow row = rowFor(FETCHABLE,
                EngineFixtures.serviceWherePresent());

        List<String> captions = EnginePanel.buttonLabelsOf(row);

        assertEquals("one button, not a choice of several: " + captions, 1, captions.size());
        assertTrue("the caption has to name the engine: " + captions.get(0),
                captions.get(0).contains(EngineRegistry.displayName(FETCHABLE)));
        assertTrue("and say what it weighs before anybody presses it: " + captions.get(0),
                captions.get(0).contains(Sizes.formatApproxSize(
                        EngineRegistry.specFor(FETCHABLE).getApproxDownloadSizeBytes())));
        assertTrue("a row with a button needs no sentence in its place",
                EnginePanel.reasonOf(row).isEmpty());
    }

    @Test
    public void anAbsentEngineThisBuildDoesNotFetchShowsTheReasonInsteadOfAButton() {
        DependencyServiceCore.DialogRow row = rowFor(NOT_FETCHABLE,
                EngineFixtures.serviceWherePresent());

        assertEquals("there is nothing to press on a row nobody can repair from here",
                new ArrayList<String>(), EnginePanel.buttonLabelsOf(row));
        String reason = EnginePanel.reasonOf(row);
        assertFalse("a row with no button has to say why", reason.isEmpty());
        assertTrue("and name somewhere to go: " + reason, reason.contains("http"));
    }

    @Test
    public void anAbsentEngineThatShipsWithFijiGetsNoButtonEither() {
        DependencyServiceCore.DialogRow row = rowFor(SHIPS_WITH_FIJI,
                EngineFixtures.serviceWherePresent());

        assertEquals("a button that fetched a piece of Fiji, or did nothing, is worse than none",
                new ArrayList<String>(), EnginePanel.buttonLabelsOf(row));
        assertTrue(EnginePanel.reasonOf(row).contains("part of the Fiji download"));
    }

    @Test
    public void aPresentEngineOffersACheckRatherThanAnInstall() {
        DependencyServiceCore.DialogRow row = rowFor(FETCHABLE,
                EngineFixtures.serviceWherePresent(FETCHABLE));

        List<String> captions = EnginePanel.buttonLabelsOf(row);

        assertEquals(Arrays.asList("Verify"), captions);
        assertTrue("an engine that is here needs no sentence about how to get it",
                EnginePanel.reasonOf(row).isEmpty());
    }

    // ------------------------------------- what a comparison would do next

    /**
     * An engine that a comparison drove and that left threads running says so on
     * its row, before somebody presses Compare and wonders why it is absent from
     * the table.
     *
     * <p>Nothing was stopped and nothing was closed when it happened, which is
     * why restarting Fiji is the honest answer rather than something the plugin
     * could do for them.
     */
    @Test
    public void anEngineThatLeftThreadsRunningSaysSoOnItsRow() {
        EngineRunner runner = leakingRunnerThatHasDriven(FETCHABLE);
        DependencyServiceCore.DialogRow row =
                rowFor(FETCHABLE, EngineFixtures.serviceWherePresent(FETCHABLE));

        String note = EnginePanel.driveNoteOf(row, runner);

        assertTrue("the row has to say the engine is not driven again: " + note,
                note.contains("does not drive it again"));
        assertTrue("and name what it left running, not just that it left something: " + note,
                note.contains("a-plugin-that-did-not-tidy-up"));
        assertTrue("and what to do about it: " + note, note.contains("Restart Fiji"));
        assertTrue("and name the engine: " + note,
                note.contains(EngineRegistry.displayName(FETCHABLE)));
    }

    /** An engine nothing has driven says nothing, rather than reassuring somebody. */
    @Test
    public void anEngineNothingHasDrivenSaysNothing() {
        DependencyServiceCore.DialogRow row =
                rowFor(FETCHABLE, EngineFixtures.serviceWherePresent(FETCHABLE));
        assertEquals("every engine measured so far can be driven twice, so there is nothing to"
                        + " say here and nothing is said", "",
                EnginePanel.driveNoteOf(row, new EngineRunner(everythingIsHere(), nothingRuns())));
    }

    /** An engine that is not here says nothing about driving it either. */
    @Test
    public void anAbsentEngineIsNotToldHowOftenItCouldBeDriven() {
        DependencyServiceCore.DialogRow row =
                rowFor(FETCHABLE, EngineFixtures.serviceWherePresent());
        assertEquals("a row whose whole message is that there is nothing to drive does not also"
                        + " discuss how often it could be driven", "",
                EnginePanel.driveNoteOf(row, leakingRunnerThatHasDriven(FETCHABLE)));
    }

    // -------------------------------------------- before anything has looked

    /**
     * Reading ten engines off a full Fiji install takes seconds, so the section
     * appears first and fills in afterwards. While it is finding out, every row
     * says it is being checked - not that the engine is absent. A row claiming
     * an engine is missing while nothing has looked for it would send somebody
     * off to reinstall software already sitting on their computer.
     */
    @Test
    public void beforeAnythingHasLookedEveryRowSaysItIsBeingChecked() {
        AutofixService cold = EngineFixtures.coldServiceWherePresent(FETCHABLE);
        assertFalse("this service has not read anything yet", cold.hasLooked());

        List<DependencyServiceCore.DialogRow> waiting = cold.rowsBeforeLooking();

        assertEquals(EngineId.values().length, waiting.size());
        for (DependencyServiceCore.DialogRow row : waiting) {
            assertEquals("Checking", EnginePanel.statusOf(row));
            assertTrue("nothing has looked, so nothing is said about what is here",
                    EnginePanel.detailOf(row).isEmpty());
            assertTrue("and nothing is said about how to get it either",
                    EnginePanel.reasonOf(row).isEmpty());
            for (String claim : Arrays.asList("Missing", "Present", "not installed")) {
                assertFalse("a row must not report a reading nobody has taken: " + claim,
                        EnginePanel.statusOf(row).contains(claim));
            }
        }
        assertFalse("asking for those rows must not count as having looked", cold.hasLooked());
    }

    // ------------------------------------------------------------- the detail

    @Test
    public void thereIsNoDetailLineUnderAnEngineThatIsHere() {
        assertEquals("", EnginePanel.detailOf(
                rowFor(FETCHABLE, EngineFixtures.serviceWherePresent(FETCHABLE))));
    }

    @Test
    public void anAbsentEngineCarriesTheLineSayingWhatWasNotFound() {
        String detail = EnginePanel.detailOf(
                rowFor(FETCHABLE, EngineFixtures.serviceWherePresent()));

        assertFalse("a row saying an engine is absent has to say what it looked for",
                detail.isEmpty());
        assertFalse("and it has to stay one line tall", detail.contains("\n"));
    }

    /**
     * Every word about an engine comes from the shared machinery. The panel lays
     * them out and writes none of its own, which is what lets this plugin and
     * its two siblings say the same thing about the same situation.
     */
    @Test
    public void thePanelPutsNoWordsOfItsOwnIntoAnEnginesRow() {
        AutofixService service = EngineFixtures.serviceWherePresent(FETCHABLE);
        for (DependencyServiceCore.DialogRow row : service.rows()) {
            assertEquals(row.getStatusLabel(), EnginePanel.statusOf(row));
            String reason = EnginePanel.reasonOf(row);
            assertTrue("the sentence on a row is the one the entry states",
                    reason.isEmpty() || reason.equals(row.getSpec().getNonFixableReason()));
            for (String caption : EnginePanel.buttonLabelsOf(row)) {
                assertTrue("a caption is the machinery's, not this panel's",
                        containsLabel(row, caption));
            }
        }
    }

    // -------------------------------------------------------- what a fix says

    @Test
    public void aRepairThatNeedsARestartSaysSoOnTopOfWhatItDid() {
        DependencyFixResult needsRestart = new DependencyFixResult(
                FETCHABLE, true, true, true, "StackReg install finished.");

        String message = EnginePanel.messageOf(needsRestart);

        assertTrue(message, message.startsWith("StackReg install finished."));
        assertTrue(message, message.contains(EnginePanel.RESTART_NEEDED));
    }

    @Test
    public void aRepairThatNeedsNoRestartSaysNothingAboutOne() {
        DependencyFixResult done = new DependencyFixResult(
                FETCHABLE, true, true, false, "StackReg is in place.");

        assertEquals("StackReg is in place.", EnginePanel.messageOf(done));
    }

    // ---------------------------------------------------------- the fixtures

    /** A runner that has driven one engine and found it left a thread running. */
    private static EngineRunner leakingRunnerThatHasDriven(EngineId engine) {
        EngineRunner runner = new EngineRunner(everythingIsHere(), new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor descriptor, String options) {
                ImageStack stack = working.getStack();
                for (int slice = 1; slice <= stack.getSize(); slice++) {
                    stack.getProcessor(slice).add(7);
                }
                Thread left = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(60_000L);
                        } catch (InterruptedException released) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }, "a-plugin-that-did-not-tidy-up");
                left.setDaemon(true);
                left.start();
                LEFT_RUNNING.add(left);
            }
        });
        runner.run(EngineDescriptor.forEngine(engine), movie(), Cancellation.never(), 30_000L);
        assertTrue("the fixture has to have left a thread running, or it asserts nothing",
                runner.drivenAlready(engine));
        return runner;
    }

    private static EngineRunner.Presence everythingIsHere() {
        return new EngineRunner.Presence() {
            @Override
            public boolean installed(EngineId engine) {
                return true;
            }
        };
    }

    private static EngineRunner.Driver nothingRuns() {
        return new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor descriptor, String options) {
                throw new AssertionError("this runner is only asked what it remembers");
            }
        };
    }

    private static ImagePlus movie() {
        ImageStack stack = new ImageStack(8, 8);
        for (int t = 0; t < 3; t++) {
            byte[] pixels = new byte[8 * 8];
            for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) ((i + t) % 100);
            stack.addSlice("t" + (t + 1), new ByteProcessor(8, 8, pixels, null));
        }
        return new ImagePlus("fixture", stack);
    }

    private static DependencyServiceCore.DialogRow rowFor(EngineId engine,
                                                          AutofixService service) {
        for (DependencyServiceCore.DialogRow row : service.rows()) {
            if (row.getSpec().getId() == engine) return row;
        }
        throw new AssertionError("no row for " + engine);
    }

    private static boolean containsLabel(DependencyServiceCore.DialogRow row, String caption) {
        for (DependencyServiceCore.DialogAction action : row.getActions()) {
            if (caption.equals(action.getLabel())) return true;
        }
        return false;
    }

    private static int indexOf(EngineId engine) {
        return Arrays.asList(EngineId.values()).indexOf(engine);
    }
}
