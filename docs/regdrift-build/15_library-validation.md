# Stage 15 — Library validation

Run the finished plugin across the twelve library entries and check that it reproduces what those
recordings are known to contain. **This is a gate, not a formality: two of the project's four kill
criteria are decided here.**

## Why this stage exists

Everything up to now has been tested against synthetic fixtures and its own assumptions. This is the
first time the whole plugin meets twelve real recordings whose movement type, severity and
registration outcome were established independently.

Two kill criteria are settled by this stage:

- **Kill criterion 1 (replaced by D12).** If localisability cannot be made comparable across images,
  the registrability verdict is not shippable — *and the verdict is the product*.
- **Kill criterion 2.** If the arbiter cannot separate the comparison arms — if the ranking is not
  stable across reruns and not consistent with the known condition-to-method table — then compare
  mode is decoration and should be cut, leaving diagnose and recommend.

Cutting compare mode is a legitimate outcome of this stage. It is not a failure to be engineered
around.

**Kill criterion 4** is also checked here, and it is the cheapest of the four: if the plugin cannot
diagnose and recommend on a bare Fiji with nothing else installed, it has become a launcher for other
people's plugins rather than a tool.

## Prerequisites

- Stage 13 `_COMPLETED`. Stage 14 is not required.

## Read first

- `00_overview.md` — the whole *Known open questions* section
- `../../../ImageJ Plugins/Registration and Drift Comparison/00_CASE.md` § *Kill criteria* — all four
- `../../../ImageJ Plugins/Registration and Drift Comparison/t4/RESULT.md` — the run this stage
  repeats properly, and § *Limits of this result*
- `../../../ImageJ Plugins/Registration and Drift Comparison/t4/T4Windows.java` and `score.py` — the
  harness and the scorer, reusable
- `Experiments\Log-Ratio Registration\library\README.md` — how each entry was cut, and what
  `entry.properties` records
- `Experiments\Log-Ratio Registration\library\RESULTS.md` — the recorded outcomes
- `…\library\<entry>\entry.properties` — **the label recorded there was measured on the full
  uncropped frame, binned, on the best-ranked plane**, whereas the entries are unbinned crops of
  channel 1. Do not compare against it directly; that mistake cost the first T4 run

## Scope

- **T12 `LibraryValidationRun`** — across the twelve entries, the verdict matches the entry's
  recorded label, and the two entries recorded as having no trustworthy answer behave as predicted:
  `10_unresolved_methods_disagree` flags `estimators_disagree`, `11_unresolved_moving_artefact` raises
  the motion-loss flag. **These two are the falsifiable cases: they were predicted to fail before they
  were run, and they did.**
- **The T4 re-run, pre-registered this time.** Knock presence and the unordered component set are now
  implemented rather than applied to the scoring afterwards, so the criterion is fixed before the run.
  Score at `W3×K12` and at the two neighbours; the shipped default stands only if it still wins.
- **The D12 measurement**, if stage 09 left it open: localisability across all twelve entries at bin
  1, 2, 4 and 8, and the scale at which the library separates. This is the evidence that closes or
  fails kill criterion 1.
- **The arbiter stability run** (kill criterion 2): run compare mode three times on the same entries
  and check the ranking is stable, and that it is consistent with the known condition-to-method table.
- **The bare-Fiji check** (kill criterion 4): diagnose and recommend on a Fiji installation with none
  of the candidate engines present.
- **T18 `BenchmarkFixture`** — measured CPU timings for `T=48, 768²` and a synthetic `T=500, 1024²`.
  **Reports only. CI never asserts a wall-clock speedup.**
- The fixtures the test plan names: the twelve entries; the three benchmark seed frames in
  `library/benchmark/seeds/` (15 MB, already committed so the fixture reproduces without re-staging
  from Dropbox placeholders); a synthetic trace generator with controllable drift, jitter, walk and
  injected jumps; a flat featureless stack; and a stack whose brightest decile *is* the sample.
- A written `VALIDATION.md` in the repo recording every number, so the result is citable and the next
  person does not re-derive it.

## Out of scope

- Fixing anything the run exposes, beyond a defect that is clearly a bug rather than a finding. A
  finding goes in `VALIDATION.md` and, if it changes scope, into `03_BUILD_PLAN.md`'s out table.
- Widening the calibration to fluorescence or confocal data — v0.2.0, and the narrowest real
  limitation the plugin has.
- Tuning any threshold to make the run pass. If a threshold has to move, the reason must be a
  measurement, and the fact that it moved goes in `VALIDATION.md`.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/test/java/regdrift/validation/LibraryValidationRun.java` | NEW | **T12.** The blocking gate |
| `src/test/java/regdrift/validation/T4Rerun.java` | NEW | The pre-registered sampler re-run |
| `src/test/java/regdrift/validation/BenchmarkFixture.java` | NEW | **T18.** Reports, never asserts |
| `src/test/java/regdrift/validation/Fixtures.java` | NEW | Synthetic traces, flat stack, bright-sample stack |
| `VALIDATION.md` | NEW | Every number, dated, with the run command |
| `src/main/resources/calibration/*.csv` | MODIFY | Only if the D12 measurement changes the threshold |

## Implementation sketch

The validation run's shape, one row per entry:

```
entry                          verdict              motion_label     expected          agrees?
01_jitter_mild                 registrable          JITTER           JITTER            yes
…
10_unresolved_methods_disagree estimators_disagree  —                (no answer)       yes — predicted
11_unresolved_moving_artefact  registrable + flag   DRIFT            (motion loss)     yes — predicted
12_long_baseline_9d            cannot separate      DRIFT+KNOCK      DRIFT             …
```

The reference for the T4 re-run is a **full-consecutive-pair run on the same pixels**, not
`entry.properties`. That was the correction that made the first run meaningful, and the reason is
worth keeping in front of whoever runs it: the properties label was measured on different pixels at a
different scale, so agreement with it measures the crop, not the sampler.

The scoring criterion is fixed **before** the run, and it is the one the implementation now enforces:
label components compared as an unordered set, knock compared as presence. Anything else is scoring
after seeing the answer, which is what `t4\RESULT.md` flagged about its own result.

`BenchmarkFixture` prints and asserts nothing about speed:

```java
// Reports:  T=48, 768²  -> fingerprint X.X s CPU;  T=500, 1024² -> fingerprint Y.Y s CPU
// The claim under test is that Y ≈ X — constant cost in recording length — and it is REPORTED,
// because a CI machine's timings are not evidence about a user's machine.
```

That constant-cost claim is currently measured at `n=1` (`12_long_baseline_9d`, 108 frames). On a
48-frame recording `W3×K12` saves 26% of the pairs; on a 500-frame recording it would save 93%, and
that is an extrapolation. This is the stage that turns it into a measurement.

## Exit gate

1. `mvn test` green with the validation run included, or the run's failures are documented findings
   rather than errors.
2. **T12 passes on all twelve entries**, with the two unresolved entries flagged as predicted.
3. **The T4 re-run scores `W3×K12` at least as well as its neighbours** under the pre-registered
   criterion. If it does not, the default changes and `02_CONTRACT.md`, `03_BUILD_PLAN.md` and stage
   08's constants are updated to match.
4. **D12 is closed with a recorded measurement table, or the verdict ships without a localisability
   threshold** and says so. There is no third option.
5. **Kill criterion 2 is decided in writing.** Either the ranking is stable across three runs and
   consistent with the known table, or compare mode is cut and `03_BUILD_PLAN.md`'s scope table is
   edited to say so.
6. **Kill criterion 4**: diagnose and recommend complete on a Fiji with no candidate engine installed,
   producing a full `Diagnosis` and `Recommendation` table with every engine marked `installed=no`.
7. `VALIDATION.md` records every number, the date, the machine, the plugin version, the exact run
   command, and any threshold that moved and why.
8. `BenchmarkFixture` reports timings for both sizes, and CI asserts nothing about them.

## Known risks

- **The library is twelve recordings on one instrument**, all IncuCyte phase contrast, and only one
  is longer than 48 frames. A clean pass here is necessary and not sufficient, and `VALIDATION.md`
  must say that as plainly as this file does.
- **The reference is itself an estimate.** "Agreement with the full-recording fingerprint" is not
  agreement with truth; no ground-truth motion exists for these recordings.
- **The temptation to tune.** Every threshold in the plugin could be nudged until this run passes,
  and the result would be a plugin calibrated on its own test set. If a threshold moves, the
  justification is a measurement and it is written down.
- **Entries are Dropbox-backed.** The benchmark seed frames were committed precisely so this fixture
  reproduces without re-staging placeholders; check the twelve entries are hydrated locally before
  blaming the code for a slow or failing run.
- **`12_long_baseline_9d` is the entry most likely to produce an awkward result** — real biological
  change dominates it and the arbiter ran out entirely on it before. "Cannot separate" is the right
  answer there, not a failure.
