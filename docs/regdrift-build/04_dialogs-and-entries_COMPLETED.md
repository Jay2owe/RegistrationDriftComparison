# Stage 04 — Dialogs and plugin entries

Both `PlugIn` entry classes, both dialogs, macro-versus-interactive routing, progress reporting and
cancellation — the whole user-facing shell, clickable end to end, before any engine exists behind it.

## Why this stage exists

A dialog built after the engine tends to expose the engine's shape rather than the user's question.
Building it against the stage 03 skeleton forces the opposite: the dialog asks what the user knows,
and the engine stages fill in behind a fixed contract.

It also makes every later stage testable by hand. From here on, an executing agent can install the
jar, click the menu item, and see whether their stage did anything — instead of writing a scratch
harness each time.

## Prerequisites

- Stage 03 `_COMPLETED`.

## Read first

- `00_overview.md` — house rules 5, 6, 8, 9, 13
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` § *Dialog* — the
  three-section shape and the exact control list
- `../../../ImageJ Plugins/Registration and Drift Comparison/01_NAMING.md` — menu entries and macro
  calls, exactly as written
- `Experiments\CPC\src\main\java\cpc\CPC_.java` — macro-vs-interactive routing
- `Experiments\CPC\src\main\java\cpc\ui\CPCDialog.java` — the three-section aesthetic, kept
- `Cores\oc3d-core\src\main\java\sc\fiji\oc3d\core\ui\CollapsiblePane.java` — the Advanced disclosure
- `Cores\oc3d-core\src\main\java\sc\fiji\oc3d\core\ui\ToggleSwitch.java` — booleans
- `Cores\oc3d-core\src\main\java\sc\fiji\oc3d\core\progress\StatusBarProgress.java`

## Scope

- `CompareRegistration_` — the full entry: macro-vs-interactive routing, option recording,
  `hide_display`, progress, cancellation, and calling `RegDriftAutoSave` when a save root is set.
- `RegistrationDiagnostics_` — the second entry. Same routing, forces `Mode.DIAGNOSE` or
  `DIAGNOSE_AND_RECOMMEND`, hides the engine controls.
- `ui/CompareDialog` — four sections: **Input**, **Analysis**, **Engines**, **Output**.
- `ui/DiagnosticsDialog` — the same dialog minus **Engines**, and with the mode control fixed.
- The **Analysis** section is one visible control plus a collapsed **Advanced** pane. That single
  decision is what keeps this plugin from becoming the thing `00_CASE.md` says it must not be.
- Progress reporting on the coordinator thread, and cancellation that reaches the engine through a
  `Cancellation` token passed in the parameters.
- Macro recording: every interactive run records a runnable option string, verified by recording one
  and playing it back.
- The **Engines** section renders as a placeholder table until stage 05 replaces it.

## Out of scope

- The Engines panel's real content, probes and fix buttons — stage 05 owns `ui/EnginePanel`.
- The results and comparison view — stage 13 owns `ui/ResultsPanel`.
- Batch dialogs — stage 14.
- Any measurement: the dialogs call `RegDrift.run` and display whatever typed failure comes back.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/CompareRegistration_.java` | MODIFY | Replaces the stage 01 stub |
| `src/main/java/regdrift/RegistrationDiagnostics_.java` | MODIFY | Replaces the stage 01 stub |
| `src/main/java/regdrift/ui/CompareDialog.java` | NEW | Input / Analysis / Engines / Output |
| `src/main/java/regdrift/ui/DiagnosticsDialog.java` | NEW | The diagnose-only subset |
| `src/main/java/regdrift/ui/Progress.java` | NEW | Coordinator-thread progress and cancellation token |
| `src/test/java/regdrift/EntryRoutingTest.java` | NEW | Macro string in, parameters out, no dialog shown |

## Implementation sketch

The dialog, section by section, from `02_CONTRACT.md` § *Dialog*:

**Input** — image (active by default), estimation channel (`auto` by default, **with the ranking
shown once it exists**), estimation slice / project Z, restrict to ROI.

**Analysis** — one visible control:

```
Mode:  ( ) Diagnose only
       (•) Diagnose and recommend          <- default
       ( ) Recommend and apply
       ( ) Compare installed engines
       ( ) Score an existing result

  ▸ Advanced
      candidate engines            [tick boxes, filled by stage 05]
      arbiter                      [sd_vs_control]
      fingerprint windows          [auto (3)]   ... "measure every frame" is an explicit choice
      frames per window            [auto (12)]
      flag motion loss             [on]
      intensity-ceiling advice     [on]   <- advice only; there is no control that enables a ceiling
```

The Advanced pane is collapsed by default and the plugin must be fully usable without opening it.
`00_CASE.md`'s hard filter 5 is that a plugin needing twenty visible options recreates the parent's
adoption problem.

**Engines** — one row per candidate engine: name, installed / missing / wrong version, what it would
download and how large, and a fix button. Rows the user cannot fix in-app show the reason instead of
a button. **Nothing in this section runs during a diagnosis.** Stage 05 fills it; here it renders a
static placeholder row so the layout is settled.

**Output** — which tables and images, auto-save root, `hide_display`.

Routing, mirroring `CPC_`:

```java
@Override
public void run(String arg) {
    String macroOptions = Macro.getOptions();
    RegDriftParameters p;
    if (macroOptions != null) {
        p = RegDriftMacroOptionsParser.parse(macroOptions, WindowManager.getCurrentImage());
    } else {
        CompareDialog d = new CompareDialog();
        if (!d.show()) return;                 // user cancelled: no result, no message, no log line
        p = d.parameters();
        Macro.record(...);                     // record before running, so a failed run still records
    }
    RegDriftResult r = RegDrift.run(p);
    if (r.failure() != null) { IJ.error("Registration & Drift Comparison", r.failure().message()); return; }
    display(r, p);
}
```

`display` is the only method that touches `WindowManager`, `ResultsTable.show` or `Plot.show`, and it
is skipped entirely when `hide_display` is set. `RegDrift.run` never displays anything — stage 03's
bytecode test enforces that, and this is the class that would otherwise be tempted to blur the line.

Cancellation reaches the engine as a token, checked at pair boundaries and between arms:

```java
public interface Cancellation { boolean cancelled(); }
```

The dialog's Cancel sets it; the engine stages check it; a cancelled run returns a typed
`Failure("cancelled")` rather than throwing.

Wording checks before this stage closes — these strings are the ones a user reads:

- No `accuracy`, no `best`, no `optimal`, no `only`.
- Engine names spelled as their authors spell them: "StackReg", "TurboReg", "Correct 3D drift",
  "Fast4DReg", "Linear Stack Alignment with SIFT", "Image Stabilizer".
- US English throughout.
- The `&` character appears in the display name and nowhere else — not in the menu entries, not in
  the macro calls, not in any file name written to disk.

## Exit gate

1. `mvn test` green; `EntryRoutingTest` passes.
2. Install the jar into Fiji. **Plugins ▸ Registration ▸ Compare Registration Methods...** opens a
   four-section dialog; **Registration Diagnostics...** opens the three-section one.
3. Cancel closes both dialogs with no message, no log line and no table.
4. Run each dialog with the macro recorder open; the recorded line replays and produces the same
   parameters. Verify for at least three different option combinations, including one with
   `windows=0`.
5. `run("Registration Diagnostics...", "mode=diagnose hide_display")` from a macro shows no window
   and produces no error — it returns stage 03's typed not-implemented failure, displayed once.
6. Opening the Advanced pane and closing it again leaves the recorded options unchanged.
7. `grep -rin "accuracy\|\bbest\b\|optimal\|\bonly\b" src/main/java/regdrift/ui/` returns nothing in
   a user-facing string.
8. No dialog class is referenced from `regdrift.diag`, `regdrift.advise` or `regdrift.score`
   (stage 03's test already covers this — confirm it still passes).

## Known risks

- **Macro recording order.** Record before running, not after. A run that throws must still leave a
  recordable line, or a user debugging a failure loses the settings that caused it.
- **`GenericDialog` and a collapsible pane.** CPC's dialog already solves this; copy its approach
  rather than inventing one. If `CollapsiblePane` from the relocated core does not compose with
  `GenericDialog` cleanly, prefer CPC's existing pattern and note the divergence.
- **Two entry classes, one options table.** Both must record option strings the *other* can parse,
  because a user will copy a line from one and run it in the other. Keep one parser, one writer.
- **The Engines placeholder must not lie.** Until stage 05 lands, it says "engine detection arrives
  in a later build", not "no engines installed". A dialog that reports a false negative is worse than
  one that says nothing.
- **Cancellation during a modal dialog.** The token is only meaningful once an engine runs; make sure
  Cancel on the dialog itself returns cleanly without constructing parameters at all.
