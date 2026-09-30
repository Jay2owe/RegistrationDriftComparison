/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import regdrift.diag.Localisability;
import regdrift.ui.ResultsPanel;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * No user-facing text says "poorly localisable".
 *
 * <p>{@link Localisability#WARN_BELOW} is defect D12's threshold, deferred to
 * 0.2.0: localisability is reported with its scale and nothing routes on it. The
 * word survives only in the debugging {@code toString()} of
 * {@code Localisability.Result} and {@code ChannelRanker.ChannelQuality}, which no
 * table, record, results view or saved file calls. This runs a recording whose
 * localisability is well below the threshold through every mode that measures
 * and reads every piece of text a person could see, so a later change that
 * routed one of those {@code toString()}s to the screen would fail here.
 */
public class PoorlyLocalisableTextTest {

    private static final String WORD = "poorly localisable";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aRecordingBelowTheOldThresholdIsNeverCalledPoorlyLocalisable() throws Exception {
        for (Mode mode : new Mode[]{Mode.DIAGNOSE, Mode.DIAGNOSE_AND_RECOMMEND}) {
            File root = folder.newFolder(mode.macroValue());
            RegDriftResult result = RegDrift.run(RegDriftParameters.builder(smoothDrift())
                    .mode(mode)
                    .hideDisplay(true)
                    .saveRoot(root.getAbsolutePath())
                    .build());
            assertTrue(result.failure() == null ? "" : result.failure().message(),
                    result.isSuccess());

            double localisability = result.diagnosis().getValue("localisability", 0);
            assertTrue("the fixture must sit below the old threshold, or this test is about"
                            + " nothing: " + localisability,
                    localisability < Localisability.WARN_BELOW);

            StringBuilder seen = new StringBuilder();
            seen.append(text(result.diagnosis())).append(text(result.recommendation()));
            seen.append(result.verdictReason()).append('\n');
            Provenance provenance = result.provenance();
            assertNotNull(provenance);
            seen.append(provenance.channelReason()).append('\n')
                    .append(provenance.calibrationSet()).append('\n');
            for (Trace trace : result.traces()) seen.append(trace).append('\n');
            for (String line : ResultsPanel.lines(result)) seen.append(line).append('\n');
            seen.append(ResultsPanel.clipboardText(result));

            RegDriftAutoSave.Report report = RegDriftAutoSave.save(result);
            assertTrue(report.isSuccess());
            seen.append(GoldenOutputsTest.tree(new File(root, RegDriftAutoSave.TREE_FOLDER),
                    root));

            assertFalse(mode.macroValue() + ": '" + WORD + "' reached user-facing text",
                    seen.toString().toLowerCase(java.util.Locale.ROOT).contains(WORD));
        }
    }

    private static String text(ResultsTable table) {
        if (table == null) return "";
        StringBuilder out = new StringBuilder();
        for (int row = 0; row < table.size(); row++) {
            for (String column : table.getHeadings()) {
                out.append(RegDriftTables.cellText(table, column, row)).append('|');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** A wide, smooth bump drifting 0.7 / -0.4 px per frame: nothing sharp to localise. */
    private static ImagePlus smoothDrift() {
        int side = 128;
        int frames = 12;
        ImageStack stack = new ImageStack(side, side);
        for (int t = 0; t < frames; t++) {
            double ox = 0.7 * t;
            double oy = -0.4 * t;
            float[] plane = new float[side * side];
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    double dx = (x - ox - side / 2.0) / (0.45 * side);
                    double dy = (y - oy - side / 2.0) / (0.45 * side);
                    plane[y * side + x] = (float) (1000 + 2000 * Math.exp(-(dx * dx + dy * dy)));
                }
            }
            stack.addSlice("t" + (t + 1), new FloatProcessor(side, side, plane, null));
        }
        return new ImagePlus("smooth", stack);
    }
}
