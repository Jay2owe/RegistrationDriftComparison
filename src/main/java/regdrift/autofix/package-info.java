/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
/**
 * This plugin's own catalogue of the registration engines it looks for, laid
 * over shared dependency machinery compiled into this jar.
 *
 * <p>The machinery is shared with two sibling plugins and knows nothing about
 * registration; the catalogue is this plugin's and is never shared, because the
 * three plugins' lists of what they need do not overlap at all.
 * {@link regdrift.autofix.EngineId} is the part number both halves of this
 * plugin quote: this package answers whether an engine is here, and the harness
 * answers how to drive it.
 *
 * <p>Reading which engines are here touches this computer and nothing else. A
 * file is fetched when a person presses a button in the Engines section, never
 * to produce a measurement and never from a macro line.
 */
package regdrift.autofix;
