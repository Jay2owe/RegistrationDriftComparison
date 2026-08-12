/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A one-way switch that says whether the person who started a run has since
 * asked it to stop.
 *
 * <p>Think of it as the emergency cord on a train: anybody can pull it, it
 * cannot be pushed back in, and the driver looks at it between stations rather
 * than continuously. A run carries one of these in its settings, the parts of a
 * run that take time look at it where stopping is safe - between frame pairs,
 * between pyramid levels and between engine arms - and a run that finds it
 * pulled hands back a {@link Failure.Kind#CANCELED} reason.
 *
 * <p>Stopping is therefore an answer, not an exception. A thrown exception
 * unwinds through code that was in the middle of something, and a half-finished
 * table shown to a user is worse than a sentence saying the run was stopped.
 *
 * <p>The spelling is US English throughout, matching
 * {@link Failure.Kind#CANCELED}, which shipped in the stage before this one.
 */
public interface Cancellation {

    /** True once the run has been asked to stop. Never goes back to false. */
    boolean canceled();

    /** A switch nobody can pull: what a run carries when nothing offered one. */
    static Cancellation never() {
        return Never.INSTANCE;
    }

    /** A fresh switch, ready to be pulled once. */
    static Flag flag() {
        return new Flag();
    }

    /**
     * The switch a dialog or a progress bar holds on to.
     *
     * <p>Safe to pull from the event dispatch thread while a worker reads it,
     * which is the whole reason it is not a plain boolean field: a Cancel button
     * and the run it stops are never on the same thread.
     */
    final class Flag implements Cancellation {

        private final AtomicBoolean pulled = new AtomicBoolean(false);

        Flag() {
        }

        /** Asks the run to stop. Doing it twice is the same as doing it once. */
        public void cancel() {
            pulled.set(true);
        }

        @Override
        public boolean canceled() {
            return pulled.get();
        }

        @Override
        public String toString() {
            return canceled() ? "canceled" : "running";
        }
    }

    /** The switch that is never pulled. */
    final class Never implements Cancellation {

        static final Never INSTANCE = new Never();

        private Never() {
        }

        @Override
        public boolean canceled() {
            return false;
        }

        @Override
        public String toString() {
            return "running";
        }
    }
}
