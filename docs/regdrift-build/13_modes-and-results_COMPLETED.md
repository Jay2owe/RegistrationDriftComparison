# Stage 13 — The five modes, wired end to end

Join everything: `apply` and `compare` become real, the results view shows the recommendation and the
head-to-head, and the plots appear. After this stage the plugin does what its README will claim.

## Why this stage exists

Stages 06 to 12 each produce a piece. Nothing has yet run a fingerprint, looked it up, installed
what was missing, driven three engines and ranked them in one click — which is the actual user
experience, and the only place the seams show.

It is also where the presentation decisions land, and they are decisions with teeth: what the plugin
shows when it cannot separate two methods, and what it shows when the recommendation is outside its
calibration.

## Prerequisites

- Stages 04, 09, 10, 11 and 12 all `_COMPLETED`.

## Read first

- `00_overview.md` — house rules 5, 6, 9, 10, 11
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` §§ *Dialog*,
  *Outputs* (all four tables plus the images-and-plots table), *Algorithm sketch*
- `../../../ImageJ Plugins/Registration and Drift Comparison/00_CASE.md` — the differentiator
  paragraph. The results view is where it either shows or does not
- Stage 04's `CompareDialog`, stage 10's `Recommender`, stage 11's `EngineRunner`, stage 12's
  `Arbiter`

## Scope

- `RegDrift.run` — `APPLY` and `COMPARE` branches become real. Every mode now returns a populated
  result; nothing returns `not_implemented`.
- **`APPLY`**: fingerprint → recommend → run the recommended engine, or the one named in
  `apply_engine` → score it → return the registered stack.
- **`COMPARE`**: fingerprint → recommend → run every selected **installed** engine serially → score
  each against the same control → rank by `sd_vs_control` and CPU seconds → one `Comparison` row per
  arm actually run.
- **The pre-dispatch estimate.** Comparison is `arms × full registration`, which on a 500-frame stack
  is minutes to tens of minutes. Warn with an estimate before dispatching, computed from the
  calibration table's CPU seconds scaled by frame count, and let the user cancel.
- `ui/ResultsPanel` (U5) — the recommendation and the head-to-head. **Not FLASH's parameter grid**;
  the comparison view shows a handful of whole-stack results, not a lattice.
- Plots: the motion trace (`ij.gui.Plot`, on by default in diagnose mode) and the intensity trend
  (off by default).
- Images: the registered stack (on in apply mode), the kymograph before/after (off), temporal SD
  before/after/control (off).
- `hide_display` honoured on every path — headless means no window, no plot, no table shown.
- Auto-save wired for every mode, including `registered/` and `qc/`.

## Out of scope

- Batch — stage 14.
- Widening the calibration, adaptive windows, elastic registration, pair-by-pair driving — all
  v0.2.0.
- Anything resembling a parameter sweep grid. Object Segmentation Sweep owns that question and that
  UI.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/RegDrift.java` | MODIFY | `APPLY` and `COMPARE` become real |
| `src/main/java/regdrift/ui/ResultsPanel.java` | NEW | **U5.** Recommendation and head-to-head |
| `src/main/java/regdrift/ui/Plots.java` | NEW | Motion trace and intensity trend |
| `src/main/java/regdrift/ui/CompareDialog.java` | MODIFY | Estimate-before-dispatch, results routing |
| `src/main/java/regdrift/RegDriftTables.java` | MODIFY | `Comparison` and `Frames` populated |
| `src/main/java/regdrift/RegDriftAutoSave.java` | MODIFY | `registered/`, `qc/`, `summary.csv` rows |
| `src/test/java/regdrift/ModesEndToEndTest.java` | NEW | All five modes on a synthetic fixture |

## Implementation sketch

The compare loop — serial over arms by construction, one control shared by every arm so the
comparison is like-for-like:

```java
Fingerprint f = Fingerprint.measure(frames, bin, plan, progress, cancel);
List<Recommendation> ranked = recommender.rank(f, selected);
ControlWarp control = ControlWarp.forTransformsOf(referenceArm);   // fractional parts only

for (EngineDescriptor e : installedAmong(selected)) {
    if (cancel.cancelled()) break;
    ArmResult arm = EngineRunner.run(e, stack, cancel);             // batch mode, no window, no exit
    ArbiterResult s = Arbiter.score(frames, arm.registered(), control);
    comparison.addRow(e, arm.cpuSeconds(), s);                      // CPU seconds, never wall-clock
}
```

Three presentation rules, and each is a place the plugin could quietly become dishonest:

1. **When the arbiter says "cannot separate", show that**, not a ranking with a tiny difference.
   Two arms within noise are displayed side by side with the same rank and the reason stated.
2. **When the recommendation is `outside_calibrated_range`, the flag is next to the engine name**,
   not in a column the user has to scroll to.
3. **When an engine is missing, the row is present** with its install action — a comparison that
   silently omits the engines the user does not have looks like a complete answer and is not.

The results panel's job is one screen a user can act on:

```
Motion:      DRIFT+JITTER          drift 0.42 px/frame, wander 1.1, knock present (max 14.2 px)
Verdict:     registrable           localisability 0.118 at bin 4; estimators agree to 0.61 px
Calibration: three IncuCyte phase-contrast seeds, localisability 0.017–0.160

  rank  engine                  sd_vs_control   cpu s   status
   1    Correct 3D drift            −41.2%       18.4    ok
   1    StackReg                    −40.8%       12.1    ok — cannot separate from rank 1
   3    Linear Stack Alignment…      −8.1%       47.9    ok
   –    Fast4DReg                        –          –    not installed  [Install (~2.1 MB)]

  [Copy macro line]   [Open plugin]   [Save all]
```

`rank 1` twice with "cannot separate" stated is correct output, not a bug to tidy away.

Language pass before this stage closes — this is the last stage that writes user-facing strings, so
it is the last chance to catch them: no `accuracy`, no `best`, no `optimal`, no `only`; engine names
spelled as their authors spell them; US English throughout.

## Exit gate

1. `mvn test` green; `ModesEndToEndTest` covers all five modes on a synthetic fixture.
2. On a real library entry inside Fiji: `Compare installed engines` runs every installed engine,
   produces one `Comparison` row per arm, and ranks them — with no window opened by any arm.
3. `Recommend and apply` returns a registered stack whose `sd_vs_control` is reported, and the stack
   opens only when `hide_display` is false.
4. Pre-dispatch estimate appears before a comparison starts, is within about 2× of the measured CPU
   time on a 48-frame stack, and Cancel at that prompt runs nothing.
5. `hide_display` on every mode: no window, no plot, no shown table, and the result object still fully
   populated.
6. Auto-save writes the full tree including `registered/` and `qc/`, and appends one `summary.csv`
   row per run.
7. Two arms within noise display the same rank and the reason; no ranking is produced where the
   arbiter says it cannot separate.
8. A missing engine appears as a row with an install action, never as an omission.
9. Stage 03's `ApiIsolationTest` still passes — `RegDrift.run` shows nothing and writes nothing, even
   now that it does everything.

## Known risks

- **This is where `RegDrift.run` is most likely to grow a window.** Displaying results is the entry
  class's job; the facade returns objects. If a plot or a stack needs showing, it is shown by
  `CompareRegistration_`, never by `RegDrift`.
- **One control for every arm** is what makes the comparison like-for-like, but arms produce different
  transforms. Decide and document which transforms the shared control uses — the reference arm's, or
  the fingerprint's own estimate — and be consistent, because changing it changes every number.
- **A long comparison with no cancellation is a hung Fiji.** Check the token between arms and, where
  the engine allows it, during.
- **The results panel is the plugin's public face.** It is also the easiest place to slip into
  "best" — resist it, including in tooltips and in the copy-to-clipboard text.
