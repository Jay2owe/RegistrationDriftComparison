# Stage 03 — Facade, tables and the auto-save tree

`RegDrift.run(params)` as a working skeleton, the four `ResultsTable`s with their columns fixed, the
auto-save tree with its `README.txt`, and the bytecode test that keeps the public API headless
forever.

## Why this stage exists

The public Java API is a CPC-standard obligation and the thing a headless user calls, so it exists
before the engine rather than being retro-fitted around it. Fixing the four tables' columns now means
stages 09 to 12 fill named columns instead of inventing them, and stage 15's validation run can be
written against a schema that already exists.

The bytecode test matters more than it sounds. "Opens no window, writes no file, installs nothing"
is easy to state and easy to break by accident three stages later — one `IJ.error` in a catch block
is enough. Asserting it against compiled classes means the build fails the moment it stops being
true.

## Prerequisites

- Stage 02 `_COMPLETED`.

## Read first

- `00_overview.md` — house rules 9, 11, 13
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` §§ *Outputs* (all four
  table definitions, the images and plots table, the auto-save tree), *Java API*, *The CPC standard*
- `Experiments\CPC\src\main\java\cpc\CPC.java` — facade shape
- `Experiments\CPC\src\main\java\cpc\CPCAnalysis.java` — **for its auto-save code only**; the
  analysis itself is replaced entirely
- `Experiments\Log-Ratio Registration\src\test\java\logratio\core\ApiIsolationTest.java` — the
  bytecode-assertion technique, already written in the family
- `Cores\oc3d-core\src\main\java\sc\fiji\oc3d\core\io\CsvWriter.java`

## Scope

- `RegDrift.run(RegDriftParameters)` — validates, dispatches on mode, returns a `RegDriftResult`.
  Every mode currently returns a result whose `failure()` says `not_implemented: stage NN`. Later
  stages replace those branches one at a time.
- `RegDriftTables` — builds all four `ResultsTable`s with the exact columns from the contract, in
  the contract's order. Coordinator-thread only.
- `RegDriftAutoSave` — writes the tree below, plus a `README.txt` in the root recording what the
  files are, the settings used, the plugin version, the engine versions found and the calibration
  set. Appends to `summary.csv` across runs.
- Column population helpers that take typed values, so a stage cannot write a string into
  `drift_rate_px` by accident.
- `ApiIsolationTest` — **T16**: bytecode assertion that `regdrift.diag.*`, `regdrift.advise.*` and
  `regdrift.score.*` reference no `ij.gui.*` and no `java.net.*`, and that `RegDrift` itself
  references no `ij.gui.*`, no `WindowManager`, no file-write API and no `java.net.*`.
- A tables test asserting every contract column exists, spelled exactly, in order.

## Out of scope

- Any measurement. The tables are built empty or from synthetic rows in tests.
- The dialog and entry classes — stage 04.
- `registered/` output — stage 11 produces the stack, stage 13 wires it in.
- `qc/` kymograph output — stage 12 produces it.
- Batch aggregation — stage 14.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/RegDrift.java` | NEW | The facade; mode dispatch with typed not-implemented branches |
| `src/main/java/regdrift/RegDriftTables.java` | NEW | The four tables, columns fixed |
| `src/main/java/regdrift/RegDriftAutoSave.java` | NEW | The tree, the `README.txt`, `summary.csv` |
| `src/test/java/regdrift/ApiIsolationTest.java` | NEW | **T16.** Bytecode assertions |
| `src/test/java/regdrift/TablesTest.java` | NEW | Column names, order and types |
| `src/test/java/regdrift/AutoSaveTest.java` | NEW | Tree layout, `README.txt` contents, append behaviour |

## Implementation sketch

The four tables, columns exactly as `02_CONTRACT.md` § *Outputs* specifies them.

**`Diagnosis`** — one row per channel:

```
channel, localisability, measured_at_bin, frame_correlation, drift_rate_px, bridge_max_px,
bridge_span, wander, step_rms_px, step_max_px, knocks, log2_trend, bright_fraction,
agreement_px, motion_label, motion_dominant, severity, verdict
```

`measured_at_bin` is provenance, not decoration — the localisability number has no meaning without
it (D12), so the two columns are always written together and neither is optional.

**`Recommendation`** — one row per candidate engine:

```
engine, rank, reason, expected_error_px, expected_seconds, calibration, installed,
install_size_mb, install_action, menu_path, macro_line
```

**`Comparison`** — one row per arm actually run:

```
engine, settings, cpu_seconds, residual_before, residual_after, residual_removed,
sd_vs_control, path_px, net_px, frames_flagged, status
```

**`Frames`** — one row per frame, for the scored arm:

```
t, cum_dx, cum_dy, step_dx, step_dy, residual_before, residual_after, valid_fraction, status
```

`status` takes one of `ok`, `refused_low_overlap`, `interpolated`, `at_shift_bound`,
`not_converged` — an enum in code, a string in the table.

The auto-save tree:

```
<root>/RegistrationDriftComparison/
  README.txt                        what these files are, settings used, plugin version,
                                    engine versions found, calibration set
  diagnosis/<title>_diagnosis.csv
  recommendation/<title>_recommendation.csv
  comparison/<title>_comparison.csv
  frames/<title>_frames.csv
  registered/<title>_<engine>.tif   only in apply mode
  qc/<title>_kymograph.tif
  summary.csv                       appended across runs
```

Facade skeleton — the shape later stages fill in:

```java
public static RegDriftResult run(RegDriftParameters p) {
    p.validate();                                   // throws with a named option on bad input
    switch (p.mode()) {
        case DIAGNOSE:               return notImplemented("stage 09");
        case DIAGNOSE_AND_RECOMMEND: return notImplemented("stage 10");
        case APPLY:                  return notImplemented("stage 13");
        case COMPARE:                return notImplemented("stage 13");
        case SCORE:                  return notImplemented("stage 12");
    }
    throw new IllegalStateException("unhandled mode: " + p.mode());
}
```

`notImplemented` returns a real `RegDriftResult` with a typed `Failure`, never null and never an
exception — house rule 14, and it means stage 04's dialog can be built and clicked before any
engine exists.

The isolation test, in the shape `logratio.core.ApiIsolationTest` already uses in the family:

```java
@Test public void diagnosisReferencesNoGuiAndNoNetwork() {
    for (String cls : classesIn("regdrift.diag", "regdrift.advise", "regdrift.score")) {
        assertNoReferenceTo(cls, "ij/gui/");
        assertNoReferenceTo(cls, "java/net/");
    }
}

@Test public void facadeWritesNothingAndShowsNothing() {
    assertNoReferenceTo("regdrift.RegDrift", "ij/gui/");
    assertNoReferenceTo("regdrift.RegDrift", "ij/WindowManager");
    assertNoReferenceTo("regdrift.RegDrift", "java/net/");
    assertNoReferenceTo("regdrift.RegDrift", "java/io/FileOutputStream");
}
```

The packages are asserted by name, so a class added to `regdrift.diag` in stage 08 is covered
automatically without anyone remembering to add it here. That is the whole point.

`RegDriftAutoSave` is the **only** class in the plugin that writes files, and it is called by the
entry classes and the batch runner — never by the facade. `RegDrift.run` writing a file would break
the CPC standard's point 8 and the test above.

## Exit gate

1. `mvn test` green; all three new tests pass.
2. `RegDrift.run` on a small synthetic stack returns a non-null result in every one of the five
   modes, each carrying a typed `not_implemented` failure and no exception.
3. `TablesTest` asserts every column name above, in order, for all four tables — and fails if one is
   renamed.
4. `AutoSaveTest` creates the full tree in a temporary folder, writes a `README.txt` containing the
   plugin version and the settings string, and appends a second run to `summary.csv` without
   rewriting the first.
5. `ApiIsolationTest` passes, and **is verified to actually fail** when a deliberate `IJ.error` is
   added to a `regdrift.diag` class and removed again.
6. Running `RegDrift.run` in a JVM with no ImageJ window open works — no `WindowManager` call
   anywhere on the path.

## Known risks

- **A bytecode test that never fails is decorative.** Exit gate 5 exists because the technique is
  easy to get subtly wrong — for example asserting on source imports rather than the constant pool,
  which misses a fully-qualified inline reference. Prove it fails before trusting it.
- **`ResultsTable` column order is display order.** Adding a column in a later stage appends it to
  the end unless it is declared here, and a diagnosis CSV whose column order changes between runs is
  a nuisance to anyone parsing it. Declare all columns now, even ones nothing fills yet.
- **`summary.csv` append across runs** must tolerate a file written by an earlier plugin version
  with fewer columns. Decide the behaviour now — append with blanks, or start a new file with a
  suffix — and record it in the `README.txt`.
- **Windows path length.** `<root>/RegistrationDriftComparison/recommendation/<title>_recommendation.csv`
  with a long microscope-generated title can exceed 260 characters inside a synchronized folder.
  Fail with a clear message naming the path, rather than an opaque IO exception.
