/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.ui;

import ij.IJ;
import regdrift.Cancellation;
import sc.fiji.oc3d.core.progress.StatusBarProgress;

/**
 * What a run says while it is working, and the switch that stops it.
 *
 * <p>Two halves of the same thing, kept together because they are the two ways a
 * long run and the person waiting for it stay in touch: the status bar says how
 * far along it is, and Esc says stop.
 *
 * <h2>Which thread does what</h2>
 *
 * <p>Every method here is meant for the coordinator - the thread that started
 * the run and is the plugin's single point of contact with ImageJ. Worker
 * threads do not report and do not poll; they are handed float arrays and hand
 * float arrays back. {@link #cancellation()} is the exception, and safely so: it
 * hands out a plain switch that a worker can read without touching ImageJ at
 * all, because {@link #checkEscape()} on the coordinator is what pulls it.
 *
 * <h2>Why Esc rather than a Stop window</h2>
 *
 * <p>Esc is what stops a long operation everywhere else in Fiji, so it is what
 * somebody will reach for without being told. A window with a Stop button is the
 * results view's business, and it can pull the same switch when it arrives.
 *
 * <h2>Wall clock and CPU clock</h2>
 *
 * <p>Anything shown here is elapsed time, which is what somebody waiting wants
 * to know. It is not a measurement, and no number from here reaches a table or a
 * ranking - those are CPU seconds, taken from the CPU clock, for the reason
 * recorded as defect D6 in the build plan: a laptop that went to sleep mid-run
 * once turned up in a benchmark as an engine taking 237 seconds per frame pair.
 */
public final class Progress {

    private final StatusBarProgress bar;
    private final Cancellation.Flag stop;

    /**
     * The switch a run actually reads, which looks at Esc as well as at the flag.
     *
     * <p>Nothing else in a run is in a position to notice Esc. The parts that
     * take time - frame pairs, pyramid levels, the gap between two engine arms -
     * read the switch, so the switch is where the keyboard has to be looked at.
     * Reading it is a read of one boolean field, which costs nothing at the rate
     * a worker asks, and a comparison that runs for ten minutes with no way to
     * stop it is a frozen Fiji.
     */
    private final Cancellation watching = new Cancellation() {
        @Override
        public boolean canceled() {
            return stop.canceled() || checkEscape();
        }

        @Override
        public String toString() {
            return stop.toString();
        }
    };

    private Progress(StatusBarProgress bar) {
        this.bar = bar;
        this.stop = Cancellation.flag();
    }

    /**
     * A reporter for a run of a known number of steps, which resets Esc so that
     * a stray press from earlier does not stop this run before it starts.
     *
     * @param totalSteps how many named steps the run has
     */
    public static Progress forRun(int totalSteps) {
        Progress progress = new Progress(StatusBarProgress.steps(totalSteps));
        progress.resetEscape();
        return progress;
    }

    /** A reporter that shows nothing, for a headless call or a batch loop. */
    public static Progress silent() {
        return new Progress(StatusBarProgress.none());
    }

    /** Moves to the next step and names it. */
    public void step(String description) {
        bar.step(description);
    }

    /** Changes what the current step says without moving the bar. */
    public void detail(String description) {
        bar.detail(description);
    }

    /** Fills the bar and says the run is done. */
    public void finish(String description) {
        bar.finish(description);
    }

    /** Says the run gave up, and clears the bar. */
    public void failed(String pluginName, String description) {
        bar.error(pluginName, description);
    }

    /**
     * The switch a run reads to find out whether it has been asked to stop.
     *
     * <p>Pulled by a Cancel button, by {@link #cancel()}, and by Esc - see
     * {@link #checkEscape()}. Once pulled it stays pulled, so a run cannot look
     * twice and get two answers.
     */
    public Cancellation cancellation() {
        return watching;
    }

    /** Asks the run to stop. What a Cancel button calls. */
    public void cancel() {
        stop.cancel();
    }

    /** True once this run has been asked to stop. */
    public boolean canceled() {
        return stop.canceled();
    }

    /**
     * Looks at whether Esc has been pressed and pulls the switch if it has.
     *
     * <p>What a worker sees is the switch this sets. The read itself is one
     * static boolean field of {@code ij.IJ} - not a window, not a component, and
     * nothing that has to be on the window's thread - which is what makes it safe
     * for {@link #cancellation()} to look at it from wherever a run happens to be.
     *
     * @return true when the run has been asked to stop, whether by Esc or by a
     *         Cancel button
     */
    public boolean checkEscape() {
        try {
            if (IJ.escapePressed()) stop.cancel();
        } catch (RuntimeException noImageJ) {
            // A unit test or a headless run has no keyboard to ask about.
        }
        return stop.canceled();
    }

    private void resetEscape() {
        try {
            IJ.resetEscape();
        } catch (RuntimeException noImageJ) {
            // A unit test or a headless run has no keyboard to reset.
        }
    }
}
