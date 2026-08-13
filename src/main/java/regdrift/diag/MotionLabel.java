/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.diag;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What kind of movement a recording contains, as an <b>unordered set</b> of
 * components, plus a separate note about which of them dominates.
 *
 * <p>The everyday version: a report on a car saying "it pulls to the left and the
 * steering wheel shakes". Both facts are true, both matter, and arguing about
 * which to say first tells the driver nothing.
 *
 * <h2>Why the set is unordered, and why that is a correction</h2>
 *
 * <p>The research harness this is promoted from rendered a compound label as an
 * ordered string - {@code DRIFT+JITTER} when drift won a comparison and
 * {@code JITTER+DRIFT} when it did not. That comparison is a knife edge: it turns
 * on whether one ratio sits above or below a single threshold, it flipped between
 * six different samplings of the same recording, and it means nothing different
 * to a person reading it, because both spellings say the recording drifts and it
 * also jitters.
 *
 * <p>Measured on 2026-08-12 across twelve recordings and six sampling
 * configurations, treating the label as a set rather than as an ordered string
 * recovered two entries that otherwise read as disagreements. That is defect D13,
 * and it is a correction rather than a concession: the ordered form was claiming
 * a distinction the measurement could not support.
 *
 * <p>So {@link #equals} is set equality, {@link #render} writes the components in
 * one fixed order whatever order they were found in, and dominance is reported
 * <em>separately</em>, in its own column, and only when the margin is wide enough
 * to survive being resampled.
 *
 * <h2>There is no periodic component, and that is deliberate</h2>
 *
 * <p>An earlier survey carried a {@code PERIODIC} label from a spectral peak in
 * the residual. On a 24-hour baseline it reported periods of exactly 16.00 h and
 * 10.67 h, and on a nine-day baseline exactly 64.0 h and 51.2 h. Those four
 * numbers are the lowest usable bins of the two transforms, not properties of the
 * recordings: a window spanning one or two cycles cannot tell a cycle from a bend
 * in the drift. The claim was <b>withdrawn</b> rather than tuned into existence,
 * the spectral routine behind it is not carried into this plugin at all, and
 * {@code NoPeriodicLabelTest} fails if either comes back. That is defect D3.
 *
 * <p>Immutable, and comparable across runs: two labels over the same components
 * are equal and render identically whatever order they were built in.
 */
public final class MotionLabel {

    /** What a recording is doing, in the words a diagnosis uses. */
    public enum Component {

        /** The field moves steadily in one direction across the recording. */
        DRIFT("DRIFT"),

        /**
         * The field wanders: each frame's position is a step away from the last
         * one rather than a step away from a fixed centre, so the excursion grows
         * with time. A random walk.
         */
        WALK("WALK"),

        /**
         * The field shakes about a fixed position. Successive positions are
         * independent of one another rather than accumulating.
         */
        JITTER("JITTER"),

        /**
         * At least one movement is far larger than the rest - somebody knocked the
         * stage, or the plate was reseated.
         *
         * <p><b>Presence, never a count.</b> The rule is "a step larger than three
         * pixels, or six times the typical step, whichever is bigger", and the
         * typical step is computed over whatever pairs were sampled. Move the
         * sample and the threshold moves with it: one recording read one, two,
         * four and seven knocks across six samplings of itself. Presence was
         * stable across all six. See defect D13.
         */
        KNOCK("KNOCK");

        private final String word;

        Component(String word) {
            this.word = word;
        }

        /** The word written into {@code motion_label} and {@code motion_dominant}. */
        public String word() {
            return word;
        }
    }

    /**
     * What {@code motion_dominant} says when the margin between the components is
     * inside the noise the resampling measurement found.
     */
    public static final String UNCLEAR = "unclear";

    /**
     * What {@code motion_label} says when no frame pair could be read, so there is
     * no movement to describe.
     *
     * <p>Lower case, like every other status word in this plugin, so it cannot be
     * mistaken for a component.
     */
    public static final String NOT_MEASURED = "not_measured";

    private static final MotionLabel UNREADABLE =
            new MotionLabel(EnumSet.noneOf(Component.class), null);

    private final EnumSet<Component> components;
    private final Component dominant;

    private MotionLabel(EnumSet<Component> components, Component dominant) {
        this.components = components;
        this.dominant = dominant;
    }

    /**
     * A label over the components that were found.
     *
     * @param components at least one. Order is not read and is not remembered
     * @param dominant   which component the movement is mostly made of, or
     *                   {@code null} when the margin is not clear. Must be one of
     *                   {@code components}, and never {@link Component#KNOCK} -
     *                   a knock is a thing that happened, not a proportion of the
     *                   movement
     */
    public static MotionLabel of(Set<Component> components, Component dominant) {
        if (components == null || components.isEmpty()) {
            throw new IllegalArgumentException("a motion label names at least one component;"
                    + " for a recording nothing could be read from, use MotionLabel.notMeasured()");
        }
        EnumSet<Component> copy = EnumSet.copyOf(components);
        if (dominant != null) {
            if (!copy.contains(dominant)) {
                throw new IllegalArgumentException("the dominant component " + dominant
                        + " is not one of the components found, " + copy);
            }
            if (dominant == Component.KNOCK) {
                throw new IllegalArgumentException("a knock is an event, not a share of the"
                        + " movement, so it is never the dominant component. See defect D13");
            }
        }
        return new MotionLabel(copy, dominant);
    }

    /** A label over one component, which is trivially the dominant one. */
    public static MotionLabel of(Component only) {
        return of(EnumSet.of(only), only == Component.KNOCK ? null : only);
    }

    /**
     * The label for a recording no frame pair could be read from.
     *
     * <p>Not an empty label quietly standing in for {@code JITTER}: it renders as
     * {@link #NOT_MEASURED} and {@link #measured()} is false, so a reader can tell
     * "the recording holds still" from "nothing in the recording pinned a position
     * down".
     */
    public static MotionLabel notMeasured() {
        return UNREADABLE;
    }

    /** The components found, unmodifiable, in the canonical order. */
    public Set<Component> components() {
        return Collections.unmodifiableSet(components);
    }

    /** True when this component was found. */
    public boolean has(Component component) {
        return components.contains(component);
    }

    /**
     * True when at least one movement was far larger than the rest.
     *
     * <p>The whole of what this plugin says about knocks, beside
     * {@code step_max_px} and {@code bridge_max_px}. There is deliberately no
     * count - see {@link Component#KNOCK}.
     */
    public boolean knockPresent() {
        return components.contains(Component.KNOCK);
    }

    /** True when pixels produced this label rather than a refusal to guess. */
    public boolean measured() {
        return !components.isEmpty();
    }

    /**
     * Which component the movement is mostly made of, or {@code null} when the
     * margin is too narrow to survive a resampling.
     */
    public Component dominant() {
        return dominant;
    }

    /** What {@code motion_dominant} holds: the component's word, or {@link #UNCLEAR}. */
    public String dominantWord() {
        return dominant == null ? UNCLEAR : dominant.word();
    }

    /**
     * What {@code motion_label} holds: the components joined by {@code +} in one
     * fixed order.
     *
     * <p>The order is this class's declaration order - drift, walk, jitter, knock
     * - and it is not the order they were found in. Two labels that are
     * {@link #equals} render identically, on every run and on every machine, which
     * is what makes last month's saved table comparable with today's.
     */
    public String render() {
        if (components.isEmpty()) return NOT_MEASURED;
        StringBuilder out = new StringBuilder();
        for (Component component : components) {
            if (out.length() > 0) out.append('+');
            out.append(component.word());
        }
        return out.toString();
    }

    /**
     * Set equality over the components. Dominance is deliberately not part of it:
     * it is the knife-edge quantity, and two labels that disagree only about which
     * component is largest are saying the same thing about the recording.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MotionLabel)) return false;
        return components.equals(((MotionLabel) other).components);
    }

    @Override
    public int hashCode() {
        return components.hashCode();
    }

    @Override
    public String toString() {
        return render() + " (dominant " + dominantWord() + ")";
    }
}
