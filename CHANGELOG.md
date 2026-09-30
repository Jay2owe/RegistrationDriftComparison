# Changelog

All notable changes to Registration & Drift Comparison are documented here. The
format follows [Keep a Changelog](https://keepachangelog.com/) and this project
adheres to [Semantic Versioning](https://semver.org/).

## [0.1.0] - Unreleased

First release. One jar, no prerequisites, three menu entries under
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
  recording, from **Plugins > Registration > Registration Batch...**. Its line
  is written to the macro recorder and replays.
- Engine detection for ten catalogue engines, with a per-engine install action
  that a person presses in a dialog. **No macro option installs anything.**
- The frozen bundled calibration: `benchmark_2026-08-11.csv`,
  `thirdparty_2026-08-11.csv` and `saturation_2026-08-11.csv`, copied from the
  research repository on 2026-08-13 and never edited. A later benchmark run
  becomes a new file beside these, with its own date and a line in this
  changelog.
- Golden outputs for all five modes over five fixed recordings, checked on every
  build, and a synthetic benchmark (`-Dregdrift.bench=true`) that times each
  mode and fails if two repeats disagree, so a later change that moves any
  number or any timing is seen.

### Fixed before release

- A `save_root` written with backslashes, as Windows paths are and as the
  dialog's folder browser fills it in, was refused by the macro parser, so the
  dialog would not run and the saved `README.txt` and `summary.csv` said the
  settings could not be written as a macro line. Backslashes now become forward
  slashes on the way in. This changed the saved-tree digests in
  `src/test/resources/golden/modes.tsv` and nothing else: every table, verdict
  and registered-pixel digest is unchanged.
- `use_roi` was accepted and then ignored. It now measures the movement inside
  the rectangle enclosing the selection, refuses a missing selection or one under
  16 x 16 pixels in words, and says in the channel reason what was measured.
- One infinite pixel anywhere in a 32-bit recording left the drift rate blank.
  Infinite pixels are now treated as unmeasured, as missing (NaN) pixels already
  were.
- A blank or all-missing frame roughly halved the reported drift rate (0.43
  instead of 0.76 pixels per frame on a test recording), because the frame pair
  it could not measure was counted as no movement. It now carries the window's
  mean measured step across the gap (0.69).
- RGB recordings were measured on the red channel alone. They are now measured
  on the unweighted mean of red, green and blue.
- Comparing a still or featureless recording failed with a message about
  interpolation paths. It now says the recording's own movement measured as
  whole pixels or none, so there is no control, and that no engine was driven.
- A window closed before the run was reported as holding one frame. It now says
  the image has no pixels left to read.
- An engine wait under one second was reported as "within 0 s".
- Cancel at the "Before this starts" box, or Esc during a run, opened an error
  box that then had to be closed. A run the person stopped now ends with a line
  in the status bar; a run with no windows still writes it to the Log.
- Esc pressed while an engine was running was lost, because ImageJ clears the
  key as each menu command starts and a comparison starts one per engine; the
  comparison then ran to the end. Esc is now watched for the whole run and stops
  it at the next engine. A stop during the last engine, when that engine
  produced nothing, also ended as a finished run; it now ends as stopped.
- The folder dialog's preview did not follow a folder chosen with its "..."
  button or typed without Enter; it now reads the folder once the text rests.
  A folder run stopped with Esc now says how many recordings it reached and
  how many it did not, rather than counting the skipped ones as failures.
- Unfolding Advanced left the dialog its old size, so the settings it showed
  sat behind a scroll bar. The dialog now resizes to fit, up to its cap of 80%
  of the screen's height.

### Faster, with every output unchanged

- The phase-correlation transform, where a comparison spends most of its own
  time, now reads its rotation factors from a table and copies columns out in
  blocks, and each spectrum's peak amplitude is taken once per pair rather than
  twice. Measured back to back on synthetic recordings: processor time down 24%
  for compare and score on 512 x 512 x 48, and 17-20% for a diagnosis. Outputs
  unchanged: every table and registered pixel hashes the same, and the transform
  is tested bit for bit against the earlier code.

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
  `MotionLabel.Component.WALK` is not produced in practice. Both are deferred to
  0.2.0; see below.

### Known limits of this release

Stated in full at the top of the README, and at length in `VALIDATION.md`.

- The recommendation is calibrated on **three IncuCyte phase-contrast seed
  frames**, and **two of the ten catalogue engines carry a measured calibration
  row** — StackReg and TurboReg, and they carry the same row.
- **Correct 3D drift cannot currently be driven.** Its command is registered by
  Fiji's script framework rather than by ImageJ's plugin scan, and it reported
  `Unrecognized command` on all 36 validation arms.
- **Localisability is reported and never thresholded** (defect D12, deferred to
  0.2.0 below): at no measured scale does a cut separate the recordings that
  register from the ones that do not.
- **Batch parallelism is restricted to diagnose and recommend.** Concurrent
  `Interpreter.batchMode` was measured leaving batch mode on after both arms had
  finished, letting an arm drive with the switch off, and making a recording
  unfindable by title.
- The fingerprint measures **whole-field translation, and nothing else**.
- **Slow drift on a frame 256 pixels or more a side is under-read.** Measured at
  bin 3-4, `drift_rate_px` read 0.39 for a true 0.84 and 1.01 for a true 1.68
  pixels per frame on synthetic recordings; 3.3 pixels per frame and faster read
  within 8%, and everything at bin 1 within 4%. Deferred to 0.2.0 below.
- **Compare can give no score on steady slow drift.** The comparison's own
  measurement of the movement falls short on 0.3 to 1.5 pixels per frame (about
  6 of a true 14 pixels over 24 frames), so a correct engine is refused a score
  as having moved the picture outside the shared region. Real StackReg and
  MultiStackReg were refused at 0.3, 0.6 and 1.5 pixels per frame and scored,
  tied exactly, at 0.2, 2.5 and 4.0. Same 0.2.0 fix as the line above.
- On a recording that moves very little, the shared region every arm is scored
  over is narrow, so a comparison is **most likely to say nothing on exactly the
  recordings that move least**.
- Handed a multichannel hyperstack, the engines this plugin drives align plane by
  plane in stack order, so every arm is refused a figure with its reason.
  Duplicate the channel you want measured first.

### Deferred to 0.2.0

- **A localisability threshold (defect D12).** Localisability is reported with
  the scale it was measured at (`measured_at_bin`) and nothing routes on it; the
  verdict routes on agreement between the two estimators instead. A threshold
  needs localisability measured against registration outcome across instruments
  and modalities. The twelve single-instrument recordings available here are the
  sample the old `WARN_BELOW = 0.05` was over-fitted to, and on them no cut at
  bin 1, 2, 4 or 8 separates the recordings that register from the ones that do
  not. Evidence: `docs/D12_MEASUREMENT.md`.
- **An unbiased sub-pixel peak fit for phase correlation.** The parabola through
  the correlation peak reads a shift of a fraction of a pixel short, which on a
  binned frame is every drift slower than about 2 native pixels per frame. The
  fix changes the measurement the bundled calibration was taken with, so it ships
  with a re-measured calibration rather than alone.
- **`DOMINANCE_MARGIN` and `WANDER_WALK`**, measured wrong and shipped unchanged
  (see `VALIDATION.md`), are re-measured against the same cross-instrument set,
  and widened or withdrawn the way the `PERIODIC` label and the knock count were
  withdrawn.

### Not done, and deliberately

No archived DOI. The jar is attached to the GitHub release; the ImageJ update
site `Registration-Drift-Comparison` and its entry in Fiji's central list of
update sites follow as separate steps.

[0.1.0]: https://github.com/Jay2owe/RegistrationDriftComparison/releases/tag/v0.1.0
