/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
/**
 * The dialogs, the progress reporter and the results panel.
 *
 * <p>Everything a person looks at lives here, and nothing that measures
 * anything does. The measurement is reached through {@code RegDrift.run}, which
 * opens no window and writes no file; this package is where a table gets shown
 * and an error reaches somebody.
 *
 * <p>The shared toggle switch, the disclosure pane and the status-bar reporter
 * are not copied in here - they are the relocated {@code oc3d-core} widgets,
 * reachable as {@code regdrift.internal.core.ui.ToggleSwitch},
 * {@code regdrift.internal.core.ui.CollapsiblePane} and
 * {@code regdrift.internal.core.progress.StatusBarProgress} in the packaged jar.
 *
 * <p>Every string here is US English, and house rules 5 and 6 of the build plan
 * govern the wording: a test reads the source of this package and fails when one
 * of the four words those rules forbid turns up in it. Three of them would claim
 * a certainty this plugin does not have, and the fourth names a quantity nobody
 * measured, since the true registration of somebody's recording is unknown.
 *
 * <p>The Engines section is a stated placeholder until the engine catalogue
 * lands. It says this build has not checked; it does not say nothing is
 * installed.
 */
package regdrift.ui;
