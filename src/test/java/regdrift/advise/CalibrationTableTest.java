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
import regdrift.autofix.EngineId;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The bundled evidence: that it is in the jar, that it parses, that it has not
 * been edited, and that no row of it is claimed for an engine it does not
 * describe.
 *
 * <h2>Why a digest test</h2>
 *
 * <p>These three files are the whole difference between "this plugin recommends
 * StackReg" and "this plugin recommends StackReg <em>because of this row of this
 * file</em>". A recommendation whose evidence file can be edited is not evidence,
 * and the way it would be edited is not maliciously - it is somebody improving a
 * number in good faith, six months from now, with no idea that two users'
 * results have just stopped being comparable. So the digests are written down
 * here and checked on every build. A better benchmark is a <b>new file with a
 * new date in its name</b> and a changelog entry, and this test is what makes
 * that the path of least resistance.
 */
public class CalibrationTableTest {

    /**
     * The three bundled files as they were copied on 2026-08-13, by SHA-1 of
     * their bytes.
     *
     * <p>Hand-written on purpose. Every other expectation in this stage is
     * recomputed from the files; this one is the opposite of that by design,
     * because it is the assertion that the files have not changed.
     */
    private static final String[][] FROZEN = {
            {CalibrationTable.BENCHMARK_FILE, "f63fd4a190309423491774d4506e4150bd1a429b", "27756"},
            {CalibrationTable.THIRD_PARTY_FILE, "fd7dd99e3697901a916bdc656b739d64f626d013", "2600"},
            {CalibrationTable.SATURATION_FILE, "faa4ff03f40e48a35c64cfe3bee3d7f34ad09ef6", "19517"},
    };

    // -------------------------------------------------------- it is in the jar

    /**
     * The files are reachable the way the shipped plugin reaches them - through
     * {@code getResourceAsStream} on the class path - rather than as files
     * beside the source. This is the check that a packaging change which drops
     * resources shows up here rather than in somebody's Fiji.
     */
    @Test
    public void everyBundledFileIsReachableAsAResource() throws IOException {
        for (String file : CalibrationTable.FILES) {
            String resource = CalibrationTable.RESOURCE_FOLDER + file;
            InputStream in = CalibrationTable.class.getResourceAsStream(resource);
            assertNotNull("the bundled calibration file " + resource + " is not on the class path,"
                    + " so no recommendation could be traced to a measurement", in);
            assertTrue("and it has to hold something", bytesOf(in).length > 0);
        }
    }

    @Test
    public void nobodyHasEditedAFrozenFile() throws IOException {
        for (String[] frozen : FROZEN) {
            byte[] bytes = bytesOf(CalibrationTable.class.getResourceAsStream(
                    CalibrationTable.RESOURCE_FOLDER + frozen[0]));
            assertEquals(frozen[0] + " has changed size. A bundled calibration file is frozen: a"
                            + " better benchmark is a new file with a new date in its name and a"
                            + " changelog entry, never an edit to a shipped one.",
                    Integer.parseInt(frozen[2]), bytes.length);
            assertEquals(frozen[0] + " has changed. See the message above: frozen means frozen.",
                    frozen[1], sha1(bytes));
        }
    }

    // ------------------------------------------------------------- it parses

    @Test
    public void everyRowOfEveryFileParsedIntoAConditionAndAnEstimator() {
        CalibrationTable table = CalibrationTable.bundled();
        assertFalse("the bundled table is empty", table.rows().isEmpty());
        for (CalibrationTable.Row row : table.rows()) {
            assertNotNull(row + " has no condition this build knows", row.condition());
            assertFalse(row + " has no estimator", row.estimator().isEmpty());
            assertFalse(row + " has no seed", row.seed().isEmpty());
            assertTrue(row + " has no median error", row.medianErrPx() >= 0);
        }
    }

    /**
     * The row counts, so that a file quietly truncated in transit is a failure
     * here rather than a ranking drawn from half a benchmark.
     */
    @Test
    public void eachFileHoldsTheNumberOfArmsItHeldWhenItWasCopied() {
        CalibrationTable table = CalibrationTable.bundled();
        assertEquals(216, table.rowsFrom(CalibrationTable.BENCHMARK_FILE).size());
        assertEquals(24, table.rowsFrom(CalibrationTable.THIRD_PARTY_FILE).size());
        assertEquals(180, table.rowsFrom(CalibrationTable.SATURATION_FILE).size());
    }

    @Test
    public void theBenchmarkRanOnThreeSeedsAndSaysWhichThree() {
        assertEquals(3, CalibrationTable.bundled().seeds().size());
        List<String> seeds = new ArrayList<String>(CalibrationTable.bundled().seeds());
        assertTrue(seeds.toString(), seeds.contains("VID47_D3_1_09d20h00m"));
        assertTrue(seeds.toString(), seeds.contains("VID52_B6_1_02d00h00m"));
        assertTrue(seeds.toString(), seeds.contains("VID52_C3_1_02d00h00m"));
    }

    // -------------------------------------------- and no row is over-claimed

    /**
     * Two engines have a measured arm and eight do not, and the eight are not
     * quietly given one.
     *
     * <p>The eight were never run on these pixels. Handing them a nearby row -
     * MultiStackReg drives TurboReg, so it is tempting - would be inventing a
     * measurement, which is the one thing this table exists not to do.
     */
    @Test
    public void exactlyTwoEnginesHaveAMeasuredArmAndTheOtherEightHaveNone() {
        CalibrationTable table = CalibrationTable.bundled();
        List<EngineId> measured = new ArrayList<EngineId>();
        for (EngineId engine : EngineId.values()) {
            if (!table.measuredConditions(engine).isEmpty()) measured.add(engine);
        }
        assertEquals("only the arm the benchmark actually drove maps onto an engine: " + measured,
                2, measured.size());
        assertTrue(measured.toString(), measured.contains(EngineId.TURBOREG));
        assertTrue(measured.toString(), measured.contains(EngineId.STACKREG));
    }

    /** Both engines were measured under all four constructed conditions. */
    @Test
    public void theMeasuredEnginesHaveAllFourConditions() {
        CalibrationTable table = CalibrationTable.bundled();
        assertEquals(CalibrationTable.Condition.values().length,
                table.measuredConditions(EngineId.TURBOREG).size());
        assertEquals(CalibrationTable.Condition.values().length,
                table.measuredConditions(EngineId.STACKREG).size());
    }

    /**
     * Every arm quoted is a plain consecutive chain.
     *
     * <p>The reconciled rows are in the file and are 6 to 24 times better, which
     * is exactly why they must not be quoted: no engine in this plugin's
     * catalogue can produce them, so quoting one would credit an engine with an
     * improvement belonging to a reconciliation nobody can run it with.
     */
    @Test
    public void nothingQuotedComesFromAReconciliationNoEngineCanRun() {
        CalibrationTable table = CalibrationTable.bundled();
        for (EngineId engine : EngineId.values()) {
            for (CalibrationTable.Condition condition : CalibrationTable.Condition.values()) {
                CalibrationTable.Arm arm = table.armFor(engine, condition);
                if (arm == null) continue;
                for (CalibrationTable.Row row : arm.rows()) {
                    assertEquals(row + " is quoted for " + engine + " and is not a chain",
                            CalibrationTable.CHAIN, row.reconciliation());
                }
            }
        }
    }

    /**
     * The engine this plugin's author wrote is not in the catalogue and is not
     * mapped to any measured arm.
     *
     * <p>It has no update site, so it cannot be installed, and an engine nobody
     * can install is not a recommendation. Its rows sit in the bundled file
     * because the file is frozen whole, and nothing reads them.
     */
    @Test
    public void noArmMapsOntoAnEngineNobodyCanInstall() {
        CalibrationTable table = CalibrationTable.bundled();
        for (EngineId engine : EngineId.values()) {
            for (CalibrationTable.Condition condition : CalibrationTable.Condition.values()) {
                CalibrationTable.Arm arm = table.armFor(engine, condition);
                if (arm == null) continue;
                assertFalse(arm + " quotes an arm of an engine that cannot be installed",
                        arm.estimator().toLowerCase(java.util.Locale.ROOT).contains("log-ratio"));
            }
        }
    }

    /**
     * The middle seed is reported and the spread travels with it, because the
     * fade collapses on one seed of three and a mean would hide that.
     */
    @Test
    public void theArmReportsTheMiddleSeedAndTheRangeAroundIt() {
        CalibrationTable.Arm fade = CalibrationTable.bundled()
                .armFor(EngineId.STACKREG, CalibrationTable.Condition.GAIN_FADE);
        assertNotNull(fade);
        assertEquals(3, fade.seeds());
        assertTrue("the middle seed must not be dragged up by the one that collapsed: "
                + fade.medianErrPx(), fade.medianErrPx() < 1.0);
        assertTrue("and the collapse still has to be visible in the range: " + fade.highestErrPx(),
                fade.highestErrPx() > 4.0);
        assertTrue("the reason has to carry both: " + fade.reason(),
                fade.reason().contains("to") && fade.reason().contains("GAIN_FADE"));
        assertTrue("and name the file: " + fade.reason(),
                fade.reason().contains(CalibrationTable.THIRD_PARTY_FILE));
    }

    // ---------------------------------------------------------------- reading

    private static byte[] bytesOf(InputStream in) throws IOException {
        assertNotNull(in);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String sha1(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xf, 16));
                out.append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
