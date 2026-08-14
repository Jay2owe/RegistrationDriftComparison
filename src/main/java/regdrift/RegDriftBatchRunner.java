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
import ij.io.Opener;
import regdrift.autofix.EngineRegistry;
import regdrift.internal.PairScheduler;
import sc.fiji.oc3d.core.io.BatchFileDiscovery;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Works through a folder of recordings: finds them, labels them, runs each one,
 * and writes what came out.
 *
 * <p>The everyday version: a pile of films and one projector room. Somebody at
 * the door reads the labels and decides the order, hands films in, and writes
 * every result into one ledger in that order. The people inside never touch the
 * ledger and never open the door.
 *
 * <h2>Batch is the outer axis and it never nests</h2>
 *
 * <p>Recordings run beside each other; the frame pairs inside one recording do
 * not. {@link RegDriftBatchParameters#perMovie} sets {@code serial} on every
 * inner run, so a batch moves the parallelism out one level rather than adding a
 * second one underneath the first.
 *
 * <h2>Order never depends on who finished first</h2>
 *
 * <p>Recordings are numbered when the folder is read, the rows are a pre-sized
 * list at those numbers, and the coordinator consumes them <b>strictly in that
 * order</b> - it waits for recording 3 even when 4 and 5 are already done. So a
 * batch run one at a time and the same batch run eight at a time produce the
 * same rows in the same order and write the same files in the same order.
 *
 * <p>Waiting in order also bounds what is held: only a few recordings ahead of
 * the cursor are ever submitted, so a folder of two hundred does not need two
 * hundred recordings in memory.
 *
 * <h2>Two movies must not drive engines at once</h2>
 *
 * <p>Measured, not assumed - see {@code BatchModeConcurrencyTest}. ImageJ's
 * batch mode is one switch for the whole application, and two arms setting and
 * restoring it against each other leave it on after both have finished, which
 * makes every image opened for the rest of that session invisible. So
 * {@link #movieWorkersFor} comes back with one worker in the two modes that
 * drive engines, and {@link #movieWorkerNoteFor} says so in words the dialog
 * shows.
 *
 * <h2>ImageJ stays on the coordinator</h2>
 *
 * <p>House rule 13, kept literally. <b>The coordinator opens every file</b> and
 * hands the recording to a worker; workers are given an {@code ImagePlus} and
 * hand back a {@link RegDriftResult}, and they open nothing, show nothing and
 * write nothing. That is not only tidiness: {@code Opener} reports a file it
 * cannot read through {@code IJ.error}, which without redirection is a modal box
 * that a worker thread would leave hanging in front of somebody with the batch
 * stopped behind it. Redirection is one more piece of global state, so it is set
 * and put back on the one thread that owns it.
 *
 * <h2>No new analysis</h2>
 *
 * <p>Every number in a row comes out of {@link RegDrift#run} and every file out
 * of {@link RegDriftAutoSave}, the same two as a single recording. Nothing here
 * measures anything, and {@code BatchRunnerTest} measures one recording both
 * ways and compares every column rather than taking that on trust.
 */
public final class RegDriftBatchRunner {

    /**
     * How much memory one recording is assumed to need while it is being worked
     * on, as a multiple of the size of its file.
     *
     * <p>Six. A recording is held as 4-byte floats whatever it was stored as, so
     * a 16-bit file costs twice its own size once, and a run holds the binned
     * copy the fingerprint measures over and the native-resolution copy the
     * control is built from at the same time. Six is that with room for the
     * pyramid the estimator builds on top.
     *
     * <p>A floor rather than a promise, which is the direction that matters:
     * asking for fewer workers than a machine could have run costs time, and
     * asking for more costs the batch.
     */
    public static final int MEMORY_PER_MOVIE_FACTOR = 6;

    /**
     * How far ahead of the row being written recordings are allowed to be
     * started.
     *
     * <p>One extra recording per worker. Enough that no worker sits idle waiting
     * for the coordinator to finish writing a row, and few enough that the number
     * of recordings held at once stays something {@link #movieWorkersFor} can
     * budget for.
     */
    private static final int LOOKAHEAD_PER_WORKER = 2;

    /**
     * The two things this runner asks the machine it is on, behind one seam.
     *
     * <p>Package-private and replaceable, the same shape and for the same reason
     * as {@link RegDrift#bench}. Two claims this stage makes cannot be checked
     * from the outside otherwise. The first is that <b>the settings handed to
     * {@link RegDrift#run} inside a batch carry {@code serial}</b> - the
     * never-nests rule is one line, it is easy to omit, and asserting it on
     * {@link RegDriftBatchParameters#perMovie} alone would only assert that the
     * line exists somewhere, not that the run goes through it. The second is that
     * <b>a batch over recordings too large for the heap runs with fewer workers
     * rather than failing</b>: a machine with too little memory to hold two of
     * this build's test recordings is not something a test can arrange, and a
     * stated budget is.
     */
    static Bench bench = Bench.thisMachine();

    /** What a batch asks of the machine it is running on. */
    interface Bench {

        /**
         * One recording measured, through the same entry point a single
         * recording goes through.
         */
        RegDriftResult measure(RegDriftParameters parameters);

        /**
         * Bytes this batch may hold at once, or 0 to ask for a share of this
         * machine's heap.
         */
        long memoryBudget();

        /** The real one: this JVM's heap, and this plugin's own facade. */
        static Bench thisMachine() {
            return new Bench() {
                @Override
                public RegDriftResult measure(RegDriftParameters parameters) {
                    return RegDrift.run(parameters);
                }

                @Override
                public long memoryBudget() {
                    return 0L;
                }
            };
        }
    }

    private RegDriftBatchRunner() {
    }

    // -------------------------------------------------------- how many at once

    /**
     * How many recordings this batch has in flight at once.
     *
     * @param mode           what each recording is asked to do
     * @param requested      the caller's choice; 0 or less decides from the
     *                       machine, 1 forces one at a time
     * @param movies         how many recordings there are
     * @param memoryPerMovie bytes one recording needs live; 0 or less asks for no
     *                       memory cap
     * @return at least 1, never more than {@code movies}
     */
    public static int movieWorkersFor(Mode mode, int requested, int movies, long memoryPerMovie) {
        return movieWorkersFor(mode, requested, movies, memoryPerMovie, 0L);
    }

    /**
     * The same, against a stated memory budget rather than this machine's.
     *
     * <p>Package-private so that the clamp can be driven with a budget a test
     * chooses - a machine large enough to make the clamp fire is not something a
     * test can arrange.
     */
    static int movieWorkersFor(Mode mode, int requested, int movies, long memoryPerMovie,
                               long budget) {
        if (drivesEngines(mode)) return 1;
        return PairScheduler.workersFor(movies, requested, memoryPerMovie, budget);
    }

    /** True for the two modes that hand a recording to somebody else's plugin. */
    public static boolean drivesEngines(Mode mode) {
        return mode == Mode.APPLY || mode == Mode.COMPARE;
    }

    /**
     * Why a batch in this mode works through the folder the way it does, in words
     * a dialog shows.
     *
     * <p>Said rather than left to be noticed: a batch that quietly runs one
     * recording at a time where the control implies several looks like a slow
     * machine, which is exactly how the defect underneath it survives.
     */
    public static String movieWorkerNoteFor(Mode mode) {
        if (drivesEngines(mode)) {
            return "This mode hands each recording to a registration engine, so the folder is"
                    + " worked through one movie at a time. ImageJ's batch mode - the switch that"
                    + " lets an engine find its input without a window being opened - is one switch"
                    + " for the whole application, and two recordings driving engines at once were"
                    + " measured setting and restoring it against each other, leaving it on after"
                    + " both had finished. Every image opened afterwards would then be invisible."
                    + " Diagnosing and recommending drive nothing and are worked through several"
                    + " at once.";
        }
        return "This mode measures and ranks and drives no engine, so several recordings are"
                + " worked through at once - as many as this machine's processors and the size of"
                + " the recordings allow, and fewer when a recording is large enough that more"
                + " would not fit.";
    }

    /** The memory one recording of this set is assumed to need live. */
    public static long memoryPerMovie(List<File> movies) {
        long largest = 0;
        if (movies != null) {
            for (File movie : movies) {
                if (movie != null) largest = Math.max(largest, movie.length());
            }
        }
        return largest <= 0 ? 0L : largest * MEMORY_PER_MOVIE_FACTOR;
    }

    // ------------------------------------------------------------- discovery

    /**
     * One recording of a batch: where it is, what it is called, and what label
     * the filename pattern gave it.
     */
    public static final class Movie {

        private final int index;
        private final File file;
        private final String relativePath;
        private final String group;
        private final String title;

        private Movie(int index, File file, String relativePath, String group, String title) {
            this.index = index;
            this.file = file;
            this.relativePath = relativePath;
            this.group = group;
            this.title = title;
        }

        /** Where this recording sat in the order the folder was read. */
        public int index() {
            return index;
        }

        /** The file. */
        public File file() {
            return file;
        }

        /** Its path below the batch folder, with {@code /} separators. */
        public String relativePath() {
            return relativePath;
        }

        /** The label the filename pattern gave it. */
        public String group() {
            return group;
        }

        /**
         * What the recording is called once it is open, and therefore what its
         * saved files are named after.
         *
         * <p>The filename, unless two recordings in this folder share one - then
         * every recording in the batch is named by its path below the folder
         * instead, so that a well called {@code movie.tif} in twelve sub-folders
         * produces twelve sets of files rather than one set written over eleven
         * times. Uniform across the batch rather than only for the ones that
         * collided, because a folder whose files are named two different ways is
         * worse than one named a way you did not expect.
         */
        public String title() {
            return title;
        }

        @Override
        public String toString() {
            return index + " " + relativePath + " [" + group + "]";
        }
    }

    /** What reading the folder found: the recordings, the labels, and the rest. */
    public static final class Scan {

        private final List<Movie> movies;
        private final List<File> skipped;
        private final Map<String, List<File>> groups;
        private final Failure refusal;

        private Scan(List<Movie> movies, List<File> skipped, Map<String, List<File>> groups,
                     Failure refusal) {
            this.movies = Collections.unmodifiableList(movies);
            this.skipped = Collections.unmodifiableList(skipped);
            this.groups = Collections.unmodifiableMap(groups);
            this.refusal = refusal;
        }

        /** The recordings the pattern matched, in the order they will be run. */
        public List<Movie> movies() {
            return movies;
        }

        /** The files it did not match, in the order they were found. */
        public List<File> skipped() {
            return skipped;
        }

        /** Label to the files carrying it, in the order the labels were first seen. */
        public Map<String, List<File>> groups() {
            return groups;
        }

        /** Why the folder could not be read at all. Null when it could. */
        public Failure refusal() {
            return refusal;
        }

        /** The files the pattern matched. */
        public List<File> files() {
            List<File> files = new ArrayList<File>(movies.size());
            for (Movie movie : movies) files.add(movie.file());
            return files;
        }
    }

    /**
     * Reads the folder: which files are recordings, what each is labelled, and
     * what was left out.
     *
     * <p><b>The one place a folder is turned into a list of recordings.</b> The
     * preview a person reads before starting and the grouping the run uses are
     * this same call made twice, so they cannot be two different answers - which
     * is the whole point of showing a preview.
     *
     * <p>Every regular file is read and offered to the pattern, rather than only
     * the ones with an image extension. A file the pattern does not match has to
     * be <em>reported as skipped</em>, because a pattern that quietly matched
     * half a folder is the mistake this preview exists to catch, and a file
     * nobody ever saw cannot be reported.
     */
    public static Scan scan(RegDriftBatchParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("Reading a folder needs a settings bundle.");
        }
        File folder = parameters.folder();
        List<Movie> movies = new ArrayList<Movie>();
        List<File> skipped = new ArrayList<File>();
        Map<String, List<File>> groups = new LinkedHashMap<String, List<File>>();

        if (folder == null || !folder.isDirectory()) {
            return new Scan(movies, skipped, groups, Failure.of(Failure.Kind.INVALID_PARAMETERS,
                    "'" + (folder == null ? "" : folder.getAbsolutePath()) + "' is not a folder"
                            + " this computer can read, so there is nothing to work through."
                            + " Choose a folder of time-lapse recordings."));
        }

        List<File> found;
        try {
            found = everyFileUnder(folder, parameters.recursive(), parameters.saveRootFolder());
        } catch (IOException unreadable) {
            return new Scan(movies, skipped, groups, Failure.of(Failure.Kind.INVALID_PARAMETERS,
                    "'" + folder.getAbsolutePath() + "' could not be read: "
                            + describe(unreadable) + " Check the folder still exists and can be"
                            + " opened."));
        }

        List<File> matched = new ArrayList<File>();
        List<String> labels = new ArrayList<String>();
        Set<String> names = new HashSet<String>();
        boolean namesCollide = false;
        for (File file : found) {
            String label = parameters.labelFor(file.getName());
            if (label == null) {
                skipped.add(file);
                continue;
            }
            matched.add(file);
            labels.add(label);
            if (!names.add(file.getName())) namesCollide = true;
        }

        for (int i = 0; i < matched.size(); i++) {
            File file = matched.get(i);
            String relative = BatchFileDiscovery.relativePath(folder, file);
            String title = namesCollide ? relative.replace('/', '_') : file.getName();
            movies.add(new Movie(i, file, relative, labels.get(i), title));
            List<File> inGroup = groups.get(labels.get(i));
            if (inGroup == null) {
                inGroup = new ArrayList<File>();
                groups.put(labels.get(i), inGroup);
            }
            inGroup.add(file);
        }
        return new Scan(movies, skipped, groups, null);
    }

    /**
     * Every regular file at or below a folder, in one order on every platform.
     *
     * <p>Sorted by the path below the folder with {@code /} as the separator, so
     * a run on Windows and a run on Linux number the recordings the same way and
     * produce the same rows in the same order.
     *
     * <p>Hidden entries are skipped, symbolic links to folders are not followed,
     * and anything under the folder being written into is left out - a batch
     * whose results land inside the folder it reads would otherwise find its own
     * output on the second run.
     */
    private static List<File> everyFileUnder(File folder, final boolean recursive, File saveRoot)
            throws IOException {
        final Path root = folder.toPath().toAbsolutePath().normalize();
        final Path excluded = saveRoot == null
                ? null : saveRoot.toPath().toAbsolutePath().normalize();
        final List<Path> found = new ArrayList<Path>();

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes about) {
                Path here = directory.toAbsolutePath().normalize();
                if (here.equals(root)) return FileVisitResult.CONTINUE;
                if (!recursive) return FileVisitResult.SKIP_SUBTREE;
                if (excluded != null && here.startsWith(excluded)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return isHidden(here) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes about) {
                Path here = file.toAbsolutePath().normalize();
                if (!about.isRegularFile()) return FileVisitResult.CONTINUE;
                if (excluded != null && here.startsWith(excluded)) return FileVisitResult.CONTINUE;
                if (!isHidden(here)) found.add(here);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException problem) {
                // One entry this computer will not describe is not a reason to abandon the
                // folder. It is not a recording either, so it is left out rather than reported
                // as one that could not be measured.
                return FileVisitResult.CONTINUE;
            }
        });

        final File rootFile = root.toFile();
        List<File> files = new ArrayList<File>(found.size());
        for (Path path : found) files.add(path.toFile());
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return BatchFileDiscovery.relativePath(rootFile, left)
                        .compareTo(BatchFileDiscovery.relativePath(rootFile, right));
            }
        });
        return files;
    }

    private static boolean isHidden(Path path) {
        Path name = path.getFileName();
        if (name != null && name.toString().startsWith(".")) return true;
        try {
            return Files.isHidden(path);
        } catch (IOException unreadableAttribute) {
            return false;
        }
    }

    // --------------------------------------------------------------- preview

    /**
     * What this pattern will do to this folder, before anything is opened.
     *
     * <p>The regular expression is the setting a batch is most often got wrong,
     * and the cost of getting it wrong is a folder of two hundred recordings
     * where a hundred and ninety were quietly left out. So the labels, the files
     * under each of them, and every file that will <em>not</em> be run are all
     * shown, and they come from {@link #scan} rather than from a second reading
     * of the folder.
     */
    public static String preview(RegDriftBatchParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("A preview needs a settings bundle.");
        }
        Scan scan = scan(parameters);
        StringBuilder said = new StringBuilder();
        said.append("Folder:  ").append(parameters.folder() == null
                ? "" : parameters.folder().getAbsolutePath())
                .append(parameters.recursive()
                        ? "  (this folder and everything under it)"
                        : "  (this folder, not what is under it)")
                .append('\n');
        said.append("Pattern: ").append(parameters.pattern()).append('\n');
        if (scan.refusal() != null) {
            said.append('\n').append(scan.refusal().message()).append('\n');
            return said.toString();
        }

        int movies = scan.movies().size();
        if (movies == 0) {
            said.append('\n').append("No file here matches this pattern, so there is nothing to"
                    + " run. ").append(scan.skipped().size())
                    .append(scan.skipped().size() == 1 ? " file was read and it did not match."
                            : " files were read and none of them matched.").append('\n');
        } else {
            said.append('\n').append(scan.groups().size())
                    .append(scan.groups().size() == 1 ? " group, " : " groups, ").append(movies)
                    .append(movies == 1 ? " recording to run" : " recordings to run");
            if (!scan.skipped().isEmpty()) {
                said.append(", ").append(scan.skipped().size())
                        .append(scan.skipped().size() == 1 ? " file skipped" : " files skipped");
            }
            said.append(".\n\n");
            for (Map.Entry<String, List<File>> group : scan.groups().entrySet()) {
                said.append("  ").append(group.getKey()).append("  ->  ")
                        .append(named(group.getValue(), parameters.folder())).append("  (")
                        .append(group.getValue().size())
                        .append(group.getValue().size() == 1 ? " recording)" : " recordings)")
                        .append('\n');
            }
        }
        if (!scan.skipped().isEmpty()) {
            said.append("\n  skipped  ->  ").append(named(scan.skipped(), parameters.folder()))
                    .append("  (").append(scan.skipped().size())
                    .append(scan.skipped().size() == 1 ? " file, not run)" : " files, not run)")
                    .append('\n');
        }
        return said.toString();
    }

    /**
     * How many files a preview names before it says how many more there are.
     *
     * <p>There is a number here at all because a recursive scan of a real folder
     * showed why: reading one working library recursively found twelve recordings
     * beside <b>12,583</b> files the pattern did not match, and naming every one
     * of them made a preview of six hundred kilobytes - which is not a preview,
     * it is the folder again. Enough names to recognise what is being taken and
     * what is being left, and a count for the rest.
     */
    private static final int NAMES_IN_A_PREVIEW = 10;

    /**
     * Files named the way somebody can tell them apart, and not too many of them.
     *
     * <p>By the path below the folder rather than by filename, because the
     * recordings a recursive scan finds are very often called the same thing:
     * twelve entries each holding one {@code original.tif} listed by name are
     * twelve identical words, and the row the preview is describing carries the
     * path.
     */
    private static String named(List<File> files, File folder) {
        StringBuilder names = new StringBuilder();
        int shown = Math.min(files.size(), NAMES_IN_A_PREVIEW);
        for (int i = 0; i < shown; i++) {
            if (names.length() > 0) names.append(", ");
            names.append(folder == null
                    ? files.get(i).getName()
                    : BatchFileDiscovery.relativePath(folder, files.get(i)));
        }
        if (files.size() > shown) {
            names.append(", and ").append(files.size() - shown).append(" more");
        }
        return names.toString();
    }

    // ------------------------------------------------------------- the batch

    /**
     * Works through the folder and hands back one row per recording.
     *
     * @param parameters what to do, built by {@link RegDriftBatchParameters#builder}
     * @return what the batch produced, or a typed reason it could not be started.
     *         Never null, and never an empty bundle a script would read as an
     *         empty folder
     */
    public static RegDriftBatchResult run(RegDriftBatchParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("RegDriftBatchRunner.run needs a settings bundle."
                    + " Build one with RegDriftBatchParameters.builder(folder).build().");
        }
        Failure impossible = whatAFolderCannotAnswer(parameters);
        if (impossible != null) return RegDriftBatchResult.failed(parameters, impossible);

        Scan scan = scan(parameters);
        if (scan.refusal() != null) return RegDriftBatchResult.failed(parameters, scan.refusal());
        if (scan.movies().isEmpty()) {
            return RegDriftBatchResult.failed(parameters, Failure.of(
                    Failure.Kind.INVALID_PARAMETERS, "No file in '"
                            + parameters.folder().getAbsolutePath() + "' matches the pattern '"
                            + parameters.pattern() + "', so nothing was run. "
                            + scan.skipped().size() + (scan.skipped().size() == 1
                            ? " file was read and did not match" : " files were read and none"
                            + " matched") + ". The default pattern, '"
                            + RegDriftBatchParameters.DEFAULT_PATTERN + "', takes every TIFF in the"
                            + " folder."));
        }

        File saveRoot = parameters.saveRootFolder();
        Failure noRoom = noRoomToWrite(saveRoot, scan);
        if (noRoom != null) return RegDriftBatchResult.failed(parameters, noRoom);

        int workers = movieWorkersFor(parameters.mode(), parameters.movieWorkers(),
                scan.movies().size(), memoryPerMovie(scan.files()), bench.memoryBudget());
        RegDriftBatchResult.Builder result = RegDriftBatchResult.builder(parameters)
                .skipped(scan.skipped())
                .groups(scan.groups())
                .workers(workers, movieWorkerNoteFor(parameters.mode()))
                .savedUnder(saveRoot);

        List<RegDriftBatchResult.MovieRow> rows = workers <= 1
                ? oneAtATime(parameters, scan, saveRoot)
                : severalAtOnce(parameters, scan, saveRoot, workers);

        RegDriftBatchResult batch = result.rows(rows)
                .stopped(parameters.cancellation().canceled())
                .build();
        if (saveRoot != null) {
            RegDriftAutoSave.saveBatch(saveRoot, batch);
        }
        return batch;
    }

    /**
     * Why this request is not something a folder can answer, or null when it is.
     *
     * <p>One mode. Scoring rates a recording against the registered form of it
     * that somebody else produced, which means two files per recording, and a
     * folder and one pattern give no way to say which second file goes with
     * which first. Said as its own sentence rather than left to fail once per
     * recording two hundred times.
     */
    private static Failure whatAFolderCannotAnswer(RegDriftBatchParameters parameters) {
        if (parameters.mode() != Mode.SCORE) return null;
        return Failure.of(Failure.Kind.INVALID_PARAMETERS, "Mode '" + Mode.SCORE.macroValue()
                + "' rates a recording against the registered form of it that another plugin"
                + " produced, so it needs two stacks for every recording. A folder and one filename"
                + " pattern give no way to say which registered stack belongs to which recording,"
                + " so a batch does not run it. Score a pair at a time from the menu, or batch one"
                + " of: " + Mode.DIAGNOSE.macroValue() + ", "
                + Mode.DIAGNOSE_AND_RECOMMEND.macroValue() + ", " + Mode.APPLY.macroValue() + ", "
                + Mode.COMPARE.macroValue() + ".");
    }

    /**
     * Whether every file this batch would write fits in a path this system
     * accepts, checked before a single recording is opened.
     *
     * <p>Windows stops at 259 characters, and a deep recursive scan inside a
     * synchronized folder reaches that without anybody noticing. Checked up
     * front, because the alternative is an hour of measurement followed by a
     * folder that could not be written - and checked as a named path and a
     * length, not as an IO exception nobody can act on.
     */
    private static Failure noRoomToWrite(File saveRoot, Scan scan) {
        if (saveRoot == null) return null;
        for (Movie movie : scan.movies()) {
            Failure tooLong = RegDriftAutoSave.checkRoomFor(saveRoot, movie.title());
            if (tooLong != null) return tooLong;
        }
        return RegDriftAutoSave.checkRoomForBatch(saveRoot);
    }

    // ------------------------------------------------------- one at a time

    /** Open, run, write, let go of, and on to the next. */
    private static List<RegDriftBatchResult.MovieRow> oneAtATime(
            RegDriftBatchParameters parameters, Scan scan, File saveRoot) {
        List<Movie> movies = scan.movies();
        List<RegDriftBatchResult.MovieRow> rows =
                new ArrayList<RegDriftBatchResult.MovieRow>(movies.size());
        for (int i = 0; i < movies.size(); i++) {
            Movie movie = movies.get(i);
            if (parameters.cancellation().canceled()) {
                rows.add(notReached(movie, parameters));
                continue;
            }
            Opened opened = open(movie, parameters);
            if (opened.refusal != null) {
                rows.add(RegDriftBatchResult.MovieRow.failed(movie.index(), movie.file(),
                        movie.relativePath(), movie.group(), parameters.mode(), opened.refusal));
                continue;
            }
            rows.add(handle(movie, parameters, saveRoot, measure(opened.image, parameters),
                    opened.image));
        }
        return rows;
    }

    // ------------------------------------------------------ several at once

    /**
     * Several recordings measured beside each other, written strictly in the
     * order the folder was read.
     *
     * <p>One bounded pool, owned by this batch and shut down before it returns.
     * The coordinator opens every file and writes every row; a worker is handed a
     * recording already in memory and hands back what {@link RegDrift#run} made
     * of it.
     */
    private static List<RegDriftBatchResult.MovieRow> severalAtOnce(
            final RegDriftBatchParameters parameters, Scan scan, File saveRoot, int workers) {
        final List<Movie> movies = scan.movies();
        List<RegDriftBatchResult.MovieRow> rows =
                new ArrayList<RegDriftBatchResult.MovieRow>(movies.size());
        List<Future<RegDriftResult>> pending =
                new ArrayList<Future<RegDriftResult>>(movies.size());
        List<ImagePlus> open = new ArrayList<ImagePlus>(movies.size());
        List<Failure> refusals = new ArrayList<Failure>(movies.size());
        for (int i = 0; i < movies.size(); i++) {
            pending.add(null);
            open.add(null);
            refusals.add(null);
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable work) {
                Thread thread = new Thread(work, "regdrift-batch-movie");
                thread.setDaemon(true);
                return thread;
            }
        });
        int submitted = 0;
        int lookahead = workers * LOOKAHEAD_PER_WORKER;
        boolean interrupted = false;
        try {
            for (int i = 0; i < movies.size(); i++) {
                while (submitted < movies.size() && submitted < i + lookahead) {
                    submitted += startOne(pool, parameters, movies.get(submitted), pending, open,
                            refusals);
                }
                Movie movie = movies.get(i);
                if (refusals.get(i) != null) {
                    rows.add(RegDriftBatchResult.MovieRow.failed(movie.index(), movie.file(),
                            movie.relativePath(), movie.group(), parameters.mode(),
                            refusals.get(i)));
                    continue;
                }
                RegDriftResult produced;
                try {
                    produced = pending.get(i).get();
                } catch (InterruptedException stopped) {
                    interrupted = true;
                    rows.add(notReached(movie, parameters));
                    continue;
                } catch (ExecutionException broke) {
                    Throwable cause = broke.getCause() == null ? broke : broke.getCause();
                    rows.add(RegDriftBatchResult.MovieRow.failed(movie.index(), movie.file(),
                            movie.relativePath(), movie.group(), parameters.mode(),
                            wentWrong(movie, cause)));
                    letGo(open.get(i));
                    open.set(i, null);
                    continue;
                } finally {
                    pending.set(i, null);
                }
                rows.add(handle(movie, parameters, saveRoot, produced, open.get(i)));
                open.set(i, null);
            }
            return rows;
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException stopped) {
                interrupted = true;
                pool.shutdownNow();
            }
            for (ImagePlus stillOpen : open) letGo(stillOpen);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Opens one recording on this thread and hands the measuring of it to the
     * pool. Returns 1 either way, because a recording that could not be opened
     * still gets its place in the order.
     */
    private static int startOne(ExecutorService pool, final RegDriftBatchParameters parameters,
                                Movie movie, List<Future<RegDriftResult>> pending,
                                List<ImagePlus> open, List<Failure> refusals) {
        int at = movie.index();
        if (parameters.cancellation().canceled()) {
            refusals.set(at, notReachedFailure(movie));
            return 1;
        }
        Opened opened = open(movie, parameters);
        if (opened.refusal != null) {
            refusals.set(at, opened.refusal);
            return 1;
        }
        open.set(at, opened.image);
        final ImagePlus image = opened.image;
        pending.set(at, pool.submit(new Callable<RegDriftResult>() {
            @Override
            public RegDriftResult call() {
                return measure(image, parameters);
            }
        }));
        return 1;
    }

    // ---------------------------------------------------------- one recording

    /**
     * One recording measured.
     *
     * <p>The whole of what a worker does, and the only thing it does. It is
     * handed a recording already in memory, it calls the same entry point a
     * single recording goes through, and it hands back what came out. It opens
     * nothing, shows nothing and writes nothing.
     *
     * <p>{@code catch (Throwable)} is deliberate here for the same reason it is
     * in the engine harness: one recording that breaks has to cost one row, not
     * the folder. What was thrown goes into that row as a typed reason rather
     * than into a log.
     */
    private static RegDriftResult measure(ImagePlus image, RegDriftBatchParameters parameters) {
        RegDriftParameters perMovie = parameters.perMovie(image);
        try {
            return bench.measure(perMovie);
        } catch (Throwable anything) {
            return RegDriftResult.failed(perMovie, Failure.of(Failure.Kind.INTERNAL_ERROR,
                    "'" + image.getTitle() + "' stopped this plugin part way through: "
                            + describe(anything) + ". The rest of the folder was still worked"
                            + " through; this is a defect in this plugin rather than something"
                            + " wrong with the recording."));
        }
    }

    /**
     * Writes what one recording produced, turns it into a row, and lets go of
     * everything it was holding.
     *
     * <p>On the coordinator, always. Two hundred recordings each holding a
     * registered stack and a before-and-after panel is a folder that runs a
     * machine out of memory rather than one that produces a table, so each is
     * written where it was asked to be written and released before the next row
     * is taken.
     */
    private static RegDriftBatchResult.MovieRow handle(Movie movie,
                                                       RegDriftBatchParameters parameters,
                                                       File saveRoot, RegDriftResult produced,
                                                       ImagePlus opened) {
        try {
            RegDriftBatchResult.MovieRow row = RegDriftBatchResult.MovieRow.of(movie.index(),
                    movie.file(), movie.relativePath(), movie.group(), produced);
            row.saved(write(saveRoot, produced));
            return row;
        } finally {
            letGo(produced.registered());
            letGo(produced.qcPanel());
            letGo(opened);
        }
    }

    /** What writing one recording's results came to, in words for its row. */
    private static String write(File saveRoot, RegDriftResult produced) {
        if (saveRoot == null) return "nothing was written";
        RegDriftAutoSave.Report report = RegDriftAutoSave.save(saveRoot, produced);
        return report.isSuccess()
                ? "ok"
                : report.failure().kind().name().toLowerCase(java.util.Locale.ROOT) + ": "
                        + report.failure().message();
    }

    /** The row for a recording the batch was stopped before it reached. */
    private static RegDriftBatchResult.MovieRow notReached(Movie movie,
                                                           RegDriftBatchParameters parameters) {
        return RegDriftBatchResult.MovieRow.failed(movie.index(), movie.file(),
                movie.relativePath(), movie.group(), parameters.mode(), notReachedFailure(movie));
    }

    /**
     * The typed reason a recording the batch never got to carries.
     *
     * <p>Written once and used by both paths on purpose. A batch stopped part way
     * through while it was running one recording at a time and the same batch
     * stopped while it was running several have to leave the same sentence in the
     * same row, or the two are different scientific outputs and the row a person
     * reads depends on how fast their computer is.
     */
    private static Failure notReachedFailure(Movie movie) {
        return Failure.of(Failure.Kind.CANCELED, "This batch was stopped before '"
                + movie.file().getName() + "' was reached, so it was not opened and nothing about"
                + " it was measured. The recordings already finished keep their rows.");
    }

    /** The typed reason a recording broke part way through. */
    private static Failure wentWrong(Movie movie, Throwable cause) {
        return Failure.of(Failure.Kind.INTERNAL_ERROR, "'" + movie.file().getName()
                + "' stopped this plugin part way through: " + describe(cause) + ". The rest of"
                + " the folder was still worked through.");
    }

    // --------------------------------------------------------------- opening

    /** A recording that opened, or the typed reason it did not. */
    private static final class Opened {

        private final ImagePlus image;
        private final Failure refusal;

        private Opened(ImagePlus image, Failure refusal) {
            this.image = image;
            this.refusal = refusal;
        }
    }

    /**
     * Opens one file, on the coordinator, without a window and without a box.
     *
     * <p>Three things happen here that cannot happen on a worker. ImageJ's opener
     * reports a file it cannot read through {@code IJ.error}, which is a modal
     * box somebody has to click; redirected, the same sentence goes to the Log
     * and is read back into this recording's row instead, which is house rule 14.
     * Redirection is global state, so it is set and put back on the one thread
     * that owns it. And the recording that comes back is never registered with
     * {@code WindowManager} and never shown.
     */
    private static Opened open(Movie movie, RegDriftBatchParameters parameters) {
        File file = movie.file();
        int limit = RegDriftAutoSave.pathLimit();
        String path = file.getAbsolutePath();
        if (limit > 0 && path.length() > limit) {
            return new Opened(null, Failure.of(Failure.Kind.PATH_TOO_LONG, "This recording cannot"
                    + " be opened because its path is " + path.length() + " characters and this"
                    + " system stops at " + limit + ": " + path + ". Move the folder closer to the"
                    + " drive root, or read it without sub-folders."));
        }

        ImagePlus image;
        String said;
        boolean wasRedirecting = IJ.redirectingErrorMessages();
        try {
            IJ.getErrorMessage();                 // drops anything left from before
            IJ.redirectErrorMessages(true);
            Opener opener = new Opener();
            opener.setSilentMode(true);
            image = opener.openImage(path);
            said = IJ.getErrorMessage();
        } catch (Throwable anything) {
            return new Opened(null, Failure.of(Failure.Kind.IMAGE_UNREADABLE, "'" + file.getName()
                    + "' could not be opened: " + describe(anything) + ". The rest of the folder"
                    + " was still worked through."));
        } finally {
            IJ.redirectErrorMessages(wasRedirecting);
        }

        if (image == null) {
            return new Opened(null, Failure.of(Failure.Kind.IMAGE_UNREADABLE, "'" + file.getName()
                    + "' is not an image this ImageJ can open"
                    + (said == null || said.trim().isEmpty() ? "" : " (" + said.trim() + ")")
                    + ", so nothing was measured from it. The rest of the folder was still worked"
                    + " through; narrow the filename pattern if this file was never meant to be"
                    + " part of the batch."));
        }
        image.setTitle(movie.title());
        if (RegDrift.frameCount(image) < RegDrift.MIN_FRAMES) {
            letGo(image);
            return new Opened(null, Failure.of(Failure.Kind.NO_TIME_AXIS, "'" + file.getName()
                    + "' opened as " + image.getWidth() + " by " + image.getHeight() + " pixels"
                    + " over " + RegDrift.frameCount(image) + " frames, so it carries no movement"
                    + " to measure. The rest of the folder was still worked through."));
        }
        return new Opened(image, null);
    }

    /**
     * Lets go of a recording the batch is finished with.
     *
     * <p>Never a window: nothing here was ever shown or registered with ImageJ's
     * list. This releases the pixels, which over a folder of two hundred is the
     * difference between a batch that finishes and one that does not.
     */
    private static void letGo(ImagePlus image) {
        if (image == null) return;
        try {
            image.changes = false;
            image.flush();
        } catch (RuntimeException stubborn) {
            // One recording that will not let go of its pixels is one object the collector will
            // take later. Losing the batch over it would be worse, and there is nothing here for
            // a person to act on.
            return;
        }
    }

    private static String describe(Throwable problem) {
        String message = problem.getMessage();
        return message == null || message.trim().isEmpty()
                ? problem.getClass().getSimpleName()
                : problem.getClass().getSimpleName() + " - " + message.trim();
    }

    /** The longest name any engine's files could be written under. */
    static String longestEngineName() {
        String longest = "registered";
        for (String name : EngineRegistry.displayNames()) {
            if (name.length() > longest.length()) longest = name;
        }
        return longest;
    }
}
