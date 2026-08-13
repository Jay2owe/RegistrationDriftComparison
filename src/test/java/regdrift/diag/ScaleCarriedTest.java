/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Defect D12, made structural: a measured number in this package cannot be handed out without the
 * effective pixel size it was measured at.
 *
 * <h2>Why a test and not a convention</h2>
 *
 * <p>D12 happened because a threshold calibrated on binned frames was applied to native-resolution
 * pixels. Nobody decided to do that; the scale was simply not attached to the number, so there was
 * nothing to notice. A convention would be forgotten by stage 09, which is precisely the stage that
 * must not forget it. So the rule is asserted over the compiled classes, and a stage that adds a
 * measurement to this package is covered the day it is written.
 *
 * <h2>The rule</h2>
 *
 * <p>Any class in {@code regdrift.diag} that hands out a measured {@code double} through a no-argument
 * accessor must also say, through a {@code measuredAt()} of its own, what scale that number belongs
 * to. Pure arithmetic that takes its inputs as arguments is not covered and does not need to be - a
 * correlation between two arrays handed to it is whatever those two arrays are.
 *
 * <p>{@link Localisability#WARN_BELOW} is a threshold, not a measurement, and is a field rather than
 * a method. It carries its calibration scale in its javadoc, which is the only place a compile-time
 * constant can carry anything.
 */
public class ScaleCarriedTest {

    private static final String PACKAGE = "regdrift.diag";

    // ------------------------------------------------- the structural assertions

    /** The scan has to be looking at something, or every assertion below is decoration. */
    @Test
    public void theScanSeesTheClassesItIsAsserting() {
        List<Class<?>> classes = classesInPackage();
        assertTrue("no compiled classes found under " + PACKAGE, classes.size() >= 5);
        assertTrue(classes.contains(Localisability.class));
        assertTrue(classes.contains(Localisability.Result.class));
        assertTrue(classes.contains(Frames.class));
        assertTrue(classes.contains(Frames.Bin.class));
        assertTrue(classes.contains(ChannelRanker.ChannelQuality.class));
    }

    /**
     * No accessor anywhere in this package hands out a measured number without its scale.
     */
    @Test
    public void everyMeasuredNumberIsAccompaniedByItsScale() {
        for (Class<?> type : classesInPackage()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) continue;
                if (!Modifier.isPublic(method.getModifiers())) continue;
                if (method.getParameterTypes().length != 0) continue;
                if (method.getReturnType() != double.class) continue;
                assertTrue(type.getName() + "." + method.getName() + "() hands out a measured"
                                + " number with no scale beside it. Add a measuredAt() returning"
                                + " Frames.Bin, or return a value object that has one - see"
                                + " defect D12.",
                        declaresScale(type));
            }
        }
    }

    /**
     * Localisability itself hands out nothing but {@link Localisability.Result}. This is the class
     * D12 was found in, so it is asserted by name as well as by the package rule.
     */
    @Test
    public void localisabilityNeverReturnsABareNumber() {
        for (Method method : Localisability.class.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) continue;
            if (!Modifier.isPublic(method.getModifiers())) continue;
            assertFalse("Localisability." + method.getName() + " returns a bare double."
                            + " Every localisability value leaves this class inside a Result,"
                            + " which carries the scale it was measured at - see defect D12.",
                    method.getReturnType() == double.class);
        }
    }

    /** Nothing named for the measurement returns a bare number, wherever it lives. */
    @Test
    public void noLocalisabilityAccessorAnywhereReturnsABareNumber() {
        for (Class<?> type : classesInPackage()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) continue;
                if (!Modifier.isPublic(method.getModifiers())) continue;
                if (!method.getName().toLowerCase(java.util.Locale.ROOT).contains("localisab")) {
                    continue;
                }
                assertFalse(type.getName() + "." + method.getName() + " returns a bare double;"
                                + " it must return a Localisability.Result - see defect D12.",
                        method.getReturnType() == double.class);
            }
        }
    }

    // ------------------------------------------------------- and at run time

    /** Every way of getting a result produces one with a scale on it. */
    @Test
    public void everyResultReallyCarriesANonNullScale() {
        Frames.Bin two = Frames.Bin.factor(2);
        float[] a = Synth.frame(32, 32, 0, 0);
        float[] b = Synth.frame(32, 32, 0.5, 0.25);

        assertNotNull(Localisability.at(0.1, two).measuredAt());
        assertNotNull(Localisability.ofPair(a, b, 32, 32, two).measuredAt());
        assertNotNull(Localisability.of(Synth.source(32, 32, two, a, b)).measuredAt());
        assertNotNull(Localisability.of(Synth.source(32, 32, two, a, b), two).measuredAt());
        assertNotNull("even an undefined measurement carries one",
                Localisability.of(Synth.source(32, 32, two, a)).measuredAt());
        assertEquals(2, Localisability.of(Synth.source(32, 32, two, a, b)).measuredAt().factor());
    }

    /** Every ranked channel does too, and the ranking as a whole states it once more. */
    @Test
    public void everyRankedChannelReallyCarriesANonNullScale() {
        float[][][] planes = new float[2][3][];
        for (int c = 0; c < 2; c++) {
            for (int t = 0; t < 3; t++) planes[c][t] = Synth.frame(32, 32, 0.3 * t, c * 0.1);
        }
        ChannelRanker.Ranking ranking = ChannelRanker.rank(
                Synth.hyperstack("two channels", 32, 32, planes), Frames.Bin.factor(4));
        assertNotNull(ranking.measuredAt());
        for (ChannelRanker.ChannelQuality q : ranking.channels()) {
            assertNotNull(q.measuredAt());
            assertNotNull(q.localisability().measuredAt());
            assertEquals(ranking.measuredAt(), q.measuredAt());
        }
    }

    /** A missing scale is refused at every entrance, rather than filled in with a default. */
    @Test
    public void aMissingScaleIsRefusedEverywhere() {
        float[] a = Synth.frame(16, 16, 0, 0);
        float[] b = Synth.frame(16, 16, 0.5, 0);
        refuses("Localisability.at", new Runnable() {
            @Override
            public void run() {
                Localisability.at(0.1, null);
            }
        });
        refuses("Localisability.ofPair", new Runnable() {
            @Override
            public void run() {
                Localisability.ofPair(a, b, 16, 16, null);
            }
        });
        refuses("Localisability.of", new Runnable() {
            @Override
            public void run() {
                Localisability.of(Synth.source(16, 16, a, b), null);
            }
        });
        refuses("Frames.of", new Runnable() {
            @Override
            public void run() {
                Frames.of(Synth.stack("s", 16, 16, a, b), 1, Frames.PROJECT_Z, null);
            }
        });
        refuses("ChannelRanker.rank", new Runnable() {
            @Override
            public void run() {
                ChannelRanker.rank(Synth.stack("s", 16, 16, a, b), null);
            }
        });
    }

    private static void refuses(String what, Runnable call) {
        try {
            call.run();
            fail(what + " accepted a missing scale");
        } catch (IllegalArgumentException expected) {
            assertTrue(what + ": " + expected.getMessage(),
                    expected.getMessage().contains("scale"));
        }
    }

    // ------------------------------------------------------------- machinery

    private static boolean declaresScale(Class<?> type) {
        try {
            Method scale = type.getMethod("measuredAt");
            return scale.getReturnType() == Frames.Bin.class;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    /** Every compiled class in the measurement package, nested classes included. */
    private static List<Class<?>> classesInPackage() {
        File folder = new File(buildOutput(), PACKAGE.replace('.', '/'));
        File[] files = folder.listFiles();
        assertNotNull("no compiled classes under " + folder.getAbsolutePath(), files);
        List<String> names = new ArrayList<String>();
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".class")) continue;
            if (name.equals("package-info.class")) continue;      // carries no methods to check
            names.add(PACKAGE + "." + name.substring(0, name.length() - ".class".length()));
        }
        Collections.sort(names);
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (String name : names) {
            try {
                classes.add(Class.forName(name));
            } catch (ClassNotFoundException unreadable) {
                throw new AssertionError("compiled but not loadable: " + name);
            }
        }
        return classes;
    }

    private static File buildOutput() {
        try {
            File output = new File(Localisability.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            assertTrue("this test reads compiled classes from a folder, and found "
                    + output.getAbsolutePath(), output.isDirectory());
            return output;
        } catch (java.net.URISyntaxException unreadable) {
            throw new AssertionError("could not find the compiled classes: " + unreadable);
        }
    }
}
