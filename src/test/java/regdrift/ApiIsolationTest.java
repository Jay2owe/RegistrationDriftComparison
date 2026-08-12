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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T16. Asserts from compiled bytecode that the public Java entry point opens no
 * window, writes no file and reaches no network, and that the measurement,
 * advice and scoring packages cannot either.
 *
 * <p>"Opens no window, writes no file, installs nothing" is a promise a headless
 * caller relies on, it is one line to state, and it is one line to break: a
 * single {@code IJ.error} in a catch block three stages from now is enough. So
 * it is asserted against the compiled form of every class in those packages,
 * and the build fails the moment it stops being true.
 *
 * <h2>Two ways to get this wrong, both guarded against here</h2>
 *
 * <p><b>Reading the source.</b> An import is not the only route to a class:
 * {@code new ij.gui.GenericDialog(...)} written out in full has no import line
 * to find, and a grep over the source would pass it. Every check below reads the
 * <em>constant pool</em> of the class file, where a fully written name and an
 * imported one are the same entry.
 *
 * <p><b>Checking package names alone.</b> {@code IJ.error} lives in
 * {@code ij.IJ}, not in {@code ij.gui}, so a check that bans {@code ij/gui/}
 * and stops there would miss the exact defect this test exists to catch. The
 * pool is therefore parsed properly, method references included, and the
 * window-opening members of {@code ij.IJ} are named one by one.
 *
 * <h2>Packages, never lists of classes</h2>
 *
 * <p>The three measurement packages are asserted <b>by package name</b>. A class
 * added to {@code regdrift.diag} in stage 07 is covered the day it is written,
 * with nobody having to remember to come back here and add it - which is the
 * whole point, because the person who would have to remember is the person
 * adding the {@code IJ.error}.
 */
public class ApiIsolationTest {

    /**
     * The three packages that carry the measurement, the advice and the score.
     * Empty at the time this test was written, and asserted by name so that they
     * do not have to be empty for it to keep working.
     */
    private static final String[] SEALED_PACKAGES = {
            "regdrift.diag", "regdrift.advise", "regdrift.score",
    };

    /** Anything under one of these opens a window or reaches off the machine. */
    private static final String[] WINDOW_AND_NETWORK = {
            "ij/gui/", "ij.gui.",
            "ij/WindowManager", "ij.WindowManager",
            "javax/swing/", "javax.swing.",
            "java/net/", "java.net.",
    };

    /** Files, in every spelling that reaches one. */
    private static final String[] FILE_ACCESS = {
            "java/io/File", "java.io.File",
            "java/io/RandomAccessFile", "java.io.RandomAccessFile",
            "java/nio/file/", "java.nio.file.",
    };

    /**
     * Members of {@code ij.IJ} that open a window, need one to be open already,
     * or write a file. The class itself is not banned - a status message and a
     * progress bar are how a long run stays honest with the person waiting.
     */
    private static final String[] IJ_MEMBERS_THAT_SHOW_OR_WRITE = {
            "error", "showMessage", "showMessageWithCancel", "noImage", "handleException",
            "beep", "log", "getImage", "save", "saveAs", "saveString", "write",
    };

    // ------------------------------------------------------- the scan itself

    /**
     * The scan has to be able to see a reference that is really there, or every
     * assertion below is decoration.
     *
     * <p>{@code RegDriftEntry} calls {@code IJ.error}, which is exactly the call
     * this test exists to catch, on a class this repository really ships. If the
     * pool reader can find that one, it can find one that should not be there.
     *
     * <p>The canary moved here when stage 04 replaced the placeholder entry
     * classes. It used to be {@code CompareRegistration_.IJ.showMessage}, which
     * was the placeholder's "under construction" box; the routing those entries
     * now share is where the plugin reports a failure to somebody, so that is
     * where the call to watch for lives.
     */
    @Test
    public void theScanSeesAReferenceThatIsReallyThere() throws IOException {
        Pool entry = poolOf("regdrift.RegDriftEntry");
        assertTrue("the scan must see ij.IJ.error in a class that really calls it",
                entry.members.contains("ij/IJ#error"));
        assertTrue("the scan must see the ij.IJ class reference too",
                entry.mentions("ij/IJ"));

        Pool facade = poolOf("regdrift.RegDrift");
        assertFalse("and must not invent a call that is absent",
                facade.members.contains("ij/IJ#showMessage"));
        assertFalse("nor a class reference that is absent",
                facade.mentions("javax/swing/"));

        assertTrue("the scan must see a filesystem call in the one class that writes files",
                poolOf("regdrift.RegDriftAutoSave").mentions("java/nio/file/"));
    }

    /**
     * The scan has to be looking at something. A package renamed without this
     * test being told would otherwise leave it asserting over nothing, forever,
     * while still passing.
     */
    @Test
    public void everyAssertedPackageExistsAndHoldsCompiledClasses() {
        File output = buildOutput();
        File sources = new File(projectRoot(), "src/main/java");
        assertTrue("the source tree is not where this test expects it: "
                + sources.getAbsolutePath(), sources.isDirectory());
        for (String pkg : SEALED_PACKAGES) {
            File sourceFolder = new File(sources, pkg.replace('.', '/'));
            assertTrue("package " + pkg + " is asserted by name here and has no source folder at "
                    + sourceFolder.getAbsolutePath() + ". If it was renamed, rename it here too,"
                    + " or this test guards nothing.", sourceFolder.isDirectory());
            assertFalse("package " + pkg + " compiled to no class at all, so scanning it asserts"
                            + " nothing. Give it a package-info.java rather than dropping it here.",
                    classesIn(output, pkg).isEmpty());
        }
    }

    // ------------------------------------------------------- the assertions

    /**
     * Measurement, advice and scoring: no window, no network, and none of the
     * {@code ij.IJ} calls that put a dialog on the screen.
     */
    @Test
    public void measurementAdviceAndScoringOpenNoWindowAndReachNoNetwork() throws IOException {
        File output = buildOutput();
        for (String pkg : SEALED_PACKAGES) {
            for (String className : classesIn(output, pkg)) {
                Pool pool = poolOf(className);
                for (String banned : WINDOW_AND_NETWORK) {
                    assertNoMention(className, pool, banned);
                }
                for (String member : IJ_MEMBERS_THAT_SHOW_OR_WRITE) {
                    assertNoCall(className, pool, "ij/IJ", member);
                }
            }
        }
    }

    /**
     * The facade: no window, no network, no file, and no call into the one class
     * that writes files.
     */
    @Test
    public void theFacadeShowsNothingAndWritesNothing() throws IOException {
        for (String className : classAndNested("regdrift.RegDrift")) {
            Pool pool = poolOf(className);
            for (String banned : WINDOW_AND_NETWORK) {
                assertNoMention(className, pool, banned);
            }
            for (String banned : FILE_ACCESS) {
                assertNoMention(className, pool, banned);
            }
            for (String member : IJ_MEMBERS_THAT_SHOW_OR_WRITE) {
                assertNoCall(className, pool, "ij/IJ", member);
            }
        }
    }

    /**
     * The facade does not reach {@link RegDriftAutoSave}, and could not write a
     * file through it even by accident.
     *
     * <p>Saving is a thing a person asked for, so it is decided by the entry
     * classes and the batch runner. A facade that saved would break the promise
     * the test above makes on its behalf, and it would do it from one line in a
     * class that otherwise still passes every other check here.
     */
    @Test
    public void theFacadeDoesNotReachTheOneClassThatWritesFiles() throws IOException {
        for (String className : classAndNested("regdrift.RegDrift")) {
            assertNoMention(className, poolOf(className), "regdrift/RegDriftAutoSave");
        }
    }

    /**
     * The nested and synthetic classes the compiler makes are covered as well - a
     * switch over an enum produces one, and it is as able to carry an unwanted
     * reference as the class that spawned it. Classes whose names merely start
     * the same way are not, or the facade's promise would be silently asserted
     * over {@link RegDriftAutoSave}, which does write files.
     */
    @Test
    public void theFacadeScanCoversWhatTheCompilerAddedAndNothingElse() {
        List<String> scanned = classAndNested("regdrift.RegDrift");
        assertTrue("the facade itself must be scanned", scanned.contains("regdrift.RegDrift"));
        assertFalse("a different class was pulled into the facade's scan: " + scanned,
                scanned.contains("regdrift.RegDriftTables")
                        || scanned.contains("regdrift.RegDriftAutoSave"));
    }

    private static void assertNoMention(String className, Pool pool, String banned) {
        assertFalse(className + " must not reference " + banned
                        + " - see this class's javadoc for why the public entry point stays"
                        + " headless", pool.mentions(banned));
    }

    private static void assertNoCall(String className, Pool pool, String owner, String member) {
        assertFalse(className + " must not call " + owner.replace('/', '.') + "." + member
                        + "(): it opens a window, needs one already open, or writes a file",
                pool.members.contains(owner + "#" + member));
    }

    // ---------------------------------------------------- finding the classes

    /** Every compiled class in a package and in the packages below it. */
    private static List<String> classesIn(File output, String packageName) {
        File folder = new File(output, packageName.replace('.', '/'));
        List<String> found = new ArrayList<String>();
        collect(output, folder, found);
        Collections.sort(found);
        return found;
    }

    /** A class and whatever the compiler generated alongside it. */
    private static List<String> classAndNested(String className) {
        int lastDot = className.lastIndexOf('.');
        String packageName = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);
        List<String> found = new ArrayList<String>();
        File folder = new File(buildOutput(), packageName.replace('.', '/'));
        File[] files = folder.listFiles();
        assertNotNull("no compiled classes under " + folder.getAbsolutePath(), files);
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".class")) continue;
            String stem = name.substring(0, name.length() - ".class".length());
            if (stem.equals(simpleName) || stem.startsWith(simpleName + "$")) {
                found.add(packageName + "." + stem);
            }
        }
        assertFalse("no compiled form found for " + className, found.isEmpty());
        Collections.sort(found);
        return found;
    }

    private static void collect(File output, File folder, List<String> into) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                collect(output, file, into);
            } else if (file.getName().endsWith(".class")) {
                String path = output.toURI().relativize(file.toURI()).getPath();
                into.add(path.substring(0, path.length() - ".class".length()).replace('/', '.'));
            }
        }
    }

    /** The folder the plugin's own classes were compiled into. */
    private static File buildOutput() {
        try {
            File output = new File(RegDrift.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            assertTrue("this test reads compiled classes from a folder, and found "
                    + output.getAbsolutePath(), output.isDirectory());
            return output;
        } catch (java.net.URISyntaxException unreadable) {
            throw new AssertionError("could not find the compiled classes: " + unreadable);
        }
    }

    /** The repository root, two folders above {@code target/classes}. */
    private static File projectRoot() {
        return buildOutput().getParentFile().getParentFile();
    }

    // ----------------------------------------------- reading a constant pool

    /**
     * What one class file names: the classes it mentions, the members it calls,
     * and every piece of text in its pool.
     *
     * <p>Descriptors and signatures live in that text, so a field merely
     * <em>typed</em> {@code ij.gui.Roi} is found as surely as a call.
     */
    private static final class Pool {

        private final Set<String> text = new TreeSet<String>();
        private final Set<String> members = new HashSet<String>();

        /** True when any name or descriptor in the pool holds this fragment. */
        private boolean mentions(String fragment) {
            for (String entry : text) {
                if (entry.contains(fragment)) return true;
            }
            return false;
        }
    }

    private static Pool poolOf(String className) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytecode(className)));
        try {
            assertEquals("not a class file: " + className, 0xCAFEBABE, in.readInt());
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();

            int[] tags = new int[count];
            String[] utf8 = new String[count];
            int[] classNames = new int[count];
            int[][] refs = new int[count][];
            int[][] namesAndTypes = new int[count][];

            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = tag;
                switch (tag) {
                    case 1:                                   // Utf8
                        utf8[i] = in.readUTF();
                        break;
                    case 7:                                   // Class
                    case 8:                                   // String
                    case 16:                                  // MethodType
                    case 19:                                  // Module
                    case 20:                                  // Package
                        classNames[i] = in.readUnsignedShort();
                        break;
                    case 9:                                   // Fieldref
                    case 10:                                  // Methodref
                    case 11:                                  // InterfaceMethodref
                        refs[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                        break;
                    case 12:                                  // NameAndType
                        namesAndTypes[i] = new int[]{in.readUnsignedShort(),
                                in.readUnsignedShort()};
                        break;
                    case 3:                                   // Integer
                    case 4:                                   // Float
                    case 17:                                  // Dynamic
                    case 18:                                  // InvokeDynamic
                        in.readInt();
                        break;
                    case 5:                                   // Long, and the slot after it
                    case 6:                                   // Double, likewise
                        in.readLong();
                        i++;
                        break;
                    case 15:                                  // MethodHandle
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    default:
                        throw new IOException("unknown constant pool tag " + tag + " in "
                                + className + "; this reader needs teaching about it before it"
                                + " can be trusted");
                }
            }

            Pool pool = new Pool();
            for (int i = 1; i < count; i++) {
                if (tags[i] == 1 && utf8[i] != null) pool.text.add(utf8[i]);
            }
            for (int i = 1; i < count; i++) {
                if (refs[i] == null) continue;
                String owner = utf8[classNames[refs[i][0]]];
                int[] nameAndType = namesAndTypes[refs[i][1]];
                if (owner == null || nameAndType == null) continue;
                pool.members.add(owner + "#" + utf8[nameAndType[0]]);
            }
            return pool;
        } finally {
            in.close();
        }
    }

    private static byte[] bytecode(String className) throws IOException {
        String resource = "/" + className.replace('.', '/') + ".class";
        InputStream in = ApiIsolationTest.class.getResourceAsStream(resource);
        assertNotNull("no compiled form found for " + className, in);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
