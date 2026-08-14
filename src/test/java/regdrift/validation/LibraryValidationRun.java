/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.validation;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Test;
import regdrift.ArmOutcome;
import regdrift.Channel;
import regdrift.Mode;
import regdrift.RegDrift;
import regdrift.RegDriftParameters;
import regdrift.RegDriftResult;
import regdrift.Recommendation;
import regdrift.Slice;
import regdrift.Verdict;
import regdrift.advise.CeilingAdvice;
import regdrift.diag.MotionLabel;
import regdrift.harness.ArmStatus;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T12 - the twelve library recordings, through the built plugin, against what
 * they were recorded as containing.
 *
 * <p><b>This is the blocking test of the validation stage.</b> Everything before
 * it was measured against fixtures and against this plugin's own assumptions.
 *
 * <h2>The criterion, and it was fixed in writing before the run</h2>
 *
 * <p>Written out in full in {@code VALIDATION.md} § <i>What is pre-registered</i>,
 * and enforced here. In one paragraph: each entry's {@code entry.properties}
 * label was measured on the <b>full uncropped frame, binned, on the best-ranked
 * plane</b>, and the entry beside it is an <b>unbinned crop of channel 1</b>, so
 * that label is reported in every row and is <b>not</b> the pass condition.
 * What is asserted is that every component the entry was cut to demonstrate is
 * present in the measured set, that no entry is called {@code not_registrable},
 * and that the two entries the library records as having no trustworthy answer
 * fail in the two ways they were predicted to fail.
 *
 * <h2>What runs without the library</h2>
 *
 * <p>Three of these tests need the twelve recordings and skip without them. The
 * bare-Fiji check and the two made-up recordings do not, and run everywhere -
 * which is deliberate, because kill criterion 4 is about a machine that has
 * nothing installed and that is exactly what a build machine is.
 */
public class LibraryValidationRun {

    /** One entry, measured. */
    static final class Row {

        final Fixtures.Entry entry;
        final String verdict;
        final String motionLabel;
        final String dominant;
        final String severity;
        final double localisability;
        final double measuredAtBin;
        final double agreementPx;
        final double wander;
        final double driftRatePx;
        final double stepMaxPx;
        final boolean knockPresent;
        final Set<MotionLabel.Component> measured;
        final int engines;
        final int enginesPresent;

        Row(Fixtures.Entry entry, String verdict, String motionLabel, String dominant,
            String severity, double localisability, double measuredAtBin, double agreementPx,
            double wander, double driftRatePx, double stepMaxPx, boolean knockPresent,
            Set<MotionLabel.Component> measured, int engines, int enginesPresent) {
            this.entry = entry;
            this.verdict = verdict;
            this.motionLabel = motionLabel;
            this.dominant = dominant;
            this.severity = severity;
            this.localisability = localisability;
            this.measuredAtBin = measuredAtBin;
            this.agreementPx = agreementPx;
            this.wander = wander;
            this.driftRatePx = driftRatePx;
            this.stepMaxPx = stepMaxPx;
            this.knockPresent = knockPresent;
            this.measured = measured;
            this.engines = engines;
            this.enginesPresent = enginesPresent;
        }

        /** True when everything the entry was cut to demonstrate came out of it. */
        boolean headlineFound() {
            return measured.containsAll(entry.headline());
        }

        /** True when the measured set equals the recorded one - reported, never gated. */
        boolean equalsRecorded() {
            return measured.equals(entry.recordedComponents());
        }
    }

    private static Map<String, Row> measured;

    /**
     * Called first by every test that reads the twelve recordings, and by none of
     * the ones that do not. A class-wide skip would have taken kill criterion 4
     * with it, and that check is most meaningful on a machine that has nothing.
     */
    private static void needsTheLibrary() {
        Assume.assumeTrue(Fixtures.whyItWasSkipped(), Fixtures.library() != null);
    }

    @AfterClass
    public static void printTheTable() {
        if (measured == null) return;
        System.out.println();
        System.out.println("T12 - the twelve library recordings through RegDrift.run,"
                + " mode=diagnose_and_recommend, defaults throughout");
        System.out.printf(Locale.US, "%-32s %-20s %-24s %-10s %-9s %8s %8s %7s %7s  %s%n",
                "entry", "verdict", "motion_label", "dominant", "severity", "loc", "agree_px",
                "wander", "step_max", "headline / recorded");
        for (Fixtures.Entry entry : Fixtures.ENTRIES) {
            Row row = measured.get(entry.name());
            if (row == null) continue;
            System.out.printf(Locale.US,
                    "%-32s %-20s %-24s %-10s %-9s %8.4f %8.3f %7.2f %7.2f  %s / %s%n",
                    row.entry.name(), row.verdict, row.motionLabel, row.dominant, row.severity,
                    row.localisability, row.agreementPx, row.wander, row.stepMaxPx,
                    row.headlineFound() ? "yes" : "NO",
                    row.equalsRecorded() ? "equal" : "differs (" + row.entry.recordedLabel() + ")");
        }
        int headline = 0;
        int equal = 0;
        int severity = 0;
        for (Row row : measured.values()) {
            if (row.headlineFound()) headline++;
            if (row.equalsRecorded()) equal++;
            if (row.severity.equals(row.entry.recordedSeverity())) severity++;
        }
        System.out.printf(Locale.US, "headline present in the measured set: %d of %d;"
                        + " set equal to entry.properties: %d of %d;"
                        + " severity equal to entry.properties: %d of %d%n",
                headline, measured.size(), equal, measured.size(), severity, measured.size());
        System.out.println();
    }

    /** Measures every entry once, and hands the same rows to every test below. */
    private static synchronized Map<String, Row> rows() {
        if (measured != null) return measured;
        Map<String, Row> found = new LinkedHashMap<String, Row>();
        for (Fixtures.Entry entry : Fixtures.ENTRIES) {
            ImagePlus imp = entry.open();
            try {
                RegDriftResult result = RegDrift.run(RegDriftParameters.builder(imp)
                        .mode(Mode.DIAGNOSE_AND_RECOMMEND)
                        .hideDisplay(true)
                        .build());
                assertTrue(entry + " did not finish: "
                                + (result.failure() == null ? "" : result.failure().message()),
                        result.isSuccess());
                found.put(entry.name(), rowOf(entry, result));
            } finally {
                imp.close();
            }
        }
        measured = found;
        return measured;
    }

    private static Row rowOf(Fixtures.Entry entry, RegDriftResult result) {
        ResultsTable diagnosis = result.diagnosis();
        assertNotNull(entry + " produced no diagnosis table", diagnosis);
        int row = rowOfTheMeasuredChannel(diagnosis);
        String label = diagnosis.getStringValue("motion_label", row);
        int present = 0;
        for (Recommendation ranked : result.ranked()) {
            if (ranked.presence() == Recommendation.Presence.PRESENT) present++;
        }
        return new Row(entry,
                result.verdict() == null ? "(none)" : result.verdict().tableValue(),
                label,
                diagnosis.getStringValue("motion_dominant", row),
                diagnosis.getStringValue("severity", row),
                diagnosis.getValue("localisability", row),
                diagnosis.getValue("measured_at_bin", row),
                diagnosis.getValue("agreement_px", row),
                diagnosis.getValue("wander", row),
                diagnosis.getValue("drift_rate_px", row),
                diagnosis.getValue("step_max_px", row),
                diagnosis.getValue("knock_present", row) > 0.5,
                componentsOf(label),
                result.ranked().size(), present);
    }

    /**
     * The one row that carries every column. The others hold the four the channel
     * ranking measures for every channel and nothing else, because nothing else
     * was measured on them.
     */
    private static int rowOfTheMeasuredChannel(ResultsTable diagnosis) {
        for (int row = 0; row <= diagnosis.getCounter() - 1; row++) {
            String verdict = diagnosis.getStringValue("verdict", row);
            if (verdict != null && !verdict.trim().isEmpty()) return row;
        }
        throw new IllegalStateException("no row of the diagnosis carries a verdict");
    }

    private static Set<MotionLabel.Component> componentsOf(String label) {
        EnumSet<MotionLabel.Component> found = EnumSet.noneOf(MotionLabel.Component.class);
        if (label == null) return found;
        for (String word : label.split("\\+")) {
            for (MotionLabel.Component component : MotionLabel.Component.values()) {
                if (component.word().equals(word.trim())) found.add(component);
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ T12

    /**
     * The table this stage is built on cannot have drifted away from the library.
     *
     * <p>Every recorded label and severity in {@link Fixtures#ENTRIES} is read back
     * out of the entry's own {@code entry.properties}. A stage that quoted a label
     * the library no longer records would be validating against itself.
     */
    @Test
    public void theRecordedLabelsInTheFixtureAreTheLibrarysOwn() throws IOException {
        needsTheLibrary();
        for (Fixtures.Entry entry : Fixtures.ENTRIES) {
            assertEquals(entry + ": entry.properties records a different label",
                    entry.recordedLabel(), entry.properties().getProperty("motion"));
            assertEquals(entry + ": entry.properties records a different severity",
                    entry.recordedSeverity(), entry.properties().getProperty("severity"));
        }
    }

    /**
     * <b>The gate.</b> Every entry finishes, every entry keeps what it was cut to
     * demonstrate, and no entry is called unregistrable.
     */
    @Test
    public void everyEntryKeepsWhatItWasCutToDemonstrate() {
        needsTheLibrary();
        Map<String, Row> found = rows();
        assertEquals("every library entry has to have been measured",
                Fixtures.ENTRIES.length, found.size());

        List<String> lost = new ArrayList<String>();
        List<String> refused = new ArrayList<String>();
        for (Row row : found.values()) {
            if (!row.headlineFound()) {
                lost.add(row.entry + " was cut to demonstrate " + row.entry.headline()
                        + " and the diagnosis measured " + row.motionLabel);
            }
            if (Verdict.NOT_REGISTRABLE.tableValue().equals(row.verdict)) {
                refused.add(row.entry.name());
            }
            assertTrue(row.entry + " produced no verdict", !"(none)".equals(row.verdict));
            assertTrue(row.entry + " produced no ranked engines", row.engines > 0);
        }
        assertEquals("entries that lost the movement they were cut for",
                Collections.<String>emptyList(), lost);
        assertEquals("every one of these twelve was registered by a real method, so"
                        + " 'nothing here tracks frame to frame' is the wrong answer on any of them",
                Collections.<String>emptyList(), refused);
    }

    /**
     * The scale is stated in every row and is the one D12 settled on. Localisability
     * is reported and is not thresholded, so no entry may be refused on the strength
     * of it - which is the whole of that decision, re-tested here.
     */
    @Test
    public void everyRowStatesItsScaleAndNoVerdictRestsOnLocalisability() {
        needsTheLibrary();
        for (Row row : rows().values()) {
            assertEquals(row.entry + " did not measure at the scale D12 settled on",
                    4.0, row.measuredAtBin, 1e-9);
            assertFalse(row.entry + " was warned about its structure, and localisability is"
                            + " reported rather than thresholded - see D12",
                    Verdict.WARN_LOW_STRUCTURE.tableValue().equals(row.verdict));
        }
    }

    /**
     * The first of the two falsifiable cases. {@code VID47_D5}'s two independent
     * estimators differ by 18.6 px per transition; the entry is named for it, it was
     * put in the library before it was ever registered, and registering it achieved
     * +0.7% - very slightly worse than doing nothing but interpolating.
     */
    @Test
    public void theEntryWhereTheMethodsDisagreeIsCalledADisagreement() {
        needsTheLibrary();
        Row row = rows().get("10_unresolved_methods_disagree");
        assertNotNull(row);
        assertEquals("10_unresolved_methods_disagree is the entry this verdict exists for",
                Verdict.ESTIMATORS_DISAGREE.tableValue(), row.verdict);
    }

    /**
     * The second. A large out-of-focus patch sweeps hundreds of pixels across the
     * field while the tissue barely moves; both estimators lock onto it, and the
     * library's own registration of it walked 4547 px across a 768 px frame.
     *
     * <p>Scored through {@code RegDrift.run} in {@code score} mode against that same
     * registration, rebuilt from the entry's own {@code original.tif} and
     * {@code shifts.csv}. No engine is needed and none is used: the arm under test
     * is the library's own answer, which is the one that followed the artefact.
     */
    @Test
    public void theMovingArtefactRaisesTheMotionPreservationFlag() throws IOException {
        needsTheLibrary();
        Assume.assumeTrue(Fixtures.needsHeap(1500), Fixtures.heapMb() >= 1500);
        Fixtures.Entry entry = Fixtures.entry("11_unresolved_moving_artefact");
        ImagePlus raw = entry.open();
        ImagePlus registered = null;
        try {
            registered = Fixtures.registeredByTheLibrary(entry, raw, 1);
            RegDriftResult result = RegDrift.run(RegDriftParameters.builder(raw)
                    .mode(Mode.SCORE)
                    .channel(Channel.of(1))
                    .slice(Slice.PROJECT)
                    .compareWith(registered)
                    .hideDisplay(true)
                    .build());
            assertTrue("scoring the moving artefact did not finish: "
                            + (result.failure() == null ? "" : result.failure().message()),
                    result.isSuccess());
            ResultsTable comparison = result.comparison();
            assertNotNull("score mode produced no comparison row", comparison);
            String status = comparison.getStringValue("status", 0);
            System.out.printf(Locale.US, "11_unresolved_moving_artefact scored: path %.1f px,"
                            + " net %.1f px, sd_vs_control %+.1f%%, status '%s'%n",
                    comparison.getValue("path_px", 0), comparison.getValue("net_px", 0),
                    100.0 * comparison.getValue("sd_vs_control", 0), status);
            assertTrue("the arm that followed the artefact was not flagged; status read '"
                            + status + "'", status.contains("motion_preservation_flag"));
        } finally {
            if (registered != null) registered.close();
            raw.close();
        }
    }

    // -------------------------------------------------- kill criterion 4, everywhere

    /**
     * <b>Kill criterion 4.</b> Diagnose and recommend on a machine with no candidate
     * engine installed at all.
     *
     * <p>This test needs no library and no Fiji: a plain build JVM has {@code ij.jar}
     * and this plugin on its classpath and nothing else, which is a stricter version
     * of a bare Fiji than a bare Fiji is. Every engine has to come back {@code no},
     * every table has to be complete, and nothing may reach a network - asserted by
     * putting a proxy chooser in front of the whole process, the same way the
     * catalogue's own test does.
     */
    @Test
    public void diagnoseAndRecommendCompleteWithNoEngineInstalled() {
        // Only a machine with nothing installed can answer this. A JVM pointed at a Fiji full
        // of engines would be asserting the opposite of what it is here to assert, so it says
        // so and steps aside rather than reporting a pass it did not earn.
        Assume.assumeTrue("this is the bare-machine check and this JVM is pointed at "
                        + System.getProperty("plugins.dir") + ", which has engines in it."
                        + " Run it without -Dplugins.dir.",
                System.getProperty("plugins.dir") == null
                        || System.getProperty("plugins.dir").trim().isEmpty());
        final List<String> reachedOut = new ArrayList<String>();
        ProxySelector original = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                reachedOut.add(String.valueOf(uri));
                return Collections.singletonList(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                reachedOut.add(String.valueOf(uri));
            }
        });
        ImagePlus imp = Fixtures.syntheticTrace(24, 256, 0.35, 0.4, 0.0, null, null, 7L);
        try {
            ProxySelector.getDefault().select(URI.create("https://sites.imagej.net/"));
            assertEquals("the recorder has to see a lookup that really happens, or the assertion"
                    + " below is decoration", 1, reachedOut.size());
            reachedOut.clear();

            RegDriftResult result = RegDrift.run(RegDriftParameters.builder(imp)
                    .mode(Mode.DIAGNOSE_AND_RECOMMEND)
                    .hideDisplay(true)
                    .build());
            assertTrue("a bare machine could not finish a diagnosis: "
                            + (result.failure() == null ? "" : result.failure().message()),
                    result.isSuccess());
            assertNotNull("no diagnosis table on a bare machine", result.diagnosis());
            assertNotNull("no recommendation table on a bare machine", result.recommendation());
            assertNotNull("no verdict on a bare machine", result.verdict());

            ResultsTable recommendation = result.recommendation();
            List<String> notMarkedAbsent = new ArrayList<String>();
            for (int row = 0; row < recommendation.getCounter(); row++) {
                String installed = recommendation.getStringValue("installed", row);
                if (!Recommendation.Presence.ABSENT.tableValue().equals(installed)) {
                    notMarkedAbsent.add(recommendation.getStringValue("engine", row)
                            + "=" + installed);
                }
            }
            System.out.printf(Locale.US, "kill criterion 4: %d engines ranked, %d marked"
                            + " installed=no, verdict %s%n", recommendation.getCounter(),
                    recommendation.getCounter() - notMarkedAbsent.size(),
                    result.verdict().tableValue());
            assertTrue("a bare machine ranked no engines at all", recommendation.getCounter() > 0);
            assertEquals("engines reported as present on a machine that has none",
                    Collections.<String>emptyList(), notMarkedAbsent);
            assertEquals("a diagnosis reached off this machine", Collections.<String>emptyList(),
                    reachedOut);
        } finally {
            ProxySelector.setDefault(original);
            imp.close();
        }
    }

    // ------------------------------------------------- kill criterion 2, the arbiter

    /**
     * <b>Kill criterion 2.</b> Three runs of compare mode over the same recordings,
     * with real engines, and the ranking has to come out the same each time and not
     * contradict what the library already records.
     *
     * <p>This is the decision the whole stage exists for: if the ranking is not
     * stable and not consistent with the known table, compare mode is decoration and
     * gets cut, leaving diagnose and recommend.
     *
     * <p><b>Measured on one channel of each recording, and the reason is measured
     * too.</b> Every library entry is three channels interleaved. StackReg,
     * MultiStackReg and Linear Stack Alignment with SIFT align a stack plane by
     * plane in stack order, so handed a three-channel hyperstack they align channel
     * 3 of one frame onto channel 1 of the next. {@link #compareModeOnAHyperstack()}
     * measures what that does. A comparison of engines has to hand those engines a
     * recording they can use, so this one does, and the hyperstack case is reported
     * beside it rather than folded into it.
     *
     * <p>Needs a Fiji with engines in it, which is a fact about a machine rather than
     * about this build, so it skips by name when {@code plugins.dir} is not set at a
     * Fiji that has any engine this plugin drives.
     *
     * <p>{@code -Dregdrift.compare} takes a comma-separated list of entry names or
     * {@code all}; whatever is left out is printed, because a silent subset reads as
     * complete coverage.
     */
    @Test
    public void theArbitersRankingIsStableAndConsistent() {
        needsTheLibrary();
        Assume.assumeTrue(Fixtures.needsHeap(3000), Fixtures.heapMb() >= 3000);
        Assume.assumeTrue(Fixtures.whyThereIsNoFiji(), Fixtures.fijiWithEngines());

        List<Fixtures.Entry> chosen = comparedEntries();
        System.out.println();
        System.out.println("KILL CRITERION 2: compare mode, three runs each, "
                + chosen.size() + " of " + Fixtures.ENTRIES.length
                + " recordings, channel 1 only.");
        if (chosen.size() < Fixtures.ENTRIES.length) {
            List<String> leftOut = new ArrayList<String>();
            for (Fixtures.Entry entry : Fixtures.ENTRIES) {
                if (!chosen.contains(entry)) leftOut.add(entry.name());
            }
            System.out.println("NOT compared, and so not evidence either way: " + leftOut);
        }

        List<String> unstable = new ArrayList<String>();
        List<String> scoredSomething = new ArrayList<String>();
        List<String> nothingToRank = new ArrayList<String>();
        List<String> rankedAndMoved = new ArrayList<String>();
        for (Fixtures.Entry entry : chosen) {
            List<String> rankings = new ArrayList<String>();
            int armsWithAFigure = 0;
            for (int run = 1; run <= 3; run++) {
                ImagePlus imp = Fixtures.openChannel(entry, 1);
                try {
                    long before = System.nanoTime();
                    RegDriftResult result = RegDrift.run(RegDriftParameters.builder(imp)
                            .mode(Mode.COMPARE)
                            .hideDisplay(true)
                            .build());
                    double seconds = (System.nanoTime() - before) / 1e9;
                    assertTrue(entry + " run " + run + " did not finish: "
                                    + (result.failure() == null ? "" : result.failure().message()),
                            result.isSuccess());
                    StringBuilder ranking = new StringBuilder();
                    StringBuilder line = new StringBuilder();
                    int withAFigure = 0;
                    for (ArmOutcome arm : result.arms()) {
                        if (arm.status() == ArmStatus.NOT_INSTALLED) continue;
                        line.append(String.format(Locale.US, " %s[%s", arm.engineName(),
                                arm.status().tableValue()));
                        if (arm.hasFigure()) {
                            withAFigure++;
                            line.append(String.format(Locale.US, " %+.2f%% rank %s%s",
                                    arm.sdVsControlPercent(), arm.rankColumn(),
                                    arm.sharesItsRank()
                                            ? "=" + arm.cannotSeparateFromRank() : ""));
                            ranking.append(arm.rankColumn()).append(':')
                                    .append(arm.engineName()).append(':')
                                    .append(arm.sharesItsRank()
                                            ? "=" + arm.cannotSeparateFromRank() : "-")
                                    .append(':')
                                    .append(arm.separation() == null
                                            ? "-" : arm.separation().word())
                                    .append(' ');
                        }
                        if (arm.motionFlagged()) line.append(" motion_flag");
                        line.append(']');
                    }
                    if (run == 1) armsWithAFigure = withAFigure;
                    rankings.add(ranking.toString().trim());
                    System.out.printf(Locale.US, "%s run %d (%.0f s):%s%n", entry.name(), run,
                            seconds, line.length() == 0 ? " no arm ran" : line.toString());
                } finally {
                    imp.close();
                }
            }
            boolean stable = rankings.get(0).equals(rankings.get(1))
                    && rankings.get(1).equals(rankings.get(2));
            System.out.printf(Locale.US, "%s: %d arm(s) produced a figure; ranking '%s'; stable"
                            + " across three runs: %s%n", entry.name(), armsWithAFigure,
                    rankings.get(0), stable ? "yes" : "NO " + rankings);
            if (!stable) unstable.add(entry.name() + " " + rankings);
            if (armsWithAFigure > 0) scoredSomething.add(entry.name());
            if (armsWithAFigure < 2) {
                nothingToRank.add(entry.name() + "=" + armsWithAFigure);
            } else if (!stable) {
                rankedAndMoved.add(entry.name() + " " + rankings);
            }
        }

        System.out.println();
        System.out.println("KILL CRITERION 2, the figures:");
        System.out.println("  recordings where at least one arm produced a figure: "
                + scoredSomething.size() + " of " + chosen.size() + " " + scoredSomething);
        System.out.println("  recordings with fewer than two comparable arms, so there was no"
                + " ranking to be stable or unstable: " + nothingToRank);
        System.out.println("  ANY change between identical runs, ranking or not: "
                + (unstable.isEmpty() ? "none" : unstable.toString()));
        System.out.println("  changes to a RANKING - two or more comparable arms whose order or"
                + " ties moved: " + (rankedAndMoved.isEmpty() ? "none" : rankedAndMoved.toString()));
        System.out.println();

        // Two statements, and the strict one is printed above whatever this asserts on.
        //
        // What is asserted is the ranking: where two or more arms produced comparable figures,
        // three identical runs have to give the identical order and the identical set of ties,
        // or the ranking is not a measurement and compare mode is decoration.
        //
        // What is REPORTED and not asserted is whether an arm produced a figure at all on a
        // recording where nothing could be ranked either way. Linear Stack Alignment with SIFT
        // matches features by a randomised search, so whether it lands inside the region every
        // arm is measured over can differ between runs of it. That is a property of that engine,
        // and on a recording with one arm there is no order for it to change. It is written into
        // VALIDATION.md by name rather than being allowed to pass unmentioned.
        assertEquals("a ranking moved between identical runs, so it is not a measurement",
                Collections.<String>emptyList(), rankedAndMoved);
    }

    /**
     * What compare mode does when the recording has more than one channel, measured
     * rather than asserted.
     *
     * <p>Reported, never a pass condition: this is a finding about how engines are
     * handed a recording, and its place is {@code VALIDATION.md} and the scope table,
     * not an assertion that would make the gate turn on it.
     */
    @Test
    public void compareModeOnAHyperstack() {
        needsTheLibrary();
        Assume.assumeTrue(Fixtures.needsHeap(3000), Fixtures.heapMb() >= 3000);
        Assume.assumeTrue(Fixtures.whyThereIsNoFiji(), Fixtures.fijiWithEngines());

        Fixtures.Entry entry = Fixtures.entry("01_jitter_mild");
        System.out.println();
        System.out.println("The same recording as a three-channel hyperstack, which is how it"
                + " sits on disk:");
        ImagePlus imp = entry.open();
        try {
            RegDriftResult result = RegDrift.run(RegDriftParameters.builder(imp)
                    .mode(Mode.COMPARE).hideDisplay(true).build());
            assertTrue("the hyperstack comparison did not finish",  result.isSuccess());
            for (ArmOutcome arm : result.arms()) {
                if (arm.status() == ArmStatus.NOT_INSTALLED) continue;
                System.out.printf(Locale.US, "  %-34s %-14s sd_vs_control %s  path %.1f px%n",
                        arm.engineName(), arm.status().tableValue(),
                        arm.hasFigure()
                                ? String.format(Locale.US, "%+.2f%%", arm.sdVsControlPercent())
                                : "none",
                        arm.pathPx());
            }
        } finally {
            imp.close();
        }
        System.out.println();
    }

    /** Which recordings a comparison run covers, from {@code -Dregdrift.compare}. */
    private static List<Fixtures.Entry> comparedEntries() {
        String asked = System.getProperty("regdrift.compare", "all").trim();
        List<Fixtures.Entry> chosen = new ArrayList<Fixtures.Entry>();
        if (asked.isEmpty() || "all".equalsIgnoreCase(asked)) {
            for (Fixtures.Entry entry : Fixtures.ENTRIES) {
                chosen.add(entry);
            }
            return chosen;
        }
        for (String name : asked.split(",")) {
            chosen.add(Fixtures.entry(name.trim()));
        }
        return chosen;
    }

    /**
     * The two made-up recordings the test plan names, and what they are for.
     *
     * <p>A flat featureless stack: nothing tracks, and the run has to say so as a
     * verdict rather than by hanging or by inventing movement out of the corner of
     * a search box (defect D8). A stack whose brightest decile <em>is</em> the
     * sample: the movement is measured correctly, and the intensity-ceiling advice
     * must not be a setting anything switched on (defect D4).
     */
    @Test
    public void theFlatStackAndTheBrightSampleStackBehaveAsTheyMust() {
        ImagePlus flat = Fixtures.flat(16, 256, 120);
        try {
            RegDriftResult result = RegDrift.run(RegDriftParameters.builder(flat)
                    .mode(Mode.DIAGNOSE_AND_RECOMMEND).hideDisplay(true).build());
            assertTrue("a flat stack has to produce a stated answer, not a failure: "
                            + (result.failure() == null ? "" : result.failure().message()),
                    result.isSuccess());
            ResultsTable diagnosis = result.diagnosis();
            int row = rowOfTheMeasuredChannel(diagnosis);
            String label = diagnosis.getStringValue("motion_label", row);
            double stepMax = diagnosis.getValue("step_max_px", row);
            System.out.printf(Locale.US, "flat 256x256 t16: verdict %s, label '%s',"
                            + " step_max %.3f px, localisability %+.4f%n",
                    result.verdict().tableValue(), label, stepMax,
                    diagnosis.getValue("localisability", row));
            // Two answers are honest here and the third is the defect. Refusing every pair by
            // name is one - it is what "no silent empties" means when the pixels say nothing.
            // Measuring a step of about zero is the other. Coming back with a large step is
            // the corner of the search box being reported as movement, which is D8.
            boolean refused = MotionLabel.NOT_MEASURED.equals(label);
            assertTrue("a featureless pair must be refused by name or return about the identity,"
                            + " never the corner of the search box - defect D8. It read '" + label
                            + "' with step_max " + stepMax + " px",
                    refused || stepMax < 1.0);
        } finally {
            flat.close();
        }

        Measured intact = measure(Fixtures.brightestDecileIsTheSample(24, 256, 0.4, 11L),
                "bright-sample, decile intact");
        Measured clipped = measure(Fixtures.brightestDecileClipped(24, 256, 0.4, 11L),
                "bright-sample, brightest decile clipped");
        System.out.printf(Locale.US, "the two together: drift %.3f px/frame with the bright decile"
                        + " kept, %.3f px/frame with it clipped - %.0f%% of the recovered movement"
                        + " lost%n", intact.drift, clipped.drift,
                100.0 * (1.0 - clipped.drift / Math.max(intact.drift, 1e-9)));

        assertTrue("the movement carried by the bright patch was not detected at all",
                intact.drift > 0.05);
        assertTrue("bright_fraction has to report the bright structure, and it read "
                + intact.brightFraction, intact.brightFraction > 0.05);
        // The shipped guarantee about D4 is not that a clipped stack fails - it is that nothing
        // clips. The advice exists, it always carries its contraindication, and there is no
        // setting, no macro option and no code path that switches it on.
        assertTrue("the ceiling advice must carry its contraindication every time it is asked for",
                CeilingAdvice.text(intact.brightFraction).endsWith(CeilingAdvice.CONTRAINDICATION));
        assertTrue("the contraindication must name the case this fixture is",
                CeilingAdvice.CONTRAINDICATION.contains("removes the sample"));
    }
    /** What one made-up recording measured at. */
    private static final class Measured {

        final double drift;
        final double brightFraction;

        Measured(double drift, double brightFraction) {
            this.drift = drift;
            this.brightFraction = brightFraction;
        }
    }

    /** What one made-up recording produced, printed and handed back. */
    private static Measured measure(ImagePlus imp, String what) {
        try {
            RegDriftResult result = RegDrift.run(RegDriftParameters.builder(imp)
                    .mode(Mode.DIAGNOSE_AND_RECOMMEND).hideDisplay(true).build());
            assertTrue(what + " did not finish: "
                            + (result.failure() == null ? "" : result.failure().message()),
                    result.isSuccess());
            ResultsTable diagnosis = result.diagnosis();
            int row = rowOfTheMeasuredChannel(diagnosis);
            double drift = diagnosis.getValue("drift_rate_px", row);
            double bright = diagnosis.getValue("bright_fraction", row);
            System.out.printf(Locale.US, "%-42s verdict %s, label '%s', drift %.3f px/frame,"
                            + " bright_fraction %.4f, localisability %+.4f%n",
                    what + ":", result.verdict().tableValue(),
                    diagnosis.getStringValue("motion_label", row), drift, bright,
                    diagnosis.getValue("localisability", row));
            return new Measured(drift, bright);
        } finally {
            imp.close();
        }
    }
}
