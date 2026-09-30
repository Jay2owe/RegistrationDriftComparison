# D12 — what scale is localisability measured at?

**Status: complete. The outcome is at the foot of this file — D12 stays open, and localisability is
reported with its measurement scale rather than thresholded.** Both routes were run to the end; the
measurement tables are the evidence and do not need repeating.

Defect D12: `Localisability.WARN_BELOW = 0.05` was calibrated on the motion survey's *binned*
frames, where the usable band measured 0.086–0.284. Applied to the twelve library entries at native
resolution it fires on eleven of twelve, including the four entries that register most cleanly, and reads
negative on two. Localisability is the fall in frame-to-frame correlation caused by displacing one
frame a single pixel, so "one pixel" has to mean the same thing in the calibration and in the
measurement, and at native resolution it does not.

Two routes were open. Both were taken.

---

## Route 1 — recover the survey's bin factor from the run record

`MotionSurvey` takes the bin factor as `args[1]` and prints it in its stdout header
(`MotionSurvey.java:107`, `:121`). That header was not kept, and `motion_survey.csv` has no `bin`
column. It is nonetheless recoverable from two columns that *are* in the CSV, because they are the
same quantity in two different units:

```
max_shift_binned = max(12, min(96, 1.5 * largestStep + 12))   // largestStep in BINNED px, "%.0f"
pc_max_step_px   = largestStep * bin                          // the same step in REAL px,  "%.1f"
```

Invert the first for `largestStep`, divide the second by it, and the bin factor falls out. Rows
where `max_shift_binned` is at the 12 px floor or the 96 px ceiling carry no information, so only
the sixteen unsaturated rows are usable, and of those the ones with the largest `largestStep` have
the least quantisation error. Taking the printing precision into account as an interval:

| series | `max_shift_binned` | `pc_max_step_px` | implied bin factor |
|---|---|---|---|
| `VID47_D4_1` | 72 | 161.1 | 3.99 – 4.06 |
| `VID47_D5_1` | 72 | 161.0 | 3.99 – 4.06 |
| `VID47_D3_1` | 45 | 89.3 | 3.99 – 4.13 |

Three independent rows bracket 4 and exclude 3 and 5. **The survey ran at bin 4.** The thirteen
lower-precision rows are consistent with it and are not quoted, because at `largestStep` near 1 px
the interval spans 3 to 6 and says nothing.

That is corroborated from a second direction: the survey's `MIN_SHIFT_BINNED = 12` and
`MAX_SHIFT_BINNED = 96` are described in `MotionSurvey.java:74-77` as binned pixels, and its header
line prints the real-pixel equivalents as `bin * those` — the numbers only sit in a sane range for
IncuCyte frames at bin 4.

---

## Route 2 — measure the library at every candidate scale

Route 1 gives a number; it does not prove that number separates recordings that register from
recordings that do not. So localisability was measured directly on all twelve library entries at
bin 1, 2, 4 and 8 — 48 runs over the real image data, channel 1, Z projected, every consecutive
frame pair in the entry.

The reference the scale has to reproduce is `library/RESULTS.md`, which is the reduction in temporal
standard deviation each entry actually achieved when it was registered, measured against an
interpolation-matched control. The four most cleanly registered are `04_drift` (−50.6%), `06_knock` (−40.3%),
`02_jitter` (−37.5%) and `07_knock_drift` (−37.1%). The two `t4/RESULT.md` calls correctly hopeless
are `10_unresolved_methods_disagree` (+0.7%) and `11_unresolved_moving_artefact` (−2.1%).

### Localisability by scale

| entry | dimensions | bin 1 | bin 2 | bin 4 | bin 8 | run |
|---|---|---|---|---|---|---|
| `01_jitter_mild` | 512x512 c3 z1 t48 | +0.0258 | +0.0791 | +0.1007 | +0.0719 | 1 s |
| `02_jitter` | 512x512 c3 z1 t48 | -0.0246 | +0.0215 | +0.1055 | +0.1527 | 0 s |
| `03_jitter_drift` | 512x512 c3 z1 t48 | +0.0122 | +0.1152 | +0.1798 | +0.1327 | 0 s |
| `04_drift` | 512x512 c3 z1 t48 | +0.0370 | +0.0485 | +0.0380 | +0.0240 | 0 s |
| `05_drift_dominant` | 512x512 c3 z1 t48 | +0.0166 | +0.0360 | +0.0400 | +0.0329 | 0 s |
| `06_knock` | 512x512 c3 z1 t48 | +0.0571 | +0.0628 | +0.0439 | +0.0317 | 0 s |
| `07_knock_drift` | 512x512 c3 z1 t48 | +0.0265 | +0.0485 | +0.0407 | +0.0268 | 0 s |
| `08_knock_severe` | 768x768 c3 z1 t48 | -0.0064 | +0.0097 | +0.0067 | +0.0026 | 2 s |
| `09_knock_extreme` | 768x768 c3 z1 t48 | +0.0071 | +0.0131 | +0.0058 | +0.0028 | 2 s |
| `10_unresolved_methods_disagree` | 768x768 c3 z1 t48 | +0.0011 | +0.0117 | +0.0055 | +0.0033 | 1 s |
| `11_unresolved_moving_artefact` | 768x768 c3 z1 t48 | +0.0081 | +0.0468 | +0.1467 | +0.2300 | 1 s |
| `12_long_baseline_9d` | 640x640 c3 z1 t108 | +0.0120 | +0.0296 | +0.1035 | +0.2260 | 2 s |

### Estimator agreement, and localisability beside it

| entry | loc bin 1 | agree bin 1 (px) | bound 1 | pairs 1 | s | loc bin 4 | agree bin 4 (px) | bound 4 | pairs 4 | s |
|---|---|---|---|---|---|---|---|---|---|---|
| `01_jitter_mild` | +0.0258 | 0.471 | 17.0 | 35/35 | 12.4 | +0.1007 | 0.225 | 13.4 | 35/35 | 1.0 |
| `02_jitter` | -0.0246 | 0.115 | 24.1 | 35/35 | 11.3 | +0.1055 | 0.197 | 15.0 | 35/35 | 1.0 |
| `03_jitter_drift` | +0.0122 | 0.143 | 21.0 | 35/35 | 12.2 | +0.1798 | 0.195 | 14.1 | 35/35 | 1.0 |
| `04_drift` | +0.0370 | 0.089 | 20.1 | 35/35 | 12.2 | +0.0380 | 0.186 | 13.8 | 35/35 | 1.0 |
| `05_drift_dominant` | +0.0166 | 0.510 | 18.6 | 35/35 | 12.2 | +0.0400 | 0.257 | 13.5 | 35/35 | 1.1 |
| `06_knock` | +0.0571 | 0.112 | 64.6 | 35/35 | 13.5 | +0.0439 | 0.173 | 25.4 | 35/35 | 1.2 |
| `07_knock_drift` | +0.0265 | 0.143 | 63.2 | 35/35 | 12.6 | +0.0407 | 0.194 | 25.2 | 35/35 | 1.0 |
| `08_knock_severe` | -0.0064 | 25.775 | 186.3 | 35/35 | 43.8 | +0.0067 | 8.084 | 211.7 | 35/35 | 9.0 |
| `09_knock_extreme` | +0.0071 | 11.466 | 83.0 | 35/35 | 48.2 | +0.0058 | 2.749 | 96.0 | 35/35 | 5.4 |
| `10_unresolved_methods_disagree` | +0.0011 | 18.033 | 256.0 | 35/35 | 51.7 | +0.0055 | 3.008 | 189.2 | 35/35 | 7.6 |
| `11_unresolved_moving_artefact` | +0.0081 | 1.183 | 256.0 | 35/35 | 49.3 | +0.1467 | 0.344 | 80.2 | 35/35 | 4.7 |
| `12_long_baseline_9d` | +0.0120 | 2.303 | 120.7 | 35/35 | 46.6 | +0.1035 | 0.539 | 29.7 | 35/35 | 3.1 |

The twelve rows are complete. Both columns are the two-pass structure the fingerprint runs — phase
correlation over exactly the sampler's 35 pairs to derive one global search bound, then both
estimators over the same pairs with that bound — one worker, `W=3, K=12`, channel 1, Z projected.
The seconds are wall-clock for the two passes only and are quoted so the cost of each scale is on
the record, not as a performance claim.

---

## The outcome: D12 does not close. Localisability is reported with its scale and is not thresholded

### Route 1 gave a scale. It did not give a threshold

The survey ran at bin 4, and that is now the scale the fingerprint measures at. But rebinning the
library to bin 4 does not rescue `WARN_BELOW = 0.05`. At bin 4 the shipped threshold still fires on
`04_drift` (+0.0380, **−50.6%** SD against control — the largest reduction in the library),
`06_knock` (+0.0439, −40.3%) and `07_knock_drift` (+0.0407, −37.1%): three of the four
most cleanly registering entries, warned as poorly localisable.

### No threshold at any measured scale separates the library

Taking the four entries the library registers most cleanly — `02_jitter`, `04_drift`, `06_knock`,
`07_knock_drift` — against the two `t4\RESULT.md` calls hopeless, `10_unresolved_methods_disagree`
(+0.7%) and `11_unresolved_moving_artefact` (−2.1%):

| scale | lowest of the four clean ones | highest of the two hopeless | is there a cut between them? |
|---|---|---|---|
| bin 1 | −0.0246 | +0.0081 | no |
| bin 2 | +0.0215 | +0.0468 | no |
| bin 4 | +0.0380 | +0.1467 | no |
| bin 8 | +0.0240 | +0.2300 | no |

Widening the question from four-against-two to every entry, and asking whether *any* cut separates
the five entries that removed more than 25% of the temporal standard deviation from the seven that
removed less than 15%, gives the same answer at every scale. At bins 2, 4 and 8, four of the seven
poor performers — `01`, `05`, `11`, `12` — sit at or above the lowest of the five good ones. At bin
1 all seven do.

Ranking power, as Spearman rank correlation between localisability and the reduction in temporal
standard deviation each entry achieved:

| scale | bin 1 | bin 2 | bin 4 | bin 8 |
|---|---|---|---|---|
| Spearman rho | +0.510 | +0.501 | **+0.133** | −0.098 |

The survey's own recovered scale is the *worst* of the four at ordering the library, and the two
that order it least badly are the two at which the shipped threshold fires on ten and eleven of the
twelve entries and two entries read negative. There is no scale at which the quantity both orders
the library and sits in a range the shipped threshold was calibrated for.

### `11_unresolved_moving_artefact` is not the reason, and is not a counter-example

`11` reads +0.1467 at bin 4, the second highest in the library, and registers at −2.1%. That looks
like a failure of the measure and is not one. A moving artefact has sharp edges, and localisability
asks whether displacing a frame one pixel is detectable in these pixels — on that recording it
plainly is. What goes wrong on `11` is that the sharp thing being tracked is the artefact rather
than the sample, so registering to it is the wrong thing to do. That is not something a measure of
one-pixel detectability can know, and it is what stage 12's motion-preservation flag exists to
catch.

`11` is therefore excluded from the reasoning above as a diagnosis of localisability. **The
conclusion does not depend on it**: `01` (−12.1%), `05` (−5.3%) and `12` (−0.2%) sit above
`04_drift` (−50.6%) at bin 4 on their own, and at bin 1 the entry with the *lowest* localisability
in the library, `02_jitter` at −0.0246, is one of the four that register most cleanly.

### What is shipped instead

1. **The fingerprint measures at bin 4**, the survey's recovered scale, and writes
   `measured_at_bin` beside `localisability` in the same row, always.
2. **Localisability is reported and is not thresholded.** `warn_low_structure` is not issued on the
   strength of it. `Localisability.WARN_BELOW` stays in the source as the survey's calibration, with
   its scale in its javadoc, and nothing in the verdict path reads it.
3. **The confidence signal is estimator agreement**, which does order the library:

| | localisability, bin 4 | agreement, bin 4 | agreement, bin 1 |
|---|---|---|---|
| Spearman rho against SD reduction | +0.133 | **−0.846** | −0.872 |

   (negative because a larger disagreement goes with a worse outcome). Agreement at bin 4 is
   monotone in outcome across the nine entries below the gap, and the three above it are
   `08_knock_severe` (−4.2%), `09_knock_extreme` (−14.4%) and `10_unresolved_methods_disagree`
   (+0.7%).

### The second place the same scale error was waiting

Measuring at bin 4 turned up a second instance of D12, in code that had nothing to do with
localisability. `MotionDescriptors`'s thresholds — the knock floor at 3 px and the severity bands at
2, 8 and 32 px — came from the same survey, and **the survey multiplied its displacements back by
the binning factor before describing them** (`MotionSurvey.java:185, :202`). They are native-pixel
thresholds. Handing them displacements measured at bin 4 shifts every severity by two bands and
changes every knock decision, and nothing complains.

So the fingerprint converts every displacement back out of the measured scale before describing it,
exactly as the survey did. That is arithmetic, not a recalibration: two pixels on frames binned four
ways *is* eight pixels of the original image. Localisability keeps bin 4, because it is a property of
the pixels rather than a length; every displacement is reported in the image's own pixels; and each
object states its own scale, which is the structural defence that caught this.

### `AGREEMENT_LIMIT_PX`, and where the number comes from

Every agreement, at both measured scales, converted to the image's own pixels — twenty-four
measurements, not twelve:

| entry | at bin 1 | at bin 4 (×4) | SD vs control |
|---|---|---|---|
| `04_drift` | 0.089 | 0.746 | −50.6% |
| `06_knock` | 0.112 | 0.692 | −40.3% |
| `02_jitter` | 0.115 | 0.786 | −37.5% |
| `03_jitter_drift` | 0.143 | 0.782 | −29.6% |
| `07_knock_drift` | 0.143 | 0.776 | −37.1% |
| `01_jitter_mild` | 0.471 | 0.899 | −12.1% |
| `05_drift_dominant` | 0.510 | 1.029 | −5.3% |
| `11_unresolved_moving_artefact` | 1.183 | 1.377 | −2.1% |
| `12_long_baseline_9d` | **2.303** | 2.156 | −0.2% |
| — nothing measured, at either scale — | | | |
| `09_knock_extreme` | 11.466 | **10.995** | −14.4% |
| `10_unresolved_methods_disagree` | 18.033 | 12.031 | +0.7% |
| `08_knock_severe` | 25.775 | 32.338 | −4.2% |

**`AGREEMENT_LIMIT_PX` = 5.0 px, in the image's own pixels.** The empty band runs 2.303 to 10.996 px
— a factor of 4.8 with nothing measured inside it at either scale — and its geometric centre is
5.03 px. Five is that, rounded to a whole pixel: 2.17× above the largest agreeing measurement and
2.20× below the smallest disagreeing one. There is no case at the boundary to fit it to.

**Why the image's pixels and not the estimators' own.** Because measured that way the limit gives
*the same answer at both scales*: the same nine recordings fall below it and the same three above,
whether the frames were binned four ways or not. Judged in the estimators' own pixels it does not —
a one-pixel limit at bin 1 would call `11_unresolved_moving_artefact` (1.183) and
`12_long_baseline_9d` (2.303) disagreements, and at bin 4 it would not. A verdict that changed when
the measurement scale changed would be D12 all over again.

`10_unresolved_methods_disagree` produces `estimators_disagree`, which is what the entry is named
for. So do `08_knock_severe` and `09_knock_extreme`, consistent with their −4.2% and −14.4%.

**Where it does not help**, stated rather than hidden: `11_unresolved_moving_artefact` (0.344 px)
and `12_long_baseline_9d` (0.539 px) come back `registrable` and register at −2.1% and −0.2%. Both
estimators agree about the movement in those recordings; what they agree about is not movement whose
removal helps. That is stage 12's flag and stage 15's validation, not this threshold's job.

### What would close D12

Not this library. Twelve recordings from one instrument, all IncuCyte phase contrast, is the sample
the shipped threshold was already over-fitted to once. Closing D12 needs localisability measured
against registration outcome across instruments and modalities — which is the v0.2.0 calibration
widening that `00_overview.md` already carries as an open question, not something this stage can
produce.

---

## What the shipped code does on the twelve entries

`RegDrift.run` in `DIAGNOSE` mode, defaults throughout, channel chosen by the ranking. This is the
built plugin, not a harness.

| entry | bin | localisability | frame_corr | agreement_px | log2_trend | bright_frac | label | severity | verdict |
|---|---|---|---|---|---|---|---|---|---|
| `01_jitter_mild` | 4 | +0.0975 | +0.8626 | 0.899 | +0.0067 | 0.0000 | DRIFT+JITTER | moderate | registrable |
| `02_jitter` | 4 | +0.0960 | +0.7478 | 0.786 | +0.0114 | 0.0000 | DRIFT+JITTER | moderate | registrable |
| `03_jitter_drift` | 4 | +0.1706 | +0.7796 | 0.782 | +0.0131 | 0.0000 | DRIFT+JITTER+KNOCK | moderate | registrable |
| `04_drift` | 4 | +0.0367 | +0.9350 | 0.746 | −0.0022 | 0.0000 | DRIFT+JITTER | severe | registrable |
| `05_drift_dominant` | 4 | +0.0382 | +0.9360 | 1.029 | +0.0063 | 0.0000 | DRIFT+JITTER | moderate | registrable |
| `06_knock` | 4 | +0.0422 | +0.9587 | 0.692 | +0.0097 | 0.0000 | DRIFT+JITTER+KNOCK | extreme | registrable |
| `07_knock_drift` | 4 | +0.0423 | +0.9615 | 0.776 | +0.0104 | 0.0000 | DRIFT+JITTER+KNOCK | extreme | registrable |
| `08_knock_severe` | 4 | +0.0094 | +0.9930 | **32.338** | −0.1303 | 0.0000 | DRIFT+JITTER+KNOCK | extreme | **estimators_disagree** |
| `09_knock_extreme` | 4 | +0.0081 | +0.9924 | **10.995** | −0.2180 | 0.0000 | DRIFT+JITTER+KNOCK | extreme | **estimators_disagree** |
| `10_unresolved_methods_disagree` | 4 | +0.0077 | +0.9735 | **12.031** | −0.2239 | 0.0000 | DRIFT+JITTER+KNOCK | extreme | **estimators_disagree** |
| `11_unresolved_moving_artefact` | 4 | +0.1617 | +0.1676 | 1.377 | +0.0197 | **0.0050** | DRIFT+JITTER+KNOCK | extreme | registrable |
| `12_long_baseline_9d` | 4 | +0.1324 | +0.2486 | 2.156 | +0.1118 | **0.0002** | DRIFT+JITTER | severe | registrable |

Three entries produce `estimators_disagree`, `10_unresolved_methods_disagree` among them, which is
what that entry is named for. **No entry produces `not_registrable`**, and no verdict anywhere is
issued on the strength of a localisability value. `04_drift` reads +0.0367 — below the shipped
threshold — and is called registrable, which is the whole point.

The localisability figures differ slightly from the Route 2 table above because the fingerprint
measures over the sampler's 34 within-window pairs rather than over every consecutive pair. The
ordering is unchanged and so is the conclusion.

### `bright_fraction`, and why its constant is not a knife edge

The column feeds the intensity-ceiling advice and nothing else — defect D4. It is the share of a
frame sitting more than `k` robust standard deviations above its own median, where the spread is
taken from the median and the lower quartile so a bright intruder cannot inflate the spread it is
being measured against. Median across the sampled frames.

| entry | k = 3 | k = 4 | k = 6 | k = 8 |
|---|---|---|---|---|
| `01`–`10` | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| `11_unresolved_moving_artefact` | 0.0144 | 0.0048 | 0.0007 | 0.0001 |
| `12_long_baseline_9d` | 0.0030 | 0.0003 | 0.0000 | 0.0000 |

The **set** of recordings this finds does not move with `k` — ten of twelve read exactly zero at
every value tried, and the two that do not are the two the library itself records as having a bright
intruder. Only how much of each one is counted moves. `k = 4` is shipped: it still finds `12`'s
intruder, and a frame of pure noise would put about one pixel in thirty thousand past it.

### T4 smoke check, and what stage 15 has to re-run

The label and severity rules were re-run through the built plugin on all twelve entries, against
`t4\RESULT.md`'s recorded run of the same rules at bin 1. Component sets — ignoring order and knock
counts, both of which D13 withdrew — match on six of twelve, and severity on seven of twelve.

That is not a regression; it is the measurement moving to a defined scale. `t4` estimated at bin 1
and this estimates at bin 4, and coarser pixels change which steps clear the knock threshold. The
labels moved further before the native-pixel conversion above was in place, which is how that second
scale error was found: three of twelve matched, and `02_jitter` came back `mild`.

**Stage 15's pre-registered re-run is the gate**, on the implemented rules at the implemented scale.
This smoke check says the path runs on all twelve and produces sensible labels; it does not say the
labels reproduce, and no claim here should be read as saying so.

### Cost, warm, with the two estimator passes and the pixel pass included

Process CPU time and wall-clock for a whole `RegDrift.run` in `DIAGNOSE` mode, after a warm-up so
the figures are the measurement rather than the JIT compiling it.

| recording | CPU s, default workers | CPU s, serial | wall s, default workers |
|---|---|---|---|
| `08_knock_severe`, 768² × 48 | **10.0** | 7.5 | 1.4 |
| `10_unresolved_methods_disagree`, 768² × 48 | 9.2 | 7.3 | 1.4 |
| `09_knock_extreme`, 768² × 48 | 6.1 | 5.2 | 1.0 |
| `01_jitter_mild`, 512² × 48 | 1.5 | 1.2 | 0.4 |
| synthetic, 768² × **48** | 3.6 | 3.4 | 0.7 |
| synthetic, 768² × **500** | 3.8 | 3.6 | 0.8 |

**The sampler's claim holds.** Ten times the recording length costs 3.6 s against 3.8 s of CPU with
the default worker count, and 3.4 s against 3.6 s serially — 6% more for ten times the frames, which
is what "constant in the length of the recording" means.

**One entry sits on the 10 s CPU budget rather than under it**, and only with the default worker
count: `08_knock_severe` at 10.0 s, against 7.5 s serial and 1.4 s of wall-clock. Its derived search
bound is 211.7 px at bin 4 on a 192 px plane — consecutive frames there barely overlap — so the
pyramid search box is larger than the frame, and parallel workers rebuild per-frame pyramids the
memory budget cannot hold all of at once. It is the extreme entry in the library and it is stated
rather than rounded down.
