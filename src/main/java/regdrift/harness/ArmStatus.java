/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.harness;

/**
 * How one engine's run ended, in a form a caller can branch on and a table can
 * print.
 *
 * <p>An arm is one registration engine driven once over one recording. Seven
 * things can happen to it and every one of them is a value here, because the
 * alternative - a null stack and a line in the ImageJ log - looks like success to
 * a macro and to a batch loop. That is house rule 14, and this is the type that
 * keeps it for the half of the plugin that runs other people's code.
 *
 * <h2>Two of these are findings rather than faults</h2>
 *
 * <p>{@link #LEAKED_THREADS} means the engine finished and did its job, and left
 * threads running afterwards. That is worth reporting - it is the reason the
 * research harness this code is lifted from ended the whole Java process when it
 * was done, which inside somebody's Fiji would take their unsaved images with it
 * - but it is not a failure of the registration. {@link #WRONG_VERSION} means the
 * engine ran at a version other than the one the plugin's numbers were measured
 * against; the result is real and the row says so.
 *
 * <h2>The precedence, when more than one applies</h2>
 *
 * <p>An arm gets exactly one status. Where several would fit, the one that
 * matters most to somebody reading the table wins:
 *
 * <ol>
 *   <li>the ones that mean there is no registered stack at all -
 *       {@link #NOT_INSTALLED}, {@link #CANCELED}, {@link #TIMED_OUT},
 *       {@link #COULD_NOT_DRIVE};</li>
 *   <li>{@link #LEAKED_THREADS}, because it changes what the plugin will let
 *       somebody do next in this session;</li>
 *   <li>{@link #WRONG_VERSION};</li>
 *   <li>{@link #OK}.</li>
 * </ol>
 *
 * <p>Nothing is lost by that ordering: the version found is written into the
 * status cell whenever it differs, whichever status won.
 *
 * <h2>The spelling of the stopped one</h2>
 *
 * <p>{@link #CANCELED} carries one L. The build plan's sketch for this stage
 * spells it with two, which is the British form; house rule 8 is US English
 * throughout, and {@code Failure.Kind.CANCELED} and {@code Cancellation.canceled()}
 * were released with one L in earlier stages. Two spellings of the same state
 * across one plugin is worse than a sketch that was not followed to the letter.
 */
public enum ArmStatus {

    /** The engine ran, changed the recording, and left nothing behind. */
    OK("ok"),

    /**
     * The engine is not on this computer, so nothing was attempted.
     *
     * <p>Reported, and the comparison carries on with the next engine. Nothing
     * here fetches anything: repairing an absent engine is a button somebody
     * presses in the Engines section, and no path from a run reaches it.
     */
    NOT_INSTALLED("not_installed"),

    /**
     * The engine ran at a version other than the one the plugin's figures were
     * measured against.
     *
     * <p>Driven anyway rather than refused, because a version this plugin has not
     * measured is still the version somebody has, and a result they can see beats
     * a refusal they cannot act on. The status cell names the version found.
     */
    WRONG_VERSION("driven_untested_version"),

    /**
     * The engine could not be driven: absent command, changed arguments, a
     * throwable out of somebody else's code, or a run that finished without
     * changing a pixel.
     *
     * <p>That last one matters. ImageJ swallows some failures into its log
     * window rather than raising them, so an arm that reported nothing and did
     * nothing would otherwise be recorded as a success that happened to improve
     * the recording by zero. The arm checks the pixels rather than trusting the
     * absence of an exception.
     */
    COULD_NOT_DRIVE("could_not_drive"),

    /**
     * The engine did not come back inside the time the arm was given.
     *
     * <p>The thread it was running on is left alone - never stopped, never
     * killed - and reported as still running. Stopping a thread from outside is
     * what unlocks half-written state, and this code is inside somebody's live
     * session.
     */
    TIMED_OUT("timed_out"),

    /** The person running the comparison stopped it before this arm started. */
    CANCELED("canceled"),

    /**
     * The engine ran and left threads behind that were still running afterwards.
     *
     * <p>The registered stack is real. The engine is marked as one to drive once
     * in a session, and the plugin says so rather than driving it again.
     */
    LEAKED_THREADS("leaked_threads");

    private final String tableValue;

    ArmStatus(String tableValue) {
        this.tableValue = tableValue;
    }

    /** The word this status is written as in the Comparison table. */
    public String tableValue() {
        return tableValue;
    }

    /** True when a registered stack came back from this arm. */
    public boolean producedAStack() {
        return this == OK || this == WRONG_VERSION || this == LEAKED_THREADS;
    }

    /** True when this engine should not be driven again in this session. */
    public boolean drivenOnceIsEnough() {
        return this == LEAKED_THREADS || this == TIMED_OUT;
    }

    @Override
    public String toString() {
        return tableValue;
    }
}
