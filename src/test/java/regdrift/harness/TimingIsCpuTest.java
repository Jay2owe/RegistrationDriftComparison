/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.harness;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.Test;
import regdrift.Cancellation;
import regdrift.RegDriftTables;
import regdrift.autofix.EngineId;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T14. Defect D6: every timing this plugin shows or ranks on is processor time,
 * and a stretch in which the computer was not running is not computation.
 *
 * <h2>The incident this guards against</h2>
 *
 * <p>The benchmark run this plugin's calibration came from reported one arm at
 * 237,000 ms per frame pair, against a median of 81 ms elsewhere. That figure
 * went into a CSV file and was quoted as a blocking defect before anybody
 * checked it. It was not computation: the machine went into standby at 17:52 and
 * came out at 20:58, and the reported number is that interval to within a
 * second. Re-run in one sitting, the same arm takes 3.1 s.
 *
 * <p>A wall clock cannot tell those two apart, because it measures how much time
 * passed rather than how much work was done. A per-thread processor clock can,
 * because a process that is not running accrues none of it. So the test below
 * stalls a thread on purpose and asserts that the stall does not reach the
 * figure.
 */
public class TimingIsCpuTest {

    /** Two seconds asleep, and a figure that must not have noticed. */
    private static final long STALL_MILLIS = 2000L;

    /**
     * The one that matters. Work, then a stall, and the figure that comes back
     * is the work.
     */
    @Test
    public void aStallDoesNotChangeAReportedTiming() {
        CpuTimer timer = CpuTimer.start();
        long checksum = busyWork();
        sleep(STALL_MILLIS);
        timer.stop();

        assertTrue("this runtime must report processor time, or D6 cannot be kept",
                timer.cpuMeasured());
        assertTrue("the work really happened, so the compiler did not delete it", checksum != 0);
        assertTrue("elapsed time must include the stall - that is what makes it the wrong figure:"
                + " " + timer.wallNanos(), timer.wallNanos() >= STALL_MILLIS * 1_000_000L);
        assertTrue("processor time must not include the stall: " + timer.cpuNanos(),
                timer.cpuNanos() < 1_500_000_000L);
        assertTrue("and the gap must be reported rather than discarded",
                timer.unscheduled());
        assertTrue("in words a person can act on: " + timer.note(),
                timer.note().contains("not spent computing"));
    }

    /** The same figure, taken the way an arm takes it. */
    @Test
    public void theShapeAnArmUsesIsTheSameFigure() {
        final long[] checksum = new long[1];
        long cpuNanos = CpuTimer.measureCpuNanos(new Runnable() {
            @Override
            public void run() {
                checksum[0] = busyWork();
                sleep(STALL_MILLIS);
            }
        });
        assertTrue(checksum[0] != 0);
        assertTrue("timing must not include the sleep: " + cpuNanos, cpuNanos < 1_500_000_000L);
    }

    /**
     * The figure an arm reports comes from the processor clock, and the elapsed
     * figure it also keeps never reaches the table.
     */
    @Test
    public void anArmReportsProcessorTimeAndTablesOnlyThat() {
        ArmResult result = new EngineRunner(everythingIsHere(), new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options) {
                busyWork();
                brighten(working);
                sleep(STALL_MILLIS);
            }
        }).run(EngineDescriptor.forEngine(EngineId.STACKREG), movie(),
                Cancellation.never(), 60_000L);

        assertEquals(ArmStatus.OK, result.status());
        assertTrue("the arm's elapsed figure must include the stall: " + result.wallNanos(),
                result.wallNanos() >= STALL_MILLIS * 1_000_000L);
        assertTrue("its processor figure must not: " + result.cpuNanos(),
                result.cpuNanos() < 1_500_000_000L);

        ResultsTable table = RegDriftTables.comparison();
        result.appendTo(table);
        double tabled = table.getValue("cpu_seconds", table.size() - 1);
        assertEquals("the table carries the processor figure and nothing else",
                result.cpuSeconds(), tabled, 1e-9);
        assertTrue("which is under the stall it just sat through", tabled < 1.5);
    }

    /**
     * An arm whose engine worked on threads of its own says the figure is a
     * floor rather than quietly reporting nearly nothing.
     *
     * <p>Measured on a real install: Correct 3D drift took fourteen seconds of
     * elapsed time on a twelve-frame recording and reported a sixteenth of a
     * second of processor time, because the script does its work elsewhere.
     * Ranked on as though it were free, that would put it first on cost.
     */
    @Test
    public void anArmThatWorkedElsewhereSaysItsFigureIsAFloor() {
        ArmResult result = new EngineRunner(everythingIsHere(), new EngineRunner.Driver() {
            @Override
            public void drive(final ImagePlus working, EngineDescriptor engine, String options)
                    throws Throwable {
                Thread elsewhere = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        busyWork();
                        brighten(working);
                    }
                }, "an-engine-thread-of-its-own");
                elsewhere.start();
                sleep(2500L);                       // the arm thread itself does nothing
                elsewhere.join();
            }
        }).run(EngineDescriptor.forEngine(EngineId.STACKREG), movie(),
                Cancellation.never(), 60_000L);

        assertEquals(ArmStatus.OK, result.status());
        assertTrue("the figure must be flagged as a floor", result.cpuIsAFloor());
        assertTrue("and said so in words: " + result.detail(),
                result.detail().contains("a floor rather than a total"));
    }

    /** An ordinary arm is not flagged, or the flag would mean nothing. */
    @Test
    public void anOrdinaryArmIsNotFlagged() {
        ArmResult result = new EngineRunner(everythingIsHere(), new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options) {
                brighten(working);
            }
        }).run(EngineDescriptor.forEngine(EngineId.STACKREG), movie(),
                Cancellation.never(), 60_000L);

        assertEquals(ArmStatus.OK, result.status());
        assertFalse("a short arm that did its own work is not a floor", result.cpuIsAFloor());
        assertFalse("and says nothing about one: " + result.detail(),
                result.detail().contains("a floor rather than a total"));
    }

    /** An arm nothing was attempted on carries no timing at all, rather than a zero. */
    @Test
    public void anArmThatNeverRanCarriesNoTimingRatherThanAZero() {
        ArmResult result = new EngineRunner(nothingIsHere(), new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options) {
                throw new AssertionError("an absent engine must never be dispatched");
            }
        }).run(EngineDescriptor.forEngine(EngineId.STACKREG), movie(),
                Cancellation.never(), 60_000L);

        assertEquals(ArmStatus.NOT_INSTALLED, result.status());
        assertTrue("no processor figure at all", Double.isNaN(result.cpuSeconds()));

        ResultsTable table = RegDriftTables.comparison();
        result.appendTo(table);
        assertEquals("and nothing written into the table, because a zero there would read as an"
                        + " engine measured to cost nothing", "",
                RegDriftTables.cellText(table, "cpu_seconds", table.size() - 1));
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Enough arithmetic to register on a clock whose tick is about 15.6 ms, and
     * a result that is returned so that nothing can optimize it away.
     */
    private static long busyWork() {
        long total = 1L;
        for (int round = 0; round < 4_000_000; round++) {
            total = total * 6364136223846793005L + 1442695040888963407L;
            total ^= total >>> 29;
        }
        return total;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private static void brighten(ImagePlus imp) {
        ImageStack stack = imp.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            stack.getProcessor(slice).add(5);
        }
    }

    private static ImagePlus movie() {
        ImageStack stack = new ImageStack(16, 16);
        for (int t = 0; t < 4; t++) {
            byte[] pixels = new byte[16 * 16];
            for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) ((i + t) % 200);
            stack.addSlice("t" + (t + 1), new ByteProcessor(16, 16, pixels, null));
        }
        return new ImagePlus("fixture", stack);
    }

    private static EngineRunner.Presence everythingIsHere() {
        return presence(EnumSet.allOf(EngineId.class));
    }

    private static EngineRunner.Presence nothingIsHere() {
        return presence(EnumSet.noneOf(EngineId.class));
    }

    private static EngineRunner.Presence presence(final Set<EngineId> here) {
        return new EngineRunner.Presence() {
            @Override
            public boolean installed(EngineId engine) {
                return here.contains(engine);
            }
        };
    }
}
