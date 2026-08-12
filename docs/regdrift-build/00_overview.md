# Registration & Drift Comparison — v0.1.0 build

## End goal

A standalone ImageJ/Fiji plugin with two menu entries under `Plugins > Registration` that takes one
time-lapse stack, measures what kind of movement it contains, says whether that movement can be
registered at all, names the registration engine the measurements support, offers to install that
engine on one click, runs it, and scores the result against a control.

The user opens a shaky movie, presses one menu item, and gets: a motion description, a
registrability verdict, a ranked list of engines with expected error and expected CPU seconds, a
copyable macro line for the winner, and — if they ask — the registered stack plus a number saying
how much better it actually got.

It installs as one jar with no extra update site. The registration engines it drives are other
people's plugins and are detected at runtime, never compiled against.

## Why we're doing this

`forum.image.sc` has the same question on it every few weeks: *which of these registration plugins
should I use?* The honest answer today is "try them and see", which nobody has time for, so people
pick whichever one their supervisor used. Meanwhile five existing registration plugins compute a
per-frame quality signal internally and throw it away.

The benchmarking in `Experiments\Log-Ratio Registration\library\` already measured which method wins
on which kind of movement, across twelve recordings with known movement types. That measured table
is the product: it turns "try them and see" into a lookup.

After this ships, the answer to "why did you register with that method?" stops being a shrug and
becomes a CSV file with a motion label, a verdict, and a `sd_vs_control` number.

## Architecture overview

Three independent halves, joined by one value object.

```
stack ──► FINGERPRINT ──► fingerprint ──► RECOMMEND ──► ranked engines + recipe + install status
             (diag)                         (advise)              │
                                                                  ▼
                                                            HARNESS ──► registered stack ──► ARBITER
                                                           (harness)                         (score)
```

**`diag`** measures. It runs two *published, independent* estimators — phase correlation and a
pyramid sum-of-squared-differences search — over a windowed sample of the recording, and turns the
resulting displacement trace into descriptors. It owns nothing from the author's own registration
work; a bytecode test enforces that, because the independence claim is what makes the
recommendation worth reading.

**`advise`** looks the fingerprint up in a table measured on real recordings and ranks engines. No
model, no heuristic, no folklore — a lookup with a flag when the input lands outside what was
measured.

**`harness`** drives other people's plugins by reflection on hidden images and times them on the CPU
clock. **`score`** rates any registered stack against an interpolation-matched control, so bilinear
blur cannot flatter a method.

Around all of it sits the CPC chassis — parameters, result, macro options, dialog, batch, auto-save
tree, headless Java facade — plus two build-time cores shaded into the plugin's own namespace.

### The sampler, in one sentence

Measuring every consecutive frame pair costs 30–100 s on a 500-frame recording, which is too slow
for the first thing anyone runs. Instead the fingerprint measures **three windows of twelve
consecutive frames** plus a **bridge pair** across each gap — a cost that does not grow with
recording length, and which `t4\RESULT.md` measured as reproducing the full-recording motion label
on all twelve library entries.

## Changed since `03_BUILD_PLAN.md` was written

| What | Then | Now |
|---|---|---|
| `autofix-core` | "separate `/core` job — vendor ~600 lines in its shape here" | **Built and green, 110 tests, both consumers migrated** (`Cores\autofix-core\`, 2026-08-12). Stage 05 depends on it, relocated to `regdrift.internal.autofix`. The vendoring plan is superseded — it is kept in that stage as the fallback if the dependency proves awkward |
| `ui/ToggleSwitch` | "copy verbatim from CPC, 118 lines" | Available inside `oc3d-core` at `sc.fiji.oc3d.core.ui.ToggleSwitch`. Stage 01 takes the relocated one; the verbatim copy is the fallback |
| `<minimizeJar>` | not discussed | **Must not be set.** CPC sets it and is right to; `autofix-core`'s README forbids it, because its entry points are reached through consumer-implemented interfaces the minimiser cannot see through. Shade minimises per execution, not per artifact, so one forbidding consumer settles it for both cores. Cost is jar size only |

## Defect ledger — who owns what

Full text in `02_CONTRACT.md` § *Defect ledger*. Every one of these is a blocking build task, not a
nice-to-have.

| # | One line | Owned by stage |
|---|---|---|
| D1 | `System.exit(0)` after driving TurboReg would destroy the user's Fiji session | 11 |
| D2 | Driving engines through two visible windows races the event dispatch thread | 11 |
| D3 | The `PERIODIC` motion label was a spectral-binning artefact — withdrawn, not tuned | 08 |
| D4 | An automatic intensity ceiling deletes the sample on fluorescence data; the benchmark cannot see that failure | 10 |
| D5 | The fingerprint must be a plain consecutive chain — multi-lag reconciliation would erase the jitter/walk distinction it exists to measure | 07, 09 |
| D6 | Every timing shown or ranked on must be CPU time, never wall-clock | 11 |
| D7 | The localisability threshold warns, never refuses | 09 |
| D8 | A featureless pair must return the identity, not the corner of the search box | 07 |
| D9 | `workersFor` overflows `int`, goes negative, and silently forces the run serial | 06 |
| D10 | The calibration is three IncuCyte phase-contrast seeds and must say so beside every recommendation | 10 |
| D11 | Bilinear interpolation lowers temporal SD for free; every arm is scored against a fractional-shift control | 12 |
| D12 | **Known open.** The localisability threshold is scale-specific and does not transfer to native-resolution pixels | 09 owns the decision; 06 parameterises the measurement |
| D13 | Knock *count* is a statistic of the sample, not of the recording; the compound label must be an unordered set | 08 |

## Stage map

| NN | Name | Goal | Size | Depends on |
|---|---|---|---|---|
| 01 | `repo-scaffold` | Repo from CPC's furniture; both cores shaded and relocated; two stub entries appear in Fiji's menu | S | – |
| 02 | `parameters-and-macro` | Immutable parameter and result value model; every macro option, its parser, and a round-trip test | M | 01 |
| 03 | `facade-tables-autosave` | `RegDrift.run` skeleton, the four `ResultsTable`s, the auto-save tree, and the bytecode test that keeps the API headless | M | 02 |
| 04 | `dialogs-and-entries` | Both `PlugIn` entries, both dialogs, macro-vs-interactive routing, progress and cancellation | M | 03 |
| 05 | `autofix-and-engine-catalogue` | `autofix-core` wired in, the engine catalogue with probes and SHA-1-verified artifacts, the Engines panel | L | 01, 04 |
| 06 | `core-primitives` | The Log-Ratio Registration primitives lifted: transform, bounded scheduler (D9), pyramid cache, frames, localisability, channel ranking | M | 01 |
| 07 | `estimators` | Phase correlation and pyramid SSD as two independent estimators, plus the three tests that keep them independent, chain-only and honest on flat pairs | L | 06 |
| 08 | `descriptors-and-sampler` | Motion descriptors with knock presence and an unordered label set (D13); the `W=3, K=12` window sampler | M | 06 |
| 09 | `fingerprint-and-verdict` | The fingerprint orchestration and the registrability verdict; carries D12 as explicitly open | L | 07, 08 |
| 10 | `recommender` | The measured calibration table, the ranking, the recipe, the ceiling advice that never becomes a setting | M | 09, 05 |
| 11 | `engine-harness` | Driving third-party engines without windows, without exiting, and on the CPU clock | L | 05 |
| 12 | `arbiter` | Fused-pass residual and temporal SD against an interpolation-matched control; the motion-preservation flag | L | 06 |
| 13 | `modes-and-results` | All five modes wired end to end; the results panel; the plots | M | 04, 09, 10, 11, 12 |
| 14 | `batch` | A folder of stacks, filename regex with a capture group, one row per movie, parallel over movies | M | 13 |
| 15 | `library-validation` | **Blocking.** The twelve library entries reproduce their recorded labels; T4 re-run against the implemented label rules | M | 13 |
| 16 | `release-furniture` | README, citation, changelog, publishing audit, local Fiji deploy, the three drafts finalised | S | all |

**Parallelism between stages.** Stage **06** depends only on 01 and can run beside 02–05 — it is the
longest engine lift and starting it early is the schedule's best move. After 06, stages **07** and
**08** are independent of each other. Stage **12** needs only 06 to be built and tested; it needs 11
only for the end-to-end wiring in 13.

**Stage 15 is a gate, not a formality.** It is kill criterion 2 in `00_CASE.md`. If the twelve
entries do not reproduce, the recommendation is not shippable and stage 16 does not start.

## House rules

Every stage must respect these. They come from `01_NAMING.md`, `02_CONTRACT.md` and the portfolio's
CPC standard.

1. **`ij` is the only compile dependency.** No ImgLib2, no FFT library, no `gnu.trove`, no `mcib3d`,
   no `sc.fiji.*`, no `net.imagej.updater`. `oc3d-core` and `autofix-core` are build-time only,
   shaded and relocated into `regdrift.internal.*`, and the user never learns they exist.
2. **This plugin ships no registration engine of its own.** It is the case the whole plugin rests
   on. `diag.*` must reference nothing from any log-ratio criterion, and a bytecode test enforces
   it. Do not "just reuse" `logratio.core.PairAligner` or `Registration` — the two estimators are
   published prior art and are ported deliberately.
3. **Copy, never move.** `Log-Ratio Registration`, FLASH, PULSE and CPC must all keep building
   throughout. Nothing in this plan edits those repos.
4. **Java 8 source level** — Fiji compatibility, inherited from CPC. No `var`, no records, no switch
   expressions. Confirm the exact level from CPC's `pom.xml` in stage 01 rather than assuming.
5. **Never the word "accuracy"** on any column, label, axis or dialog string. The quantities are
   `residual_before`, `residual_after`, `residual_removed`, `sd_vs_control` and `agreement_px`. The
   true registration is unknown, and a column called accuracy claims otherwise.
6. **Never "best", "optimal" or "only"** in the UI, the README or the wiki page. The plugin reports
   which method scored highest on this movie by a named arbiter, and shows the number.
7. **Engine names are spelled as their authors spell them** — "StackReg", "TurboReg",
   "Correct 3D drift", "Fast4DReg", "Linear Stack Alignment with SIFT", "Image Stabilizer". These
   strings are also what the user searches for.
8. **US English in every user-facing string** — menu entries, macro options, column headers, dialog
   labels, folder names, and the `README.txt` written into the auto-save tree.
9. **Nothing is installed to produce a diagnosis**, and **no macro option installs anything**.
   Autofix is interactive and opt-in only. On a bare Fiji, diagnose and recommend must work
   completely.
10. **The intensity ceiling is never auto-enabled** (D4). It is advice, it always carries its
    contraindication, and there is no option that turns it on.
11. **Timings are CPU time** (D6). Wall-clock appears in the progress bar and nowhere else.
12. **Determinism.** Every parallel stage writes into a pre-sized array at a stable index and merges
    in index order. Serial, two-worker and max-worker runs must be **bit-identical**. One bounded,
    run-owned executor per run; no shared static pool; no `parallelStream()` on any path touching
    ImageJ state.
13. **ImageJ state is coordinator-only.** `IJ`, `WindowManager`, `ResultsTable`, `Prefs`, `Plot` and
    every file write happen on the coordinator thread. Workers see float arrays and return float
    arrays.
14. **No silent empties.** Every "I could not compute that" path returns a typed reason, not `null`
    and not `Optional.empty()`. `IJ.log` is not error handling.
15. **Relocation rewrites bytecode, not strings.** Before adding any `Class.forName` or resource
    path, check it is not inside a relocated package — and never relocate the third-party engine
    class names the harness looks up.

## Known open questions

Carried from `03_BUILD_PLAN.md`. Only D12 blocks a stage.

| Question | Owner stage |
|---|---|
| **At what effective pixel size is localisability measured?** (D12) The shipped `WARN_BELOW = 0.05` is calibrated on binned frames; on the twelve unbinned library entries eleven fall below it and two read negative | **09** — must be built with the scale explicit and the threshold's status stated, never silently worked around |
| Do knock-presence and the unordered component set survive a pre-registered re-run? They were applied to the T4 run after the fact | 08 implements; 15 re-runs |
| Can the arbiter rank arms of similar quality, or only arms differing by 100×? | 12 builds; 15 measures |
| Which engines can be driven repeatedly in one session without leaking threads? | 11, one engine at a time. The answer sets which arms are drive-once-per-session |
| What does the recommendation say between the two regimes the survey found? | 10 flags `outside_calibrated_range`; widening the calibration is v0.2.0 |
| Should the recommendation ever name Log-Ratio Registration? | **No**, until its update site is live. An engine that cannot be installed is not a recommendation |

**Honest unknown.** Whether third-party engines can be driven repeatedly in one Fiji session without
leaking AWT threads. `ThirdParty.java` sidesteps the question by calling `System.exit(0)`, which is
exactly what this plugin cannot do. Stage 11 should budget for at least one engine turning out to be
drive-once-per-session.

## Source documents

The canonical long-form record, not superseded by this folder:

- `../../../ImageJ Plugins/Registration and Drift Comparison/00_CASE.md` — why this plugin, the
  seven candidates, ecosystem evidence, kill criteria
- `../../../ImageJ Plugins/Registration and Drift Comparison/01_NAMING.md` — **the identity table is
  authoritative for every name in this folder**
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` — dependency table,
  defect ledger, I/O contract, dialog shape, algorithm sketch, performance contract
- `../../../ImageJ Plugins/Registration and Drift Comparison/03_BUILD_PLAN.md` — the source plan
  this folder splits
- `../../../ImageJ Plugins/Registration and Drift Comparison/t4/RESULT.md` — the sampler measurement,
  and where D12 and D13 came from

Sources to copy from, never edit:

```
Experiments\Log-Ratio Registration\src\main\java\logratio\   the primitives (stage 06)
Experiments\Log-Ratio Registration\src\test\java\logratio\   the estimators and harnesses (07, 08, 11, 12)
Experiments\Log-Ratio Registration\library\                  the twelve entries and the benchmark CSVs (10, 15)
Experiments\CPC\src\main\java\cpc\                           the chassis (01–04, 14)
Experiments\ImageJ Plugins\Cores\oc3d-core\                  shaded, build-time only
Experiments\ImageJ Plugins\Cores\autofix-core\               shaded, build-time only
Experiments\FLASH\src\main\java\flash\pipeline\runtime\      read for the panel's shape only (05)
```

## How to run a stage

```
/do-step docs/regdrift-build/
```

Executes the lowest-numbered file without a `_COMPLETED` suffix, then renames it.
