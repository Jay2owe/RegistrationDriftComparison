/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
/**
 * Driving other people's registration plugins: detection by reflection,
 * execution on hidden images, and timing on the CPU clock.
 *
 * <p>Filled by stage 11. Third-party class names are looked up as strings and
 * must never be relocated. Nothing here may end the Java process, on any path,
 * ever: this code runs inside somebody's live Fiji session, and ending it takes
 * their unsaved images with it.
 */
package regdrift.harness;
