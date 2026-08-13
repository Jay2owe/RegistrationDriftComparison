# Stage 07 — The two estimators

Two independent, published displacement estimators — phase correlation and a pyramid
sum-of-squared-differences search — plus the three tests that keep them independent, chain-only and
honest on featureless frames.

## Why this stage exists

The whole plugin rests on one claim: *this recommendation is not biased toward the author's own
registration method.* The claim is only worth making if it is structurally true, which means the
measurement runs on estimators that share no machinery with the log-ratio criterion. Two of them,
because agreement between two independent estimators is the confidence signal in the `Diagnosis`
table — `agreement_px` — and a single estimator has no way to say "I am not sure".

This is also the stage most at risk of being quietly shortcut, so read the next section before
writing anything.

## The SSD estimator must be written, not lifted

`03_BUILD_PLAN.md` row E3 says the second estimator comes from "the `SSD no gain` arm of
`Benchmark.java`". That arm is **not** a separate estimator. It is
`logratio.core.PairAligner`/`Registration` configured with `RobustNorm.LEAST_SQUARES` and
`profileGain = false` (`Benchmark.java:160, 226-236`) — that is, it is the author's own criterion
with two switches flipped.

Lifting it would make the two estimators share the alignment machinery, the pyramid schedule, the
convergence rule and the pixel-support selection, and `EstimatorIndependenceTest` would be asserting
something that is not true.

**So: write the pyramid SSD estimator fresh.** Read `PairAligner` for its pyramid schedule and
convergence criterion as *reference for what a competent implementation does* — the method is
standard, published, and predates all of this — and then implement it independently against a plain
sum of squared differences on raw intensities. Budget roughly 300 lines rather than the 100 a copy
would take. That cost is the independence claim, and the claim is the product.

Phase correlation is the opposite case: `PhaseCorrelation.java` has **no imports at all**, carries
its own radix-2 FFT, and its class javadoc already states it shares no machinery with the log-ratio
estimator. It lifts cleanly and near-verbatim.

## Prerequisites

- Stage 06 `_COMPLETED`.

## Read first

- `00_overview.md` — house rule 2 above all
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` §§ *Defect ledger*
  (D5, D8), *Algorithm sketch*
- `Experiments\Log-Ratio Registration\src\test\java\logratio\PhaseCorrelation.java` (261 lines,
  self-contained) — `shift(a, b, w, h)` at line 56, `shift(..., normalise)` at 66, `SPECTRAL_FLOOR`,
  `TAPER_FRACTION`, `parabolic`, `fft2`. **Read the class javadoc; it explains why the spectral floor
  is relative, and two tests failed when it was absolute**
- `…\src\test\java\logratio\PhaseCorrelationTest.java` — carried across
- `…\src\main\java\logratio\core\PairAligner.java` — **reference only**, for the pyramid schedule and
  the `coarseSearch` fix behind D8. Do not import it, do not copy it
- `…\src\test\java\logratio\core\AlignerDefectsTest.java` — the featureless-pair case (D8)
- `…\src\test\java\logratio\Benchmark.java:150-240` — how the arms were configured, so the SSD
  estimator matches what was measured

## Scope

- `diag/PhaseCorrelation` — ported near-verbatim, package-private statics promoted to a small public
  surface. Keep the sign convention: `shift(a, b)` returns the displacement of the content from `a`
  to `b`.
- `diag/PyramidSsd` — **written fresh**. Coarse-to-fine, plain sum of squared differences on raw
  intensities, no gain model, no robust weighting, no pixel-support selection. This is deliberately
  the naive method, because that is what StackReg, TurboReg and Image Stabilizer minimise and what
  the benchmark measured as the `SSD no gain (StackReg family)` arm.
- `diag/Estimator` — the common interface, so the fingerprint runs both through one path.
- `diag/Estimators` (E3) — runs both over a set of pairs, shares plane extraction between them, and
  reuses cached pyramids through `PyramidCache`. Returns per-pair displacements from each, plus their
  per-transition difference.
- **The featureless-pair rule (D8)**: zero is the incumbent, and a candidate shift must beat it
  *strictly*. Without it a flat pair returns the corner of the search box — a confident 45 px answer
  on a blank 192² pair. A tool that fingerprints arbitrary user data meets featureless frames
  constantly.
- **One global search bound**, taken as a parameter, never derived per call. Stage 08 computes it;
  this stage must not invent a per-pair bound (`t4\RESULT.md`: a per-window bound gave a quiet window
  a bound near the floor, which clamped genuine motion and made the descriptors describe the search
  box).
- **T5** `EstimatorIndependenceTest`, **T6** `NoReconciliationInFingerprintTest`, **T7**
  `CoarseSearchFeaturelessTest`.

## Out of scope

- Choosing which pairs to measure — stage 08's sampler.
- Turning displacements into descriptors — stage 08.
- Any multi-lag reconciliation, ever (D5). Not as an option, not behind a flag.
- Sub-pixel refinement beyond the parabolic peak fit phase correlation already does.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/regdrift/diag/Estimator.java` | NEW | The common interface |
| `src/main/java/regdrift/diag/PhaseCorrelation.java` | NEW | Ported near-verbatim, self-contained FFT |
| `src/main/java/regdrift/diag/PyramidSsd.java` | NEW | **Written fresh.** ~300 lines |
| `src/main/java/regdrift/diag/Estimators.java` | NEW | **E3.** Runs both, shares planes and pyramids |
| `src/test/java/regdrift/diag/PhaseCorrelationTest.java` | NEW | Carried from the source repo |
| `src/test/java/regdrift/diag/PyramidSsdTest.java` | NEW | Synthetic shifts, sub-pixel, bound clamping |
| `src/test/java/regdrift/diag/EstimatorIndependenceTest.java` | NEW | **T5.** Bytecode assertion |
| `src/test/java/regdrift/diag/CoarseSearchFeaturelessTest.java` | NEW | **T7 / D8** |

## Implementation sketch

The interface both estimators satisfy — note the bound is an argument, not a field:

```java
public interface Estimator {
    /** Displacement of the content from a to b, in pixels. NaN,NaN when it could not be estimated. */
    double[] shift(float[] a, float[] b, int w, int h, double maxShift);
    String name();          // published name, for provenance
}
```

`Estimators` runs both over the sampler's pairs and produces the confidence signal:

```java
public static final class PairEstimate {
    public final int from, to;
    public final double[] byPhaseCorrelation;
    public final double[] byPyramidSsd;
    public final double differencePx;      // Euclidean distance between the two answers
}
// agreement_px in the Diagnosis table is the MEDIAN of differencePx across measured pairs.
```

The featureless rule, which is the entire content of D8:

```java
double best = ssd(a, b, 0, 0);          // zero is the incumbent, scored, not assumed
for (candidate : searchBox) {
    double s = ssd(a, b, dx, dy);
    if (s < best - EPS) { best = s; bestDx = dx; bestDy = dy; }   // STRICTLY better
}
```

The original defect was a sweep taking the *first* improvement over an unscored incumbent. On a flat
pair every candidate scored an identical zero, so the first one examined won, and the first one
examined was the corner of the search box.

T5, the independence assertion — the point of the whole stage:

```java
@Test public void diagnosisSharesNothingWithTheLogRatioCriterion() {
    for (String cls : classesIn("regdrift.diag")) {
        assertNoReferenceTo(cls, "logratio/");
        assertNoReferenceTo(cls, "PairAligner");
        assertNoReferenceTo(cls, "RobustNorm");
        assertNoReferenceTo(cls, "LogPlane");
        assertNoReferenceTo(cls, "Reconciler");
    }
}

@Test public void theTwoEstimatorsShareNoClass() {
    assertDisjoint(referencedClassesOf(PhaseCorrelation.class),
                   referencedClassesOf(PyramidSsd.class),
                   allowing("java.lang.Math", "java.util.Arrays"));   // keep this allow-list short
}
```

T6, the no-reconciliation assertion (D5) — a later contributor will read "RCC improves every
estimator by an order of magnitude" and try to turn it on. Multi-lag reconciliation averages error
out of a trace, which alters the very motion process the descriptors characterise: it would suppress
the difference between white jitter and a random walk *by construction*, and that distinction is what
the `wander` descriptor exists to make.

```java
@Test public void theFingerprintPathTakesNoReconciliationParameter() {
    for (Method m : Estimators.class.getMethods())
        for (Class<?> p : m.getParameterTypes())
            assertFalse(p.getSimpleName().toLowerCase().contains("reconcil"));
    for (String cls : classesIn("regdrift.diag")) assertNoReferenceTo(cls, "Reconciler");
}
```

## Exit gate

1. `mvn test` green; all four new tests pass.
2. `PhaseCorrelationTest` passes as carried, including the half-pixel case and the
   brightness-scaling case that caught the absolute spectral floor.
3. `PyramidSsdTest`: on a synthetic pair shifted by a known `(dx, dy)` including fractional values,
   the estimate is within 0.1 px; at a shift beyond `maxShift` the result is reported as clamped,
   with a status, not silently truncated.
4. **T7**: a flat, featureless 192² pair returns `(0, 0)` from **both** estimators — not the corner
   of the search box, not NaN.
5. **T5** passes, and is verified to fail when a deliberate `import logratio.core.PairAligner` is
   added to a `regdrift.diag` class and then removed.
6. On one library entry, the two estimators agree to better than 1 px median across the measured
   pairs — check against `t4/t4_windows_2026-08-12.csv`, where the well-behaved entries agree to
   0.60–0.71 px.
7. `Estimators` takes `maxShift` as an argument on every path; `grep -n "maxShift" src/main/java/regdrift/diag/`
   shows no field storing one.
8. Neither estimator allocates per pair inside the loop — pyramids come from `PyramidCache`.

## Known risks

- **The fresh SSD estimator is the biggest single piece of new algorithm work in the build.** It is
  also standard and well documented. If it comes out slower than phase correlation by more than about
  3×, check the pyramid schedule before optimising anything else.
- **Sign conventions.** `PhaseCorrelation.shift(a, b)` returns the displacement of the content from
  `a` to `b`. Get the SSD estimator's convention to match, and assert it in the test — a sign flip
  here produces a fingerprint that is exactly wrong and completely plausible.
- **Sharing `PyramidCache` between two estimators** is a work saving and a coupling. If the two want
  different pyramid schedules, give each its own cache rather than compromising the schedules; the
  independence claim is about the algorithms, and a shared cache of downsampled planes does not
  breach it, but a shared *schedule* forced on both would degrade one of them.
- **`agreement_px` is a median, not a mean.** One clamped pair should not swamp the confidence
  signal.
