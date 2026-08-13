# Stage 05 — Autofix and the engine catalogue

Wire `autofix-core` in, write this plugin's own catalogue of candidate registration engines — how to
detect each one, what installing it would cost, and whether it may be installed at all — and build
the Engines panel that shows it.

## Why this stage exists

The plugin recommends other people's plugins. A recommendation the user then has to go and hunt down
an update site for costs most of its value: the in-JVM StarDist plugin has roughly 89,000 users and
the conda-dependent TrackMate-Cellpose has roughly 15,000, and the difference is install friction.
This stage is the difference.

It is also where an honest boundary gets drawn. Some of these engines are freely redistributable and
some are not, and a plugin that silently downloads a jar whose licence forbids it has a worse problem
than a missing feature.

## Prerequisites

- Stage 01 `_COMPLETED` (the relocation must already work).
- Stage 04 `_COMPLETED` (the Engines section placeholder exists to be replaced).

## Read first

- `00_overview.md` — house rule 9, and the *Changed since `03_BUILD_PLAN.md`* table
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` § *The autofix layer*
  and § *Reflection into other plugins' internals*
- `Cores\autofix-core\README.md` — **in full**, especially § *Using it*, § *The boundary*,
  § *Consuming it* and § *The third consumer, coming*, which is about this plugin
- `Cores\autofix-core\src\main\java\sc\fiji\autofix\core\` — `DependencySpec`, `Probes`,
  `DependencyServiceCore`, `JarDependencyFixer`, `Artifacts`, `Product`, `FijiLayout`
- `Experiments\FLASH\src\main\java\flash\pipeline\runtime\` — **read for the panel's shape only.**
  Do not copy `DependencyRegistry`; it is 1,866 lines of StarDist/TensorFlow/Cellpose specs and has
  nothing to do with this plugin

## The plan changed here — read this before starting

`03_BUILD_PLAN.md` rows A1–A4 say to vendor roughly 600 lines in `autofix-core`'s shape, because at
the time the core did not exist. **It does now** — built 2026-08-12, 110 tests green, FLASH and PULSE
both migrated. Stage 01 already shades it into `regdrift.internal.autofix`.

So A1–A4 collapse to configuration, and this stage writes only what was always going to be the
plugin's own: the catalogue (A5) and the panel (U4). If the dependency turns out to be awkward for a
reason nobody has anticipated, the vendoring plan in `03_BUILD_PLAN.md` remains the fallback — but
take it only with a written reason, because three copies of this layer was the problem the core was
extracted to solve.

## Scope

- `EngineId` — an enum of candidate engines implementing `autofix-core`'s `DependencyKey`.
  `Enum.name()` already satisfies the interface, so nothing is implemented by hand and `EnumMap`,
  `EnumSet` and `switch` keep working.
- `EngineRegistry` — one `DependencySpec` per engine: display name (**the author's own spelling**),
  description, affected features, probe, artifacts, approximate download size, restart flag, fix
  button label, and either a fixer or a `nonFixableReason`.
- A `Product` named once, before anything is written to disk: `RegistrationDriftComparison`. The
  restart log, the repair log, the deferred-disable script and the writability probe are all named
  after it, and those names are the only trace a user has when a restart silently does not happen.
- `AutofixService` — a thin wrapper over `DependencyServiceCore` holding the catalogue and the
  fixers, exposing probe → plan → fix and the finished `DialogRow`s.
- `ui/EnginePanel` — renders those rows. Name, status, download size, one button. Rows that cannot be
  fixed in-app show the reason instead of a button.
- **The licence check.** Every engine's spec records where the artifact comes from and under what
  terms. Any engine whose licence does not clearly permit automated download by a third-party tool
  gets `fixableInApp(false)` and a `nonFixableReason` naming the page the user should visit. TurboReg
  is the one to check first — its licence is restrictive and it is the engine the harness drives most
  directly.
- Probes that work on a bare Fiji: class presence, command presence, and jar-file presence with a
  version prefix, so "installed", "missing" and "wrong version: <found>" are all distinguishable.
- **T17** `AutofixSpecTest`: every spec has a probe, a stated download size, and either a fixer or a
  `nonFixableReason`; a SHA-1 mismatch aborts and leaves nothing behind; a failed download is
  idempotent on retry.

## Out of scope

- **Driving** any engine — stage 11 owns `EngineDescriptor` and `EngineRunner`. This stage says
  whether an engine is *present*; that stage says how to *use* it. `EngineId` is the join key
  between them, so that no second catalogue appears.
- The recommendation ranking — stage 10.
- Anything installed automatically. Nothing here runs during a diagnosis, and no macro option reaches
  this code (stage 02's test asserts it).

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/autofix/EngineId.java` | NEW | The enum, implementing `DependencyKey` |
| `src/main/java/regdrift/autofix/EngineRegistry.java` | NEW | **This plugin's own catalogue.** Never copied from FLASH |
| `src/main/java/regdrift/autofix/AutofixService.java` | NEW | Catalogue + fixers over `DependencyServiceCore` |
| `src/main/java/regdrift/ui/EnginePanel.java` | NEW | The Engines section, real |
| `src/main/java/regdrift/ui/CompareDialog.java` | MODIFY | Replaces the placeholder section |
| `src/test/java/regdrift/autofix/AutofixSpecTest.java` | NEW | **T17** |
| `src/test/java/regdrift/autofix/ProbeTest.java` | NEW | Present / missing / wrong-version, against fixtures |

## Implementation sketch

The candidate engines, with what is known today. **Versions confirmed present on this machine**
(`02_CONTRACT.md` § *Reflection into other plugins' internals*):

| Engine | Jar found locally | Source | Notes |
|---|---|---|---|
| TurboReg | `TurboReg_-2.0.1.jar` | BIG, EPFL | **Check the licence before writing a fixer.** Likely `nonFixableReason` |
| StackReg | `StackReg_-2.0.1.jar` | BIG, EPFL | Same question; depends on TurboReg at runtime |
| MultiStackReg | `MultiStackRegistration_-1.46.5.jar` | update site | |
| Correct 3D drift | `Correct_3D_Drift-1.0.7.jar` | Fiji core | Ships with Fiji — probe only, no fixer needed |
| Descriptor-based registration | `Descriptor_based_registration-2.1.8.jar` | Fiji core | |
| Register Virtual Stack Slices | `register_virtual_stack_slices-3.0.8.jar` | Fiji core | |
| Linear Stack Alignment with SIFT | `mpicbg_-1.6.0.jar` | Fiji core | |
| Image Stabilizer | **not installed** | author's page | Last updated June 2009 |
| Fast4DReg | **not installed** | update site | CellMigrationLab |
| NanoJ-Core | **not installed** | update site | |

For update-site-hosted engines the artifact URL has the form
`https://sites.imagej.net/<Site>/plugins/<name>.jar-<timestamp>`. Resolve and record the exact URL
and SHA-1 per engine at build time; do not compute either at runtime, and do not reach for
`net.imagej.updater` — `EmbeddabilityTest` in the core fails the build if any class references it,
and dragging the SciJava stack into this jar is the exact cost the one-jar rule exists to avoid.

Declaring one engine, in the core's builder shape:

```java
DependencySpec.builder(EngineId.FAST_4D_REG, "Fast4DReg")
        .description("Drift correction for 2D and 3D time-lapse data, from CellMigrationLab.")
        .affectedFeatures("Compare installed engines", "Recommend and apply")
        .probe(Probes.composite(
                Probes.classProbe("<confirm the entry class from the installed jar>"),
                Probes.artifactProbe(FAST4DREG_JARS, Collections.<String>emptyList())))
        .artifacts(FAST4DREG_JARS)
        .approxDownloadSizeBytes(<measured>)
        .restartRequired(true)
        .fixableInApp(true)
        .fixButtonLabelTemplate("Install Fast4DReg%s")     // -> "Install Fast4DReg (~2.1 MB)"
        .attribute("source", "https://sites.imagej.net/...")
        .attribute("licence", "<name the licence>")
        .build();
```

And one that must not be auto-installed:

```java
DependencySpec.builder(EngineId.TURBOREG, "TurboReg")
        .description("Pyramidal intensity-based registration from the Biomedical Imaging Group, EPFL.")
        .affectedFeatures("Compare installed engines", "Recommend and apply")
        .probe(Probes.classProbe("TurboReg_"))
        .fixableInApp(false)
        .nonFixableReason("TurboReg is distributed by its authors under terms this plugin does not "
                + "redistribute under. Download it from <page>, place the jar in Fiji's plugins "
                + "folder, and restart.")
        .build();
```

The panel renders finished text from the core and adds no words of its own:

```java
for (DependencyServiceCore.DialogRow row : service.getDialogRows()) {
    panel.add(row.getSpec().getDisplayName(), row.getStatusLabel(), row.getActions());
}
```

`DialogRow` is deliberately widget-free — every label, caption and explanatory line is finished text
computed in the core, which is what lets FLASH, PULSE and this plugin say the same words about the
same problem while looking nothing alike.

Two rules the core enforces and this stage must respect:

- **Disable, never delete.** A wrong-version jar is renamed `name.jar.disabled-YYYYMMDD`. On Windows
  the jar is usually locked by the running JVM, so the rename defers to a helper that waits for Fiji
  to exit — which is why the `Product` name matters, because that helper is named after it.
- **Never relocate a third-party class name.** The probes look up `TurboReg_` and friends by string.
  Shading rewrites bytecode, not strings — but it *does* rewrite string constants that look like a
  relocated package, so keep engine class names well away from anything resembling
  `sc.fiji.autofix` or `sc.fiji.oc3d.core`.

## Exit gate

1. `mvn test` green; both new tests pass.
2. `AutofixSpecTest` passes for every entry in the catalogue — no spec without a probe, no spec with
   neither a fixer nor a `nonFixableReason`, no fixable spec without a stated download size.
3. On the local Fiji, the Engines panel reports the seven installed engines as present with their
   found versions, and the three missing ones as missing — matching the table above.
4. A deliberately corrupted SHA-1 in a test spec aborts the fix, leaves no partial file, and reports
   a message naming the expected and found digests.
5. Re-running a failed download leaves the same state as the first attempt — no half-written jar, no
   duplicate `.disabled-` rename.
6. **Every engine whose licence was not verified is `fixableInApp(false)`.** Unverified is not a
   reason to try; it is a reason to send the user to a page.
7. Opening the dialog and doing nothing performs no network access — confirm by running with the
   network disabled and checking no exception, no delay and no log line.
8. `grep -rn "net.imagej.updater" src/` returns nothing.

## Known risks

- **Update-site URLs carry a timestamp and change when the author re-uploads.** A pinned URL and
  SHA-1 will eventually 404. The failure must be a clear "this download is no longer available;
  install it from <page>" rather than a stack trace, and the version pinned must be recorded so
  stage 11 can say which version was measured.
- **TurboReg's licence is the one to check first**, because it is the engine the harness drives most
  directly and the one the benchmark measured most. If it cannot be auto-installed, that is a fact to
  state in the README, not a problem to route around.
- **StackReg depends on TurboReg at runtime.** Probing StackReg as present while TurboReg is missing
  gives an arm that fails at run time with a confusing message. Probe the pair together.
- **Fiji-bundled engines need no fixer at all** — Correct 3D drift, Descriptor-based registration,
  Register Virtual Stack Slices and SIFT ship with Fiji. Giving them an install button that does
  nothing is worse than giving them none.
- **This is the stage most likely to need network access to complete.** Sizes and SHA-1s cannot be
  invented. If the environment has no network, record the specs with the size and digest fields
  marked `TODO: measure`, mark those engines `fixableInApp(false)` in the meantime, and say so in
  the exit-gate report rather than shipping a guess.
