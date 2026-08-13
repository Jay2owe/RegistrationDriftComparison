# D11 — the arbiter measured against the twelve library entries

**Measured 2026-08-13, stage 12.** Every entry of
`Experiments\Log-Ratio Registration\library\` was re-scored by
`regdrift.score.Arbiter`, from that entry's own `original.tif` and `shifts.csv`, on
channel 1, at native resolution, with bilinear resampling and a control built from
the fractional part only of each frame's transform.

The purpose is threefold: to show the arbiter reproduces the figures the library
recorded; to derive the "cannot separate" threshold from data rather than from
taste; and to answer whether the arbiter can rank arms that differ two-fold rather
than a hundred-fold, which is one of the build's open questions and stage 15's
kill criterion 2.

## What the defect is

Resampling a picture at a fractional offset averages neighbouring pixels, and that
averaging smooths. Smoothing lowers temporal standard deviation for free. So a
registered recording scored against the **raw** recording gets credit for
smoothing that any method at all would have got — including one that did nothing
but blur.

The control closes it: the same recording shifted by the fractional part **only**
of each frame's transform. Always under half a pixel, so no systematic drift is
taken out, but every frame goes through the identical resampling. An identity warp
is not a control — a whole-pixel shift of zero takes the block-copy path and
resamples nothing — and the arbiter refuses one by name.

## Reproduction

`sd_vs_control`, in percent. Negative is stiller than the control.

| entry | library `SD vs ctrl` | re-measured | difference |
|---|---|---|---|
| 01_jitter_mild | −12.1 | −12.1 | 0.0 |
| 02_jitter | −37.5 | −37.5 | 0.0 |
| 03_jitter_drift | −29.6 | −29.6 | 0.0 |
| 04_drift | **−50.6** | **−50.7** | 0.1 |
| 05_drift_dominant | −5.3 | −5.3 | 0.0 |
| 06_knock | −40.3 | −40.3 | 0.0 |
| 07_knock_drift | −37.1 | −37.2 | 0.1 |
| 08_knock_severe | −4.2 | −3.8 | 0.4 |
| 09_knock_extreme | −14.4 | −14.4 | 0.0 |
| 10_unresolved_methods_disagree | **+0.7** | **+0.3** | 0.4 |
| 11_unresolved_moving_artefact | −2.1 | −2.1 | 0.0 |
| 12_long_baseline_9d | −0.2 | −0.3 | 0.1 |

Nine of the twelve are exact to the printed decimal; the largest difference is
0.4 percentage points. The two entries the stage named — `04_drift` at about −50.6%
and `10_unresolved_methods_disagree` at about +0.7% — come back at −50.7% and
+0.3%.

The residual differences are accounted for by two deliberate differences from the
research harness, neither of which is a tolerance being accepted:

- the per-pixel spread is computed from running sums (`E[x²] − E[x]²`) rather than
  from a second pass over the pixels, which is what allows the three passes to be
  fused into one and is identical in exact arithmetic;
- the frame median that equalises brightness is taken over the valid margin at a
  stride derived from the cropped area rather than the whole frame, so a slightly
  different subsample of pixels sets each frame's scale.

## The "cannot separate" threshold

The re-measured figures sort into a list with exactly one wide gap in it:

```
 -50.7  -40.3  -37.5  -37.2  -29.6  -14.4  -12.1  -5.3  -3.8     registered
 ------------------------------------------------------------    the gap
                                            -2.1  -0.3  +0.3     did not
```

**The threshold is 3.0 percent**, in that gap. It clears the largest null result
(−2.1) by a factor of 1.4 and the smallest kept result (−3.8) by a factor of 1.3,
and there is nothing at all between them to sit near it. Any cut between 2.1 and
3.8 gives the same twelve answers; 3.0 is the round number in the middle.

What it puts on the "cannot separate" side is the right set:

- `10_unresolved_methods_disagree` (+0.3) and `11_unresolved_moving_artefact`
  (−2.1) are the two entries the library itself records as unresolved;
- `12_long_baseline_9d` (−0.3) is the entry where the arbiter is known to run out
  entirely — frames two hours apart over nine days, where the sample genuinely
  moves between frames and no pixel-variance measure can tell that from
  misregistration. The honest output there is "cannot separate", not a ranking.

## Two-fold, not a hundred-fold

Arms that remove a **stated share** of the same real drift were built on three
entries and scored, each against its own control:

| share of the drift removed | 04_drift | 02_jitter | 06_knock |
|---|---|---|---|
| 1.00 | −50.7% | −37.5% | −40.3% |
| 0.90 | −44.4% | −33.3% | −34.0% |
| 0.75 | −28.9% | −21.5% | −26.6% |
| 0.50 | −10.9% | −9.3% | −12.9% |
| 0.25 | −1.5% *cannot separate* | −2.3% *cannot separate* | −1.9% *cannot separate* |
| 0.00 | refused: identity control | refused | refused |

An arm that removes twice as much drift as another reads about forty percentage
points apart from it — **thirteen times the threshold**. An arm that removes ten
percent more reads about six points apart, twice the threshold. The floor is at
about a quarter of the drift, where the arbiter correctly reports that it cannot
separate the arm from its control.

**So on the arbiter's own terms the answer is yes**: it ranks arms differing
two-fold with a wide margin, and it does not need a hundred-fold difference. The
caveat that belongs with that answer is that these arms differ only in *how much*
drift they removed. Two real engines differing two-fold also differ in *where* they
put the error and in what interpolator they used, and stage 15 measures that on
real arms.

The `0.00` row is worth reading on its own: an arm that removed nothing has a
control that resamples nothing, and the arbiter refuses to score it rather than
returning a number. That is the identity-warp refusal working on real data.

## The motion-preservation flag

Recovered path length as a multiple of the frame's short side:

| entry | path px | frame | path / short side | flag |
|---|---|---|---|---|
| 11_unresolved_moving_artefact | 4547.0 | 768 | **5.92** | **raised** |
| 08_knock_severe | 872.2 | 768 | 1.14 | — |
| 12_long_baseline_9d | 701.5 | 640 | 1.10 | — |
| 09_knock_extreme | 437.3 | 768 | 0.57 | — |
| 10_unresolved_methods_disagree | 308.6 | 768 | 0.40 | — |
| every other entry | 73–118 | 512 | 0.14–0.23 | — |

The trigger is 2.0. It separates the one entry a moving object was found in from
every other, with about a factor of three of clearance on each side.

**Single-structure dominance did nothing on this library, and that is reported
rather than hidden.** All twelve entries are IncuCyte phase contrast, whose texture
is fine-grained everywhere; every one measured below 0.003, the moving artefact
included, because it is a dark object in a textured field rather than a bright one
in an empty field. On this calibration the flag is carried by path length alone.
The dominance route is kept because the failure it describes is real on
fluorescence recordings, where one labelled structure genuinely can be most of the
frame — but it is untested against real data and the number is printed beside the
flag so a reader can see which of the two fired.

## How to reproduce this

The measurement harness was scratch and was deleted with the stage, as the build's
house rules require. To re-run it, score each entry with:

```java
Frames raw = Frames.of(IJ.openImage(entry + "/original.tif"), 1,
        Frames.PROJECT_Z, Frames.Bin.none());
Transform[] cumulative = /* cum_dx, cum_dy from that entry's shifts.csv */;
// the registered arm: every frame warped back by its own transform
Arbiter.Scoring scoring = Arbiter.score(raw, registered, cumulative,
        ControlWarp.Interpolation.BILINEAR, 0,
        PairScheduler.Progress.NONE, Cancellation.never());
```

`original.tif` holds three channels interleaved with no hyperstack metadata, so
`imp.setDimensions(stackSize / frames, 1, frames)` has to be called before
`Frames` reads it, with `frames` taken from the entry's `entry.properties`.

Stage 15 re-runs this as part of its gate.
