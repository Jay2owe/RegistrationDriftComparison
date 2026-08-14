# Stage 14 — Batch over a folder

Run the whole plugin across a folder of stacks: filename regex with a capture group, group preview,
recursive scan, one row per movie, aggregated summaries — parallel over movies, serial inside.

## Why this stage exists

Point 5 of the CPC standard, and the reason the plugin is useful to somebody with an experiment
rather than an image. It is also the mode where a defect that a human would catch on one image goes
unnoticed across two hundred: nobody looks at every row.

Batch is the outer parallel axis. It **moves out one level and never nests** — a batch run is
parallel over movies and serial inside each, so the per-movie executor gets one worker.

## Prerequisites

- Stage 13 `_COMPLETED`.

## Read first

- `00_overview.md` — house rules 11, 12, 13
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` §§ *Outputs* → auto-save
  tree; *Performance and parallelism contract*, the "Batch over a folder" row; *The CPC standard*,
  point 5
- `Experiments\CPC\src\main\java\cpc\CPCBatch.java`, `CPCBatchParameters.java`, `CPCBatchResult.java`,
  `CPCBatchRunner.java` — the shape, adapted
- `Experiments\CPC\src\test\java\cpc\CPCBatchRunnerTest.java`
- `Cores\oc3d-core\src\main\java\sc\fiji\oc3d\core\io\{BatchFileDiscovery, RegexGroupDiscovery, SummaryReporter}.java`

## Scope

- `RegDriftBatchParameters` — folder, recursive flag, filename regex with a capture group, mode, and
  everything a single run takes.
- `RegDriftBatchRunner` — discovers files, groups by the capture group, runs each movie, aggregates.
- `RegDriftBatch` — the headless facade, mirroring `RegDrift`.
- `RegDriftBatchResult` — per-movie rows plus the aggregate, and a typed per-movie failure that does
  not stop the run.
- The batch dialog: folder chooser, regex field, **group preview** showing which files land in which
  group before anything runs, recursive toggle, mode.
- **Parallel over movies, serial inside.** One bounded, run-owned executor at the movie level; each
  movie's own analysis runs with a single worker. Never nested.
- Aggregated `summary.csv`: one row per movie, with the group, the verdict, the motion label, the
  recommendation and — in compare mode — the winning arm and its `sd_vs_control`.
- Each output folder gets its `README.txt` (CPC standard point 6).
- `BatchParallelTest` — serial and parallel batch runs produce **identical rows in identical order**.

## Out of scope

- Comparison arms in parallel. Third-party engines are not thread-safe and several use ImageJ global
  state; arms stay serial inside a movie even when movies run in parallel. **A batch comparison over
  N movies runs N engine arms at once, which is already the risky part** — see the risks below.
- Any new analysis. Batch calls stage 13's modes and adds nothing to them.
- Cross-movie statistics beyond the aggregate table.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/RegDriftBatchParameters.java` | NEW | Adapted from `CPCBatchParameters` |
| `src/main/java/regdrift/RegDriftBatchRunner.java` | NEW | Discovery, grouping, per-movie run |
| `src/main/java/regdrift/RegDriftBatch.java` | NEW | Headless batch facade |
| `src/main/java/regdrift/RegDriftBatchResult.java` | NEW | Rows, aggregate, typed failures |
| `src/main/java/regdrift/ui/BatchDialog.java` | NEW | Folder, regex, group preview, recursive |
| `src/test/java/regdrift/BatchParallelTest.java` | NEW | Serial and parallel identical |
| `src/test/java/regdrift/BatchRunnerTest.java` | NEW | Discovery, grouping, per-movie failure isolation |

## Implementation sketch

```java
int movieWorkers = PairScheduler.workersFor(movies.size(), requested, memPerMovie, budget);
List<MovieRow> rows = PairScheduler.map(movies.size(), movieWorkers,
        i -> runOneMovie(movies.get(i), perMovieParamsWithSerialInside()), progress, cancel);
// rows are pre-sized and merged in index order: batch output does not depend on completion order
```

`perMovieParamsWithSerialInside()` sets `serial = true` on the inner run. That is the "never nests"
rule made concrete, and it is one line that is easy to omit — assert it in the test.

A movie that fails does not stop the batch:

```java
catch (Throwable t) {
    return MovieRow.failed(path, Failure.of(t));    // typed, recorded, run continues
}
```

Group preview, before anything runs — the regex is the part users get wrong:

```
Pattern: ^(?<group>[A-Z]\d+)_.*\.tif$

  A1   →  A1_t0.tif, A1_t1.tif, A1_t2.tif           (3 files)
  A2   →  A2_t0.tif, A2_t1.tif                       (2 files)
  ungrouped → readme.txt, thumbnail.png              (2 files, skipped)
```

ImageJ state stays coordinator-only even here: worker threads open no images through
`WindowManager`, show no tables, and write no files. The coordinator writes every row.

## Exit gate

1. `mvn test` green; both new tests pass.
2. **`BatchParallelTest`**: a serial batch and a parallel batch over the same folder produce
   identical rows in identical order, including the aggregate.
3. Each movie's inner run is serial — assert that the parameters handed to `RegDrift.run` inside a
   batch have `serial = true`.
4. A folder containing one unreadable or non-image file completes, records a typed failure for that
   file, and processes the rest.
5. Group preview matches the actual grouping used by the run, for at least three different patterns
   including one that matches nothing.
6. Recursive scan finds stacks in sub-folders; non-recursive does not.
7. `summary.csv` has one row per movie plus the aggregate, and every output folder has its
   `README.txt`.
8. Cancelling mid-batch stops at the next movie boundary, keeps completed rows, and leaks no threads.
9. Memory: a batch over movies larger than the heap allows degrades to fewer workers rather than
   failing — the D9 clamp path, exercised here for real.

## Known risks

- **Batch comparison is the heaviest thing this plugin can do.** Parallel over movies means N
  third-party engine arms running at once, in one JVM, several of which use ImageJ global state. If
  that proves unsafe, the correct answer is to force `movieWorkers = 1` **in compare and apply modes
  only**, keeping the parallel path for diagnose and recommend — and to say so in the dialog rather
  than silently. Decide this from stage 11's leak findings, not from first principles.
- **Batch mode is global.** Stage 11 sets `Interpreter.batchMode` per arm; concurrent movies setting
  it will interfere. This is the concrete form of the risk above and is the thing to test first.
- **Windows path length** inside a synchronized folder, again — a deep recursive scan plus the
  auto-save tree can exceed 260 characters.
- **Do not let batch grow its own analysis.** Every number in a batch row must be produced by the
  same code path as the single-image mode, or the two will disagree and nobody will know which is
  right.
