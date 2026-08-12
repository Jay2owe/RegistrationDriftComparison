# Stage 02 — Parameters, result and the macro surface

The immutable input bundle, the result bundle, and the complete macro option table with its parser —
every name a user or a macro will ever type, fixed once, before anything reads them.

## Why this stage exists

Every later stage takes a `RegDriftParameters` and fills part of a `RegDriftResult`. Fixing both
shapes now means twelve stages agree on what they are passed without negotiating it. Macro option
names are the part of a plugin that can never be changed after release without breaking somebody's
script, so they are decided here, in one file, with a round-trip test — not accumulated one option
at a time as each feature lands.

One rule is enforced structurally rather than by review: **no option installs anything**. A macro
that silently downloads software is not something to ship, and the parser is where that is
guaranteed.

## Prerequisites

- Stage 01 `_COMPLETED`.

## Read first

- `00_overview.md` — the house rules, especially 5, 6, 8 and 9
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` §§ *Inputs*, *Outputs*,
  *Macro options*, *Java API* — **the option table there is the specification, copy it exactly**
- `Experiments\CPC\src\main\java\cpc\CPCParameters.java` — builder shape
- `Experiments\CPC\src\main\java\cpc\CPCResult.java` — result shape
- `Experiments\CPC\src\main\java\cpc\CPCMacroOptions.java` and `CPCMacroOptionsParser.java` — the
  parsing and writing shape is reusable; **not one option name is**
- `Experiments\CPC\src\test\java\cpc\CPCMacroOptionsParserTest.java` — the test shape to mirror
- `Cores\oc3d-core\src\main\java\sc\fiji\oc3d\core\macro\MacroOptions.java` — tokenising, including
  `strictTokens`, which refuses unclosed brackets and line breaks inside values

## Scope

- `RegDriftParameters` — immutable, builder-constructed, one required input (`ImagePlus`), every
  other field defaulted per the contract's option table.
- The enums: `Mode` (`DIAGNOSE`, `DIAGNOSE_AND_RECOMMEND`, `APPLY`, `COMPARE`, `SCORE`), `Channel`
  (`AUTO` or a 1-based index), `Slice` (`PROJECT` or a 1-based index), `Verdict`
  (`REGISTRABLE`, `WARN_LOW_STRUCTURE`, `ESTIMATORS_DISAGREE`, `NOT_REGISTRABLE`).
- `RegDriftResult` — carries the four tables, the verdict with its reason, the recommendation list,
  an optional registered `ImagePlus`, the provenance record, and a typed failure reason.
- `RegDriftMacroOptions` — the option model plus the writer that produces a recordable option string.
- `RegDriftMacroOptionsParser` — string to options, using `oc3d-core`'s `strictTokens`.
- The provenance record: plugin version, mode, channel chosen and why, **the measurement scale**
  (`measured_at_bin`), the window positions, engine versions found, and the calibration set name.
- Validation with typed reasons: an unknown option, a channel index beyond the stack, `compare_with`
  set outside `score` mode, `apply_engine` set outside `apply` mode.
- Tests: full round-trip of every option; unknown option rejected with a useful message; **no option
  name anywhere in the table triggers an install**.

## Out of scope

- Reading any pixels. Nothing in this stage opens, projects or measures an image — validation checks
  dimensions only. The engine starts at stage 06.
- The dialog that produces these options — stage 04.
- Populating the tables — stage 03 defines their columns; the engine stages fill them.
- Batch parameters — stage 14.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/RegDriftParameters.java` | NEW | Immutable input bundle, builder |
| `src/main/java/regdrift/RegDriftResult.java` | NEW | Result bundle with verdict and provenance |
| `src/main/java/regdrift/Mode.java` | NEW | The five modes, plus `Channel`, `Slice`, `Verdict` as nested or sibling enums |
| `src/main/java/regdrift/RegDriftMacroOptions.java` | NEW | Option model and writer |
| `src/main/java/regdrift/RegDriftMacroOptionsParser.java` | NEW | Macro string to options |
| `src/test/java/regdrift/MacroOptionsParserTest.java` | NEW | **T15.** Round-trip, rejection, no-install |
| `src/test/java/regdrift/ParametersTest.java` | NEW | Defaults, immutability, typed validation reasons |

## Implementation sketch

The option table, verbatim from `02_CONTRACT.md` § *Macro options*. These strings are the contract:

| Option | Default | Notes |
|---|---|---|
| `mode` | `diagnose_recommend` | `diagnose`, `diagnose_recommend`, `apply`, `compare`, `score` |
| `channel` | `auto` | or a 1-based index |
| `slice` | `project` | or a 1-based index |
| `use_roi` | false | |
| `engines` | `installed` | `installed`, `all`, or a comma-separated list |
| `apply_engine` | *(empty)* | Overrides the recommendation in `apply` mode |
| `windows` | `auto` | `auto` is 3; `0` measures every consecutive pair |
| `window_frames` | `auto` | `auto` is 12, floored at the recording length |
| `arbiter` | `sd_vs_control` | |
| `flag_motion_loss` | true | |
| `advise_ceiling` | true | **Advice only. There is no option that enables a ceiling** — D4 |
| `compare_with` | *(empty)* | Second stack for `score` mode |
| `save_root` | *(empty)* | |
| `hide_display` | false | Headless |
| `serial` | false | Performance override; forces one worker everywhere |

Two defaults differ from the prose in `02_CONTRACT.md`, which predates the measurement: `windows`
`auto` is **3** and `window_frames` `auto` is **12**, from `t4\RESULT.md`. The contract's
"`auto` is 16" line is superseded. Stage 08 owns the sampler that reads them.

Builder shape, mirroring `CPCParameters`:

```java
RegDriftParameters p = RegDriftParameters.builder(imp)
        .mode(Mode.DIAGNOSE_AND_RECOMMEND)
        .channel(Channel.AUTO)
        .slice(Slice.PROJECT)
        .windows(Windows.auto())          // auto -> 3, or explicit, or ALL_PAIRS
        .windowFrames(WindowFrames.auto())// auto -> 12
        .serial(false)
        .build();
```

Every field is `final`; the builder is the only way to construct one; `build()` runs validation and
throws `IllegalArgumentException` with a message naming the option, its value and what was expected.

`RegDriftResult` shape — note it carries the verdict rather than merely a table of it, because
stage 13 routes on it and stage 15 asserts on it:

```java
public final class RegDriftResult {
    public ResultsTable diagnosis();       // one row per channel
    public ResultsTable recommendation();  // one row per candidate engine
    public ResultsTable comparison();      // one row per arm actually run
    public ResultsTable frames();          // one row per frame, scored arm
    public Verdict verdict();
    public String verdictReason();         // finished text, never a code
    public List<Recommendation> ranked();
    public ImagePlus registered();         // null unless apply mode produced one
    public Provenance provenance();
    public Failure failure();              // null on success; typed reason, never a bare null result
}
```

Parsing uses the strict tokeniser from the relocated core, which refuses unclosed brackets, stray
closing brackets and line breaks inside values — all of which the permissive tokeniser mis-parses
silently:

```java
Map<String, String> tokens = MacroOptions.strictTokens(optionString);
```

The no-install guarantee is asserted, not documented:

```java
@Test public void noOptionTriggersAnInstall() {
    for (String option : RegDriftMacroOptions.allOptionNames()) {
        assertFalse("option '" + option + "' must not name an install action",
                option.contains("install") || option.contains("download") || option.contains("fix"));
    }
    // and the parser's own bytecode references nothing in regdrift.autofix
}
```

Language check before this stage closes: grep the whole stage's output for `accuracy`, `best`,
`optimal` and `only` in any user-facing string, and for British spellings in option names and column
headers. Javadoc may use British spelling; option names and messages may not.

## Exit gate

1. `mvn test` green; both new tests pass.
2. Every option in the table above round-trips: `parse(write(options)).equals(options)`.
3. An unknown option produces a message naming the option and listing the valid ones — not a stack
   trace, not a silent ignore.
4. `RegDriftParameters` has no non-final field, and no setter.
5. Constructing parameters with a channel index beyond the stack's channel count throws with a
   message naming both numbers.
6. `grep -rn "install\|download" src/main/java/regdrift/RegDriftMacroOptions*.java` returns nothing.
7. `grep -rin "accuracy\|\bbest\b\|optimal" src/main/java/regdrift/` returns nothing outside a
   comment explaining why the word is banned.
8. Nothing in this stage imports `ij.gui.*` or `java.net.*`.

## Known risks

- **Option names are permanent.** Anything released here cannot be renamed without breaking user
  macros. If a name looks wrong, change it now; after stage 16 it is frozen.
- **`windows=0` means "every pair", not "no windows".** It is the explicit "measure everything"
  escape hatch the dialog exposes. Name the constant `ALL_PAIRS` so no later stage reads it as an
  empty sample.
- **`Verdict` is user-facing text as well as an enum.** Keep the finished wording in one place —
  stage 09 owns the words, this stage owns the type. Do not let both grow a copy.
- **Do not let `RegDriftResult` grow a `getRegisteredOrThrow()`.** House rule 14: typed reasons, and
  `registered()` returning null in diagnose mode is expected, not an error.
