/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

/**
 * Why a run could not produce what it was asked for.
 *
 * <p>Every path that gives up returns one of these instead of an empty result:
 * a kind a caller can branch on, and a finished sentence a user can read. A run
 * that returns nothing and logs a line to the ImageJ window is a run that looks
 * successful to a macro and to a batch loop, and that is the failure mode this
 * type exists to make impossible.
 *
 * <p>Later stages add kinds as they find new ways to give up. Nothing removes
 * one: a kind that was released is something a script may already branch on.
 */
public final class Failure {

    /** What went wrong, in a form a caller can branch on. */
    public enum Kind {

        /** The settings could not be used as given. */
        INVALID_PARAMETERS,

        /**
         * The request is understood and this build does not carry it out.
         *
         * <p>A branch that has not been written returns this rather than an empty
         * bundle or a thrown exception, so a dialog can be opened and clicked, and
         * a macro can be replayed, before the engine underneath it exists.
         *
         * <p><b>Nothing in this build produces it.</b> All five modes are carried
         * out; the last two arrived in stage 13. It is kept as vocabulary - a
         * later build that adds a mode has somewhere to put the answer before the
         * branch behind it exists, which is what let the dialog be built and
         * clicked four stages before an estimator existed. {@code FacadeTest}
         * asserts that no mode gives this reason, so it cannot come back
         * unnoticed.
         */
        NOT_IMPLEMENTED,

        /** The image holds a single frame, so there is no movement to measure. */
        NO_TIME_AXIS,

        /** There are frames, but too few for the measurement asked for. */
        TOO_FEW_FRAMES,

        /** The second stack does not match the recording it is scored against. */
        SECOND_STACK_MISMATCH,

        /** The movement estimate did not converge or could not be trusted. */
        ESTIMATOR_FAILED,

        /** A named engine is absent from this Fiji, or is a version not driven. */
        ENGINE_UNAVAILABLE,

        /** An engine ran and did not return a usable stack. */
        ENGINE_FAILED,

        /** The arm produced a stack that could not be scored. */
        SCORING_FAILED,

        /** The user stopped the run. */
        CANCELED,

        /** The results could not be written to the folder they were asked for. */
        SAVE_FAILED,

        /**
         * A file in the auto-save tree would sit at a path this system refuses.
         *
         * <p>Separate from {@link #SAVE_FAILED} because the repair is different
         * and the user can carry it out: shorten the save root, or shorten the
         * image title. Windows refuses a path of 260 characters or more, and a
         * microscope-generated title inside a synchronized folder reaches that
         * without anybody noticing.
         */
        PATH_TOO_LONG,

        /** A defect in this plugin. Reported as such rather than as user error. */
        INTERNAL_ERROR
    }

    private final Kind kind;
    private final String message;

    private Failure(Kind kind, String message) {
        this.kind = kind;
        this.message = message;
    }

    /**
     * A typed reason with the sentence a user reads.
     *
     * @param kind    what went wrong
     * @param message finished text, not a code and not an exception class name
     * @throws IllegalArgumentException when either argument is missing, since a
     *         reason nobody can read is the empty result this type replaces
     */
    public static Failure of(Kind kind, String message) {
        if (kind == null) {
            throw new IllegalArgumentException("A failure needs a kind, so a caller can branch on it.");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("A failure of kind " + kind
                    + " needs a sentence a user can read.");
        }
        return new Failure(kind, message.trim());
    }

    /** What went wrong. */
    public Kind kind() {
        return kind;
    }

    /** The finished sentence a user reads. */
    public String message() {
        return message;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Failure)) return false;
        Failure that = (Failure) other;
        return kind == that.kind && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return kind.hashCode() * 31 + message.hashCode();
    }

    @Override
    public String toString() {
        return kind + ": " + message;
    }
}
