/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The macro grammar, pinned.
 *
 * <p>Option names cannot be changed after a release without breaking somebody's
 * script, so this test is the record of what was released: every name in the
 * table, every documented default, and a round trip through the writer and the
 * parser for each one.
 *
 * <p>It also holds the guarantee that no option in the grammar can make the
 * plugin fetch or write software. That is checked over the names and over the
 * compiled bytecode of the two classes that make up the macro surface, because
 * a rule that is only written down is a rule that gets broken by somebody who
 * did not read it.
 */
public class MacroOptionsParserTest {

    /**
     * One case per option, each setting that option to something other than its
     * default. {@link #everyOptionInTheTableIsExercised()} checks that the set of
     * names used here is the whole table, so an option cannot be added to the
     * grammar and quietly left out of the round trip.
     *
     * <p>Two options belong to one mode each, so they travel with that mode.
     */
    private static final String[] ROUND_TRIP_CASES = {
            "mode=diagnose",
            "channel=2",
            "slice=3",
            "use_roi=true",
            "engines=all",
            "engines=[StackReg,Image Stabilizer]",
            "mode=apply apply_engine=[Correct 3D drift]",
            "windows=5",
            "windows=0",
            "window_frames=20",
            "arbiter=sd_vs_control",
            "flag_motion_loss=false",
            "advise_ceiling=false",
            "mode=score compare_with=[Registered stack]",
            "save_root=[C:/out/reg drift]",
            "hide_display=true",
            "serial=true",
    };

    /** The macro surface: the two classes a released option name lives in. */
    private static final Class<?>[] MACRO_SURFACE = {
            RegDriftMacroOptions.class,
            RegDriftMacroOptionsParser.class,
    };

    @Test
    public void defaultsAreTheOnesInTheContractTable() {
        RegDriftMacroOptions options = RegDriftMacroOptionsParser.parse("");

        assertEquals(Mode.DIAGNOSE_AND_RECOMMEND, options.getMode());
        assertEquals("diagnose_recommend", options.getMode().macroValue());
        assertTrue("channel defaults to auto", options.getChannel().isAuto());
        assertTrue("slice defaults to project", options.getSlice().isProject());
        assertFalse(options.isUseRoi());
        assertEquals("installed", options.getEngines().toMacroValue());
        assertEquals("", options.getApplyEngine());
        assertTrue("windows defaults to auto", options.getWindows().isAuto());
        assertTrue("window_frames defaults to auto", options.getWindowFrames().isAuto());
        assertEquals("sd_vs_control", options.getArbiter().macroValue());
        assertTrue(options.isFlagMotionLoss());
        assertTrue(options.isAdviseCeiling());
        assertEquals("", options.getCompareWith());
        assertEquals("", options.getSaveRoot());
        assertFalse(options.isHideDisplay());
        assertFalse(options.isSerial());
    }

    @Test
    public void anAbsentOptionStringIsTheSameAsAnEmptyOne() {
        assertEquals(RegDriftMacroOptionsParser.parse(""), RegDriftMacroOptionsParser.parse(null));
    }

    /**
     * The two defaults that came out of a measurement rather than a preference:
     * three windows of twelve frames, from the run of 2026-08-12 over the
     * twelve-recording library. The prose in the contract predates that run and
     * says sixteen frames; this is the number that reproduced every entry.
     */
    @Test
    public void autoResolvesToTheMeasuredThreeWindowsOfTwelveFrames() {
        RegDriftMacroOptions options =
                RegDriftMacroOptionsParser.parse("windows=auto window_frames=auto");

        assertEquals(3, options.getWindows().resolvedCount());
        assertEquals(12, options.getWindowFrames().resolved());
        assertEquals(3, Windows.AUTO_COUNT);
        assertEquals(12, WindowFrames.AUTO_FRAMES);
    }

    /** {@code windows=0} is the escape hatch, not an empty sample. */
    @Test
    public void windowsZeroMeansEveryConsecutivePair() {
        Windows windows = RegDriftMacroOptionsParser.parse("windows=0").getWindows();

        assertTrue(windows.isAllPairs());
        assertFalse(windows.isAuto());
        assertEquals("0", windows.toMacroValue());
    }

    @Test
    public void everyOptionRoundTripsThroughTheWriterAndBack() {
        for (String written : ROUND_TRIP_CASES) {
            RegDriftMacroOptions first = RegDriftMacroOptionsParser.parse(written);
            RegDriftMacroOptions second =
                    RegDriftMacroOptionsParser.parse(first.toMacroOptions());

            assertEquals("'" + written + "' did not survive a round trip", first, second);
            assertEquals("'" + written + "' rewrote itself differently the second time",
                    first.toMacroOptions(), second.toMacroOptions());
        }
    }

    @Test
    public void everyOptionInTheTableIsExercised() {
        Set<String> exercised = new TreeSet<String>();
        for (String written : ROUND_TRIP_CASES) {
            for (String token : written.split(" (?=[a-z_]+=)")) {
                exercised.add(token.substring(0, token.indexOf('=')));
            }
        }
        assertEquals("every option in the table needs a round-trip case",
                new TreeSet<String>(RegDriftMacroOptions.allOptionNames()), exercised);
        assertEquals("the table holds fifteen options",
                15, RegDriftMacroOptions.allOptionNames().size());
    }

    @Test
    public void theWrittenLineNamesEverySettingInTableOrder() {
        String written = RegDriftMacroOptionsParser.parse("mode=compare serial=true")
                .toMacroOptions();

        int at = -1;
        for (String name : RegDriftMacroOptions.allOptionNames()) {
            if (RegDriftMacroOptions.APPLY_ENGINE.equals(name)
                    || RegDriftMacroOptions.COMPARE_WITH.equals(name)
                    || RegDriftMacroOptions.SAVE_ROOT.equals(name)) {
                assertFalse("empty text options are left out: " + name,
                        written.contains(name + "="));
                continue;
            }
            int found = written.indexOf(name + "=");
            assertTrue("the recorded line should name " + name + ": " + written, found >= 0);
            assertTrue("the recorded line should follow the contract's order: " + written,
                    found > at);
            at = found;
        }
    }

    @Test
    public void anUnknownOptionIsRefusedAndTheValidOnesAreListed() {
        try {
            RegDriftMacroOptionsParser.parse("mode=diagnose surprise=true");
            fail("an unknown option should be refused");
        } catch (IllegalArgumentException expected) {
            String message = expected.getMessage();
            assertTrue("the message should name the option: " + message,
                    message.contains("surprise"));
            for (String name : RegDriftMacroOptions.allOptionNames()) {
                assertTrue("the message should list " + name + ": " + message,
                        message.contains(name));
            }
        }
    }

    @Test
    public void anUnknownBareFlagIsRefusedTheSameWay() {
        try {
            RegDriftMacroOptionsParser.parse("do_the_thing");
            fail("an unknown flag should be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("do_the_thing"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("hide_display"));
        }
    }

    @Test
    public void anUnknownValueIsRefusedAndTheValidOnesAreListed() {
        try {
            RegDriftMacroOptionsParser.parse("mode=guess");
            fail("an unknown mode should be refused");
        } catch (IllegalArgumentException expected) {
            String message = expected.getMessage();
            assertTrue(message, message.contains("guess"));
            for (Mode mode : Mode.values()) {
                assertTrue("the message should list " + mode.macroValue() + ": " + message,
                        message.contains(mode.macroValue()));
            }
        }
    }

    @Test
    public void booleansAcceptTheBareFlagFormImageJRecords() {
        RegDriftMacroOptions options =
                RegDriftMacroOptionsParser.parse("use_roi serial hide_display");

        assertTrue(options.isUseRoi());
        assertTrue(options.isSerial());
        assertTrue(options.isHideDisplay());
        assertTrue("a default-on option stays on when untouched", options.isAdviseCeiling());
    }

    @Test
    public void aBooleanWithAValueThatIsNotOneIsRefused() {
        try {
            RegDriftMacroOptionsParser.parse("serial=perhaps");
            fail("a boolean should refuse a value that is not one");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("serial"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("perhaps"));
        }
    }

    @Test
    public void anOptionGivenTwiceIsRefusedRatherThanResolvedSilently() {
        try {
            RegDriftMacroOptionsParser.parse("mode=diagnose mode=compare");
            fail("a repeated option should be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("mode"));
        }
    }

    /**
     * The three shapes the shared strict tokenizer refuses and the lenient one
     * quietly mis-reads. Their being refused here is what proves the strict one
     * is the tokenizer in the path.
     */
    @Test
    public void malformedOptionStringsAreRefusedRatherThanGuessedAt() {
        assertRefused("save_root=[C:/out", "an unclosed bracket");
        assertRefused("save_root=C:/out]", "a closing bracket that opened nothing");
        assertRefused("save_root=[C:/out\nmore]", "a line break inside a value");
    }

    @Test
    public void aBackslashInAPathIsRefusedWithSomethingActionableToDo() {
        try {
            RegDriftMacroOptionsParser.parse("save_root=[C:\\out]");
            fail("a backslash should be refused");
        } catch (IllegalArgumentException expected) {
            String message = expected.getMessage();
            assertTrue(message, message.contains("save_root"));
            assertTrue("the message should say what to do instead: " + message,
                    message.toLowerCase(Locale.ROOT).contains("forward slash"));
        }
    }

    @Test
    public void engineNamesKeepTheirAuthorsSpellingAndSurviveARoundTrip() {
        RegDriftMacroOptions options = RegDriftMacroOptionsParser.parse(
                "engines=[StackReg,Correct 3D drift,Linear Stack Alignment with SIFT]");

        List<String> names = options.getEngines().names();
        assertEquals(Arrays.asList("StackReg", "Correct 3D drift",
                "Linear Stack Alignment with SIFT"), names);
        assertEquals(names, RegDriftMacroOptionsParser
                .parse(options.toMacroOptions()).getEngines().names());
    }

    @Test
    public void aSettingThatBelongsToOneModeIsRefusedUnderAnother() {
        try {
            RegDriftMacroOptionsParser.parse("mode=diagnose compare_with=[Other stack]");
            fail("compare_with belongs to score mode");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("compare_with"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("score"));
        }
        try {
            RegDriftMacroOptionsParser.parse("mode=compare apply_engine=StackReg");
            fail("apply_engine belongs to apply mode");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("apply_engine"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("apply"));
        }
    }

    /**
     * No option name can be read as an instruction to fetch software, and the
     * two classes that make up the macro surface hold no reference to the repair
     * layer at all. The second half is the one that matters: a name is easy to
     * keep clean, a call is not.
     */
    @Test
    public void noOptionTriggersAnInstall() {
        for (String option : RegDriftMacroOptions.allOptionNames()) {
            assertFalse("option '" + option + "' must not name an install action",
                    option.contains("install") || option.contains("download")
                            || option.contains("fix") || option.contains("update"));
        }
        for (Class<?> type : MACRO_SURFACE) {
            String bytecode = bytecodeOf(type);
            assertFalse(type.getName() + " must not reach the repair layer",
                    bytecode.contains("regdrift/autofix") || bytecode.contains("regdrift.autofix"));
            assertFalse(type.getName() + " must not reach the shaded repair core",
                    bytecode.contains("autofix/core") || bytecode.contains("sc/fiji/autofix"));
        }
    }

    @Test
    public void optionNamesAreLowerCaseUsEnglishWithUnderscores() {
        String[] britishSpellings = {
                "colour", "centre", "analyse", "normalise", "optimise", "behaviour",
                "recognise", "summarise", "visualise", "grey",
        };
        Set<String> seen = new HashSet<String>();
        for (String option : RegDriftMacroOptions.allOptionNames()) {
            assertTrue("'" + option + "' should be lower case, ASCII, with underscores",
                    option.matches("[a-z][a-z0-9_]*"));
            assertTrue("'" + option + "' appears twice in the table", seen.add(option));
            for (String british : britishSpellings) {
                assertFalse("'" + option + "' uses a British spelling", option.contains(british));
            }
        }
    }

    private static void assertRefused(String options, String what) {
        try {
            RegDriftMacroOptionsParser.parse(options);
            fail(what + " should be refused: " + options);
        } catch (IllegalArgumentException expected) {
            assertTrue("the message should say something: " + expected,
                    expected.getMessage() != null && expected.getMessage().length() > 0);
        }
    }

    /**
     * The compiled form of a class, as text, so a test can ask what it refers
     * to. Class names sit in the constant pool as plain ASCII, so a byte-wise
     * search finds every reference a reviewer would have had to spot by eye.
     */
    static String bytecodeOf(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        InputStream in = type.getResourceAsStream(resource);
        assertTrue("no compiled form found for " + type.getName(), in != null);
        try {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return new String(out.toByteArray(), "ISO-8859-1");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }
}
