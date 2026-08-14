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
 * The answer to "this will take about this long - go ahead?", asked once, before
 * any engine is driven.
 *
 * <p>A comparison is every engine run over the whole recording, which on a
 * five-hundred-frame stack is minutes to tens of minutes. Starting that without
 * saying so is how a plugin earns a reputation for hanging Fiji.
 *
 * <p><b>Asking is not this package's job, and answering is not the run's.</b>
 * {@link RegDrift#run} cannot open a window - that is asserted from its compiled
 * form - so it works out the estimate, hands it here, and does nothing if the
 * answer is no. The menu entry supplies an implementation that puts the question
 * in a box; a macro line, a batch loop and a headless caller get
 * {@link #always()}, because a script that asked for a comparison has already
 * said yes and there is nobody at the keyboard to ask.
 *
 * <p>A run whose answer is no does exactly nothing: no engine is dispatched, no
 * file is written, and the result carries a {@link Failure.Kind#CANCELED} reason
 * rather than an empty table.
 */
public interface Dispatch {

    /**
     * Whether to go ahead.
     *
     * @param estimate what the comparison is expected to cost, and what is a
     *                 measurement and what is a stand-in
     * @return true to drive the engines, false to run nothing at all
     */
    boolean proceed(DispatchEstimate estimate);

    /** The answer a script gives: yes, without asking anybody. */
    static Dispatch always() {
        return Always.INSTANCE;
    }

    /** The answer a test gives when it wants nothing driven. */
    static Dispatch never() {
        return Never.INSTANCE;
    }

    /** Yes, without asking. */
    final class Always implements Dispatch {

        static final Always INSTANCE = new Always();

        private Always() {
        }

        @Override
        public boolean proceed(DispatchEstimate estimate) {
            return true;
        }

        @Override
        public String toString() {
            return "dispatch without asking";
        }
    }

    /** No, without asking. */
    final class Never implements Dispatch {

        static final Never INSTANCE = new Never();

        private Never() {
        }

        @Override
        public boolean proceed(DispatchEstimate estimate) {
            return false;
        }

        @Override
        public String toString() {
            return "dispatch nothing";
        }
    }
}
