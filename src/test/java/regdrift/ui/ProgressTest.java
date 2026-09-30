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
import org.junit.After;
import org.junit.Test;

import java.awt.event.KeyEvent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Esc stops a run even when ImageJ clears it before the run looks.
 *
 * <p>ImageJ clears the key every time a menu command starts, and a comparison
 * starts one per engine. Found by the GUI checks: Esc pressed during one engine
 * was gone by the time the next had started, and the comparison ran to the end.
 */
public class ProgressTest {

    @After
    public void clearTheKey() {
        IJ.resetEscape();
    }

    @Test
    public void escClearedByTheNextCommandStillStopsTheRun() throws Exception {
        IJ.resetEscape();
        Progress progress = Progress.silent();
        Runnable endWatch = progress.watchEscape();
        try {
            assertFalse(progress.canceled());
            IJ.setKeyDown(KeyEvent.VK_ESCAPE);
            Thread.sleep(Progress.ESC_POLL_MS * 6);
            IJ.resetEscape();   // what the next engine's menu command does as it starts
            assertTrue("Esc was lost when ImageJ cleared it", progress.cancellation().canceled());
        } finally {
            endWatch.run();
        }
    }

    @Test
    public void noEscNoStop() throws Exception {
        IJ.resetEscape();
        Progress progress = Progress.silent();
        Runnable endWatch = progress.watchEscape();
        try {
            Thread.sleep(Progress.ESC_POLL_MS * 3);
            assertFalse(progress.cancellation().canceled());
        } finally {
            endWatch.run();
        }
    }
}
