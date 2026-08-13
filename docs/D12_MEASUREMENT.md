# D12 — what scale is localisability measured at?

**Status while this file is being written: the run is in progress and rows are appended as each
library entry finishes.** If the run was cut off, everything above the last row is still valid
evidence and does not need repeating.

Defect D12: `Localisability.WARN_BELOW = 0.05` was calibrated on the motion survey's *binned*
frames, where the usable band measured 0.086–0.284. Applied to the twelve library entries at native
resolution it fires on eleven of twelve, including the four entries that register best, and reads
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
interpolation-matched control. The four best are `04_drift` (−50.6%), `06_knock` (−40.3%),
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
