# Stage 11 — The engine harness

Drive other people's registration plugins end to end on the user's stack — without opening a window,
without exiting the JVM, without leaking threads into their session, and timed on the CPU clock.

## Why this stage exists

"Run them all on your own movie and see" is the thing nobody has time to do by hand, and it is the
only way to answer the question on data the calibration never saw. It is also the stage where a bug
does the most damage: this code runs inside a stranger's Fiji session, alongside their unsaved images.

The source it is lifted from calls `System.exit(0)`.

## The two defects, and why they are the stage

**D1 — `ThirdParty.java:114` calls `System.exit(0)`** after driving TurboReg, with the comment
"TurboReg leaves AWT threads behind". In a research harness that is housekeeping. **Inside Fiji it
destroys the user's session and every unsaved image in it.** Never exit. Run each arm in a dedicated
thread group with a bounded lifetime, quarantine the threads it leaves behind, and report a leak
count. If a tool cannot be driven without leaking, mark it drive-once-per-session and say so in the
UI.

**D2 — `ThirdParty.java:205-268` opens two ImageJ windows and swaps their pixel arrays** between
pairs. In a live session that races the event dispatch thread and shows the user flickering windows.

The fix is not simply "use hidden images". That source's own javadoc records why: *"TurboReg finds its
inputs through `WindowManager` by title, so they have to be real windows."* An engine driven by
`IJ.run` generally looks its inputs up the same way. The route that satisfies both constraints is
**ImageJ's batch mode** — images are registered with `WindowManager` and reachable by title, but no
window is ever shown:

```java
boolean wasBatch = Interpreter.batchMode;
Interpreter.batchMode = true;
try { /* drive the arm */ } finally { Interpreter.batchMode = wasBatch; }
```

Set it on the coordinator thread, restore it in a `finally`, and never leave it set across a return.

## Prerequisites

- Stage 05 `_COMPLETED` (the catalogue says which engines are present and at what version).

## Read first

- `00_overview.md` — D1, D2, D6 rows and the *Honest unknown*
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` — defects D1, D2, D6;
  § *Reflection into other plugins' internals*; § *Performance and parallelism contract*, the
  "Engine arm" row
- `Experiments\Log-Ratio Registration\src\test\java\logratio\ThirdParty.java` — **read lines 105–125
  and 205–290 carefully.** The sign-convention javadoc at 205–216 is the part worth keeping: TurboReg
  refines a landmark in the *source* to match the *target*, so the motion of the content from A to B
  is `sourcePoints[0] - targetPoints[0]`, verified against a fixture built at (3.25, −2.50) and
  recovered as (3.2500, −2.5004)
- `…\src\test\java\logratio\Timing.java` (164 lines) — the CPU-clock timing (D6)
- Stage 05's `EngineId` and `EngineRegistry` — **the join key**

## Scope

- `harness/EngineDescriptor` (E11) — one per candidate engine, keyed by the **same `EngineId`** stage
  05 defined: how to drive it, what settings map to what, which version was measured, and whether it
  is drive-once-per-session. **No second catalogue.** Detection lives in stage 05; driving lives here.
- `harness/EngineRunner` (E12) — runs one arm end to end on a whole stack and returns the registered
  stack plus a status. **Serial over arms, by construction** — third-party plugins are not
  thread-safe, several use ImageJ global state, and one leaks AWT threads.
- **Whole-stack driving through `IJ.run` in batch mode** is the v0.1.0 path, and it covers every
  engine including ones that expose no per-pair API. Pair-by-pair reflective driving is v0.2.0 and is
  out of scope here.
- Thread-group quarantine: each arm runs in its own `ThreadGroup`; on completion, count and report
  threads still alive; never kill them, never exit.
- `harness/CpuTimer` — from `Timing.java`. **Every timing surfaced in a table or a ranking comes from
  the CPU clock** (D6). Wall-clock appears in the progress bar and nowhere else.
- Version checking: if the found version differs from the measured one, the arm still runs but the
  `Comparison` row records `status = driven_untested_version: <found>`.
- Every reflective path has a fallback: class absent or signature changed means
  "could not drive this version", naming the version found, and **the run continues**.
- Cancellation between arms, and a per-arm timeout with an estimate shown before dispatch.
- **T9** `EngineRunnerIsolationTest`, **T14** `TimingIsCpuTest`.

## Out of scope

- Scoring the result — stage 12's arbiter. This stage returns a registered stack; it never judges it.
- Choosing which engines to run — stage 10 ranks, stage 13 dispatches.
- Installing anything — stage 05. If an engine is missing, the arm reports it and the run continues.
- Pair-by-pair estimates from any engine — v0.2.0.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/harness/EngineDescriptor.java` | NEW | **E11.** How to drive, keyed by `EngineId` |
| `src/main/java/regdrift/harness/EngineRunner.java` | NEW | **E12.** Batch mode, thread group, no exit |
| `src/main/java/regdrift/harness/CpuTimer.java` | NEW | CPU-clock timing (D6) |
| `src/main/java/regdrift/harness/ArmStatus.java` | NEW | Typed outcomes, never a bare null |
| `src/test/java/regdrift/harness/EngineRunnerIsolationTest.java` | NEW | **T9 / D1, D2** |
| `src/test/java/regdrift/harness/TimingIsCpuTest.java` | NEW | **T14 / D6** |

## Implementation sketch

```java
public static ArmResult run(EngineDescriptor engine, ImagePlus stack, Cancellation cancel);

public enum ArmStatus {
    OK,
    NOT_INSTALLED,              // reported, run continues
    WRONG_VERSION,              // driven anyway, flagged in the table
    COULD_NOT_DRIVE,            // class absent or signature changed
    TIMED_OUT,
    CANCELLED,
    LEAKED_THREADS              // completed, but left threads behind — engine marked drive-once
}
```

The arm body — batch mode set and restored, thread group counted, no exit on any path:

```java
ThreadGroup group = new ThreadGroup("regdrift-arm-" + engine.id());
boolean wasBatch = Interpreter.batchMode;
long cpu0 = CpuTimer.nanos();
try {
    Interpreter.batchMode = true;
    ImagePlus working = stack.duplicate();      // never touch the user's image
    working.setTitle(uniqueTitle());            // the engine finds it by title
    IJ.run(working, engine.command(), engine.options());
    return ArmResult.ok(working, CpuTimer.nanos() - cpu0, liveThreads(group));
} catch (Throwable t) {
    return ArmResult.couldNotDrive(engine, t, CpuTimer.nanos() - cpu0);
} finally {
    Interpreter.batchMode = wasBatch;
    closeQuietly(working);
}
```

`catch (Throwable)` is deliberate and is one of the few places it is correct: a third-party plugin
throwing `NoClassDefFoundError` or `ExceptionInInitializerError` must degrade to one failed arm, not
take down the comparison. Record the throwable's type and message in the status; do not log it and
swallow it (house rule 14).

T9's assertions, which are the reason this stage exists:

```java
@Test public void drivingAnEngineOpensNoWindow() { /* WindowManager image count unchanged */ }
@Test public void nothingOnThisPathCallsSystemExit() {
    for (String cls : classesIn("regdrift.harness")) assertNoReferenceTo(cls, "java/lang/System.exit");
}
@Test public void aLeakedThreadIsReportedNotIgnored() { /* fixture engine spawns a thread */ }
@Test public void aMissingEngineReportsAndTheRunContinues() { /* NOT_INSTALLED, next arm still runs */ }
@Test public void batchModeIsRestoredEvenWhenTheArmThrows() { }
```

T14 — a simulated stall must not change a reported figure. The regression it guards against is a
prior benchmark run reporting one arm at 237,000 ms per pair, which was a laptop standby counted as
computation:

```java
@Test public void aStallDoesNotChangeAReportedTiming() {
    long cpu = CpuTimer.measure(() -> { busyWork(); sleep(2000); });
    assertTrue("timing must not include the sleep", cpu < 1_500_000_000L);
}
```

## Exit gate

1. `mvn test` green; both new tests pass.
2. **`grep -rn "System.exit" src/main/java/` returns nothing.** Not in a comment, not behind a flag,
   not in a shutdown hook.
3. Driving every locally installed engine on a small stack, inside a running Fiji, opens **no**
   window: `WindowManager.getImageCount()` is unchanged before and after each arm.
4. After all seven installed engines have been driven once in a single session, the thread count is
   reported per arm, and any engine leaving threads behind is marked drive-once-per-session in the
   UI — **name which engines those turned out to be in the completion note.** That answers one of the
   build's open questions.
5. A missing engine (one of the three not installed) reports `NOT_INSTALLED` and the next arm runs.
6. An engine at a version other than the measured one runs and is flagged in the `Comparison` table.
7. Every `cpu_seconds` in the `Comparison` table comes from `CpuTimer`; `grep -rn "currentTimeMillis\|nanoTime"`
   in `regdrift.harness` shows use only in progress reporting.
8. Cancelling mid-comparison stops before the next arm, returns a typed `CANCELLED`, and leaves batch
   mode off.
9. The user's original `ImagePlus` is untouched — same pixels, same title, same window state.

## Known risks

- **This is the build's honest unknown.** Nobody knows whether these engines can be driven repeatedly
  in one session without leaking, because the source sidestepped the question by exiting. Budget for
  at least one engine turning out to be drive-once-per-session, and treat that as a finding to report
  rather than a bug to fix.
- **Batch mode is global state.** Two things setting it concurrently corrupt each other, which is
  part of why arms are serial by construction. Never set it from a worker thread.
- **Title collisions.** An engine that finds its input by title will happily find the user's image if
  the titles collide. Generate a unique title per arm and assert it is not already in `WindowManager`.
- **`IJ.run` swallows errors into the ImageJ log** on some paths. Check the returned image actually
  changed rather than trusting the absence of an exception — an arm that silently did nothing must
  report `COULD_NOT_DRIVE`, not `OK` with a zero improvement.
- **StackReg needs TurboReg present.** Stage 05 probes them together; make sure the runner does not
  attempt a StackReg arm when TurboReg is absent, or the failure message will name the wrong tool.
- **Do not relocate engine class names.** They are looked up as strings, and shading rewrites string
  constants that look like a relocated package. Keep them well away from `sc.fiji.*` shapes.
