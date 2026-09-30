/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import java.util.Locale;

/**
 * Whether the movement in a recording can be registered, and the finished
 * sentence saying why.
 *
 * <p>The everyday version: a mechanic who has looked at a car says one of four
 * things. It can be fixed. It can be fixed, but nothing here grips well enough
 * to be sure. My two instruments read different numbers, so I cannot tell you.
 * Or - and only this one - there is no engine in it to look at.
 *
 * <p>The wording is written once, here, so the dialog, the {@code verdict}
 * column and the auto-saved {@code README.txt} cannot end up saying three
 * different things about one measurement.
 *
 * <h2>What this routes on, and what it deliberately does not</h2>
 *
 * <p>It routes on <b>estimator agreement</b>: the median distance between two
 * independent published methods measuring the same frame pairs. That is the one
 * quantity measured here that orders the twelve library recordings by how much
 * registration actually helped them - Spearman rank correlation -0.85 against
 * the reduction in temporal standard deviation each achieved.
 *
 * <p>It does <b>not</b> route on frame correlation. That measure fires on
 * {@code 02_jitter} (0.163) and {@code 03_jitter_drift} (0.198), two of the
 * entries that register most cleanly, while {@code 08}, {@code 09} and {@code 10} sit at
 * 0.88-0.89. It ranks the library almost inversely, and it is reported in the
 * table and routed on nowhere.
 *
 * <p>And it does <b>not</b> threshold localisability. See below.
 *
 * <h2>Defect D12 is open, and this class is where that is visible</h2>
 *
 * <p>{@link Localisability#WARN_BELOW} was calibrated on the motion survey's
 * binned frames. The survey's binning factor has since been recovered - it ran
 * at bin 4 - and {@link Fingerprint} now measures at that same scale, so the
 * obvious repair was available and was tried. <b>It does not work.</b> At bin 4
 * the threshold still warns on {@code 04_drift} (+0.0380, the largest reduction
 * in the library at -50.6%), {@code 06_knock} (+0.0439, -40.3%) and
 * {@code 07_knock_drift} (+0.0407, -37.1%), and no cut at bin 1, 2, 4 or 8
 * separates the recordings that register from the recordings that do not. At the
 * survey's own scale the measure's rank correlation with the outcome is +0.13.
 *
 * <p>So <b>localisability is reported with its measurement scale and is not
 * thresholded</b>, and {@code warn_low_structure} is not issued on the strength
 * of it. Picking a different number off twelve recordings from one instrument is
 * how the shipped threshold came to be wrong in the first place; the honest
 * answer is that the calibration is too narrow, not that it is off by a factor.
 * The measurement, and what would be needed to close D12, are in
 * {@code docs/D12_MEASUREMENT.md}.
 *
 * <h2>Warns, never refuses - defect D7</h2>
 *
 * <p>{@code warn_low_structure} is a warning beside a number and stops nothing.
 * Noise-free synthetic content scores 0.032 and registers to a hundredth of a
 * pixel, so a plugin that refused on a low measurement would refuse correct
 * work.
 *
 * <p>{@link regdrift.Verdict#NOT_REGISTRABLE} is reserved for structural
 * impossibility - no time axis, one frame, a plane the sampler cannot place a
 * window in. {@link #of} can never return it, and a test asserts that. It is
 * reached only through {@link #notRegistrable}, which takes the structural
 * reason as an argument because there is no measurement behind it.
 */
public final class Verdict {

    /**
     * Above this median distance between the two independent estimators, the
     * verdict is {@code estimators_disagree} - in the image's own pixels.
     *
     * <p><b>Five pixels, and the number is the middle of a gap nothing was
     * measured inside.</b> All twelve library recordings were measured twice,
     * once with the frames binned four ways and once unbinned, and every
     * agreement converted back to the image's own pixels. Across all
     * twenty-four measurements:
     *
     * <ul>
     *   <li>the nine recordings whose two estimators tracked each other sat at
     *       0.089-2.303 px;</li>
     *   <li>the three where they parted sat at 10.996-32.336 px;</li>
     *   <li><b>nothing sat between 2.303 and 10.996</b>, a factor of 4.8.</li>
     * </ul>
     *
     * <p>The band's geometric centre is 5.03 px, and this is that rounded to a
     * whole pixel: 2.17x above the largest agreeing measurement and 2.20x below
     * the smallest disagreeing one. Not fitted to a boundary case, because there
     * is no case at the boundary.
     *
     * <p><b>Why the image's pixels and not the ones the estimators worked in.</b>
     * Measured this way, the same limit puts the same nine recordings on one
     * side and the same three on the other <em>at both scales</em>. In the
     * estimators' own pixels it does not: at bin 1 a one-pixel limit would call
     * {@code 11_unresolved_moving_artefact} and {@code 12_long_baseline_9d}
     * disagreements and at bin 4 it would not. A verdict that changed when the
     * measurement scale changed would be defect D12 over again. The twenty-four
     * rows are in {@code docs/D12_MEASUREMENT.md}.
     */
    public static final double AGREEMENT_LIMIT_PX = 5.0;

    /**
     * What a reader is told about the calibration, wherever a verdict is shown.
     *
     * <p>A verdict that hides its own calibration is the failure this plugin
     * exists to fix in other tools, so the sentence travels with the verdict
     * rather than sitting in a manual.
     */
    public static final String CALIBRATION_NOTE =
            "Calibrated on twelve real IncuCyte phase-contrast recordings, measured at a 4 x 4"
                    + " pixel mean. Noise-free or synthetic content can register well while"
                    + " measuring low, so read these numbers as \"on data like that\", not as a"
                    + " law.";

    /**
     * What a reader is told about localisability, wherever it is shown.
     *
     * <p>Reported, not thresholded, and the reason is stated rather than left as
     * a silence somebody would later fill with a threshold.
     */
    public static final String LOCALISABILITY_NOTE =
            "Localisability is reported with the scale it was measured at and is not thresholded"
                    + " in this version. The warning threshold that shipped with it was calibrated"
                    + " on a narrower set of recordings and warns on recordings that register well,"
                    + " so no warning is issued on the strength of it.";

    private final regdrift.Verdict kind;
    private final String text;
    private final Frames.Bin measuredAt;

    private Verdict(regdrift.Verdict kind, String text, Frames.Bin measuredAt) {
        this.kind = kind;
        this.text = text;
        this.measuredAt = measuredAt;
    }

    // ------------------------------------------------------------- deciding

    /**
     * The verdict a measurement earns, and the sentence that goes with it.
     *
     * <p>Three outcomes, in this order, and no fourth:
     *
     * <pre>
     * agreement above AGREEMENT_LIMIT_PX   -&gt; estimators_disagree   "I cannot tell"
     * neither estimator could read a pair  -&gt; warn_low_structure    warn, never refuse
     * otherwise                            -&gt; registrable
     * </pre>
     *
     * <p>{@code not_registrable} is not among them and cannot be returned here.
     *
     * @param fingerprint what was measured. Never {@code null}
     */
    public static Verdict of(Fingerprint fingerprint) {
        if (fingerprint == null) {
            throw new IllegalArgumentException("a verdict is made from a measurement, and none"
                    + " was given");
        }
        Frames.Bin scale = fingerprint.measuredAt();
        if (!fingerprint.measurablePlane()) {
            // Structural, not a low number: there is no plane to place a window in. Routed here
            // rather than left to the caller so that the one structural refusal in the measurement
            // always produces the one verdict that is allowed to say so.
            return notRegistrable(String.format(Locale.US,
                    "The measured plane is %d x %d pixels, which is too small to place a"
                            + " measurement window in or to displace by a pixel. Open a recording"
                            + " of at least %d pixels on each side.",
                    fingerprint.width(), fingerprint.height(),
                    Fingerprint.MIN_MEASURABLE_SIDE_PX), scale);
        }

        double agreement = fingerprint.agreementPx();
        String localisability = String.format(Locale.US,
                "Localisability measured %.4f at bin %d; displacements are in this image's own"
                        + " pixels.",
                fingerprint.localisability().value(), scale.factor());

        if (agreement > AGREEMENT_LIMIT_PX) {
            return new Verdict(regdrift.Verdict.ESTIMATORS_DISAGREE, String.format(Locale.US,
                    "The two independent methods describe different movement in this recording:"
                            + " they differ by %.3f px per transition, above the %.1f px limit,"
                            + " over %d of %d frame pairs. This plugin cannot tell you how this"
                            + " recording moved, and a registration driven by either method would"
                            + " be a guess. %s %s",
                    agreement, AGREEMENT_LIMIT_PX, fingerprint.comparedPairs(),
                    fingerprint.measuredPairs(), localisability, CALIBRATION_NOTE), scale);
        }
        if (fingerprint.comparedPairs() == 0) {
            return new Verdict(regdrift.Verdict.WARN_LOW_STRUCTURE, String.format(Locale.US,
                    "Neither method could localize a displacement in any of the %d frame pairs"
                            + " measured on channel %d, so no method is likely to localize this"
                            + " channel well. This is a warning and not a refusal - the"
                            + " measurement below stands, and registration can still be run."
                            + " %s %s %s",
                    fingerprint.measuredPairs(), fingerprint.channel(), localisability,
                    LOCALISABILITY_NOTE, CALIBRATION_NOTE), scale);
        }
        return new Verdict(regdrift.Verdict.REGISTRABLE, String.format(Locale.US,
                "The two independent methods agree to %.3f px per transition, within the %.1f px"
                        + " limit, over %d of %d frame pairs, so the movement in this recording is"
                        + " measurable. %s %s %s",
                agreement, AGREEMENT_LIMIT_PX, fingerprint.comparedPairs(),
                fingerprint.measuredPairs(), localisability, LOCALISABILITY_NOTE,
                CALIBRATION_NOTE), scale);
    }

    /**
     * The verdict for a recording there is structurally nothing to measure in.
     *
     * <p>No time axis, one frame, or a plane the sampler cannot place a window
     * in. <b>Never a low measurement</b>, which is why the reason is an argument:
     * a caller that cannot name a structural obstacle has not got one.
     *
     * @param structuralReason a finished sentence naming what is absent
     * @param measuredAt       the scale the run would have measured at
     */
    public static Verdict notRegistrable(String structuralReason, Frames.Bin measuredAt) {
        if (structuralReason == null || structuralReason.trim().isEmpty()) {
            throw new IllegalArgumentException("not_registrable is for a recording there is"
                    + " structurally nothing to measure in - no time axis, one frame, no plane to"
                    + " place a window in - and it needs that reason named. A low measurement is a"
                    + " warning, never this. See defect D7");
        }
        if (measuredAt == null) {
            throw new IllegalArgumentException("a verdict needs the scale the measurement was"
                    + " taken at; pass Frames.Bin.none() for native resolution. See defect D12");
        }
        return new Verdict(regdrift.Verdict.NOT_REGISTRABLE, structuralReason.trim(), measuredAt);
    }

    // ------------------------------------------------------------- what it says

    /** The verdict itself, as the token the {@code verdict} column holds. */
    public regdrift.Verdict kind() {
        return kind;
    }

    /** The token written into the {@code verdict} column. */
    public String tableValue() {
        return kind.tableValue();
    }

    /**
     * The finished text a user reads - in the dialog, in the results panel and
     * in the auto-saved notes. Never empty.
     */
    public String text() {
        return text;
    }

    /**
     * The effective pixel size the measurement behind this verdict was taken at.
     *
     * <p>A verdict is not comparable across two scales, so it carries one - see
     * defect D12.
     */
    public Frames.Bin measuredAt() {
        return measuredAt;
    }

    /**
     * The distance between the two estimators above which the verdict is
     * {@code estimators_disagree}, in the image's own pixels.
     */
    public double limitPx() {
        return AGREEMENT_LIMIT_PX;
    }

    /** True when nothing about this verdict asks the user to look twice. */
    public boolean clear() {
        return kind == regdrift.Verdict.REGISTRABLE;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Verdict)) return false;
        Verdict that = (Verdict) other;
        return kind == that.kind && text.equals(that.text) && measuredAt.equals(that.measuredAt);
    }

    @Override
    public int hashCode() {
        return (kind.hashCode() * 31 + text.hashCode()) * 31 + measuredAt.hashCode();
    }

    @Override
    public String toString() {
        return "Verdict[" + kind.tableValue() + " at " + measuredAt + ": " + text + "]";
    }
}
