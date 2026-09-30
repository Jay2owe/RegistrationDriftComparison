/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.IJ;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The line {@code Registration Batch...} records, and reading it back. */
public class RegDriftBatchMacroTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aRecordedLineReadsBackToTheSameRequest() throws Exception {
        File recordings = folder.newFolder("rec");
        RegDriftBatchParameters written = RegDriftBatchParameters.builder(recordings)
                .pattern("(A|B)_.*\\.tif")
                .groupCapture(1)
                .recursive(true)
                .mode(Mode.COMPARE)
                .windows(Windows.of(4))
                .windowFrames(WindowFrames.of(8))
                .adviseCeiling(false)
                .movieWorkers(2)
                .saveRoot(folder.getRoot().getPath() + File.separator + "out")
                .build();
        String line = RegDriftBatchMacro.toMacroOptions(written);
        assertFalse("a Windows path is written with forward slashes: " + line,
                line.contains("folder=[" + recordings.getPath() + "]") && recordings.getPath().contains("\\"));

        RegDriftBatchParameters read = RegDriftBatchMacro.parse(line).build();
        assertEquals(written.folder().getAbsoluteFile(), read.folder().getAbsoluteFile());
        assertEquals(written.pattern(), read.pattern());
        assertEquals(1, read.groupCapture());
        assertTrue(read.recursive());
        assertEquals(Mode.COMPARE, read.mode());
        assertEquals(written.windows().toMacroValue(), read.windows().toMacroValue());
        assertEquals(written.windowFrames().toMacroValue(), read.windowFrames().toMacroValue());
        assertFalse(read.adviseCeiling());
        assertEquals(2, read.movieWorkers());
        assertEquals(new File(written.saveRoot()).getAbsoluteFile(),
                new File(read.saveRoot()).getAbsoluteFile());
        assertEquals("the line is stable once written", line, RegDriftBatchMacro.toMacroOptions(read));
    }

    @Test
    public void theDefaultPatternSurvivesTheMacroStringItIsRecordedIn() throws Exception {
        RegDriftBatchParameters p = RegDriftBatchParameters.builder(folder.newFolder("d")).build();
        String line = RegDriftBatchMacro.toMacroOptions(p);
        assertTrue(line, line.contains("pattern=[" + RegDriftBatchParameters.DEFAULT_PATTERN + "]"));
        String inMacro = RegDriftBatchMacro.inMacroString(line);
        assertTrue(inMacro, inMacro.contains("\\\\.tif"));
        // What the macro language hands back from that string literal is the line itself.
        String replayed = inMacro.replace("\\\\", "\\");
        assertEquals(p.pattern(), RegDriftBatchMacro.parse(replayed).build().pattern());
    }

    @Test
    public void aPatternWithACharacterClassIsRefusedForRecordingInWords() throws Exception {
        RegDriftBatchParameters p = RegDriftBatchParameters.builder(folder.newFolder("c"))
                .pattern("^([A-Z]\\d+)_.*\\.tif$").build();
        try {
            RegDriftBatchMacro.toMacroOptions(p);
            fail("a bracket in the pattern was written");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("square bracket"));
        }
    }

    @Test
    public void aLineWithoutAFolderOrWithAnUnknownNameSaysSo() {
        try {
            RegDriftBatchMacro.parse("mode=diagnose");
            fail("no folder was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("folder=["));
        }
        try {
            RegDriftBatchMacro.parse("folder=[C:/x] chanel=2");
            fail("an unknown name was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("chanel")
                    && expected.getMessage().contains(RegDriftBatchMacro.FOLDER));
        }
        try {
            RegDriftBatchMacro.parse("folder=[C:/x] workers=0");
            fail("zero workers was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("workers"));
        }
    }

    @Test
    public void aParsedLineRunsTheFolder() throws Exception {
        File recordings = folder.newFolder("run");
        IJ.saveAsTiff(GoldenOutputsTest.Fixture.drifting("a", 64, 8, 0.73, -0.41, 0, 0, 0, 32, 1, 1, 32)
                .raw("a"), new File(recordings, "a.tif").getPath());
        IJ.saveAsTiff(GoldenOutputsTest.Fixture.drifting("b", 64, 8, 0.5, 0.2, 0, 0, 0, 32, 1, 1, 32)
                .raw("b"), new File(recordings, "b.tif").getPath());
        String line = "folder=[" + RegDriftMacroOptions.forwardSlashes(recordings.getPath())
                + "] mode=diagnose";
        RegDriftBatchResult result = RegDriftBatch.run(RegDriftBatchMacro.parse(line).build());
        assertTrue(String.valueOf(result.failure()), result.isSuccess());
        assertEquals(2, result.rows().size());
        assertTrue(RegistrationBatch_.summaryOf(result), RegistrationBatch_.summaryOf(result)
                .startsWith("Worked through 2 recordings."));
    }

    /**
     * A stopped folder run counts what it reached, not every row. Found by the GUI
     * checks: Esc after 5 of 24 said "Stopped after 24 recordings ... 19 could not
     * be measured".
     */
    @Test
    public void aStoppedRunSaysHowFarItGotAndHowManyItDidNotReach() throws Exception {
        File recordings = folder.newFolder("stop");
        for (String name : new String[]{"a", "b", "c"}) {
            IJ.saveAsTiff(GoldenOutputsTest.Fixture.drifting(name, 64, 8, 0.73, -0.41, 0, 0, 0, 32,
                    1, 1, 32).raw(name), new File(recordings, name + ".tif").getPath());
        }
        final Cancellation.Flag stop = Cancellation.flag();
        RegDriftBatchRunner.Bench before = RegDriftBatchRunner.bench;
        RegDriftBatchRunner.bench = new RegDriftBatchRunner.Bench() {
            @Override
            public RegDriftResult measure(RegDriftParameters parameters) {
                RegDriftResult produced = RegDrift.run(parameters);
                stop.cancel();
                return produced;
            }

            @Override
            public long memoryBudget() {
                return 0L;
            }
        };
        try {
            RegDriftBatchResult result = RegDriftBatch.run(RegDriftBatchParameters
                    .builder(recordings).mode(Mode.DIAGNOSE).movieWorkers(1).cancellation(stop)
                    .build());
            String said = RegistrationBatch_.summaryOf(result);
            assertTrue(said, result.stopped());
            assertTrue(said, said.startsWith("Stopped when Esc was pressed, after 1 recording of 3;"
                    + " the other 2 were not measured."));
            assertFalse("the ones not reached are not called failures: " + said,
                    said.contains("could not be measured"));
        } finally {
            RegDriftBatchRunner.bench = before;
        }
    }
}
