# Stage 12 — The arbiter

Score a registered stack against an interpolation-matched control, in one fused pass over each frame
pair, and flag when a method has removed real motion along with the drift.

## Why this stage exists

Every comparison in the plugin ends here. Ranking arms, rating another plugin's output, and the
`score` mode all reduce to one question: *is this stack more stable than it was, by more than the
interpolation alone explains?*

That qualifier is the whole stage. Bilinear interpolation is a low-pass filter and lowers temporal
standard deviation for free — on the library entries the blur alone accounts for **−18% to −27%**.
Scoring a registered stack against the raw stack therefore flatters every method, including one that
did nothing but blur.

## Prerequisites

- Stage 06 `_COMPLETED`.

Stage 11 is needed only for the end-to-end wiring in stage 13. The arbiter can be built and fully
tested against synthetic warps, so this stage can run beside stage 11.

## Read first

- `00_overview.md` — D11 row, house rule 12
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` — defect D11;
  § *Outputs* → the `Comparison` and `Frames` tables; § *Performance and parallelism contract*, the
  "Arbiter" and "Warp for the control" rows
- `Experiments\Log-Ratio Registration\src\test\java\logratio\ValidationRun.java` (848 lines) — the
  arbiter: residual before/after, temporal SD, the fractional-part control warp, kymograph panels
- `…\src\main\java\logratio\core\Warper.java` (271 lines) — including the bit-exact integer
  block-copy path
- `…\src\test\java\logratio\core\WarperTest.java` — carried across

## Scope

- `score/ControlWarp` (E14) — from `Warper.java`. The control is the raw stack shifted by the
  **fractional part only** of each frame's transform: it interpolates identically to the real warp
  and removes no systematic drift. **Parallel over frames.** Keeps the bit-exact integer block-copy
  special case, which is both faster and exactly right when the shift is a whole number of pixels.
- `score/Arbiter` (E13) — residual before, residual after, and temporal SD, **fused into one walk**
  per frame pair rather than three passes. **Parallel over frames**, merged in index order.
- **Reports `sd_vs_control`, never raw SD** (D11). Raw SD is not exposed as an effect anywhere — not
  in a table, not in a plot axis, not in a tooltip.
- **"Cannot separate"** when the control and the result are within noise. A ranking that cannot be
  justified is not produced; the arbiter says so instead.
- `score/MotionPreservation` (E15) — recovered path length against frame size, and single-structure
  dominance. **A flag, never a verdict**, with its caveat attached to the flag itself.
- The kymograph before/after panel — the one QC output that works as a still, and therefore the
  figure panel for the README and the wiki page.
- Wire `RegDrift.run`'s `SCORE` mode: rate a registration another plugin produced, and compare two
  attempts.
- **T10** `ArbiterControlTest`, **T11** `ArbiterFusedPassTest` and `ArbiterParallelTest`.

## Out of scope

- Running engines — stage 11.
- Ranking arms into the `Comparison` table — stage 13 does the wiring; this stage produces the
  numbers for one arm at a time.
- Any claim about accuracy. The true registration is unknown; the quantities are residual mismatch
  and temporal standard deviation against a control (house rule 5).

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/score/ControlWarp.java` | NEW | **E14.** Fractional-part control, bit-exact integer path |
| `src/main/java/regdrift/score/Arbiter.java` | NEW | **E13.** Fused single pass, parallel over frames |
| `src/main/java/regdrift/score/MotionPreservation.java` | NEW | **E15.** A flag with its caveat |
| `src/main/java/regdrift/score/Kymograph.java` | NEW | The before/after QC panel |
| `src/main/java/regdrift/RegDrift.java` | MODIFY | `SCORE` mode becomes real |
| `src/test/java/regdrift/score/ArbiterControlTest.java` | NEW | **T10 / D11** |
| `src/test/java/regdrift/score/ArbiterFusedPassTest.java` | NEW | **T11**, plus the parallel case |
| `src/test/java/regdrift/score/WarperExactnessTest.java` | NEW | Carried; integer path is bit-exact |

## Implementation sketch

The control, which is the entire defence against D11:

```java
// For each frame t with transform (dx, dy):
double fx = dx - Math.round(dx);        // fractional part ONLY
double fy = dy - Math.round(dy);
control[t] = warp(raw[t], fx, fy);      // same interpolator, same code path, no net displacement
```

The control therefore carries exactly the interpolation blur the registered stack carries, and none
of the alignment. `sd_vs_control` is the registered stack's temporal SD relative to that, so a method
that only blurred scores zero — which is the correct answer.

An **identity warp is not a control** and must be rejected: a control with `fx = fy = 0` for every
frame does no interpolation at all and re-opens the whole defect. Assert it.

The fused pass — three accumulators, one walk, instead of three passes over the same pixels:

```java
for (int t = 1; t < n; t++) {
    float[] a = frames.plane(t - 1), b = frames.plane(t), c = control.plane(t), r = registered.plane(t);
    // one loop over pixels, accumulating:
    //   residualBefore[t], residualAfter[t], sdAccum[t], sdControlAccum[t]
}
```

Written into pre-sized arrays at index `t` and merged by the coordinator in index order, so serial,
two-worker and max-worker runs are **bit-identical** — including the floating-point summation order,
which is why the merge is indexed rather than accumulated as workers finish.

The motion-preservation flag, and its caveat, travel together:

```java
public final class MotionFlag {
    public boolean raised();
    public String caveat();   // "Recovered path length is N% of the frame width. On a recording
                              //  dominated by one large moving structure, a registration can follow
                              //  the structure rather than the field. This is a flag, not a verdict."
}
```

`12_long_baseline_9d` is the case that motivated it: real biological change dominated and the arbiter
ran out entirely. On that entry the honest output is "cannot separate", not a ranking.

## Exit gate

1. `mvn test` green; all three new tests pass.
2. **T10**: the control warp interpolates identically to the real warp (same code path, asserted),
   removes no systematic drift, and an identity warp is **rejected** as a control with a clear reason.
3. **T10**: `sd_vs_control` is what appears in the `Comparison` table, and raw SD is not exposed as
   an effect on any public surface. `grep -rn "rawSd\|sdRaw" src/main/java/` returns nothing public.
4. **T11**: the fused single pass is **numerically identical** to three separate passes on the same
   data — not close, identical.
5. **T11**: serial, two-worker and max-worker runs are **bit-identical**; stable under deliberately
   reversed completion order; cancellation works both while queued and while running; the worker
   budget is honoured under a small `maxMemory` (**D9**); no leaked threads, images or files.
6. `WarperExactnessTest`: an integer-pixel shift goes through the block-copy path and is bit-exact
   against the source pixels.
7. On a library entry with a known outcome, `sd_vs_control` reproduces the figure in `t4\RESULT.md`
   to within noise — for example `04_drift` at about −50.6% and `10_unresolved_methods_disagree` at
   about +0.7%.
8. Where the control and the result are within noise, the arbiter reports "cannot separate" and no
   ranking is produced.
9. The kymograph panel renders as an 8-bit before/after image that is legible as a still.

## Known risks

- **"Cannot separate" needs a defined noise level.** Pick it from the library — the entries that
  genuinely improve sit at −12% to −50%, and the two hopeless ones at +0.7% and −0.2%. A threshold
  somewhere around a few percent is defensible; record the number chosen and why.
- **Whether the arbiter can rank arms differing by 2× rather than 100× is an open question**, and
  stage 15 measures it. Do not tune the arbiter to make a ranking appear.
- **Fused passes are where numerical drift creeps in.** Accumulate in `double`, and if the fused and
  three-pass results differ at all, treat it as a bug in the fusion rather than as acceptable
  tolerance — the test says identical for a reason.
- **`ValidationRun.java` is 848 lines of research harness** and includes plotting, CSV writing and
  fixture management that must not come across. Lift the arbiter maths; leave the harness.
- **Memory.** Four planes live per frame pair (raw, previous, control, registered). On a 2048² 32-bit
  stack that is 64 MB per worker; the scheduler's memory budget must account for all four, or D9's
  clamp will be the only thing standing between the run and an out-of-memory error.
