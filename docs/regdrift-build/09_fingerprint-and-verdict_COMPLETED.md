# Stage 09 — Fingerprint and verdict

Orchestrate frames, channel ranking, sampler, estimators and descriptors into one `Fingerprint`, and
turn it into the registrability verdict the whole plugin is named for.

**This stage owns defect D12, which is open and blocking. Read § *D12* before writing any code.**

## Why this stage exists

The verdict is the product. Everything before this stage measures; everything after it acts. A user
who runs nothing else should still get an answer to "can this movie be registered at all, and what
kind of movement does it have" — on a bare Fiji, with nothing installed, in a few seconds.

It is also the last stage before the plugin starts making claims about other people's tools, so the
honesty rules bite hardest here: warn, never refuse; report the scale beside every scale-dependent
number; and say "I cannot tell" when the two estimators disagree.

## D12 — the open, blocking defect

`Localisability.WARN_BELOW = 0.05` is calibrated on the survey's **binned** frames, where the usable
band measured 0.086–0.284. Measured on the twelve library entries — unbinned crops of channel 1 — it
reads an order of magnitude lower:

| entry | localisability | verdict at 0.05 | what it actually does when registered |
|---|---|---|---|
| `02_jitter` | **−0.0246** | WARN | **−37.5% SD vs control**, estimators agree to 0.60 px |
| `04_drift` | +0.0370 | WARN | **−50.6%** |
| `06_knock` | +0.0571 | ok | −40.3% |
| `08_knock_severe` | **−0.0064** | WARN | −4.2% |
| `10_unresolved_methods_disagree` | +0.0011 | WARN | +0.7% — correctly hopeless |

Eleven of twelve fall below the threshold, **including the four best-registering entries in the
library**, and two read negative — meaning frame-to-frame correlation *rises* when a frame is
displaced a pixel, which is a signal the measure is being applied outside the regime it was defined
in. Full table in `t4\RESULT.md`.

The cause is scale. Localisability is the fall in correlation under a one-pixel displacement, and one
pixel at native resolution is a far smaller relative displacement than one pixel after binning. The
quantity is scale-dependent and the threshold is scale-specific.

**And the obvious alternative is worse.** The existing `UNREGISTRABLE` rule — frame correlation below
0.30 — fires on `02` (0.163), `03` (0.198), `11` (0.031) and `12` (0.045), and `02` and `03` are
among the *best*-registering entries here. Meanwhile `08`, `09` and `10` sit at 0.88–0.89 with
localisability at or below 0.007. The two numbers rank the library almost inversely. **Do not gate on
frame correlation.**

### What this stage must do about it

1. **Measure localisability at a defined effective pixel size**, not at whatever resolution the user's
   image happens to be, and write that scale into `measured_at_bin` beside every value.
2. **Recover the survey's bin factor** and use it as the default. It is a command-line argument to
   `MotionSurvey` (`<out> <bin> <from> <count> <dirs...>`, see `library/README.md:187`) and is printed
   in that tool's stdout header, not stored in `motion_survey.csv`. Look in the survey run record and
   the `library/survey/traces/` outputs first.
3. **If it cannot be recovered, measure it.** Compute localisability across all twelve library entries
   at bin 1, 2, 4 and 8, and choose the scale at which the library separates — the four
   best-registering entries above the threshold, the two hopeless ones below. Record the table in
   this stage's completion note; it becomes the new calibration and closes D12 with evidence.
4. **Whatever is chosen, say so in the UI.** The verdict panel states the scale the measurement was
   taken at and that the threshold is calibrated on noisy real IncuCyte data. A verdict that hides its
   own calibration is the failure mode this plugin exists to fix in other tools.
5. **Do not silently work around it** — no clamping negatives to zero, no rescaling the threshold per
   image, no quietly dropping the warning. If it is not closed by the end of this stage, it is marked
   open in the completion note and stage 15's validation run is the gate that catches it.

## Prerequisites

- Stage 07 `_COMPLETED`, stage 08 `_COMPLETED`.

## Read first

- `00_overview.md` — D7 and D12 rows
- `../../../ImageJ Plugins/Registration and Drift Comparison/t4/RESULT.md` §§ *Defect found*, *And
  the frame-correlation gate is worse* — **in full**
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` — defects D5, D7, D12;
  § *Outputs* → the `Diagnosis` table; § *Algorithm sketch*
- `Experiments\Log-Ratio Registration\library\README.md:180-200` — how the survey was invoked
- `Experiments\Log-Ratio Registration\library\survey\motion_survey.csv` — the calibration data
- Stage 06's `Localisability` and `Frames`; stage 08's `WindowSampler`

## Scope

- `diag/Fingerprint` (E6) — orchestrates E1–E5 behind one call. One run-owned executor over pairs,
  bounded, cancellable at pair boundaries. Returns a `Fingerprint` value object carrying every
  `Diagnosis` column plus the provenance: window positions, measurement scale, estimator names and
  versions, and how many pairs were measured out of how many exist.
- `diag/Verdict` (E7) — `registrable` / `warn_low_structure` / `estimators_disagree` /
  `not_registrable`, each with finished user-facing text explaining why.
- **Warns, never refuses** (D7). The threshold is calibrated on noisy real recordings; noise-free
  synthetic content scores 0.032 and registers to a hundredth of a pixel, so a plugin that *refused*
  below the threshold would refuse correct work. The wording is "no method is likely to localise this
  channel well", with the caveat stated.
- `estimators_disagree` when the median `agreement_px` exceeds its threshold — this is the one verdict
  that says "I cannot tell", and `10_unresolved_methods_disagree` is the entry that must produce it.
- Wire `RegDrift.run`'s `DIAGNOSE` branch through to a real result and a populated `Diagnosis` table.
- `FingerprintParallelTest` — serial, two-worker and max-worker fingerprints **bit-identical**.
- Re-run the T4 harness against the implemented sampler and descriptors as a smoke check; the full
  pre-registered re-run belongs to stage 15.

## Out of scope

- Recommending an engine — stage 10.
- Running any engine — stage 11.
- Any reconciliation, at any lag, under any name (D5).
- Deciding severity or label rules — stage 08 owns those.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/diag/Fingerprint.java` | NEW | **E6.** Orchestration, one executor, provenance |
| `src/main/java/regdrift/diag/Verdict.java` | NEW | **E7.** The four verdicts and their wording |
| `src/main/java/regdrift/RegDrift.java` | MODIFY | `DIAGNOSE` branch becomes real |
| `src/main/java/regdrift/RegDriftTables.java` | MODIFY | Populate the `Diagnosis` table |
| `src/test/java/regdrift/diag/FingerprintParallelTest.java` | NEW | Determinism at every worker count |
| `src/test/java/regdrift/diag/VerdictTest.java` | NEW | Four verdicts, wording, the never-refuses rule |

## Implementation sketch

```java
public static Fingerprint measure(Frames frames, Bin bin, WindowPlan plan,
                                  PairScheduler.Progress progress,
                                  PairScheduler.Cancellation cancel);
```

The two-pass structure from `t4\T4Windows.java`, which is what was measured:

```
PASS 1  cheap estimator over exactly the pairs the sampler will measure
        -> one GLOBAL maxShift from window pairs AND bridge pairs together
PASS 2  both estimators over the same pairs, with that bound
        -> per-pair displacements, agreement, descriptors, bridges
```

Verdict, with the frame-correlation gate deliberately absent:

```java
if (agreementPx > AGREEMENT_LIMIT_PX)        return ESTIMATORS_DISAGREE;   // "I cannot tell"
if (localisability.value() < WARN_BELOW)     return WARN_LOW_STRUCTURE;    // warn, never refuse
                                             return REGISTRABLE;
// NOT_REGISTRABLE is reserved for structural impossibility — no time axis, one frame,
// a stack the sampler cannot place a single window in. Never for a low measurement.
```

Every verdict carries finished text, in one place, so the dialog, the table and the auto-saved
`README.txt` cannot drift apart:

```java
WARN_LOW_STRUCTURE:
  "No method is likely to localise this channel well: localisability measured %.3f at bin %d,
   below the %.2f threshold. That threshold is calibrated on noisy real recordings, so
   noise-free or synthetic content can register well while scoring below it."
```

The `Diagnosis` table's `localisability` and `measured_at_bin` columns are written together, always.
Any code path that can produce one without the other is the bug D12 describes.

## Exit gate

1. `mvn test` green; both new tests pass.
2. `RegDrift.run` in `DIAGNOSE` mode on a library entry returns a populated `Diagnosis` table with
   every contract column filled and no `NaN` in a column that is not documented as possibly NaN.
3. `FingerprintParallelTest`: serial, two-worker and max-worker runs are **bit-identical**, including
   the derived global `maxShift`.
4. Cancellation mid-run returns a typed `Failure("cancelled")`, leaves no live threads and no
   temporary images.
5. **D12 is either closed with a recorded measurement table, or explicitly marked open in the
   completion note.** Silence is not an option: if it is still open, say which of the two routes was
   attempted and what it produced.
6. `10_unresolved_methods_disagree` produces `estimators_disagree`, and no entry produces
   `not_registrable` on the strength of a low measurement.
7. No code path refuses to produce a fingerprint because localisability is low (D7).
8. `grep -rn "frame_correlation" src/main/java/regdrift/diag/Verdict.java` returns nothing — the gate
   is reported in the table but never routed on.
9. Timing: a diagnosis on a 48-frame 768² stack completes in under 10 s CPU, and on a synthetic
   500-frame stack in **the same time**, within noise. That equality is the sampler's whole claim.

## Known risks

- **D12 may not close cleanly.** The survey's bin factor may not be recoverable from the run record,
  and the four-scale measurement may show no single scale that separates the library. If so, the
  honest outcome is a verdict that reports localisability with its scale and *does not threshold it*
  until v0.2.0 widens the calibration — say that plainly rather than shipping a threshold that fires
  on almost everything.
- **`AGREEMENT_LIMIT_PX` has no calibration yet.** The library gives 0.60–0.71 px for entries that
  register well and a much larger figure for `10_unresolved_methods_disagree`; pick the threshold from
  that gap and record the number chosen, rather than inheriting one from the survey.
- **The provenance object is easy to under-fill.** Window positions, measurement scale, estimator
  names and the measured-pairs-out-of-total count all end up in the auto-saved `README.txt`, and a
  diagnosis nobody can reproduce is not evidence.
- **Bit-identical across worker counts is stricter than it sounds.** The global bound is derived from
  a set of measurements; if that derivation uses a running min or max over completion order rather
  than index order, the bound changes with thread scheduling and every downstream number moves.

---

# Completion note

**361 tests before, 394 after. `mvn test` green.**

## D12: open, and now stated in the open

**Both routes were run to the end and the threshold does not survive either.** Route 1 recovered the
survey's binning factor — **bin 4**, by inverting two columns of `motion_survey.csv` that hold the
same step in binned and in real pixels, three high-precision rows bracketing 4 and excluding 3 and 5.
The fingerprint now measures at that scale and writes it into `measured_at_bin` beside every
localisability.

Route 2 measured all twelve library entries at bins 1, 2, 4 and 8. **Fixing the scale does not rescue
`WARN_BELOW = 0.05`.** At bin 4 it still warns on `04_drift` (+0.0380, −50.6% SD against control, the
largest reduction in the library), `06_knock` (+0.0439, −40.3%) and `07_knock_drift` (+0.0407,
−37.1%). At **no** scale is there a cut that separates the recordings that register from the
recordings that do not: the lowest of the four best-registering entries sits below the highest of the
two hopeless ones at bin 1, 2, 4 and 8 alike. Rank correlation against SD reduction is +0.51, +0.50,
**+0.13** and −0.10 — the survey's own scale is the worst of the four at ordering the library.

So **localisability is reported with its scale and is not thresholded**, `warn_low_structure` is not
issued on the strength of it, and a bytecode test asserts the verdict never calls
`Localisability.Result.poor()`. `ChannelRanker`'s "expect any method to struggle on this recording"
sentence is gone with it — it would have been printed on `04_drift`.

The confidence signal the verdict routes on instead is **estimator agreement**, which does order the
library: rank correlation −0.85. Full evidence, all four scales and both routes:
**`docs/D12_MEASUREMENT.md`**.

Closing D12 needs localisability measured against registration outcome across instruments and
modalities. Twelve recordings from one instrument is the sample the shipped threshold was already
over-fitted to once. `00_overview.md` and `02_CONTRACT.md` are updated to match.

## The same scale error, found in a second place

Measuring at bin 4 exposed one more instance of D12, in code that has nothing to do with
localisability. `MotionDescriptors`'s knock floor (3 px) and severity bands (2, 8, 32 px) came from a
survey that **multiplied its displacements back by the binning factor before describing them**
(`MotionSurvey.java:185, :202`). They are native-pixel thresholds. Feeding them bin-4 displacements
shifted every severity by two bands and changed every knock decision, and nothing complained —
`02_jitter` came back `mild`.

Fixed the way the survey did it: every displacement is converted out of the measured scale before
anything reads it. Localisability keeps bin 4 because it is a property of the pixels; every
displacement is in the image's own pixels; `Fingerprint.displacementsAt()` says so, and each object
still carries its own scale, which is the structural defence that caught this.

## `AGREEMENT_LIMIT_PX = 5.0 px`, in the image's own pixels

All twelve entries measured at bin 1 and bin 4, every agreement converted to native pixels — 24
measurements. The nine whose two estimators tracked each other sit at 0.089–2.303 px; the three where
they parted sit at 10.996–32.338 px; **nothing sits between 2.303 and 10.996**, a factor of 4.8. The
band's geometric centre is 5.03 px and the limit is that rounded to a whole pixel: 2.17× above the
largest agreeing measurement, 2.20× below the smallest disagreeing one.

Judged in the image's pixels rather than the estimators' own because that way it gives the same
answer at both scales — same nine below, same three above. In measured pixels it does not, and a
verdict that moved with the measurement scale would be D12 again.

`08_knock_severe`, `09_knock_extreme` and `10_unresolved_methods_disagree` produce
`estimators_disagree`. **No entry produces `not_registrable`.**

## Cost

Warm, whole `RegDrift.run` in `DIAGNOSE` mode, process CPU time.

| recording | CPU s, default workers | CPU s, serial | wall s |
|---|---|---|---|
| `08_knock_severe`, 768² × 48 | **10.0** | 7.5 | 1.4 |
| `10_unresolved_methods_disagree`, 768² × 48 | 9.2 | 7.3 | 1.4 |
| `01_jitter_mild`, 512² × 48 | 1.5 | 1.2 | 0.4 |
| synthetic, 768² × **48** | 3.6 | 3.4 | 0.7 |
| synthetic, 768² × **500** | 3.8 | 3.6 | 0.8 |

Ten times the length costs 6% more CPU. That is the sampler's whole claim, measured rather than
extrapolated. `08_knock_severe` sits **on** the 10 s budget with the default worker count rather than
under it: its derived bound is 211.7 px at bin 4 on a 192 px plane, so consecutive frames barely
overlap and the search box is larger than the frame. Serial it is 7.5 s. Stated rather than rounded.

## Carried forward

- **`use_roi` is not acted on.** An ROI restricting which pixels vote needs `Frames` to carry one and
  it does not. The setting travels through the request and the record and changes nothing about the
  measurement. Noted in `RegDrift.diagnose`'s javadoc, not left to be discovered.
- **Labels moved against `t4\RESULT.md`.** Six of twelve component sets and seven of twelve
  severities match the bin-1 run. That is the measurement moving to a defined scale, not a
  regression — before the native-pixel fix above it was three of twelve, which is how the fix was
  found. **Stage 15's pre-registered re-run is the gate**, on the implemented rules at the
  implemented scale.
- **`bright_fraction`'s cut is 4 robust standard deviations above the median**, spread taken from the
  dim half of the frame so a bright intruder cannot inflate what it is measured against. Swept at 3,
  4, 6 and 8: ten of twelve entries read exactly zero at every value, and the two that do not are the
  two the library records as having a bright intruder. The set does not move with the constant.
