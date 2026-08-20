> **Not submitted, and not submittable yet.** This is the real page, corrected against the plugin
> that was actually built — it replaces `drafts/wiki-page.md`, which was written before the build and
> describes a plugin whose scope changed twice. It is held here rather than opened as a pull request
> against `imagej.github.io` because **there is no update site, no GitHub release and no DOI**, and
> the `update-site:` and `source-url:` fields below would be claims about things that do not exist.
> Submitting it is a separate, outward decision. Destination when that decision is taken:
> `imagej.github.io/_pages/plugins/registration-drift-comparison.md`.
>
> Fill in `release-date` and uncomment `update-site` on the day distribution actually happens, and
> not before.

---

```
---
title: Registration & Drift Comparison
description: Measures the movement in a time-lapse stack, says whether it can be registered, names registration engines from measurements taken on real recordings, and scores the result against an interpolation-matched control.
categories: [Registration, Analysis]
source-url: https://github.com/Jay2owe/RegistrationDriftComparison
# update-site: RegistrationDriftComparison      <- no site exists; do not uncomment until one does
release-version: 0.1.0
release-date: PENDING
dev-status: Active
support-status: Active
team-maintainers: '@Jay2owe'
license-url: https://github.com/Jay2owe/RegistrationDriftComparison/blob/main/LICENSE
license-label: BSD-3-Clause
---
```

Fiji's **Plugins ▸ Registration** menu has more than fifteen entries, and nothing tells you which one
your recording needs. Registration & Drift Comparison measures your time series first — how much of
the movement is a slow drift, how much is frame-to-frame jitter, whether the stage was knocked,
whether the illumination changed, whether a bright artefact is crossing the field, and whether the
images carry enough structure to be registered at all — then names the engines those measurements
support, runs the ones you have on your own recording, and scores what each of them did against a
control that accounts for interpolation blur.

It contains no registration engine of its own.

## The engines it drives, and where each one comes from

This list comes before the feature list on purpose: what the plugin depends on is part of the pitch,
not a caveat underneath it.

| Engine | Whose it is | How you get it | Measured calibration row | Compare mode drives it |
|---|---|---|---|---|
| **TurboReg** | Biomedical Imaging Group, EPFL | Its own update site, or one click here | **yes** | no — its menu entry opens a window and waits for a person. The whole-recording form is StackReg |
| **StackReg** | Biomedical Imaging Group, EPFL | Its own update site, or one click here | **yes**, the same row | **yes** |
| **MultiStackReg** | Brad Busse, in Kota Miura's script-usable form | Its own update site, or one click here | no | **yes** |
| **Correct 3D drift** | Fiji | Part of the Fiji download | no | a recipe is written **and it did not run** — see Limitations |
| **Descriptor-based registration** | Fiji | Part of the Fiji download | no | no — it fuses a series into one image, leaving nothing to score |
| **Register Virtual Stack Slices** | Fiji | Part of the Fiji download | no | no — it reads and writes folders of files, so an arm would write your recording to disk unasked |
| **Linear Stack Alignment with SIFT** | mpicbg, in Fiji | Part of the Fiji download | no | **yes** |
| **Image Stabilizer** | Kang Li | Manual download, last updated June 2009 | no | a recipe is written, and it has never been run here |
| **Fast4DReg** | CellMigrationLab | Its own update site, or one click here | no | no — macro files that ask their own questions and write into a folder |
| **NanoJ-Core** | Henriques lab | Its own update site, or one click here | no | no — a packaged class whose menu entry this build has not read |

Every one of them is somebody else's plugin, detected at run time, never compiled against, and
installed if you press the button and not otherwise. Every "no" in that last column is a sentence
the plugin says to you with the reason in it, rather than an engine quietly missing from a
comparison. See the Fiji
[Registration](https://imagej.net/imaging/registration) page for those plugins and their own
documentation. This plugin recommends and compares them; it does not replace them.

## Limitations, before the features

- **The calibration is three IncuCyte phase-contrast seed frames**, 48 frames each, spanning
  localisability 0.017 to 0.160 at a 4 × 4 pixel mean. That sentence travels with every
  recommendation, inside the plugin.
- **Two of the ten engines carry a measured calibration row, and it is the same row.** The benchmark
  drove TurboReg slice to slice, which is what StackReg is, so those two carry a measured expectation
  and the other eight do not. For the other eight, what the plugin offers is the comparison it runs
  on your own recording.
- **A comparison has three engines to compare, not ten.** Five of the ten carry a recipe for driving
  them; the other five say why not, in the table above and in the row you get back. Of those five,
  **Correct 3D drift cannot currently be driven** — it is found on disk, but its command is registered
  by Fiji's script framework rather than by ImageJ's plugin scan, and it reported
  `Unrecognized command` on all 36 validation arms — and **Image Stabilizer has a recipe that has
  never been run here.** So the comparison evidence rests on three engines, two of which are the same
  algorithm.
- **Localisability is reported with the scale it was measured at and is never thresholded**, because
  at no scale tested does a cut separate the recordings that register from the ones that do not: rank
  correlation against the reduction in temporal standard deviation was +0.51, +0.50, +0.13 and −0.10
  at bins 1, 2, 4 and 8. The verdict routes on estimator agreement instead.
- **The WALK label is out of reach at the shipped window length.** A random walk reads `wander` 0.96
  over twelve frames against a threshold of 2.5, and 3.41 over two hundred. Twelve frames of a walk
  have not had time to wander anywhere, so a walk is labelled as jitter.
- **`DOMINANCE_MARGIN` is measured too narrow and was deliberately not widened.** The twelve
  validation recordings need 2.00 to 26.29 to hold their own crossings, median 4.46, against a
  shipped 1.5 — and fitting 26.29 to the worst of twelve recordings from one instrument is how the
  localisability defect happened in the first place. `motion_dominant` is a reported column and
  nothing routes on it.
- **The validation set is twelve recordings from one instrument**, all IncuCyte phase contrast, one
  of them longer than 48 frames, with no ground-truth movement for any of them.
- **The fingerprint measures whole-field translation and nothing else.** A recording whose parts move
  differently is outside what the diagnosis describes.
- **Batch parallelism is restricted to diagnose and recommend.** Driving two engines through ImageJ's
  `Interpreter.batchMode` at once was measured leaving batch mode on after both arms had finished,
  letting an arm drive with the switch off, and making a recording unfindable by title.
- **The author maintains a competing registration plugin.** It is not in the catalogue and is not
  named in any output. The independence measures are: this plugin ships no registration engine of its
  own; its two estimators are published prior art (phase correlation, and a pyramid
  sum-of-squared-differences search); and a bytecode test asserts that the measurement code
  references nothing from the author's own criterion.

Everything above is measured, and each measurement is in `VALIDATION.md` in the repository with the
date, the machine and the run command beside it.

## One figure

![The same recording registered by two methods, with a residual map and an sd_vs_control number beside each](figure/registration-comparison.png)

The same 48-frame recording registered by two methods and scored the same way. Top row: a residual
map, where each pixel is the root-mean-square of its own frame-to-frame change — bright means it kept
changing, dark means it settled — with all three panels stretched by one pair of limits taken from the
raw recording. Bottom row: one row of the picture per frame, time running downwards, so a feature that
stays put draws a straight stripe and one that drifts draws a slanted one. Beneath, the recommendation
the plugin made before any engine ran; above, the `sd_vs_control` figure the arbiter measured
afterwards. It is reproducible from `docs/figure/MakeFigure.java` in the repository.

## What it does

- **Diagnoses the recording.** Ranks the channels by how well a one-pixel displacement can be
  detected in them, separates drift from jitter from a knocked stage, reports knock presence,
  measures the intensity trend across the recording, and estimates how much of the frame a bright
  structure occupies.
- **Says whether registration can work.** Two independent estimators measure the movement and their
  agreement is reported. Where they disagree, that is the verdict rather than an average.
- **Names the engines the measurements support**, with the measured row each reason came from, an
  expected error, an expected processor time, and a copyable macro line. When your recording falls
  outside the calibrated range it says so instead of extrapolating.
- **Shows what is missing and offers to install it**, with the download size and a checked SHA-1.
  Interactive and opt-in; no macro option installs anything.
- **Applies the engine you choose** and returns the registered stack.
- **Compares the engines you have** on your own recording and ranks what each of them did, putting
  two arms within 3.0 percentage points of each other at the same rank through *cannot separate*
  rather than inventing an order between them.
- **Scores a registration you already made**, including one from a different plugin.
- **Flags when real movement may have been removed** along with the drift.
- **Runs over a folder** from its Java API, one row per recording.

The measurement cost does not grow with the length of the recording: three windows of twelve
consecutive frames plus a bridge across each gap. Measured, a 500-frame recording costs 12% more
processor time than a 48-frame one, and all of that is the larger frames.

## Installation

There is **no update site for this plugin**. Drop `RegistrationDriftComparison-0.1.0.jar` into
`Fiji.app/plugins/` and restart Fiji.

It is a single JAR with no prerequisites, and it adds two commands:

- {% include bc path="Plugins|Registration|Registration Diagnostics..." %}
- {% include bc path="Plugins|Registration|Compare Registration Methods..." %}

On a Fiji with none of the candidate engines installed, diagnose and recommend still complete: every
engine is listed and marked `installed=no`, nothing is downloaded, and nothing is written.

## Usage

Open a time series and choose
{% include bc path="Plugins|Registration|Registration Diagnostics..." %}. Leave **Estimation
channel** on **auto** unless you have a reason not to — the plugin ranks the channels itself, and
choosing the channel with the sharpest-looking detail is often the wrong choice, because on
photon-limited recordings that channel is the noisiest one.

The **Diagnosis** table has one row per channel. The columns that decide everything else are:

| Column | What it tells you |
|---|---|
| `agreement_px` | How far apart two independent estimators are, per transition. Above 5 px, neither is trusted and the verdict says so |
| `motion_label` | The kind of movement, as an unordered set — jitter, drift, a knocked stage, or a combination |
| `severity` | How large that movement is, in the image's own pixels |
| `localisability` | Whether a one-pixel error is visible in these pixels at all, with `measured_at_bin` beside it. **Reported, never thresholded** |
| `verdict` | Whether this channel is worth registering, or whether the two estimators disagree about it |

The **Recommendation** table lists candidate engines in order, with the measured reason each was
ranked where it was, whether it is installed, and a macro line you can copy. The **Engines** section
of the dialog shows what is missing and offers to install it.

To fix the recording, choose
{% include bc path="Plugins|Registration|Compare Registration Methods..." %} and set **Mode** to
**apply**, or to **compare** to run several engines and see how they score on your data.

**If your recording has more than one channel, duplicate the channel you want measured first.** The
engines this plugin drives align a stack plane by plane in stack order, so on a three-channel
hyperstack they align channel 3 of one frame onto channel 1 of the next. Every arm is then refused a
figure with its reason, which is correct and is also a table with no numbers in it.

### Reading the scores

Every registration is scored the same way, and the number to read is `sd_vs_control`. Temporal
standard deviation falls when a stack is registered, but it also falls simply because interpolation
blurs the image — on real recordings the blur alone accounts for a fifth to a quarter of the change.
Each run therefore also scores a control: the same stack shifted by the fractional part of each
transform, which interpolates identically and removes no systematic drift. The difference against
that control is the part attributable to holding the field still. Negative is stiller. Raw temporal
standard deviation is computed nowhere in this plugin, and an identity warp is refused as a control
by name.

`residual_before` and `residual_after` describe how well the frames match after alignment, as an
equivalent displacement in pixels. They are not a measure of correctness: the true registration is
unknown, and no measurement here can recover it.

Every timing in every table is processor time. Wall-clock appears in the progress bar and nowhere
else.

## When this will not help

- **When the movement is not a whole-field translation.** The diagnosis measures translation. A
  recording whose parts move differently is outside what it describes.
- **When something that is not your sample is the brightest thing moving.** An out-of-focus patch
  sweeping across the field will be tracked instead of the tissue, by any method that works from
  intensities. The plugin flags the signature — a recovered path far longer than the field — but it
  cannot tell which moving thing is the sample.
- **When the images carry no structure at the pixel scale.** Consecutive frames that are nearly
  uncorrelated cannot be registered by anything, and the plugin refuses each pair by name rather than
  returning a number.
- **When your recording barely moves.** Every arm is scored inside the part of the frame that is real
  in every frame of the recording's own movement, plus 2 px. On a recording that moves very little
  that region is narrow, and an arm that put the content further out is reported without a figure —
  so a comparison says least on the recordings that move least.
- **When your recording is unlike the data the recommendation was calibrated on.** The calibration is
  three phase-contrast frames from one instrument; outside it, the recommendation is flagged and the
  comparison is the more reliable route.

## Macro recording

Both commands are macro-recordable and both run headless. `hide_display` suppresses windows for batch
use. No macro option installs anything — installation is interactive.

```
run("Registration Diagnostics...", "mode=diagnose_recommend channel=auto slice=project");
run("Compare Registration Methods...", "mode=compare engines=installed arbiter=sd_vs_control hide_display=true");
```

## Java API

```java
RegDriftParameters p = RegDriftParameters.builder(imp)
        .mode(Mode.DIAGNOSE_AND_RECOMMEND)
        .build();
RegDriftResult r = RegDrift.run(p);
```

`RegDrift.run` opens no dialog, shows no window, writes no file, makes no network call, installs
nothing, and needs no active ImageJ window. `regdrift.RegDriftBatch` is the same shape for a folder.

## Related plugins

For choosing segmentation settings once your recording is registered, see Object Segmentation Sweep.

## Citing

No DOI yet: nothing has been archived. If this plugin informed a registration choice in your methods
section, please also cite the registration plugin you actually used.
