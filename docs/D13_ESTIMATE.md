# The pre-dispatch estimate: what it is built from

A comparison drives every installed registration engine over the whole
recording. On a 500-frame stack that is minutes to tens of minutes, and starting
it without saying so is how a plugin gets a reputation for freezing Fiji. So
`RegDrift.run` works out what it expects the run to cost, hands the sentence to
whoever is driving (`Dispatch`), and does nothing at all if the answer is no.

Everything below is **processor** time, because everything ranked or tabulated in
this plugin is (defect D6). On a machine running several workers the elapsed time
is smaller, sometimes by a lot; the estimate says so in words.

## The two halves

| Half | What it covers | Where the figure comes from |
|---|---|---|
| The engines | one arm each | one measured per-arm figure, from a real comparison — **not** the bundled calibration; see below |
| This plugin | measuring the recording's own movement once, then recovering and scoring each arm | the two constants below, measured |

### The bundled calibration is the wrong source for this, and that was measured

The obvious source for the engine half is `cpu_ms_per_pair` from
`thirdparty_2026-08-11.csv`, which is what the `expected_seconds` column of the
Recommendation table carries. It was tried first, and it is out by a factor of
sixty.

Those figures come from TurboReg driven **one frame pair at a time from Java**,
with no ImageJ around it. An arm drives an engine over the **whole recording
through ImageJ's command table**, which is a different piece of work: the
per-pair arithmetic predicts 0.33 s for a StackReg arm on `04_drift`, and the arm
cost about 22 processor seconds. On the first real run the whole estimate came
out at 53.7 s against 111.8 s measured — a ratio of 0.48, right on the edge of
useless.

So the engine half is one measured per-arm figure instead, and the
`expected_seconds` column keeps the calibration figure, which is what that column
has always meant. Two numbers about two different things, and neither is now
being asked to predict the other.

`ARM_SECONDS_PER_MEGAPIXEL_FRAME = 1.75`, from the run below: three engines over
48 frames of 512 x 512, all three near 22 processor seconds. **Three engines, one
recording, one machine** — a thin measurement, and the sentence a person reads
says so in those words.

## The two constants, and how they were measured

Three library entries of different sizes and lengths, opened from
`Log-Ratio Registration/library/<entry>/original.tif`, channel 1, native
resolution. Processor time is the whole process's, from
`OperatingSystemMXBean.getProcessCpuTime()`, so the pool's workers are counted;
each pass was run once to warm the JIT and then measured. Machine: 16 workers
available, 2026-08-14.

| Entry | frames | frame | padded | own motion | recover an arm | score an arm |
|---|---|---|---|---|---|---|
| `04_drift` | 48 | 512x512 | 512x512 | 9.078 s | 9.719 s | 2.531 s |
| `08_knock_severe` | 48 | 768x768 | 1024x1024 | 36.547 s | 37.016 s | 2.766 s |
| `12_long_baseline_9d` | 108 | 640x640 | 1024x1024 | 81.266 s | 82.719 s | 6.438 s |

### The estimator pass scales with the padded area, not the raw one

This is the part that would have made the estimate useless if it had been taken
at face value. Per **raw** megapixel-frame the three entries read 0.72, 1.29 and
1.84 — a factor of 2.5 apart, on the same code doing the same thing. A phase
correlation transforms a frame at the next power of two on each side, so a
768-pixel frame and a 640-pixel frame both cost what a 1024-pixel one costs, and
a 512-pixel frame costs a quarter of that. Per **padded** megapixel-frame:

```
04_drift             9.078 / (48 x 0.262)  = 0.722
08_knock_severe     36.547 / (48 x 1.049)  = 0.726
12_long_baseline_9d 81.266 / (108 x 1.049) = 0.717
```

Three entries of three different sizes and two different lengths, agreeing to
1.3%. `ESTIMATOR_SECONDS_PER_PADDED_MEGAPIXEL_FRAME = 0.72`.

Recovering one arm's transforms is the same pass over the same pixels and reads
0.772, 0.771 and 0.771 by the same arithmetic — within 8% of the movement pass,
which is inside the noise of an estimate that has to be within about a factor of
two. One constant covers both.

### The scoring pass

Per raw megapixel-frame: 0.201, 0.098, 0.146. The spread is the valid margin — a
recording that drifted a long way has much less of its frame left to walk over,
and `12_long_baseline_9d` and `08_knock_severe` both drift over a hundred pixels.
`SCORING_SECONDS_PER_MEGAPIXEL_FRAME = 0.14` is the middle of that range, inside
a factor of 1.5 of both ends.

It matters less than it looks: on all three entries the estimator passes are
three to thirteen times larger than the scoring walk, so the scoring constant
moves the total by a few percent.

### What the plugin's own half predicts, against what was measured

A three-arm comparison of `04_drift` — one movement pass, three recoveries, three
scoring walks:

```
predicted   0.72 x 0.262 x 48 x 4  +  0.14 x 0.262 x 48 x 3  =  41.6 s
measured    9.078 + 3 x 9.719 + 3 x 2.531                    =  45.9 s
```

0.91 of the measured figure.

## The whole thing, against a real 48-frame comparison

`Compare installed engines` on `library/04_drift`, channel 1 as a plain 48-frame
time series of 512 x 512, in one Fiji with StackReg 2.0.1, MultiStackReg 1.46.5,
TurboReg 2.0.1 and mpicbg 1.6.0 installed, on 2026-08-14. Ten engines
considered, four dispatched, three of them produced a registered recording.

```
THE ESTIMATE   4 arms, engines 88.1 s, this plugin 52.3 s, total 140.4 s
MEASURED       cpu 117.4 s, wall 55.1 s
RATIO          estimate / measured = 1.20
```

Well inside the factor of two the stage asks for. The overshoot is the fourth
arm: Correct 3D drift is in the catalogue as present at 1.0.7 but its menu entry
is not registered in this Fiji, so the arm came back in 47 ms with
`Unrecognized command` and the estimate had counted 22 s for it. An estimate that
counts an engine which then fails immediately is the right way round to be wrong.

What the same run produced, which is the rest of stage 13's exit gate seen at
once:

```
  1    StackReg                          -35.6%   2.3 s  ok; improved; rank=1; cpu_is_a_floor
  1    MultiStackReg                     -35.6%   2.5 s  ok; improved; rank=1;
                                                        cannot_separate_from_rank=1; cpu_is_a_floor
  3    Linear Stack Alignment with SIFT  -15.2%  13.4 s  ok; improved; rank=3
  -    TurboReg                               -       -  could_not_drive
  -    Correct 3D drift                       -   0.0 s  could_not_drive
  -    Descriptor-based registration          -       -  could_not_drive
  -    Register Virtual Stack Slices          -       -  could_not_drive
  -    Image Stabilizer                       -       -  not_installed
  -    Fast4DReg                              -       -  not_installed
  -    NanoJ-Core                             -       -  not_installed

  windows before = 0, after = 0.  batch mode after = false.
  registered stack: "original.tif - StackReg", 48 slices.  qc panel: 1006 x 48.
```

StackReg and MultiStackReg produced **identical** figures, which they should:
MultiStackReg is StackReg's engine with another front end on it, driven with the
same transformation over the same recording. Two real engines reading the same to
the last digit is the case the "cannot separate" rule exists for, and it arrived
without being arranged.

The `cpu_is_a_floor` flag on both is stage 11's: the arm's clock is the thread
the engine was driven from, and StackReg hands work to threads of its own, so
2.3 s is a floor against about 12 s of elapsed time. That is also why the
per-arm figure used by this estimate is process time rather than the arm's own
column.

### Two things this run found that are not stage 13's to fix

- **Multi-channel recordings.** `04_drift` is three channels interleaved. Driven
  as it arrives, StackReg aligns slice to slice across the whole 144-slice stack,
  mixes the channels, and walks the content 129 pixels off the frame — which the
  shared-region guard then refused to score, correctly and for the wrong-sounding
  reason. The run above extracts channel 1 first. Which channel an arm is driven
  on is a question for stage 15; what stage 13 does about it is refuse to put a
  number on an arm that put the content outside the region every arm is measured
  over, rather than quietly scoring the fill.
- **Correct 3D drift** reads as present in the catalogue at 1.0.7 and has no menu
  entry registered in this Fiji, so no arm can be driven for it. That is a
  catalogue question (stage 05) rather than a comparison one; the row says
  `could_not_drive` with the message ImageJ gave, which is the behaviour stage 11
  built for exactly this.

## A side result worth recording

The same runs scored a perfectly registered arm — every frame warped back by the
movement the plugin itself measured — against the shared control:

| Entry | `sd_vs_control` | own motion path | net |
|---|---|---|---|
| `04_drift` | -32.2% | 101.3 px | 4.1 px |
| `08_knock_severe` | -14.5% | 125.4 px | 116.6 px |
| `12_long_baseline_9d` | -13.8% | 716.3 px | 118.8 px |

All three improved, well clear of the 3.0% the arbiter calls noise. That is the
sign convention of `Arbiter.ownMotion` checked against real recordings rather
than against a fixture: had the chain come back with its sign inverted, the
control would have added the drift back rather than leaving it and these figures
would have been positive.

They are not the figures `docs/D11_MEASUREMENT.md` records for the same entries,
and they should not be. Those were scored against each arm's own control from
each entry's recorded `shifts.csv`; these are scored against one control built
from this plugin's own consecutive chain, which is what a comparison uses so that
its arms are like-for-like. Two different controls, two different questions, and
`provenance` says which in both cases.

## What is not in the estimate

- **Loading the recording.** It is already open; a comparison duplicates it per
  arm, which is memory rather than processor time.
- **How long an engine waits for something.** A plugin that stops and asks a
  question costs almost no processor time and an unbounded amount of clock time.
  The per-arm timeout is what bounds that, and the sentence says what it is.
- **The clock.** Said plainly in the text rather than left to be discovered:
  with several workers a comparison finishes sooner than this figure, and on a
  busy machine it takes longer.
