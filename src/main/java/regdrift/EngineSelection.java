/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Which registration engines a run considers: the ones already present in this
 * Fiji, every engine the plugin knows about, or a named list.
 *
 * <p>Choosing a set of engines is not the same as fetching one. Nothing here
 * reaches the network or writes to the Fiji folder under any value: the widest
 * setting, {@link Kind#ALL}, means the plugin ranks and reports engines that are
 * absent, marks them absent, and stops there. Repairing an absent engine is a
 * button somebody presses, never a macro value.
 *
 * <p>Named engines are spelled exactly as their own authors spell them -
 * "StackReg", "TurboReg", "Correct 3D drift", "Fast4DReg", "Linear Stack
 * Alignment with SIFT", "Image Stabilizer". A recommendation that misspells the
 * tool it recommends is not a good look, and those strings are also what a user
 * types into a search box. Names are therefore stored with their capitalization
 * intact and compared case-insensitively.
 */
public final class EngineSelection {

    /** How the set was specified. */
    public enum Kind {
        /**
         * The engines already present in this Fiji.
         *
         * <p>Spelled {@code installed} in a macro. The constant is named
         * {@code PRESENT} so that the two files holding the macro surface can be
         * swept clean of software-fetching words and stay that way; the released
         * macro value is the one in the contract and does not change.
         */
        PRESENT,

        /** Every engine the plugin knows about, present or not. */
        ALL,

        /** A named list, in the order the user gave it. */
        NAMED
    }

    /** The macro value for {@link Kind#PRESENT}, and the default. */
    public static final String PRESENT_VALUE = "installed";

    /** The macro value for {@link Kind#ALL}. */
    public static final String ALL_VALUE = "all";

    private static final EngineSelection PRESENT = new EngineSelection(
            Kind.PRESENT, Collections.<String>emptyList());
    private static final EngineSelection ALL = new EngineSelection(
            Kind.ALL, Collections.<String>emptyList());

    private final Kind kind;
    private final List<String> names;

    private EngineSelection(Kind kind, List<String> names) {
        this.kind = kind;
        this.names = names;
    }

    /** The engines already present in this Fiji. The default. */
    public static EngineSelection present() {
        return PRESENT;
    }

    /** Every engine the plugin knows about. */
    public static EngineSelection all() {
        return ALL;
    }

    /** What a run uses when the {@code engines} option is left alone. */
    public static EngineSelection defaultSelection() {
        return PRESENT;
    }

    /**
     * A named list of engines, in the order given.
     *
     * @throws IllegalArgumentException when the list is empty or holds a blank name
     */
    public static EngineSelection named(List<String> engineNames) {
        if (engineNames == null || engineNames.isEmpty()) {
            throw new IllegalArgumentException("Macro option 'engines' must be '" + PRESENT_VALUE
                    + "', '" + ALL_VALUE + "', or a comma-separated list of engine names"
                    + " (engines=<empty>).");
        }
        List<String> cleaned = new ArrayList<String>(engineNames.size());
        for (String name : engineNames) {
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Macro option 'engines' has an empty engine name"
                        + " in its list (engines='" + join(engineNames) + "').");
            }
            cleaned.add(trimmed);
        }
        return new EngineSelection(Kind.NAMED, Collections.unmodifiableList(cleaned));
    }

    /** How the set was specified. */
    public Kind kind() {
        return kind;
    }

    /**
     * The engine names, in the order given. Empty for {@link Kind#PRESENT} and
     * {@link Kind#ALL}, where the catalogue supplies the names instead.
     */
    public List<String> names() {
        return names;
    }

    /** True when this names engines explicitly. */
    public boolean isNamed() {
        return kind == Kind.NAMED;
    }

    /** The text this is spelled with in a macro. */
    public String toMacroValue() {
        if (kind == Kind.PRESENT) return PRESENT_VALUE;
        if (kind == Kind.ALL) return ALL_VALUE;
        return join(names);
    }

    /**
     * Reads an {@code engines} macro value.
     *
     * @throws IllegalArgumentException naming the option and the value
     */
    public static EngineSelection parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (PRESENT_VALUE.equals(lower)) return PRESENT;
        if (ALL_VALUE.equals(lower)) return ALL;
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Macro option 'engines' must be '" + PRESENT_VALUE
                    + "', '" + ALL_VALUE + "', or a comma-separated list of engine names"
                    + " (engines='" + trimmed + "').");
        }
        List<String> parsed = new ArrayList<String>();
        for (String part : trimmed.split(",", -1)) {
            parsed.add(part);
        }
        return named(parsed);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(',');
            sb.append(part == null ? "" : part.trim());
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EngineSelection)) return false;
        EngineSelection that = (EngineSelection) other;
        if (kind != that.kind) return false;
        if (names.size() != that.names.size()) return false;
        for (int i = 0; i < names.size(); i++) {
            if (!names.get(i).equalsIgnoreCase(that.names.get(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = kind.hashCode();
        for (String name : names) {
            hash = hash * 31 + name.toLowerCase(Locale.ROOT).hashCode();
        }
        return hash;
    }

    @Override
    public String toString() {
        return "engines=" + toMacroValue();
    }
}
