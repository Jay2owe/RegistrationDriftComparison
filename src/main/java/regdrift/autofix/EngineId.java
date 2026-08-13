/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package regdrift.autofix;

import sc.fiji.autofix.core.DependencyKey;

/**
 * The registration engines this plugin knows about, one constant each.
 *
 * <h2>One catalogue, two halves</h2>
 *
 * <p>Two separate questions get asked about every engine here, and they are
 * asked by different code written at different times. <b>Is it there?</b> is
 * this package: a probe, a download size, a repair button. <b>How do I drive
 * it?</b> is the harness: which ImageJ command to call, which arguments it
 * takes, whether it can be run twice in one session. This enum is the join
 * between the two, and it exists so that there is exactly one list of engines
 * in the plugin rather than one per half. Adding an engine means adding a
 * constant here, a spec in {@link EngineRegistry}, and - when the harness
 * exists - a description of how to drive it, all keyed on the same constant.
 *
 * <p>Think of it as the part number. The parts catalogue and the fitting
 * instructions are separate documents; they agree because they quote the same
 * number.
 *
 * <h2>Why an enum, and why it implements an interface</h2>
 *
 * <p>{@code autofix-core} - the shared chassis this plugin's dependency
 * handling is built on - cannot own this list. FLASH needs StarDist and
 * TensorFlow, PULSE needs TrackMate, this plugin needs the engines below, and
 * the three sets do not overlap at all, so a list inside the chassis would be
 * the union of every plugin's dependencies in every plugin's dialog. The
 * chassis therefore declares {@link DependencyKey} and each plugin's own enum
 * implements it.
 *
 * <p>{@link Enum#name()} already satisfies that interface, so nothing below is
 * implemented by hand, and {@code EnumMap}, {@code EnumSet}, {@code values()},
 * {@code valueOf(String)} and {@code switch} all keep working.
 *
 * <p>The constant names are part of the plugin's own vocabulary and are stable:
 * they key the harness's table and they are what a diagnostic line names when
 * there is no display name to hand. Renaming one is a breaking change, not a
 * tidy-up.
 */
public enum EngineId implements DependencyKey {

    /** TurboReg, from the Biomedical Imaging Group at EPFL. */
    TURBOREG,

    /** StackReg, from the same group. Needs TurboReg at run time. */
    STACKREG,

    /** MultiStackReg, Brad Busse's derivative of StackReg. Needs TurboReg too. */
    MULTISTACKREG,

    /** Correct 3D drift, the Jython script that ships inside Fiji. */
    CORRECT_3D_DRIFT,

    /** Descriptor-based registration, which ships inside Fiji. */
    DESCRIPTOR_BASED_REGISTRATION,

    /** Register Virtual Stack Slices, which ships inside Fiji. */
    REGISTER_VIRTUAL_STACK_SLICES,

    /** Linear Stack Alignment with SIFT, which ships inside Fiji as mpicbg. */
    LINEAR_STACK_ALIGNMENT_WITH_SIFT,

    /** Image Stabilizer, Kang Li's Lucas-Kanade plugin. */
    IMAGE_STABILIZER,

    /** Fast4DReg, from CellMigrationLab. */
    FAST_4D_REG,

    /** NanoJ-Core, from the Henriques lab. */
    NANOJ_CORE,
}
