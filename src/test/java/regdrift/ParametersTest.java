/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The input bundle and the result bundle: their defaults, the fact that neither
 * can be edited after it is built, and the typed reasons they give when a
 * request cannot be carried out.
 *
 * <p>No pixel is read anywhere here, by this test or by the code it tests.
 * Validation looks at dimensions and at nothing else, which is why these tests
 * need no image on disk and no ImageJ window.
 */
public class ParametersTest {

    /** Every class this stage adds. None of them opens a window or a socket. */
    private static final Class<?>[] STAGE_CLASSES = {
            RegDriftParameters.class,
            RegDriftResult.class,
            RegDriftMacroOptions.class,
            RegDriftMacroOptionsParser.class,
            Mode.class,
            Channel.class,
            Slice.class,
            Verdict.class,
            Windows.class,
            WindowFrames.class,
            Arbiter.class,
            EngineSelection.class,
            Failure.class,
            Provenance.class,
            Recommendation.class,
    };

    @Test
    public void oneRecordingIsEnoughToBuildARun() {
        RegDriftParameters parameters = RegDriftParameters.builder(stack(1, 1, 8)).build();

        assertEquals(Mode.DIAGNOSE_AND_RECOMMEND, parameters.mode());
        assertTrue(parameters.channel().isAuto());
        assertTrue(parameters.slice().isProject());
        assertFalse(parameters.useRoi());
        assertEquals(EngineSelection.Kind.PRESENT, parameters.engines().kind());
        assertEquals("", parameters.applyEngine());
        assertEquals(3, parameters.windows().resolvedCount());
        assertEquals(12, parameters.windowFrames().resolved());
        assertEquals(Arbiter.SD_VS_CONTROL, parameters.arbiter());
        assertTrue(parameters.flagMotionLoss());
        assertTrue(parameters.adviseCeiling());
        assertNull(parameters.compareWith());
        assertEquals("", parameters.saveRoot());
        assertFalse(parameters.hideDisplay());
        assertFalse(parameters.serial());
    }

    @Test
    public void aRunWithoutARecordingIsRefusedAtOnce() {
        try {
            RegDriftParameters.builder(null);
            fail("a run needs a recording");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().length() > 0);
        }
    }

    /**
     * Twelve later stages read one of these while a run is in flight, several
     * from worker threads. A field that could be reassigned would make a run's
     * settings a question of timing.
     */
    @Test
    public void theInputBundleHasNoFieldThatCanBeReassignedAndNoSetter() {
        for (Field field : RegDriftParameters.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            assertTrue(field.getName() + " should be final",
                    Modifier.isFinal(field.getModifiers()));
            assertTrue(field.getName() + " should not be visible outside the class",
                    Modifier.isPrivate(field.getModifiers()));
        }
        for (Method method : RegDriftParameters.class.getDeclaredMethods()) {
            assertFalse(method.getName() + " is a setter on an immutable bundle",
                    method.getName().startsWith("set"));
        }
    }

    @Test
    public void reusingABuilderDoesNotChangeWhatItAlreadyBuilt() {
        RegDriftParameters.Builder builder = RegDriftParameters.builder(stack(1, 1, 8));
        RegDriftParameters first = builder.build();

        builder.mode(Mode.COMPARE).serial(true).windows(Windows.of(7));

        assertEquals(Mode.DIAGNOSE_AND_RECOMMEND, first.mode());
        assertFalse(first.serial());
        assertTrue(first.windows().isAuto());
        assertEquals(Mode.COMPARE, builder.build().mode());
    }

    @Test
    public void aChannelBeyondTheStackIsRefusedWithBothNumbersInTheMessage() {
        try {
            RegDriftParameters.builder(stack(3, 1, 8)).channel(Channel.of(4)).build();
            fail("channel 4 of a 3-channel stack should be refused");
        } catch (IllegalArgumentException expected) {
            String message = expected.getMessage();
            assertTrue("the message should name the channel asked for: " + message,
                    message.contains("4"));
            assertTrue("the message should name how many there are: " + message,
                    message.contains("3"));
        }
    }

    @Test
    public void theLastChannelOfTheStackIsAccepted() {
        RegDriftParameters parameters = RegDriftParameters.builder(stack(3, 1, 8))
                .channel(Channel.of(3))
                .build();

        assertEquals(3, parameters.channel().index());
    }

    @Test
    public void aSettingThatBelongsToOneModeIsRefusedUnderAnother() {
        try {
            RegDriftParameters.builder(stack(1, 1, 8))
                    .mode(Mode.DIAGNOSE)
                    .compareWith(stack(1, 1, 8))
                    .build();
            fail("a second stack belongs to score mode");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("score"));
        }
        try {
            RegDriftParameters.builder(stack(1, 1, 8))
                    .mode(Mode.COMPARE)
                    .applyEngine("StackReg")
                    .build();
            fail("naming one engine to run belongs to apply mode");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("StackReg"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("apply"));
        }
    }

    @Test
    public void macroOptionsBecomeParametersWithTheSameSettings() {
        RegDriftMacroOptions options = RegDriftMacroOptionsParser.parse(
                "mode=apply apply_engine=[Correct 3D drift] channel=2 slice=4 use_roi"
                        + " engines=all windows=0 window_frames=20 flag_motion_loss=false"
                        + " advise_ceiling=false save_root=[C:/out] hide_display serial");

        RegDriftParameters parameters = options.toParameters(stack(2, 6, 8), null);

        assertEquals(Mode.APPLY, parameters.mode());
        assertEquals("Correct 3D drift", parameters.applyEngine());
        assertEquals(2, parameters.channel().index());
        assertEquals(4, parameters.slice().index());
        assertTrue(parameters.useRoi());
        assertEquals(EngineSelection.Kind.ALL, parameters.engines().kind());
        assertTrue(parameters.windows().isAllPairs());
        assertEquals(20, parameters.windowFrames().resolved());
        assertFalse(parameters.flagMotionLoss());
        assertFalse(parameters.adviseCeiling());
        assertEquals("C:/out", parameters.saveRoot());
        assertTrue(parameters.hideDisplay());
        assertTrue(parameters.serial());
    }

    @Test
    public void aWindowCannotBeLongerThanTheRecordingItSitsIn() {
        assertEquals(8, WindowFrames.auto().resolvedFor(8));
        assertEquals(12, WindowFrames.auto().resolvedFor(500));
    }

    @Test
    public void theValueTypesRefuseNumbersThatCannotMeanAnything() {
        assertRefused("channel 0", new Runnable() {
            public void run() {
                Channel.of(0);
            }
        });
        assertRefused("slice 0", new Runnable() {
            public void run() {
                Slice.of(0);
            }
        });
        assertRefused("a negative window count", new Runnable() {
            public void run() {
                Windows.of(-1);
            }
        });
        assertRefused("a window of one frame", new Runnable() {
            public void run() {
                WindowFrames.of(1);
            }
        });
        assertRefused("an empty engine list", new Runnable() {
            public void run() {
                EngineSelection.named(Arrays.asList("StackReg", "  "));
            }
        });
    }

    @Test
    public void aRunThatCouldNotFinishCarriesATypedReasonRatherThanAnEmptyBundle() {
        RegDriftParameters parameters = RegDriftParameters.builder(stack(1, 1, 1)).build();
        Failure failure = Failure.of(Failure.Kind.NO_TIME_AXIS,
                "'test stack' holds one frame, so there is no movement between frames to measure.");

        RegDriftResult result = RegDriftResult.failed(parameters, failure);

        assertFalse(result.isSuccess());
        assertEquals(Failure.Kind.NO_TIME_AXIS, result.failure().kind());
        assertTrue(result.failure().message().length() > 0);
        assertNull(result.verdict());
        assertTrue("a failed run ranks nothing", result.ranked().isEmpty());
    }

    @Test
    public void aReasonNobodyCanReadIsNotAReason() {
        try {
            Failure.of(Failure.Kind.INTERNAL_ERROR, "  ");
            fail("a failure needs a sentence");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("INTERNAL_ERROR"));
        }
        try {
            RegDriftResult.failed(RegDriftParameters.builder(stack(1, 1, 8)).build(), null);
            fail("a failed run needs a typed reason");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().length() > 0);
        }
    }

    /** In diagnose mode there is no registered stack, and that is not an error. */
    @Test
    public void aSuccessfulDiagnosisCarriesNoRegisteredStackAndNoFailure() {
        RegDriftParameters parameters = RegDriftParameters.builder(stack(1, 1, 8))
                .mode(Mode.DIAGNOSE)
                .build();

        RegDriftResult result = RegDriftResult.builder(parameters)
                .verdict(Verdict.REGISTRABLE, "The movement tracks frame to frame.")
                .build();

        assertTrue(result.isSuccess());
        assertNull(result.failure());
        assertNull(result.registered());
        assertEquals(Verdict.REGISTRABLE, result.verdict());
        assertEquals("registrable", result.verdict().tableValue());
    }

    @Test
    public void theRankedListCannotBeEditedThroughTheResult() {
        RegDriftResult result = RegDriftResult.builder(
                        RegDriftParameters.builder(stack(1, 1, 8)).build())
                .ranked(Arrays.asList(Recommendation.builder("StackReg", 1).build()))
                .build();

        try {
            result.ranked().add(Recommendation.builder("TurboReg", 2).build());
            fail("the ranked list should not be editable through the result");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, result.ranked().size());
        }
    }

    /**
     * The registrability verdict is not comparable across two measurement
     * scales, so a provenance record cannot be built without stating the one it
     * used. Defect D12, carried open rather than worked around.
     */
    @Test
    public void aProvenanceRecordCannotLeaveOutTheScaleItMeasuredAt() {
        try {
            Provenance.builder().pluginVersion("0.1.0-SNAPSHOT").mode(Mode.DIAGNOSE).build();
            fail("a provenance record needs the scale it measured at");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("measured_at_bin"));
        }

        Provenance provenance = Provenance.builder()
                .pluginVersion("0.1.0-SNAPSHOT")
                .mode(Mode.DIAGNOSE)
                .measuredAtBin(2)
                .channel(1)
                .channelReason("ranked first of two")
                .windowStarts(new int[] {0, 18, 36})
                .windowFrames(12)
                .build();

        assertEquals(2, provenance.measuredAtBin());
        assertEquals(3, provenance.windowStarts().length);
        assertTrue("engine versions default to an empty record, not null",
                provenance.engineVersions().isEmpty());
    }

    @Test
    public void provenanceCopiesTheWindowPositionsItWasGiven() {
        int[] starts = {0, 18, 36};
        Provenance provenance = Provenance.builder()
                .pluginVersion("0.1.0-SNAPSHOT")
                .mode(Mode.DIAGNOSE)
                .measuredAtBin(1)
                .windowStarts(starts)
                .build();

        starts[0] = 99;
        provenance.windowStarts()[1] = 99;

        assertEquals(0, provenance.windowStarts()[0]);
        assertEquals(18, provenance.windowStarts()[1]);
    }

    /**
     * The public Java API opens no dialog and reaches no network, and this is
     * the stage where that starts being true. Later stages add classes that do
     * show windows; this list is the ones this stage owns.
     */
    @Test
    public void nothingInThisStageShowsAWindowOrReachesTheNetwork() {
        for (Class<?> type : STAGE_CLASSES) {
            String bytecode = bytecodeOf(type);
            assertFalse(type.getName() + " should not reach ij.gui",
                    bytecode.contains("ij/gui/"));
            assertFalse(type.getName() + " should not reach java.net",
                    bytecode.contains("java/net/"));
        }
    }

    private static void assertRefused(String what, Runnable call) {
        try {
            call.run();
            fail(what + " should be refused");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /** A stack with no window attached, sized in channels, slices and frames. */
    private static ImagePlus stack(int channels, int slices, int frames) {
        ImageStack images = new ImageStack(8, 8);
        for (int i = 0; i < channels * slices * frames; i++) {
            images.addSlice("plane " + (i + 1), new ByteProcessor(8, 8));
        }
        ImagePlus image = new ImagePlus("test stack", images);
        image.setDimensions(channels, slices, frames);
        return image;
    }

    /** @see MacroOptionsParserTest#bytecodeOf */
    private static String bytecodeOf(Class<?> type) {
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
