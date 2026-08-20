# Changelog

All notable changes to Registration & Drift Comparison are documented here. The
format follows [Keep a Changelog](https://keepachangelog.com/) and this project
adheres to [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-08-14

First release. One jar, no prerequisites, two menu entries under
**Plugins ▸ Registration**. It ships no registration engine of its own: every
engine it can run belongs to another plugin and is detected at run time.

### Added

- **Registration Diagnostics…** — measures the movement in a time-lapse stack,
  ranks the channels by how well a one-pixel displacement can be detected in
  them, reaches a registrability verdict, and names the engines the measurements
  support with the measured row each reason came from.
- **Compare Registration Methods…** — runs the engines this computer has on your
  own recording, one after another, and ranks what each of them did by
  `sd_vs_control`; also applies one engine, or scores a registration somebody
  else already made.
- Five modes, spelled `diagnose`, `diagnose_recommend`, `apply`, `compare` and
  `score`, and fifteen macro options. Both commands are macro-recordable, and
  `regdrift.RegDrift.run` is a public Java entry point that opens no dialog,
  shows no window, writes no file and makes no network call.
- Four result tables — Diagnosis, Recommendation, Comparison and Frames — with
  their columns fixed in one place, and an auto-save tree that writes them with a
  `README.txt` describing every column.
- Batch over a folder with a filename regex and a capture group, one row per
  recording.
- Engine detection for ten catalogue engines, with a per-engine install action
  that a person presses in a dialog. **No macro option installs anything.**
- The frozen bundled calibration: `benchmark_2026-08-11.csv`,
  `thirdparty_2026-08-11.csv` and `saturation_2026-08-11.csv`, copied from the
  research repository on 2026-08-13 and never edited. A later benchmark run
  becomes a new file beside these, with its own date and a line in this
  changelog.

### Measured, and shipped as measured

Every figure below is in `VALIDATION.md`, with the machine, the date, the plugin
version and the run command beside it.

- **Twelve real recordings, all twelve keep the movement they were cut to
  demonstrate**, and none is called `not_registrable`. The two recordings
  predicted to fail before they were run both failed as predicted:
  `10_unresolved_methods_disagree` reaches `estimators_disagree`, and
  `11_unresolved_moving_artefact` raises the motion-preservation flag.
- **Compare mode ships.** Three identical runs over twelve recordings produced
  the identical ranking on every recording where a ranking existed. StackReg and
  MultiStackReg — two front ends on one algorithm — tie to four significant
  figures and are reported as tied rather than ordered; Linear Stack Alignment
  with SIFT separates from them by 4.1 to 20.6 percentage points against a 3.0%
  "cannot separate" threshold.
- **A diagnosis of a 768 × 768 × 48 recording fits in half a gigabyte of heap**,
  and costs 12% more processor time at 500 frames than at 48.
- **No threshold was moved to make any of this pass.** Two constants are measured
  to be wrong and ship anyway with the measurement in their own javadoc:
  `DOMINANCE_MARGIN = 1.5` is below the margin every one of the twelve recordings
  needs, and `WANDER_WALK = 2.5` is unreachable at the shipped window length, so
  `MotionLabel.Component.WALK` is not produced in practice. Both are widened
  against a cross-instrument measurement in a later version, or withdrawn the way
  the `PERIODIC` label and the knock count were withdrawn.

### Known limits of this release

Stated in full at the top of the README, and at length in `VALIDATION.md`.

- The recommendation is calibrated on **three IncuCyte phase-contrast seed
  frames**, and **two of the ten catalogue engines carry a measured calibration
  row** — StackReg and TurboReg, and they carry the same row.
- **Correct 3D drift cannot currently be driven.** Its command is registered by
  Fiji's script framework rather than by ImageJ's plugin scan, and it reported
  `Unrecognized command` on all 36 validation arms.
- **Localisability is reported and never thresholded** (defect D12, open): at no
  measured scale does a cut separate the recordings that register from the ones
  that do not.
- **Batch parallelism is restricted to diagnose and recommend.** Concurrent
  `Interpreter.batchMode` was measured leaving batch mode on after both arms had
  finished, letting an arm drive with the switch off, and making a recording
  unfindable by title.
- The fingerprint measures **whole-field translation, and nothing else**.
- On a recording that moves very little, the shared region every arm is scored
  over is narrow, so a comparison is **most likely to say nothing on exactly the
  recordings that move least**.
- Handed a multichannel hyperstack, the engines this plugin drives align plane by
  plane in stack order, so every arm is refused a figure with its reason.
  Duplicate the channel you want measured first.

### Not done, and deliberately

No GitHub release, no archived DOI, no ImageJ update site and no entry in Fiji's
central list of update sites. Those are separate, outward, irreversible steps and
none of them is a statement about this source tree. See `PUBLISHING_AUDIT.md`.

[0.1.0]: https://github.com/Jay2owe/RegistrationDriftComparison
