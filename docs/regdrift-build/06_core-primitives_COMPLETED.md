# Stage 06 — Core primitives

Lift the parts of `Log-Ratio Registration`'s core that are general infrastructure rather than
registration method: the transform value type, the bounded scheduler, the pyramid cache, frame
extraction, localisability and channel ranking.

## Why this stage exists

Stages 07 to 12 all need the same six things, and all six already exist, tested, in the research
repo. Lifting them once — with the two upstream defect fixes carried across and their tests with them
— means the estimator, sampler, fingerprint and arbiter stages start from working primitives instead
of each porting a piece.

This stage depends only on stage 01, so it can and should run beside stages 02 to 05. It is the
longest lift in the engine half and starting it early is the schedule's best single move.

**What is *not* lifted matters as much as what is.** `PairAligner`, `Registration`, `Reconciler`,
`LogPlane`, `RobustNorm` and `ChainRepair` are the log-ratio criterion — the author's own registration
method, and the thing this plugin must be independent of. They stay in the other repo. Stage 07's
bytecode test will fail if any of them appears here.

## Prerequisites

- Stage 01 `_COMPLETED`.

## Read first

- `00_overview.md` — house rules 2, 3, 12, 13
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` §§ *Defect ledger*
  (D9, D12), *Performance and parallelism contract* (the channel-ranking row)
- `../../../ImageJ Plugins/Registration and Drift Comparison/t4/RESULT.md` § *Defect found* — why
  the measurement scale is a parameter here and not a constant
- `Experiments\Log-Ratio Registration\src\main\java\logratio\core\Transform.java` (142 lines)
- `…\core\PairScheduler.java` (172) — **`workersFor` at line 78 carries D9**
- `…\core\PyramidCache.java` (170)
- `…\core\Localisability.java` (159) — `WARN_BELOW = 0.05`, `of`, `ofPair`, `poor`
- `…\core\FrameSource.java` (43) — the interface that keeps the core free of ImageJ classes
- `…\StackFrames.java` (229) — `of(imp)`, `of(imp, channel, slice)`, `PROJECT_Z = 0`, `plane(int)`,
  `ChannelQuality`, `rankChannels(imp)` at line 193, `frameCorrelation` at 211
- `…\src\test\java\logratio\core\{PairSchedulerTest, LocalisabilityTest}.java` — carried with the code

## Scope

- `regdrift.internal.Transform` — port `Transform.java` near-verbatim. A value type; no behaviour to
  redesign.
- `regdrift.internal.PairScheduler` — port, **carrying D9's fix**: `workersFor` computes in `long`
  and clamps to `Integer.MAX_VALUE` before narrowing. Carry `PairSchedulerTest` with it, including
  the small-`maxMemory` case.
- `regdrift.internal.PyramidCache` — port. Per-run, never static.
- `regdrift.diag.FrameSource` — port the interface. It is the boundary that keeps the measurement
  code free of ImageJ classes, which is what makes stage 03's bytecode test possible.
- `regdrift.diag.Frames` (E1) — from `StackFrames`. Hyperstack to per-`t` 2D float plane, with
  channel choice and Z slice or projection. Adds **binning to a specified effective pixel size**,
  which is new and is the whole reason this is not a straight copy.
- `regdrift.diag.Localisability` — port. Keep `WARN_BELOW = 0.05` and its documented caveat, and
  **add the measurement scale to every result**: a localisability number without the scale it was
  measured at is meaningless (D12).
- `regdrift.diag.ChannelRanker` (E2) — from `rankChannels` and `ChannelQuality`. Ranks on a strided
  subset of frames, **parallel over channels**, bounded. Never ranks by gradient magnitude: that
  picks the noisiest channel, which is exactly backwards for photon-limited imaging.
- Tests: `LocalisabilityTest` (T1) carried; `PairSchedulerBudgetTest` (D9) carried;
  `RankingParallelTest` — serial, two-worker and max-worker rankings **bit-identical**.

## Out of scope

- **Any registration method.** No `PairAligner`, no `Registration`, no `Reconciler`, no `LogPlane`,
  no `RobustNorm`, no `ChainRepair`. Stage 07 writes two published estimators from different sources.
- The warper — stage 12 lifts `Warper.java` for the control warp.
- Deciding the binning value. This stage makes the scale a **parameter**; stage 09 owns the decision
  about what it should be and whether the threshold survives (D12).
- The window sampler — stage 08.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/internal/Transform.java` | NEW | Value type, ported near-verbatim |
| `src/main/java/regdrift/internal/PairScheduler.java` | NEW | Bounded run-owned executor; **D9 fix** |
| `src/main/java/regdrift/internal/PyramidCache.java` | NEW | Per-run pyramid cache |
| `src/main/java/regdrift/diag/FrameSource.java` | NEW | The ImageJ-free boundary |
| `src/main/java/regdrift/diag/Frames.java` | NEW | **E1.** Plane extraction plus binning to a stated scale |
| `src/main/java/regdrift/diag/Localisability.java` | NEW | Ported; every result carries its scale |
| `src/main/java/regdrift/diag/ChannelRanker.java` | NEW | **E2.** Parallel over channels |
| `src/test/java/regdrift/diag/…` and `…/internal/…` | NEW | T1, D9 budget test, ranking determinism |

## Implementation sketch

`Frames` keeps `StackFrames`'s surface and adds one thing:

```java
public static Frames of(ImagePlus imp, int channel, int slice, Bin bin);

/** The effective pixel size a measurement was taken at. Provenance, not decoration. */
public static final class Bin {
    public static Bin none();               // factor 1
    public static Bin factor(int n);        // n x n mean
    public int factor();
    public String provenance();             // written into measured_at_bin
}
```

`PROJECT_Z = 0` keeps its meaning: slice 0 means project Z, any positive value is a 1-based slice.

Binning is a mean over `n × n` blocks, computed once per plane and cached with the plane, because
every consumer in stages 07 to 09 wants the same binned planes. Do not bin per pair.

Localisability keeps the upstream definition — the fall in frame-to-frame correlation when one frame
is displaced a single pixel — and gains its scale:

```java
public static final double WARN_BELOW = 0.05;   // calibrated on the survey's BINNED frames

public static Result of(FrameSource frames, Bin bin);

public static final class Result {
    public double value();
    public Bin measuredAt();
    public boolean poor();      // value < WARN_BELOW — see stage 09 and D12 before trusting this
}
```

`poor()` stays, because the upstream API has it and the test carries across, but **stage 09 owns
whether the verdict is allowed to use it**. Leave a javadoc pointer to D12 on that method so nobody
wires it into a user-facing verdict without reading the open question first.

D9's fix, at `PairScheduler.workersFor` (upstream line 78) — the whole defect is one narrowing:

```java
long byMemory = budget / Math.max(1L, memPerTask);
int workers = (int) Math.min(Math.min((long) tasks, (long) requested),
                             Math.min(byMemory, (long) Integer.MAX_VALUE));
return Math.max(1, workers);
```

A generous memory budget divided by a small per-task cost overflowed `int`, came out negative, won
every `min()` and silently forced the whole run serial. Carry the test that catches it.

Channel ranking is parallel over channels with a bounded scheduler, and the result is merged in
channel index order so the ranking is identical at any worker count:

```java
ChannelQuality[] ranked = PairScheduler.map(channelCount, workers,
        c -> quality(frames, c + 1, stride, bin), progress, cancellation)
        .toArray(new ChannelQuality[0]);
Arrays.sort(ranked);      // stable, ties broken by channel index — never by measurement order
```

Ties must break on channel index, not on which thread finished first. That is the difference between
a deterministic default channel and one that changes between runs on the same data.

## Exit gate

1. `mvn test` green.
2. `LocalisabilityTest` (T1) passes as carried, including the NaN handling and the two-band
   separation from the survey.
3. `PairSchedulerBudgetTest` fails against the unfixed `workersFor` and passes against the fixed one
   — verify both directions, since a test that never saw the bug proves nothing.
4. `RankingParallelTest`: serial, two-worker and max-worker rankings are **bit-identical** on a
   multi-channel fixture, including the tie case.
5. `Frames.of(imp, c, PROJECT_Z, Bin.factor(2))` on a 512² stack returns 256² planes, and the binned
   plane's mean equals the unbinned plane's mean to float tolerance.
6. Every `Localisability.Result` carries a non-null `Bin`, and there is no API that returns a bare
   `double` without one.
7. `grep -rn "PairAligner\|Reconciler\|LogPlane\|RobustNorm\|ChainRepair\|logratio" src/main/java/`
   returns nothing outside a comment attributing a lifted file to its source.
8. Nothing in `regdrift.diag` or `regdrift.internal` imports `ij.gui.*` — stage 03's test covers it;
   confirm it still passes.

## Known risks

- **The temptation to lift `PairAligner` too.** It is right there, it is tested, and it would save
  stage 07 a day. It is also the author's own registration criterion, and lifting it destroys the
  independence claim the entire plugin rests on. If stage 07 looks hard, that is the cost of the
  claim, and it is the claim that makes the plugin worth writing.
- **`FrameSource` is the isolation boundary.** If `Frames` leaks an `ImagePlus` through it, the
  bytecode isolation test stops meaning anything. Keep the ImageJ types on the construction side only.
- **Binning changes what everything downstream measures.** Every number in the `Diagnosis` table is
  scale-dependent to some degree, not just localisability. Carry the `Bin` through every result
  object from here on rather than storing it once at the top — one object with a stale scale is how
  D12 happened in the first place.
- **`StackFrames` is 229 lines and has ImageJ types throughout.** Splitting it into `Frames`
  (ImageJ-facing) and the `FrameSource` implementation (float arrays only) is the port's real work,
  not the copy.
