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
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.macro.Interpreter;
import ij.process.FloatProcessor;
import ij.process.ImageConverter;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import regdrift.autofix.AutofixService;
import regdrift.autofix.EngineFixtures;
import regdrift.autofix.EngineId;
import regdrift.diag.Frames;
import regdrift.harness.ArmResult;
import regdrift.harness.EngineDescriptor;
import regdrift.harness.EngineRunner;
import regdrift.ui.ImageChoices;
import regdrift.ui.Progress;
import regdrift.ui.RegDriftDialog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The inputs a person can hand the plugin that a tidy fixture never does: odd
 * shapes, odd pixels, odd paths, odd macro lines, engines that misbehave, and
 * folders full of the wrong things. Each named test pins one defect found while
 * preparing the release; {@link #noInputInTheSweepThrows} holds the whole list
 * to the rule that every one of them ends in a result or a plain sentence, never
 * a stack trace.
 */
public class EdgeCasesTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final Set<EngineId> HERE = EnumSet.of(EngineId.TURBOREG, EngineId.STACKREG,
            EngineId.MULTISTACKREG, EngineId.IMAGE_STABILIZER);

    private RegDrift.Bench realBench;
    private boolean batchModeBefore;

    @Before
    public void inventEngines() {
        realBench = RegDrift.bench;
        batchModeBefore = Interpreter.batchMode;
        RegDrift.bench = bench(NOTHING);
    }

    @After
    public void restore() {
        RegDrift.bench = realBench;
        Interpreter.batchMode = batchModeBefore;
    }

    // ------------------------------------------------------------- defects

    @Test
    public void saveRootWithBackslashesIsAcceptedFromAMacro() throws Exception {
        File root = folder.newFolder("with spaces \u00e9 \u00fc \u4e2d");
        assertTrue(root.getPath().contains("\\") || File.separatorChar == '/');
        Entry entry = macro(new RegistrationDiagnostics_(),
                "mode=diagnose_recommend hide_display=true save_root=[" + root.getPath() + "]",
                drift("sr", 64, 8));
        assertTrue(entry.failures.toString(), entry.failures.isEmpty());
        File tree = new File(root, "RegistrationDriftComparison");
        assertTrue(new File(tree, "summary.csv").isFile());
        assertTrue(new File(tree, "diagnosis/sr_diagnosis.csv").isFile());
        assertEquals("C:/a/b", RegDriftMacroOptions.forwardSlashes("C:\\a\\b"));
    }

    @Test
    public void useRoiMeasuresInsideTheSelection() {
        ImagePlus whole = drift("whole", 64, 8);
        ImagePlus inside = drift("inside", 64, 8);
        inside.setRoi(new OvalRoi(8, 8, 40, 40));
        RegDriftResult all = RegDrift.run(RegDriftParameters.builder(whole).mode(Mode.DIAGNOSE).build());
        RegDriftResult roi = RegDrift.run(RegDriftParameters.builder(inside).mode(Mode.DIAGNOSE)
                .useRoi(true).build());
        assertTrue(roi.isSuccess());
        assertFalse("use_roi changed nothing",
                GoldenOutputsTest.canon(all.diagnosis()).equals(GoldenOutputsTest.canon(roi.diagnosis())));
        assertTrue(roi.provenance().channelReason().contains("selection"));
    }

    @Test
    public void useRoiRefusesAMissingOrTinySelectionInWords() {
        RegDriftResult none = RegDrift.run(RegDriftParameters.builder(drift("none", 64, 8))
                .mode(Mode.DIAGNOSE).useRoi(true).build());
        assertEquals(Failure.Kind.INVALID_PARAMETERS, none.failure().kind());
        assertTrue(none.failure().message().contains("no area selection"));
        ImagePlus tiny = drift("tiny", 64, 8);
        tiny.setRoi(new Roi(10, 10, 1, 1));
        RegDriftResult small = RegDrift.run(RegDriftParameters.builder(tiny)
                .mode(Mode.DIAGNOSE).useRoi(true).build());
        assertEquals(Failure.Kind.INVALID_PARAMETERS, small.failure().kind());
        assertTrue(small.failure().message().contains(RegDrift.MIN_ROI_SIDE_PX + " x "
                + RegDrift.MIN_ROI_SIDE_PX));
        ImagePlus outside = drift("outside", 64, 8);
        outside.setRoi(new Roi(200, 200, 20, 20));
        assertEquals(Failure.Kind.INVALID_PARAMETERS, RegDrift.run(RegDriftParameters
                .builder(outside).mode(Mode.DIAGNOSE).useRoi(true).build()).failure().kind());
    }

    @Test
    public void infiniteAndMissingPixelsDoNotBlankTheDriftRate() {
        ImagePlus odd = drift("odd", 64, 8);
        ((float[]) odd.getStack().getPixels(3))[100] = Float.NaN;
        ((float[]) odd.getStack().getPixels(4))[200] = Float.POSITIVE_INFINITY;
        ((float[]) odd.getStack().getPixels(5))[300] = Float.NEGATIVE_INFINITY;
        double clean = driftRate(drift("clean", 64, 8));
        double measured = driftRate(odd);
        assertFalse("drift rate went blank", Double.isNaN(measured));
        assertEquals(clean, measured, 0.1 * clean);
    }

    @Test
    public void aBlankFrameDoesNotHalveTheReportedDrift() {
        double clean = driftRate(drift("clean", 64, 8));
        ImagePlus blank = drift("blank", 64, 8);
        Arrays.fill((float[]) blank.getStack().getPixels(4), 0f);
        ImagePlus missing = drift("missing", 64, 8);
        Arrays.fill((float[]) missing.getStack().getPixels(4), Float.NaN);
        assertTrue(driftRate(blank) > 0.8 * clean);
        assertTrue(driftRate(missing) > 0.8 * clean);
    }

    @Test
    public void colourIsMeasuredOnBrightnessNotTheRedChannel() {
        int rgb = (30 << 16) | (60 << 8) | 90;
        assertEquals(60f, Frames.brightness(new int[]{rgb})[0], 0f);
        ImagePlus colour = drift("colour", 64, 8);
        new ImageConverter(colour).convertToRGB();
        assertFalse(Double.isNaN(driftRate(colour)));
    }

    @Test
    public void aStillRecordingSaysInPlainWordsWhyItCannotBeCompared() {
        ImageStack still = new ImageStack(64, 64);
        for (int i = 0; i < 8; i++) {
            FloatProcessor frame = new FloatProcessor(64, 64);
            frame.set(100);
            still.addSlice(frame);
        }
        RegDriftResult r = RegDrift.run(RegDriftParameters.builder(new ImagePlus("still", still))
                .mode(Mode.COMPARE).build());
        assertEquals(Failure.Kind.SCORING_FAILED, r.failure().kind());
        assertTrue(r.failure().message().startsWith("'still' cannot be compared: its own movement"));
        assertTrue(r.failure().message().contains("No engine was driven."));
    }

    @Test
    public void aClosedImageSaysItHasNoPixelsRatherThanOneFrame() {
        ImagePlus closed = drift("closed", 64, 8);
        closed.flush();
        RegDriftResult r = RegDrift.run(RegDriftParameters.builder(closed).mode(Mode.DIAGNOSE).build());
        assertEquals(Failure.Kind.IMAGE_UNREADABLE, r.failure().kind());
        assertTrue(r.failure().message().contains("no pixels left"));
    }

    @Test
    public void aWaitUnderASecondIsNotReportedAsZero() {
        EngineRunner slow = new EngineRunner(new EngineRunner.Presence() {
            @Override
            public boolean installed(EngineId engine) {
                return true;
            }
        }, new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options)
                    throws Throwable {
                Thread.sleep(1500);
            }
        });
        Interpreter.batchMode = false;
        ArmResult timed = slow.run(EngineDescriptor.forEngine(EngineId.STACKREG),
                drift("slow", 64, 8), Cancellation.never(), 200L);
        assertTrue(timed.detail(), timed.detail().contains("within 0.2 s,"));
        assertFalse(Interpreter.batchMode);
    }

    @Test
    public void armFaultsBecomeRowStatusesAndBatchModeComesBack() {
        Arm[] faults = {
            new Arm() {
                @Override
                public void drive(ImagePlus working) {
                    throw new IllegalStateException("engine blew up");
                }
            },
            new Arm() {
                @Override
                public void drive(ImagePlus working) {
                    throw new OutOfMemoryError("Java heap space (simulated)");
                }
            },
            new Arm() {
                @Override
                public void drive(ImagePlus working) {
                    working.setStack(new ImageStack(10, 10, 1));
                }
            },
            new Arm() {
                @Override
                public void drive(ImagePlus working) {
                    working.close();
                    working.flush();
                }
            },
        };
        for (Arm fault : faults) {
            RegDrift.bench = bench(fault);
            for (Mode mode : new Mode[]{Mode.APPLY, Mode.COMPARE}) {
                Interpreter.batchMode = false;
                RegDriftResult r = RegDrift.run(RegDriftParameters.builder(drift("f", 64, 8))
                        .mode(mode).build());
                assertTrue(mode + ": " + r.failure(), r.isSuccess());
                assertEquals("could_not_drive", RegDriftTables.cellText(r.comparison(), "status", 0));
                assertFalse(Interpreter.batchMode);
            }
        }
    }

    @Test
    public void cancellingPartWayEndsCleanly() {
        final AtomicInteger polls = new AtomicInteger();
        Interpreter.batchMode = false;
        RegDriftResult early = RegDrift.run(RegDriftParameters.builder(drift("c", 128, 48))
                .mode(Mode.DIAGNOSE_AND_RECOMMEND).cancellation(new Cancellation() {
                    @Override
                    public boolean canceled() {
                        return polls.incrementAndGet() > 6;
                    }
                }).build());
        assertEquals(Failure.Kind.CANCELED, early.failure().kind());
        assertFalse(Interpreter.batchMode);
        final Cancellation.Flag stop = Cancellation.flag();
        RegDrift.bench = bench(new Arm() {
            @Override
            public void drive(ImagePlus working) {
                stop.cancel();
            }
        });
        RegDriftResult midArm = RegDrift.run(RegDriftParameters.builder(drift("m", 64, 8))
                .mode(Mode.COMPARE).cancellation(stop).build());
        assertNotNull(midArm);
        assertFalse(Interpreter.batchMode);
    }

    @Test
    public void anInvalidBatchPatternIsRefusedByTheBuilderInWords() throws Exception {
        try {
            RegDriftBatchParameters.builder(folder.newFolder("b")).pattern("good(").build();
            fail("an unreadable pattern was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cannot be read as a regular expression"));
        }
    }

    // --------------------------------------------------------------- sweep

    /** Every input from the release bug hunt ends in a result or a sentence. */
    @Test
    public void noInputInTheSweepThrows() throws Exception {
        List<String> threw = new ArrayList<String>();
        RegDriftEntry diag = new RegistrationDiagnostics_();
        RegDriftEntry cmp = new CompareRegistration_();

        for (int n : new int[]{1, 2, 5, 36, 37}) {
            sweep(threw, "frames=" + n, diag, "mode=diagnose_recommend hide_display=true", drift("f" + n, 64, n));
            sweep(threw, "frames=" + n + " compare", cmp, "mode=compare hide_display=true", drift("c" + n, 64, n));
        }
        sweep(threw, "2D image", diag, "mode=diagnose hide_display=true",
                new ImagePlus("flat", new FloatProcessor(64, 64)));
        ImagePlus zstack = drift("z", 64, 10);
        zstack.setDimensions(1, 10, 1);
        sweep(threw, "Z-stack", diag, "mode=diagnose hide_display=true", zstack);
        ImagePlus hyper = GoldenOutputsTest.Fixture.drifting("h", 64, 8, 0.73, -0.41, 0, 0, 0,
                32, 3, 2, 32).raw("hyper");
        for (String opt : new String[]{"mode=diagnose", "mode=compare", "mode=diagnose slice=5",
                "mode=diagnose channel=0", "mode=diagnose channel=4"}) {
            sweep(threw, "hyperstack " + opt, opt.contains("compare") ? cmp : diag,
                    opt + " hide_display=true", hyper);
        }
        File vdir = folder.newFolder("virtual");
        ImagePlus vsrc = drift("v", 64, 8);
        for (int i = 1; i <= vsrc.getStackSize(); i++) {
            IJ.saveAsTiff(new ImagePlus("p", vsrc.getStack().getProcessor(i)),
                    new File(vdir, String.format("p%03d.tif", i)).getPath());
        }
        ImagePlus virtual = ij.plugin.FolderOpener.open(vdir.getPath(), "virtual");
        assertTrue(virtual.getStack().isVirtual());
        sweep(threw, "virtual", cmp, "mode=compare hide_display=true", virtual);

        String[] diagLines = {"mode=diagnose chanel=2", "mode=diagnos", "mode=diagnose windows=0",
                "mode=diagnose windows=-1", "mode=diagnose window_frames=1",
                "mode=diagnose window_frames=0", "mode=diagnose windows=100",
                "mode=diagnose window_frames=1000", "mode=diagnose engines=bogus",
                "mode=diagnose_recommend engines=[StackReg,bogus]", "mode=diagnose apply_engine=StackReg",
                "mode=diagnose arbiter=raw_sd", "mode=diagnose compare_with=missing",
                "mode=diagnose channel=abc", "mode=diagnose slice=-3", "mode=diagnose hide_display=maybe",
                "mode=diagnose save_root=", "mode=apply", "mode=diagnose [broken",
                "mode=diagnose mode=compare", "=5", "   ", "mode=diagnose use_roi=true"};
        for (String opt : diagLines) sweep(threw, "diag " + opt, diag, opt, drift("m", 64, 8));
        String[] cmpLines = {"mode=score", "mode=score compare_with=missing hide_display=true",
                "mode=apply apply_engine=bogus hide_display=true",
                "mode=apply apply_engine=Fast4DReg hide_display=true",
                "mode=apply apply_engine=[Correct 3D drift] hide_display=true",
                "mode=compare engines=[StackReg] hide_display=true",
                "mode=compare windows=1 window_frames=2 hide_display=true", "mode=diagnose"};
        for (String opt : cmpLines) sweep(threw, "cmp " + opt, cmp, opt, drift("m2", 64, 8));
        sweep(threw, "score other size", cmp, "mode=score compare_with=other hide_display=true",
                drift("m3", 64, 8), drift("other", 48, 8));
        sweep(threw, "score shorter", cmp, "mode=score compare_with=shorter hide_display=true",
                drift("m4", 64, 8), drift("shorter", 64, 6));

        File file = folder.newFile("notafolder.txt");
        String[] roots = {file.getPath(), new File(folder.getRoot(), "a/b/c").getPath(), "Q:\\nope"};
        for (String root : roots) {
            sweep(threw, "save_root " + root, diag, "mode=diagnose hide_display=true save_root=["
                    + root + "]", drift("sr", 64, 8));
        }
        File old = folder.newFolder("old");
        File oldTree = new File(old, "RegistrationDriftComparison");
        assertTrue(oldTree.mkdirs());
        Files.write(new File(oldTree, "summary.csv").toPath(),
                "run_utc,image,verdict\n2026-01-01,x,registrable\n".getBytes(StandardCharsets.UTF_8));
        sweep(threw, "older summary", diag, "mode=diagnose hide_display=true save_root=["
                + old.getPath() + "]", drift("old", 64, 8));
        assertTrue(new File(oldTree, "summary_2.csv").isFile());
        File titles = folder.newFolder("titles");
        for (String title : new String[]{"a:b*c?\"d<e>f|g", "CON", "a[1]b", "same", "same"}) {
            sweep(threw, "title " + title, diag, "mode=diagnose hide_display=true save_root=["
                    + titles.getPath() + "]", drift(title, 64, 8));
        }
        assertEquals(6, Files.readAllLines(new File(titles,
                "RegistrationDriftComparison/summary.csv").toPath()).size());

        File empty = folder.newFolder("empty");
        File junk = folder.newFolder("junk");
        Files.write(new File(junk, "notes.txt").toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(junk, "fake.tif").toPath(), "not a tiff".getBytes(StandardCharsets.UTF_8));
        IJ.saveAsTiff(drift("good1", 64, 8), new File(junk, "good1.tif").getPath());
        File single = folder.newFolder("single2d");
        IJ.saveAsTiff(new ImagePlus("one", new FloatProcessor(32, 32)), new File(single, "one.tif").getPath());
        batch(threw, "empty folder", () -> RegDriftBatchParameters.builder(empty).build());
        batch(threw, "junk and good", () -> RegDriftBatchParameters.builder(junk).build());
        batch(threw, "no capture group", () -> RegDriftBatchParameters.builder(junk)
                .pattern("good.*").groupCapture(1).build());
        batch(threw, "missing folder", () -> RegDriftBatchParameters.builder(
                new File(folder.getRoot(), "nope")).build());
        batch(threw, "one 2D image", () -> RegDriftBatchParameters.builder(single).build());

        assertTrue(threw.toString(), threw.isEmpty());
    }

    // ------------------------------------------------------------- helpers

    interface Arm {
        void drive(ImagePlus working) throws Throwable;
    }

    private static final Arm NOTHING = new Arm() {
        @Override
        public void drive(ImagePlus working) {
        }
    };

    private static RegDrift.Bench bench(final Arm arm) {
        final AutofixService catalogue = EngineFixtures.serviceWherePresent(
                HERE.toArray(new EngineId[0]));
        final EngineRunner runner = new EngineRunner(new EngineRunner.Presence() {
            @Override
            public boolean installed(EngineId engine) {
                return HERE.contains(engine);
            }
        }, new EngineRunner.Driver() {
            @Override
            public void drive(ImagePlus working, EngineDescriptor engine, String options)
                    throws Throwable {
                arm.drive(working);
            }
        });
        return new RegDrift.Bench() {
            @Override
            public AutofixService catalogue() {
                return catalogue;
            }

            @Override
            public EngineRunner runner() {
                return runner;
            }
        };
    }

    /** A menu entry with the windows and dialogs taken out. */
    static final class Entry extends RegDriftEntry {
        final RegDriftEntry real;
        final Map<String, ImagePlus> images = new LinkedHashMap<String, ImagePlus>();
        final List<String> failures = new ArrayList<String>();
        String active = "";

        Entry(RegDriftEntry real) {
            this.real = real;
        }

        void open(ImagePlus image) {
            images.put(image.getTitle(), image);
            if (active.isEmpty()) active = image.getTitle();
        }

        @Override public String command() { return real.command(); }
        @Override public String otherCommand() { return real.otherCommand(); }
        @Override public List<Mode> modesRunHere() { return real.modesRunHere(); }
        @Override protected RegDriftDialog newDialog(ImageChoices c) { throw new AssertionError(); }
        @Override protected ImagePlus activeImage() { return images.get(active); }
        @Override protected ImagePlus imageNamed(String t) { return images.get(t); }
        @Override protected boolean canAsk() { return false; }
        @Override protected Progress newProgress() { return Progress.silent(); }
        @Override protected void record(String s, String l) { }
        @Override protected void reportFailure(String m, boolean q) { failures.add(m); }
        @Override protected void showStatus(String t) { }
        @Override protected void display(RegDriftResult r) { }
    }

    private static Entry macro(RegDriftEntry which, String options, ImagePlus... images) {
        Entry entry = new Entry(which);
        for (ImagePlus image : images) entry.open(image);
        entry.run(options);
        return entry;
    }

    private static void sweep(List<String> threw, String name, RegDriftEntry which, String options,
                              ImagePlus... images) {
        try {
            Entry entry = macro(which, options, images);
            for (String said : entry.failures) {
                if (said.contains("Exception") || said.contains("\tat ")) threw.add(name + ": " + said);
            }
        } catch (Throwable t) {
            threw.add(name + ": " + t);
        }
    }

    private static void batch(List<String> threw, String name, Callable<RegDriftBatchParameters> make) {
        try {
            RegDriftBatchResult r = RegDriftBatch.run(make.call());
            if (!r.isSuccess() && r.failure().message().contains("Exception")) {
                threw.add(name + ": " + r.failure().message());
            }
        } catch (Throwable t) {
            threw.add(name + ": " + t);
        }
    }

    private static double driftRate(ImagePlus image) {
        RegDriftResult r = RegDrift.run(RegDriftParameters.builder(image).mode(Mode.DIAGNOSE).build());
        assertTrue(String.valueOf(r.failure()), r.isSuccess());
        String text = RegDriftTables.cellText(r.diagnosis(), "drift_rate_px", 0);
        return text.isEmpty() ? Double.NaN : Double.parseDouble(text);
    }

    private static ImagePlus drift(String title, int side, int frames) {
        return GoldenOutputsTest.Fixture.drifting(title, side, frames, 0.73, -0.41, 0, 0,
                0, 32, 1, 1, 32).raw(title);
    }
}
