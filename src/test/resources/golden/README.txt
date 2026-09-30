Golden outputs for GoldenOutputsTest
====================================

modes.tsv holds one SHA-256 digest per fixture x mode x output. It was written
once from the unmodified tree at commit dee2caa (2026-09-30, version 0.1.0), and
the test compares every later build against it.

Fixtures (all deterministic, built in the test from fixed seeds):
  drift_f32       96 x 96 x 12 float, drifting 0.73 / -0.41 px per frame
  hyper_c3z2t12   3 channels x 2 slices x 12 frames of the same drift
  drift_u8        drift_f32 mapped to 8 bits
  drift_u16       drift_f32 mapped to 16 bits
  knock_256x48    256 x 256 x 48, drifting 0.61 / -0.37 px per frame, with a
                  knock of (5.3, -3.1) px at frame 20

Modes: diagnose, diagnose_recommend, apply, compare, score. Score rates each
fixture against a partner made by removing all of its movement.

Outputs per run:
  failure         the failure kind and a digest of its message, or none
  verdict         the verdict word
  diagnosis, recommendation, comparison, frames
                  every number cell as the hex of Double.doubleToLongBits,
                  every word cell as written
  registered      dimensions and pixel values of the registered stack
  tree            the auto-save tree: CSVs and README.txt as text, TIFFs as
                  pixel values

Excluded or normalised:
  cpu_seconds     processor time (Comparison table and CSV)
  run_utc         the clock (summary.csv)
  "Written by version ... on ..." line of README.txt (the clock)
  the save folder path -> <root>, and serial=true|false -> serial=*
  (settings column of summary.csv and the Macro line of README.txt)

The engines are invented through the RegDrift.bench seam with a fixed set
"installed" (TurboReg, StackReg, MultiStackReg, Image Stabilizer), so the
goldens do not depend on the machine. Every run is made twice, serially and with
the maximum worker count, and the two must agree before the golden is consulted.

Regenerating: -Dregdrift.golden.write=true, only for a change that is meant to
move a number, in a commit of its own with a CHANGELOG line saying which
numbers moved and why.
