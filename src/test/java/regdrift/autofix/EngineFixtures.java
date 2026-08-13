/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.autofix;

import sc.fiji.autofix.core.DependencyKey;
import sc.fiji.autofix.core.DependencyServiceCore;
import sc.fiji.autofix.core.DependencySpec;
import sc.fiji.autofix.core.DependencyStatus;
import sc.fiji.autofix.core.ProbeContext;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Test fixtures: a real catalogue over an invented set of answers, and an
 * invented Fiji.app on disk.
 *
 * <p>Both exist so that a test never depends on what happens to be installed on
 * the machine running it. A test asserting that a missing engine offers an
 * install button would otherwise pass or fail according to whose laptop it ran
 * on, which is the opposite of what it is for.
 */
public final class EngineFixtures {

    /** A classloader that resolves nothing, so a class probe always reports absent. */
    public static final ClassLoader NOTHING_LOADS = new ClassLoader(null) {
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            throw new ClassNotFoundException(name);
        }
    };

    private EngineFixtures() {
    }

    /**
     * The real catalogue and the real repair paths, over a fixed set of
     * answers, already read.
     *
     * <p>Read before it is handed back, because a service that has not looked
     * yet draws every row as being checked and answers from another thread. A
     * test asserting on what a row says wants the answers, and wants them
     * without a race.
     *
     * @param present the engines this service reports as present; every other
     *                engine in the catalogue reports as absent
     */
    public static AutofixService serviceWherePresent(EngineId... present) {
        AutofixService service = coldServiceWherePresent(present);
        service.rows();
        return service;
    }

    /**
     * The same, before anything has been read - for a test about what the
     * section shows while it is still finding out.
     */
    public static AutofixService coldServiceWherePresent(EngineId... present) {
        final Set<EngineId> here = present.length == 0
                ? EnumSet.noneOf(EngineId.class)
                : EnumSet.copyOf(Arrays.asList(present));
        return new AutofixService(EngineRegistry.catalogue(), AutofixService.defaultFixers(),
                new DependencyServiceCore.StatusSnapshotProvider() {
                    @Override
                    public Map<DependencyKey, DependencyStatus> snapshot(
                            List<DependencySpec> specs) {
                        Map<DependencyKey, DependencyStatus> answers =
                                new LinkedHashMap<DependencyKey, DependencyStatus>();
                        for (DependencySpec spec : specs) {
                            DependencyStatus status = here.contains(spec.getId())
                                    ? DependencyStatus.present("Fixture: present.")
                                    : DependencyStatus.missing("Fixture: not on this computer.");
                            answers.put(spec.getId(), status.withKey(spec.getId()));
                        }
                        return answers;
                    }
                });
    }

    /**
     * A probe context over a folder, with a classloader that resolves nothing.
     *
     * <p>The empty classloader is the point: it isolates the half of a probe
     * that looks at files from the half that looks at classes, so a test about
     * jar versions is not quietly answered by a class that happens to be on the
     * test's own classpath.
     */
    public static ProbeContext contextOver(File fijiDir) {
        return new ProbeContext(NOTHING_LOADS, fijiDir);
    }

    /** An empty Fiji.app, with the two folders engines are installed into. */
    public static File emptyFiji(File parent, String name) {
        File fiji = new File(parent, name);
        require(new File(fiji, "plugins").mkdirs(), fiji);
        require(new File(fiji, "jars").mkdirs(), fiji);
        return fiji;
    }

    /**
     * Writes a file of {@code bytes} bytes at {@code relativePath} under
     * {@code fijiDir}, filled with repeatable noise.
     *
     * <p>Noise rather than zeroes because the machinery rejects a download under
     * a kilobyte as an error page, and because a digest over a run of zeroes is
     * the same digest for every file, which would let a test pass by accident.
     */
    public static File writeFile(File fijiDir, String relativePath, int bytes) throws IOException {
        File file = new File(fijiDir, relativePath);
        File folder = file.getParentFile();
        if (!folder.isDirectory()) require(folder.mkdirs(), folder);
        byte[] content = new byte[bytes];
        new Random(relativePath.hashCode()).nextBytes(content);
        java.io.FileOutputStream out = new java.io.FileOutputStream(file);
        try {
            out.write(content);
        } finally {
            out.close();
        }
        return file;
    }

    /** The names of the files directly inside a folder, sorted. Empty when absent. */
    public static List<String> listing(File folder) {
        String[] names = folder.list();
        if (names == null) return java.util.Collections.emptyList();
        Arrays.sort(names);
        return Arrays.asList(names);
    }

    private static void require(boolean made, File what) {
        if (!made && !what.isDirectory()) {
            throw new IllegalStateException("could not create the fixture folder " + what);
        }
    }
}
