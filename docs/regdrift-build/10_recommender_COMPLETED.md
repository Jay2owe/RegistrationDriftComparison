# Stage 10 — The recommender

Turn the benchmark CSVs into a bundled calibration table, look a fingerprint up in it, rank the
candidate engines, and produce a copyable recipe for each — with an honest flag whenever the
fingerprint lands outside what was actually measured.

## Why this stage exists

This is the answer to the question the plugin is named for. Every existing option makes the user
choose a registration method from folklore; this makes it a lookup in measurements taken on real
recordings. That is the entire differentiator, and it is worth exactly as much as its honesty about
what was and was not measured.

The calibration is **three IncuCyte phase-contrast seed frames** spanning localisability 0.017 to
0.160. Presenting its output as a general recommendation would overstate it, so the flag is not a
nicety — it is what makes the table publishable.

## Prerequisites

- Stage 09 `_COMPLETED` (there must be a fingerprint to look up).
- Stage 05 `_COMPLETED` (install status comes from the catalogue).

## Read first

- `00_overview.md` — D4, D10 rows and house rules 5, 6, 7
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` — defects D4, D6, D10;
  § *Outputs* → the `Recommendation` table
- `Experiments\Log-Ratio Registration\library\benchmark\README.md` — **in full**, especially
  § *The intensity ceiling* and § *Results*
- `…\library\benchmark\benchmark_2026-08-11.csv` — 145 rows. Columns: `seed, condition, estimator,
  reconciliation, frames, pairs, estimate_cpu_ms, reconcile_cpu_ms, total_cpu_ms, cpu_ms_per_pair,
  unscheduled_ms, median_err_px, p90_err_px, max_err_px, refused, saturation_pct, floor_pct`
- `…\library\benchmark\thirdparty_2026-08-11.csv` — 25 rows, the third-party arms
- `…\library\benchmark\saturation_2026-08-11.csv` — 181 rows
- `…\library\survey\motion_survey.csv` — the motion labels the lookup is keyed on

## Scope

- `resources/calibration/*.csv` — the three benchmark CSVs, bundled as resources and **frozen**. A
  recommendation whose evidence file changes silently is not evidence.
- `advise/CalibrationTable` (E8) — loads them once from the bundled resource, indexes by condition
  (`CLEAN`, `GAIN_FADE`, and the rest as the CSVs name them), and exposes the measured error and CPU
  cost per engine per condition.
- `advise/Recommender` (E9) — maps a fingerprint (`motion_label`, `severity`, `log2_trend`,
  `bright_fraction`, `agreement_px`) to a ranked list of engines with expected error, expected CPU
  seconds scaled by frame count, and the measured row the recommendation came from.
- **`calibration = outside_calibrated_range`** whenever the fingerprint lands outside the measured
  span. Never silently extrapolate (D10). The UI states the calibration set next to the
  recommendation, always — not in a footnote.
- `advise/Recipe` (E10) — menu path, macro line, install status per engine, all copyable.
- **The intensity-ceiling advice (D4)**, and the boundary that makes it safe: report
  "a bright structure covers about N% of your frame; a ceiling near the Nth percentile would exclude
  it", **always with the contraindication in the same panel**, and never as a setting.
- Wire `RegDrift.run`'s `DIAGNOSE_AND_RECOMMEND` branch and populate the `Recommendation` table.
- **T8** `RecommenderTest`, **T13** `CeilingAdviceTest`.

## Out of scope

- Running anything — stage 11. A recommendation is produced with nothing installed and nothing
  downloaded (house rule 9).
- Rotation-aware or elastic recommendations — v0.2.0. The fingerprint measures translation only, and
  recommending bUnwarpJ for something not measured would be guessing.
- Widening the calibration beyond three phase-contrast seeds — v0.2.0's first item.
- **Recommending Log-Ratio Registration.** Not until its update site is live. An engine the user
  cannot install is not a recommendation.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/resources/calibration/*.csv` | NEW | The three benchmark CSVs, bundled and frozen |
| `src/main/java/regdrift/advise/CalibrationTable.java` | NEW | **E8.** Loaded once, indexed by condition |
| `src/main/java/regdrift/advise/Recommender.java` | NEW | **E9.** The ranking and the calibration flag |
| `src/main/java/regdrift/advise/Recipe.java` | NEW | **E10.** Menu path, macro line, install status |
| `src/main/java/regdrift/RegDrift.java` | MODIFY | `DIAGNOSE_AND_RECOMMEND` becomes real |
| `src/test/java/regdrift/advise/RecommenderTest.java` | NEW | **T8** |
| `src/test/java/regdrift/advise/CeilingAdviceTest.java` | NEW | **T13 / D4** |

## Implementation sketch

The lookup is a table read, not a model. `O(1)`, serial, and that is the design:

```java
public List<Recommendation> rank(Fingerprint f, Set<EngineId> candidates) {
    Condition c = conditionOf(f);              // from log2_trend, bright_fraction, severity
    List<Row> rows = table.forCondition(c);    // measured rows, one per engine
    // rank by measured median_err_px, tie-break on cpu_ms_per_pair
    // expected_seconds = cpu_ms_per_pair * pairs(f.frameCount()) / 1000
    // calibration = table.spans(f) ? IN_RANGE : OUTSIDE_CALIBRATED_RANGE
}
```

`expected_seconds` is **CPU seconds** (D6). A prior benchmark run reported one arm at 237,000 ms per
pair, which was a laptop standby being counted as computation. Wall-clock never enters a table or a
ranking.

`reason` is one clause naming the measured row, so a sceptical user can check it:

```
"median error 0.47 px on the CLEAN condition of three phase-contrast seeds (thirdparty_2026-08-11.csv)"
```

The recipe, exactly as a user would run it:

```java
public final class Recipe {
    public String menuPath();     // "Plugins > Registration > StackReg"
    public String macroLine();    // run("StackReg ", "transformation=Translation");
    public String installStatus();// "yes" | "no" | "wrong version: 1.46.5"
}
```

D4 is the one place in this plugin where the tempting thing is the wrong thing, and it is worth
restating in full because whoever writes this stage will meet the temptation directly. A
90th-percentile per-frame intensity ceiling turns the benchmark's worst column into its best —
4.755 px to 0.06 px — **and runs 35% faster**. It works only because in those recordings the artefact
is the brightest thing present and covers a known tenth of the field. Where the sample *is* the
brightest decile — any fluorescence recording of cell bodies — the same setting deletes the sample.
All three benchmark seeds are phase contrast, so **the benchmark cannot see that failure.** At the 99th
and 99.5th percentiles the cut is *worse than doing nothing*: it strips real signal and leaves the
artefact. There is no safe default and no partial credit.

```java
@Test public void noCodePathEnablesACeiling() {
    for (String cls : classesIn("regdrift.advise", "regdrift.harness", "regdrift")) {
        assertNoMethodNamed(cls, "setCeiling", "applyCeiling", "enableCeiling");
    }
    assertTrue(CeilingAdvice.text(0.10).contains("would exclude"));
    assertTrue(CeilingAdvice.text(0.10).contains(CeilingAdvice.CONTRAINDICATION));
}
```

`bright_fraction` is reported in the `Diagnosis` table and converted to a **sentence**, never to a
setting.

## Exit gate

1. `mvn test` green; both new tests pass.
2. Every row of the calibration table maps to the engine the measurements support — `RecommenderTest`
   asserts this row by row against the CSVs, not against a hand-written expectation.
3. A fingerprint outside the measured range is flagged `outside_calibrated_range`, and the flag
   reaches the `Recommendation` table and the dialog. Verify with a deliberately out-of-range
   fingerprint.
4. The calibration set — "three IncuCyte phase-contrast seed frames, localisability 0.017–0.160" — is
   visible next to the recommendation in the dialog and written into the auto-saved `README.txt`.
5. **No code path enables an intensity ceiling automatically**; the advice text always carries its
   contraindication; `bright_fraction` never becomes a setting.
6. `RegDrift.run` in `DIAGNOSE_AND_RECOMMEND` mode works **on a bare Fiji with no engine installed** —
   every engine appears with `installed=no` and an install action, and nothing is downloaded.
7. Every `expected_seconds` traces to a `cpu_ms_per_pair` in a bundled CSV; no figure is computed from
   wall-clock anywhere.
8. `grep -rin "\bbest\b\|optimal\|accuracy" src/main/java/regdrift/advise/` returns nothing in a
   user-facing string. `rank=1` is "rank 1", not "the best".
9. Log-Ratio Registration appears nowhere in the catalogue or the recommendation output.

## Known risks

- **The condition mapping is the weakest link.** The CSVs are keyed on conditions the benchmark
  constructed (`CLEAN`, `GAIN_FADE`, …); the fingerprint produces motion labels and intensity trends.
  That mapping is a judgement, not a measurement. Write it in one small function, document each
  branch with the evidence, and make `outside_calibrated_range` the default when no branch fits
  cleanly.
- **The survey found two regimes with nothing between them**, probably an artefact of one instrument.
  A fingerprint landing between them should flag rather than pick the nearer regime.
- **Bundled CSVs and `<minimizeJar>`.** Resources are safe from minimisation, but stage 01 already
  removed it; confirm the CSVs are actually in the packaged jar and readable through
  `getResourceAsStream` after shading.
- **Frozen means frozen.** If a later benchmark run produces better numbers, that is a new bundled
  file with a new date in its name and a changelog entry — not an edit to a shipped one. Anything
  else makes two users' recommendations incomparable with no way to tell.
