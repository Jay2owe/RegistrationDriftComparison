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
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code RegDrift.run} in diagnose mode, end to end: a real recording in, a filled
 * {@code Diagnosis} table and a verdict out.
 *
 * <h2>What this is checking that the measurement tests are not</h2>
 *
 * <p>{@code FingerprintParallelTest} and {@code VerdictTest} check the measurement and the decision.
 * This checks the join: that the channel ranking picks a channel and says why, that the scale the
 * fingerprint chose reaches the {@code measured_at_bin} column beside the localisability it belongs
 * to, that the estimation channel's row has every contract column in it, and that the verdict a
 * caller reads and the verdict in the table are the same verdict.
 *
 * <p>The pairing of {@code localisability} and {@code measured_at_bin} is asserted directly. A row
 * carrying one without the other is exactly defect D12, and the row is where it would happen.
 */
public class DiagnoseModeTest {

    private static final int SIDE = 96;
    private static final int FIELD = 176;
    private static final int FRAMES = 36;

    /** Columns that may be blank on a recording nothing unusual happened in. */
    private static final List<String> MAY_BE_UNSET = Arrays.asList("bridge_span");

    @Test
    public void diagnoseModeFillsEveryContractColumnOnTheChannelItMeasured() {
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie(3))
                .mode(Mode.DIAGNOSE)
                .build());

        assertTrue(result.failure() == null ? "" : result.failure().message(), result.isSuccess());
        ResultsTable diagnosis = result.diagnosis();
        assertNotNull(diagnosis);
        assertEquals("one row per channel", 3, diagnosis.size());

        int measured = rowOfChannel(diagnosis, result.provenance().channel());
        for (String column : RegDriftTables.DIAGNOSIS_COLUMNS) {
            String cell = RegDriftTables.cellText(diagnosis, column, measured);
            if (MAY_BE_UNSET.contains(column)) continue;
            assertFalse("the measured channel's '" + column + "' is empty, and every contract"
                    + " column is filled on the channel the movement was measured on",
                    cell.isEmpty());
            assertFalse("the measured channel's '" + column + "' reads NaN", "NaN".equals(cell));
        }
    }

    /**
     * Defect D12: the scale and the number it belongs to are written together, in the same row,
     * every time. There is no row that holds one and not the other.
     */
    @Test
    public void everyRowThatHasALocalisabilityAlsoHasTheScaleItWasMeasuredAt() {
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie(2))
                .mode(Mode.DIAGNOSE)
                .build());

        ResultsTable diagnosis = result.diagnosis();
        assertEquals(2, diagnosis.size());
        for (int row = 0; row < diagnosis.size(); row++) {
            String value = RegDriftTables.cellText(diagnosis, "localisability", row);
            String scale = RegDriftTables.cellText(diagnosis, "measured_at_bin", row);
            assertFalse("row " + row + " has no measurement scale beside its localisability."
                    + " See defect D12.", scale.isEmpty());
            assertFalse("row " + row + " has a scale but nothing measured at it", value.isEmpty());
            assertTrue("the scale is a binning factor of 1 or more, and reads '" + scale + "'",
                    Integer.parseInt(scale) >= 1);
        }
        assertEquals("and the provenance states the same scale once more",
                Integer.parseInt(RegDriftTables.cellText(diagnosis, "measured_at_bin", 0)),
                result.provenance().measuredAtBin());
    }

    @Test
    public void theVerdictACallerReadsIsTheVerdictInTheTable() {
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie(1))
                .mode(Mode.DIAGNOSE)
                .build());

        assertNotNull(result.verdict());
        assertFalse("the verdict carries the sentence behind it",
                result.verdictReason().isEmpty());
        assertEquals(result.verdict().tableValue(),
                RegDriftTables.cellText(result.diagnosis(), "verdict", 0));
    }

    /**
     * The provenance is what answers "why was this movie registered that way?" six months later, so
     * it carries the scale, the channel, the reason for the channel and where the windows sat.
     */
    @Test
    public void theProvenanceSaysEnoughToRepeatTheRun() {
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie(2))
                .mode(Mode.DIAGNOSE)
                .build());

        Provenance provenance = result.provenance();
        assertNotNull(provenance);
        assertEquals(RegDrift.VERSION, provenance.pluginVersion());
        assertEquals(Mode.DIAGNOSE, provenance.mode());
        assertTrue("a channel was settled on", provenance.channel() >= 1);
        assertFalse("and the reason is a finished sentence", provenance.channelReason().isEmpty());
        assertTrue(provenance.measuredAtBin() >= 1);
        assertEquals("three windows of twelve frames on a 36-frame recording",
                3, provenance.windowStarts().length);
        assertEquals(12, provenance.windowFrames());
    }

    /** A named channel is measured instead of the ranking's choice, and the record says so. */
    @Test
    public void aNamedChannelIsMeasuredInsteadOfTheRankingsChoice() {
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie(3))
                .mode(Mode.DIAGNOSE)
                .channel(Channel.of(2))
                .build());

        assertTrue(result.isSuccess());
        assertEquals(2, result.provenance().channel());
        assertTrue(result.provenance().channelReason(),
                result.provenance().channelReason().contains("was asked for"));
        assertEquals("2", RegDriftTables.cellText(result.diagnosis(), "channel",
                rowOfChannel(result.diagnosis(), 2)));
    }

    /**
     * Defect D7, at the level a user meets it: a recording nothing can be localized in still
     * produces a full diagnosis. The verdict warns; nothing refuses.
     */
    @Test
    public void aRecordingNothingCanBeLocalizedInStillProducesADiagnosis() {
        float[][] planes = new float[FRAMES][];
        for (int t = 0; t < FRAMES; t++) {
            planes[t] = new float[SIDE * SIDE];
            Arrays.fill(planes[t], 700f);
        }
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(
                        stack("nothing at all", planes))
                .mode(Mode.DIAGNOSE)
                .build());

        assertTrue("a low measurement never stops a diagnosis being produced - see defect D7",
                result.isSuccess());
        assertNull(result.failure());
        assertEquals(Verdict.WARN_LOW_STRUCTURE, result.verdict());
        assertNotNull(result.diagnosis());
        assertEquals(1, result.diagnosis().size());
        assertFalse("and the scale is still stated",
                RegDriftTables.cellText(result.diagnosis(), "measured_at_bin", 0).isEmpty());
    }

    /** Asking for every consecutive pair measures every consecutive pair. */
    @Test
    public void askingForEveryConsecutivePairMeasuresTheWholeChain() {
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie(1))
                .mode(Mode.DIAGNOSE)
                .windows(Windows.of(Windows.ALL_PAIRS))
                .build());

        assertTrue(result.isSuccess());
        assertEquals("one window covering the recording leaves no gap to bridge",
                0, result.provenance().windowStarts().length);
        assertEquals(FRAMES, result.provenance().windowFrames());
    }

    /** A slice that is not in the stack is a settings mistake, reported as one. */
    @Test
    public void aSliceOutsideTheStackIsReportedAsASettingsMistake() {
        RegDriftResult result = RegDrift.run(RegDriftParameters.builder(movie(1))
                .mode(Mode.DIAGNOSE)
                .slice(Slice.of(7))
                .build());

        assertFalse(result.isSuccess());
        assertEquals(Failure.Kind.INVALID_PARAMETERS, result.failure().kind());
        assertTrue(result.failure().message(), result.failure().message().contains("slice 7"));
    }

    // ---------------------------------------------------------------- machinery

    private static int rowOfChannel(ResultsTable table, int channel) {
        for (int row = 0; row < table.size(); row++) {
            if (RegDriftTables.cellText(table, "channel", row).equals(Integer.toString(channel))) {
                return row;
            }
        }
        throw new AssertionError("no row for channel " + channel);
    }

    /**
     * A recording that drifts, jitters and takes one knock, cut as overlapping windows out of one
     * broadband field. Later channels are the same movement over a dimmer copy, so the ranking has
     * something to rank and the answer is not a coin toss.
     */
    private static ImagePlus movie(int channels) {
        float[] field = texture(FIELD, FIELD, 20260813L, 4);
        float[][][] planes = new float[channels][FRAMES][];
        for (int t = 0; t < FRAMES; t++) {
            int knock = t >= FRAMES / 2 ? 9 : 0;
            int ox = 8 + (int) Math.round(0.35 * t) + knock;
            int oy = 8 + (t % 5) + knock;
            float[] window = crop(field, FIELD, ox, oy, SIDE, SIDE);
            for (int c = 0; c < channels; c++) {
                float[] plane = new float[window.length];
                for (int i = 0; i < window.length; i++) {
                    // Channel 1 keeps the contrast; the others are flattened toward their own mean,
                    // so channel 1 is the one a localisability ranking should settle on.
                    plane[i] = (float) (128 + (window[i] - 128) / (1 + 3 * c));
                }
                planes[c][t] = plane;
            }
        }
        if (channels == 1) return stack("one channel", planes[0]);
        ImageStack images = new ImageStack(SIDE, SIDE);
        for (int t = 0; t < FRAMES; t++) {
            for (int c = 0; c < channels; c++) {
                images.addSlice("c" + (c + 1) + "t" + (t + 1),
                        new FloatProcessor(SIDE, SIDE, planes[c][t], null));
            }
        }
        ImagePlus imp = new ImagePlus("several channels", images);
        imp.setDimensions(channels, 1, FRAMES);
        imp.setOpenAsHyperStack(true);
        return imp;
    }

    private static ImagePlus stack(String title, float[][] planes) {
        ImageStack images = new ImageStack(SIDE, SIDE);
        for (int t = 0; t < planes.length; t++) {
            images.addSlice("t" + (t + 1), new FloatProcessor(SIDE, SIDE, planes[t], null));
        }
        return new ImagePlus(title, images);
    }

    /** A smoothed random field: broadband, with structure at every scale down to a few pixels. */
    private static float[] texture(int w, int h, long seed, int passes) {
        java.util.Random rng = new java.util.Random(seed);
        float[] f = new float[w * h];
        for (int i = 0; i < f.length; i++) f[i] = (float) (128 + 40 * rng.nextGaussian());
        for (int pass = 0; pass < passes; pass++) {
            float[] t = f.clone();
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    f[y * w + x] = (t[y * w + x] * 4
                            + t[y * w + x - 1] + t[y * w + x + 1]
                            + t[(y - 1) * w + x] + t[(y + 1) * w + x]) / 8f;
                }
            }
        }
        return f;
    }

    private static float[] crop(float[] field, int fieldWidth, int ox, int oy, int w, int h) {
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            System.arraycopy(field, (y + oy) * fieldWidth + ox, out, y * w, w);
        }
        return out;
    }
}
