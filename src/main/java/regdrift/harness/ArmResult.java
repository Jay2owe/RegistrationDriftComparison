/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.harness;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import regdrift.RegDriftTables;
import regdrift.autofix.EngineId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one engine's run produced: the registered recording where there is one,
 * what it cost on the processor clock, how it ended, and what it left running.
 *
 * <p>Never null and never empty. A run that could not be attempted, one that was
 * stopped, one that threw out of somebody else's code and one that finished
 * without touching a pixel all come back as one of these, carrying a status a
 * caller can branch on and a sentence a person can read. A null recording and a
 * line in the ImageJ log would look like success to a macro and to a batch loop,
 * which is the failure this type exists to make impossible - house rule 14.
 *
 * <p>This says nothing about whether the registration was any good. Scoring is
 * the arbiter's work, in the stage after this one; an arm hands back a recording
 * and never judges it.
 */
public final class ArmResult {

    /** Below this share of the elapsed figure, the processor figure is a floor. */
    private static final double FLOOR_SHARE = 0.2;

    /** And only over an interval this long, so a short arm is not flagged for noise. */
    private static final long MIN_FLOOR_WALL_NANOS = 2_000_000_000L;

    private final EngineId engine;
    private final String engineName;
    private final ArmStatus status;
    private final String detail;
    private final String settings;
    private final ImagePlus registered;
    private final long cpuNanos;
    private final long wallNanos;
    private final String measuredVersion;
    private final String foundVersion;
    private final List<String> threadsLeftBehind;

    private ArmResult(EngineId engine, String engineName, ArmStatus status, String detail,
                      String settings, ImagePlus registered, long cpuNanos, long wallNanos,
                      String measuredVersion, String foundVersion, List<String> threadsLeftBehind) {
        this.engine = engine;
        this.engineName = engineName;
        this.status = status;
        this.detail = detail;
        this.settings = settings;
        this.registered = registered;
        this.cpuNanos = cpuNanos;
        this.wallNanos = wallNanos;
        this.measuredVersion = measuredVersion;
        this.foundVersion = foundVersion;
        this.threadsLeftBehind = Collections.unmodifiableList(
                new ArrayList<String>(threadsLeftBehind));
    }

    /**
     * A finished arm.
     *
     * <p>The status is worked out here rather than by the caller, from what the
     * arm actually found: threads still running outrank a version nobody
     * measured, which outranks a plain success. See {@link ArmStatus} for why
     * that order and not another.
     */
    static ArmResult finished(EngineDescriptor engine, String settings, ImagePlus registered,
                              long cpuNanos, long wallNanos, String foundVersion,
                              List<String> threadsLeftBehind) {
        String measured = engine.measuredVersion();
        boolean leaked = !threadsLeftBehind.isEmpty();
        boolean untested = versionDiffers(measured, foundVersion);
        ArmStatus status = leaked ? ArmStatus.LEAKED_THREADS
                : untested ? ArmStatus.WRONG_VERSION : ArmStatus.OK;
        return new ArmResult(engine.id(), engine.displayName(), status,
                detailFor(engine, status, threadsLeftBehind, measured, foundVersion,
                        cpuNanos, wallNanos),
                settings, registered, cpuNanos, wallNanos, measured, foundVersion,
                threadsLeftBehind);
    }

    /** An arm that could not be attempted, or that produced nothing usable. */
    static ArmResult failed(EngineDescriptor engine, ArmStatus status, String detail,
                            String settings, long cpuNanos, long wallNanos, String foundVersion,
                            List<String> threadsLeftBehind) {
        return new ArmResult(engine.id(), engine.displayName(), status, detail, settings, null,
                cpuNanos, wallNanos, engine.measuredVersion(), foundVersion, threadsLeftBehind);
    }

    // ------------------------------------------------------------ what it says

    /** Which engine this arm drove. */
    public EngineId engine() {
        return engine;
    }

    /** How that engine's own authors spell its name. */
    public String engineName() {
        return engineName;
    }

    /** How the arm ended, in a form a caller can branch on. */
    public ArmStatus status() {
        return status;
    }

    /** The finished sentence a person reads about how it ended. Never empty. */
    public String detail() {
        return detail;
    }

    /** The arguments the engine was driven with, as the table shows them. */
    public String settings() {
        return settings;
    }

    /**
     * The registered recording, or null where the arm produced none.
     *
     * <p>Not shown, not saved, and not registered with any window: what comes
     * back is a recording in memory for the arbiter to score.
     */
    public ImagePlus registered() {
        return registered;
    }

    /** True when a registered recording came back. */
    public boolean hasRegisteredStack() {
        return registered != null;
    }

    /** What the arm cost on the processor clock. Never the wall clock - defect D6. */
    public long cpuNanos() {
        return cpuNanos;
    }

    /** The same figure in seconds, as the Comparison table carries it. */
    public double cpuSeconds() {
        return cpuNanos < 0 ? Double.NaN : cpuNanos / 1e9;
    }

    /**
     * How much time passed, which belongs in a progress bar and nowhere else.
     *
     * <p>Kept because the gap between this and the processor figure is how a run
     * that spent three hours asleep is told apart from one that spent three
     * hours computing. It is never ranked on and never written into a table.
     */
    public long wallNanos() {
        return wallNanos;
    }

    /** The version this plugin's figures were measured against. */
    public String measuredVersion() {
        return measuredVersion;
    }

    /** The version found on this computer, or an empty string when unread. */
    public String foundVersion() {
        return foundVersion;
    }

    /** True when the engine ran at a version other than the measured one. */
    public boolean drivenAtAnUntestedVersion() {
        return versionDiffers(measuredVersion, foundVersion);
    }

    /**
     * True when the processor figure beside this arm is a floor rather than what
     * the engine cost.
     *
     * <p>Processor time is counted per thread, and the thread an arm counts is
     * the one the engine was driven from. An engine that hands its work to
     * threads of its own is therefore counted for almost none of it. That is not
     * a hypothesis: Correct 3D drift, driven on a twelve-frame recording on
     * 2026-08-13, took fourteen seconds and reported a sixteenth of one, because
     * the script does its work elsewhere.
     *
     * <p>A run that spent the time asleep leaves the same trace, and these two
     * numbers cannot tell the two apart. So this says the figure is a floor
     * rather than guessing which, and {@link #detail()} says so in words.
     *
     * <p>The test is deliberately blunt - less than a fifth of an interval of at
     * least two seconds - so that ordinary background, class loading and garbage
     * collection do not trip it.
     */
    public boolean cpuIsAFloor() {
        if (cpuNanos < 0 || wallNanos < MIN_FLOOR_WALL_NANOS) return false;
        return cpuNanos < FLOOR_SHARE * wallNanos;
    }

    /**
     * The threads that were still running after the arm finished, by name.
     *
     * <p>Reported and never stopped. See {@link EngineRunner} for what counts as
     * left behind and why stopping one from outside is not on the table.
     */
    public List<String> threadsLeftBehind() {
        return threadsLeftBehind;
    }

    /** True when this engine should not be driven again in this session. */
    public boolean driveOncePerSession() {
        return status.drivenOnceIsEnough();
    }

    /**
     * The {@code status} cell of the Comparison table.
     *
     * <p>The status word, then whatever a person needs beside it: the version
     * found whenever it differs from the measured one - whichever status won the
     * precedence - and the number of threads still running when the arm left
     * some behind.
     */
    public String statusColumn() {
        StringBuilder cell = new StringBuilder(status.tableValue());
        if (status == ArmStatus.WRONG_VERSION) {
            cell.append(": ").append(foundVersion);
        } else if (drivenAtAnUntestedVersion()) {
            cell.append("; ").append(ArmStatus.WRONG_VERSION.tableValue())
                    .append(": ").append(foundVersion);
        }
        if (!threadsLeftBehind.isEmpty() && status != ArmStatus.LEAKED_THREADS) {
            cell.append("; ").append(ArmStatus.LEAKED_THREADS.tableValue())
                    .append("=").append(threadsLeftBehind.size());
        } else if (status == ArmStatus.LEAKED_THREADS) {
            cell.append("=").append(threadsLeftBehind.size());
        }
        return cell.toString();
    }

    /**
     * Writes this arm into a Comparison table.
     *
     * <p>The one place an arm and the table meet, so the figure a caller reads
     * and the figure a person sees cannot disagree. The scoring columns are left
     * unmeasured: the arbiter fills those, and a zero written here would read as
     * a measurement of no improvement rather than as a measurement nobody took.
     */
    public void appendTo(ResultsTable table) {
        RegDriftTables.ComparisonRow row = RegDriftTables.comparisonRow()
                .engine(engineName)
                .settings(settings)
                .status(statusColumn());
        if (cpuNanos >= 0) row.cpuSeconds(cpuSeconds());
        row.appendTo(table);
    }

    @Override
    public String toString() {
        return engineName + ": " + statusColumn();
    }

    // ---------------------------------------------------------------- inside

    private static boolean versionDiffers(String measured, String found) {
        if (measured == null || found == null) return false;
        if (measured.trim().isEmpty() || found.trim().isEmpty()) return false;
        return !measured.trim().equals(found.trim());
    }

    private static String detailFor(EngineDescriptor engine, ArmStatus status,
                                    List<String> left, String measured, String found,
                                    long cpuNanos, long wallNanos) {
        StringBuilder said = new StringBuilder();
        if (status == ArmStatus.LEAKED_THREADS) {
            said.append(engine.displayName()).append(" registered the recording and left ")
                    .append(left.size()).append(left.size() == 1 ? " thread" : " threads")
                    .append(" running afterwards (").append(joined(left)).append("). Nothing was")
                    .append(" stopped and nothing was closed. This plugin drives it once in a")
                    .append(" session; restart Fiji to compare it again.");
        } else {
            said.append(engine.displayName()).append(" registered the recording.");
        }
        if (versionDiffers(measured, found)) {
            said.append(" This computer has version ").append(found.trim())
                    .append(" and this plugin's figures were measured against ")
                    .append(measured.trim()).append(", so the result is real and the numbers")
                    .append(" beside it come from a version somebody else had.");
        }
        if (cpuNanos >= 0 && wallNanos >= MIN_FLOOR_WALL_NANOS
                && cpuNanos < FLOOR_SHARE * wallNanos) {
            said.append(String.format(" Of the %.1f s this arm took, %.1f s was processor time on"
                            + " the thread the engine was driven from, so the figure beside it is"
                            + " a floor rather than a total: either the engine handed its work to"
                            + " threads of its own, which are not counted here, or this computer"
                            + " was not giving the run a processor.",
                    wallNanos / 1e9, cpuNanos / 1e9));
        }
        return said.toString();
    }

    private static String joined(List<String> names) {
        StringBuilder joined = new StringBuilder();
        for (String name : names) {
            if (joined.length() > 0) joined.append(", ");
            joined.append(name);
        }
        return joined.toString();
    }
}
