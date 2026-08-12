# Stage 08 — Motion descriptors and the window sampler

Turn a displacement trace into a motion description — drift rate, wander, step statistics, knock
presence, severity and a compound label — and decide which frame pairs get measured in the first
place.

## Why this stage exists

The sampler is why this plugin can be the first thing a user runs. Measuring every consecutive pair
costs 3–10 s on a 48-frame stack and grows linearly, so 30–100 s on a 500-frame overnight recording.
The windowed sampler costs `2·W·(K−1) + (W−1)` pairs — **constant in recording length** — and
`t4\RESULT.md` measured that at `W=3, K=12` it reproduces the full-recording motion label on all
twelve library entries.

The descriptors are what the recommendation looks up. Two of their rules were wrong as shipped in the
research harness and are corrected here, both because the T4 run caught them (D13).

## Prerequisites

- Stage 06 `_COMPLETED`.

## Read first

- `00_overview.md` — the sampler paragraph and D3, D13
- `../../../ImageJ Plugins/Registration and Drift Comparison/t4/RESULT.md` — **in full.** It is the
  measurement this stage implements, including the two label changes and why they are corrections
  rather than concessions
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` § *The fingerprint
  sampler — windows and bridges*, and defects D3 and D13
- `Experiments\Log-Ratio Registration\src\test\java\logratio\MotionSurvey.java` — `Descriptors` and
  the linear fit at **266–310**, `label()` and `severity()` at **396–440**. Everything relevant is
  `private`, so this is a promotion to main scope, not a call
- `…\ImageJ Plugins\Registration and Drift Comparison\t4\T4Windows.java` — the harness that measured
  the sampler. `windowStarts` and the two-pass bound derivation are copied from it

## Scope

- `diag/WindowSampler` (E5) — `W` evenly spaced windows of `K` consecutive frames plus one **bridge
  pair** across each gap. Default `W=3, K=12`, measured not guessed. `windows=0` means every
  consecutive pair.
- **The two-pass bound.** Pass 1 runs the cheap estimator over exactly the pairs the sampler will
  measure and derives **one global** `maxShift` from the window pairs *and* the bridge pairs
  together. Pass 2 measures with that bound. Never per window.
- `diag/MotionDescriptors` (E4) — linear fit, residual, `wander`, `stepRms`, `stepMax`, knock
  detection, local drift rate per window, bridge displacements.
- `diag/MotionLabel` — an **unordered set** of components from `JITTER`, `DRIFT`, `WALK`, `KNOCK`,
  rendered in a fixed canonical order, plus a separate `dominant` field that reads `unclear` unless
  the margin is clear (D13).
- **Knock presence and the largest step. Never a knock count** (D13).
- **No `PERIODIC` label**, and `periodicity()` is not ported at all (D3).
- Severity from `max(drift_px, step_max_px)` against the shipped thresholds.
- Descriptors come from **within-window pairs only**. Bridges are reported separately and never
  folded into `wander` or `step_rms` — a bridge spans many frames and is not a step.
- **T2** `MotionDescriptorsTest`, **T3** `NoPeriodicLabelTest`.

## Out of scope

- Running the estimators — stage 07 owns those; this stage decides *which pairs* and *what the
  numbers mean*.
- The verdict — stage 09.
- Adaptive window placement (spending an extra window on a gap whose bridge step is an outlier) —
  v0.2.0. The bridge step already reports the event; refining only locates it more precisely.
- Re-running T4. Stage 15 does that, once these rules are implemented, because in the recorded run
  they were applied after the fact.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/diag/WindowSampler.java` | NEW | **E5.** Windows, bridges, the global bound |
| `src/main/java/regdrift/diag/MotionDescriptors.java` | NEW | **E4.** Promoted from `MotionSurvey`, `periodicity()` omitted |
| `src/main/java/regdrift/diag/MotionLabel.java` | NEW | The unordered component set and `dominant` |
| `src/test/java/regdrift/diag/MotionDescriptorsTest.java` | NEW | **T2** |
| `src/test/java/regdrift/diag/NoPeriodicLabelTest.java` | NEW | **T3** |
| `src/test/java/regdrift/diag/WindowSamplerTest.java` | NEW | Placement, bridge pairing, cost formula |

## Implementation sketch

Window placement, copied from the harness that measured it:

```java
private static int[] windowStarts(int n, int W, int K) {
    int[] s = new int[W];
    if (W == 1) { s[0] = 0; return s; }
    double span = n - K;
    for (int i = 0; i < W; i++) s[i] = (int) Math.round(i * span / (W - 1));
    return s;
}
```

Bridge pair `i` is `(last frame of window i, first frame of window i+1)`. Total measured pairs are
`W·(K−1)` within windows plus `W−1` bridges, run through two estimators — `2·W·(K−1) + (W−1)`
estimates. At `W=3, K=12` that is 68 estimates whether the recording is 48 frames or 5,000.

**Do not add windows for coverage.** `W=4, K=12` measures *every* pair of a 48-frame recording — four
contiguous windows whose three bridges are themselves consecutive pairs — and still scored 11/12,
while `W=3, K=12` measured twelve fewer pairs and scored 12/12. Each window adds a stitch point, and
stitching error accumulates faster than extra coverage removes it. That is the opposite of the
intuition the plan was originally written on, and it is measured.

The constants, copied verbatim from `MotionSurvey` via the T4 harness:

```java
MIN_SHIFT = 12;   SHIFT_HEADROOM = 1.5;   MAX_SHIFT = 256;
KNOCK_FLOOR_PX = 3.0;   KNOCK_MULTIPLE = 6.0;
WANDER_WALK = 2.5;      DRIFT_DOMINANCE = 3.0;
MILD_PX = 2.0;   MODERATE_PX = 8.0;   SEVERE_PX = 32.0;
```

D13, and it is the reason knock count leaves the API entirely: the knock rule is "a step exceeding
`max(3 px, 6 × the median step)`", and that median is computed over whatever steps were sampled. The
threshold moves when the sample moves, so **the count is a statistic of the sample, not of the
recording** — `08_knock_severe` read KNOCK1, KNOCK2, KNOCK4 and KNOCK7 across six samplings of the
same recording. Presence is stable; the count never was a number anyone should act on.

```java
public final class MotionLabel {
    private final EnumSet<Component> components;    // JITTER, DRIFT, WALK, KNOCK — unordered
    private final Component dominant;               // null -> "unclear"

    /** Fixed canonical order, so two equal labels render identically. */
    public String render();                         // e.g. "DRIFT+JITTER", never "JITTER+DRIFT"
    public boolean equals(Object o);                // set equality; order is not identity
}
```

`DRIFT+JITTER` against `JITTER+DRIFT` is a knife-edge decision about which component dominates. It
flips under resampling and it means nothing different to a user — both say the recording drifts and
jitters. Dominance is reported separately and only when the margin is clear, which is why
`motion_dominant` is its own column in the `Diagnosis` table.

Drift is reported as a **local rate per window** plus the bridge displacements, never as one number
fitted across the whole recording. Long baselines accumulate discontinuities, so a recording whose
drift is not linear has no single honest drift figure and the plugin does not invent one.

T2's cases, all synthetic and all cheap:

| Input | Expected |
|---|---|
| Pure ramp | `wander` ≈ 0, `drift_px` = the ramp |
| White jitter | `wander` ≈ 0.7 |
| Random walk | `wander` several |
| One injected jump | knock **present**, largest step = the jump, `drift_px` not inflated |
| Pure sinusoid | Labelled by its drift and wander. **Never `PERIODIC`** (T3) |

## Exit gate

1. `mvn test` green; all three new tests pass.
2. `WindowSamplerTest`: on `n=48, W=3, K=12` the measured pair count is exactly `3·11 + 2 = 35`, and
   on `n=500` it is **the same 35** — the constant-cost property, asserted rather than assumed.
3. Window starts on `n=48, W=3, K=12` are `{0, 18, 36}`, and the three windows do not overlap.
4. `maxShift` is derived once per run from window and bridge pairs together, and the same value
   reaches every window. Assert it: a fixture with one quiet window and one busy window must give the
   quiet window the same bound as the busy one.
5. **T3**: no input produces a `PERIODIC` label, `MotionLabel.Component` has no periodic member, and
   `grep -rin "periodic" src/main/java/` returns nothing outside a comment recording that the claim
   was withdrawn.
6. `MotionLabel` exposes **no** knock-count accessor. `grep -rn "knockCount\|knocks()" src/main/java/`
   returns nothing that returns a number greater than a boolean.
7. `MotionLabel.equals` treats component order as irrelevant, and `render()` is stable across runs.
8. Bridge displacements appear in `bridge_max_px` and `bridge_span` only, and never in `wander`,
   `step_rms_px` or `step_max_px`. Assert with a fixture whose largest movement is inside a gap.

## Known risks

- **The `knocks` column still exists in the `Diagnosis` table.** It is documented as a count in
  `02_CONTRACT.md` § *Outputs* and D13 says report presence. Resolve it here, once: write `1`/`0`
  presence, or rename the column to `knock_present`. Renaming is cleaner and this is the last stage
  where it is free — stage 03 fixed the column set, so changing it means editing `RegDriftTables`
  and `TablesTest` too. Record which was chosen.
- **`MotionSurvey`'s descriptor logic is private**, so this is a re-implementation with a reference,
  not a copy. The T4 harness (`t4\T4Windows.java`) already re-implemented it once and is the closer
  starting point of the two.
- **`windows=0` must not divide by zero** anywhere in the placement maths. It takes an entirely
  different path: every consecutive pair, no bridges.
- **A recording shorter than `W·K` frames.** Fall back to fewer or shorter windows deterministically,
  record what was actually measured in the provenance, and never silently overlap windows — an
  overlapping window double-counts steps and inflates `step_rms`.
- **Everything here is measured on one instrument.** All twelve library entries are IncuCyte phase
  contrast, and only one is longer than 48 frames. The constant-cost claim at 500 frames is an
  extrapolation from `n=1`. Do not tighten any threshold on the strength of this calibration.
