/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Reads what a compiled class names, so a structural claim can be asserted
 * against the class file rather than against the source that produced it.
 *
 * <h2>Why the class file and not the source</h2>
 *
 * <p>An import is not the only route to a class. {@code new
 * logratio.core.PairAligner()} written out in full has no import line to find,
 * and a search through the source would pass it. A constant pool holds a written
 * name and an imported one as the same entry, so this reads the pool.
 *
 * <p>The same reader shape is already used by {@code regdrift.ApiIsolationTest},
 * which seals this package against windows and the network. It is duplicated here
 * rather than shared because the two tests assert different things and a helper
 * that grew a flag for each of them would be the thing most likely to be quietly
 * loosened.
 */
final class Bytecode {

    private Bytecode() {
    }

    /** What one class file names. */
    static final class Pool {

        /** Every piece of text in the pool: names, descriptors and string constants. */
        final Set<String> text = new TreeSet<String>();

        /** Every class the pool names, in slash form, e.g. {@code regdrift/diag/Frames$Bin}. */
        final Set<String> classes = new TreeSet<String>();

        /** Every member the pool refers to, as {@code owner#name}. */
        final Set<String> members = new HashSet<String>();

        /** True when any name, descriptor or constant holds this fragment. */
        boolean mentions(String fragment) {
            for (String entry : text) {
                if (entry.contains(fragment)) return true;
            }
            return false;
        }

        /** The classes it names, in dotted form, which is how a person writes them. */
        Set<String> dottedClasses() {
            Set<String> out = new TreeSet<String>();
            for (String name : classes) {
                if (name.startsWith("[")) continue;             // an array descriptor, not a class
                out.add(name.replace('/', '.'));
            }
            return out;
        }
    }

    /** Every compiled class in a package and the packages below it, sorted. */
    static List<String> classesIn(String packageName) {
        File output = buildOutput();
        File folder = new File(output, packageName.replace('.', '/'));
        List<String> found = new ArrayList<String>();
        collect(output, folder, found);
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
    static File buildOutput() {
        try {
            File output = new File(Estimator.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            assertTrue("this reads compiled classes from a folder, and found "
                    + output.getAbsolutePath(), output.isDirectory());
            return output;
        } catch (java.net.URISyntaxException unreadable) {
            throw new AssertionError("could not find the compiled classes: " + unreadable);
        }
    }

    /** A class and whatever the compiler generated alongside it, but nothing merely similar. */
    static List<String> classAndNested(String className) {
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
        assertTrue("no compiled form found for " + className, !found.isEmpty());
        Collections.sort(found);
        return found;
    }

    static Pool poolOf(String className) throws IOException {
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
                if (tags[i] != 7) continue;                   // Class entries only
                String named = utf8[classNames[i]];
                if (named != null) pool.classes.add(named);
            }
            for (int i = 1; i < count; i++) {
                if (refs[i] == null) continue;
                String owner = utf8[classNames[refs[i][0]]];
                int[] nameAndType = namesAndTypes[refs[i][1]];
                if (owner == null || nameAndType == null) continue;
                pool.members.add(owner + "#" + utf8[nameAndType[0]]);
                pool.classes.add(owner);
            }
            return pool;
        } finally {
            in.close();
        }
    }

    private static byte[] bytecode(String className) throws IOException {
        String resource = "/" + className.replace('.', '/') + ".class";
        InputStream in = Bytecode.class.getResourceAsStream(resource);
        assertNotNull("no compiled form found for " + className, in);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
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
