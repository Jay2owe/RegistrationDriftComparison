Registration and Drift Comparison - the bundled calibration
===========================================================

The three CSV files beside this one are the measured evidence behind every
figure in the recommendation table. They are FROZEN. A run of this plugin on
one computer and a run on another have to be comparable, and they cannot be if
the evidence file changed underneath them.

FROZEN MEANS FROZEN
  Nothing in these files is edited, ever. A later benchmark run that produces
  better numbers is a NEW file, with its own date in its name, bundled beside
  these and named in the changelog. The old file stays exactly as it is, so a
  recommendation saved last year can still be traced to the numbers that
  produced it.

WHERE THEY CAME FROM
  Copied on 2026-08-13 from
      Experiments/Log-Ratio Registration/library/benchmark/
  which is the research repository the benchmark was run in. That folder is
  read-only to this plugin and holds newer files dated 2026-08-12 and
  2026-08-13 that are somebody else's work in progress. Those are deliberately
  not bundled: only the 2026-08-11 run is.

  file                          bytes   SHA-1
  benchmark_2026-08-11.csv      27756   f63fd4a190309423491774d4506e4150bd1a429b
  thirdparty_2026-08-11.csv      2600   fd7dd99e3697901a916bdc656b739d64f626d013
  saturation_2026-08-11.csv     19517   faa4ff03f40e48a35c64cfe3bee3d7f34ad09ef6

WHAT WAS MEASURED
  Three IncuCyte phase-contrast seed frames, 48 frames each, spanning
  localisability 0.017 to 0.160 measured at a 4 x 4 pixel mean:

      VID47_D3_1_09d20h00m   0.0170   low structure, the hard case
      VID52_C3_1_02d00h00m   0.1260   well textured
      VID52_B6_1_02d00h00m   0.1602   well textured

  A stack is built from ONE real frame, so there is no native drift in it. The
  crop window is moved by whole pixels at full resolution and box-averaged 4x4
  down, so the truth is exact and favours no method. Four conditions are then
  introduced per frame, where they cannot cancel:

      CLEAN           independent per-frame noise at 10% of the frame's SD
      GAIN_FADE       a 4x intensity ramp across the recording (2.0 in log2)
      CHANGE_MOVED    10% of the field replaced with real content from a
                      nearby offset
      CHANGE_BLOCKS   the same patches, same positions, filled with a
                      constant bright value

WHAT THE FILES HOLD
  benchmark_2026-08-11.csv   216 arms: nine estimators x two reconciliations x
                             four conditions x three seeds.
  thirdparty_2026-08-11.csv  24 arms: a real third-party plugin driven pair by
                             pair on the same pixels, through the same pair
                             plan. This is the file the engine recommendation
                             is drawn from.
  saturation_2026-08-11.csv  180 arms: the per-frame intensity ceiling swept
                             from the 99.5th percentile down to the 85th. The
                             evidence behind the ceiling ADVICE, and behind the
                             contraindication that always travels with it.

  Every timing in all three is CPU time. A prior run of this benchmark reported
  one arm at 237,000 ms per pair, which was a laptop standby being counted as
  computation.

WHAT THIS PLUGIN READS THEM FOR
  See regdrift.advise.CalibrationTable, which states row by row which measured
  arm describes which installable engine and which rows describe none.
