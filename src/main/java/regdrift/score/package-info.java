/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
/**
 * The arbiter: rating a registered stack against an interpolation-matched
 * control, so that interpolation blur cannot flatter a method.
 *
 * <p>Filled by stage 12. The quantities are {@code residual_before},
 * {@code residual_after}, {@code residual_removed}, {@code sd_vs_control} and
 * {@code agreement_px}.
 */
package regdrift.score;
