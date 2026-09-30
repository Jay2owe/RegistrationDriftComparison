# Physical test handoff

Status:          PARTIAL
Target and mode: Registration & Drift Comparison; main
Last run:        `run_07e8d9124377`; last completed physical pass: `run_9f624316820d`
Verdict:         The packaged RIPR runtime works on the synthetic hyperstack in automated execution; a dedicated physical run reached RIPR and produced a registered hyperstack, but later gate attempts were stopped by harness menu-geometry focus failures.
Proven:          Disposable physical run `run_9f624316820d` proved both registered dialogs open and Cancel cleanly, with cleanup proven. Dedicated physical run `run_0773cf2d6a15` selected RIPR, pressed Run it, and captured a second 2-channel x 2-Z-slice x 4-frame registered image; Maven proved every non-reference plane changes on its 2-channel x 2-Z-slice x 3-frame hyperstack; the final package passed 591 tests with 0 failures and 9 skips.
Not proven:      A green dedicated RIPR scenario after correcting the harness depth-field assertion; physical full-video result presentation; desktop macro-recorder round-trips; missing-engine install buttons; full comparison with third-party engines.
Outputs checked: deployed jar identity, native runtime extraction and warp output, physical RIPR result state and screenshots, current harness cleanup, and final candidate SHA-256 `FE921E94751BC1B57B22559B0812C126B35109925E6E1E28ABA08986F9A82FB`.
Findings:        0 plugin failures. `run_0773cf2d6a15` reached and applied RIPR; its only functional assertion mismatch was the harness `depth` field meaning Z-slices, not total planes. Later attempts `run_07e8d9124377` and `run_451f6504c15a` stopped before physical dispatch on native menu geometry.
Claims:          bundled RIPR runtime and whole-hyperstack propagation are automated-test claims; registered dialog routes are physical claims from `run_9f624316820d`; RIPR selection/application is physically observed in `run_0773cf2d6a15`; video/split review remains untested.
Review file:     `docs/REVIEW-2026-09-21.md`
Gate / lessons:  Two physical gate routes added under `docs/test-scenarios/`; ImageJAI agent knowledge: not applicable.
Carried forward: prior physical dialog/cancel pass from `run_cf3f018e0bee`; superseded by `run_9f624316820d`
Desktop used:    one disposable Fiji launch, two attempted routes, 139 seconds; working Fiji unchanged
Next action:     Resume the dedicated RIPR route after the harness restores a valid foreground menu rectangle, then keep the video/split review claim separate.
Resume command:  `imagej-test-auto --json run --plugin "C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\RegistrationDriftComparison" --scenario "C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\RegistrationDriftComparison\docs\test-scenarios\ripr-apply" --jar "C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\RegistrationDriftComparison\target\RegistrationDriftComparison-0.1.0.jar" --skip-build`
Blocked by:      harness `native_menu_rect_inconsistent` / foreground menu geometry; no plugin failure was observed.
