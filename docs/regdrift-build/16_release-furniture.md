# Stage 16 — Release furniture

README, citation, changelog, the publishing audit, a local Fiji deploy, and the three drafts turned
into the real thing. Everything needed to hand the plugin to a stranger — and nothing that makes it
public.

## Why this stage exists

Publishing is not adoption, and an audit is the gate rather than the update site. This stage produces
everything a reader needs to decide whether they want the plugin, and stops short of pushing anything
outward. The public steps are separate, deliberate and someone else's decision.

## Prerequisites

- Stage 15 `_COMPLETED`, **and its kill criteria decided in writing**. If compare mode was cut, this
  stage documents the plugin that exists, not the one that was planned.

## Read first

- `00_overview.md`
- `../../../ImageJ Plugins/Registration and Drift Comparison/03_BUILD_PLAN.md` §§ *Publishing
  pathway*, *Adoption plan*
- `../../../ImageJ Plugins/Registration and Drift Comparison/drafts/README.md`,
  `drafts/wiki-page.md`, `drafts/sites-yml-entry.md` — all three marked DRAFT, all written before the
  build. **Diff them against what was actually built before promoting any of them**
- `../../../ImageJ Plugins/Registration and Drift Comparison/01_NAMING.md` § *Conventions this name
  commits the family to*
- `Experiments\CPC\README.md`, `CITATION.cff`, `CHANGELOG.md` — the family's shape
- `Experiments\Object-Segmentation-Sweep\PUBLISHING_AUDIT.md` — the sibling's audit, as a model
- `VALIDATION.md` from stage 15 — the README's honest numbers come from here

## Scope

- `README.md` — CPC-shaped: one-paragraph pitch, install, the two menu entries, the option table,
  the four output tables, **the engine list and where each comes from before the feature list, not in
  a footnote**, a Limitations section, and the citation.
- **The Limitations section, in full and near the top**: the calibration is three IncuCyte
  phase-contrast seed frames; the validation set is twelve recordings on one instrument; the
  fingerprint measures translation only; D12's status as resolved by stage 15; and the disclosure
  that the author has a competing registration plugin, with the independence measures named
  (no engine of its own, two published estimators, a bytecode test).
- `CITATION.cff`, `CHANGELOG.md` (0.1.0 entry), version set from `0.1.0-SNAPSHOT` to `0.1.0`.
- `PUBLISHING_AUDIT.md` — run the `plugin-publish-audit` skill and record its output.
- **One figure, not a feature list**: the same movie registered by two methods, with the residual map
  and the `sd_vs_control` number beside each, and the recommendation that picked the winner. It goes
  in the README and the wiki page, and it is worth more than the whole feature table.
- Promote `drafts/README.md` and `drafts/wiki-page.md` to real documents, corrected against the built
  plugin. Keep `drafts/sites-yml-entry.md` as a draft — it belongs to a later, separate decision.
- Local Fiji install and a hands-on pass through all five modes.
- Macro recording, headless and public Java API verified end to end (`add-imagej-macro-api`).

## Out of scope — and this list is the point of the stage

| Not now | Why |
|---|---|
| Public GitHub push | Its own step, after the audit passes |
| Zenodo DOI | After the push |
| Update-site upload to `RegistrationDriftComparison` | After the audit, and it is not readiness either |
| The central list PR | **Not until genuinely ready for Fiji's default manager.** An active update site is not readiness |
| Telling the engine authors | Part of the adoption plan, and it happens **before** release, not in this stage. See below |

## Files touched

| Path | Action | Reason |
|---|---|---|
| `README.md` | NEW | The real one, replacing anything from stage 01 |
| `CITATION.cff` | NEW | From CPC's shape |
| `CHANGELOG.md` | NEW | 0.1.0 |
| `PUBLISHING_AUDIT.md` | NEW | The audit output, dated |
| `pom.xml` | MODIFY | `0.1.0-SNAPSHOT` → `0.1.0` |
| `docs/figure/` | NEW | The one figure, and the script or macro that made it |

## Implementation sketch

The README's opening, which is the sentence test from `00_CASE.md` — a stranger reads one sentence
and knows whether they need it:

```
Registration & Drift Comparison measures the movement in a time-lapse stack, tells you whether it
can be registered, recommends a registration engine from measurements taken on real recordings, and
scores the result against a control that accounts for interpolation blur.

It installs as one jar with no extra update site. The registration engines it can run — StackReg,
TurboReg, Correct 3D drift, Linear Stack Alignment with SIFT, MultiStackReg, Register Virtual Stack
Slices, Descriptor-based registration, Image Stabilizer, Fast4DReg — are other people's plugins,
detected at runtime and installed only if you ask.
```

The engine list goes there, at the top, because the plugin's honesty about its dependencies is part
of the pitch rather than a caveat buried under it.

The adoption plan, from `03_BUILD_PLAN.md`, and the first item has a deadline this stage owns:

1. **Tell the engine authors before release, not after.** Fast4DReg (CellMigrationLab), Correct 3D
   Drift (Fiji), the mpicbg/SIFT maintainers. A plugin that benchmarks yours and sends you traffic is
   welcome if you hear about it first and unwelcome if you read about it. Ask two of them to check
   the row describing their tool.
2. The Fiji wiki `Registration` index page — https://imagej.net/imaging/registration. Submit the wiki
   page and request the index link in the same PR.
3. BIII.eu's "Image registration" listing — https://biii.eu/image-registration.
4. The `forum.image.sc` threads where the question is already being asked, by name: *Registration
   Recommendations*, *Stack alignment plugins*, *Registration to correct large drifts*.
5. Cross-link with Object Segmentation Sweep in both directions; one methods sentence naming both.
6. The figure.

Not on the list: mailing-list announcements and social posts, which have never moved a plugin in this
family.

Language pass over every public document: no `accuracy`, no `best`, no `optimal`, no `only`; engine
names spelled as their authors spell them; US English in user-facing strings; `&` in the display name
and nowhere else.

## Exit gate

1. `bash mvnw clean package -Denforcer.skip=true` green at version `0.1.0`.
2. The jar installs into a clean Fiji and all five modes work by hand, including on a Fiji with no
   candidate engine installed.
3. `PUBLISHING_AUDIT.md` exists and its blocking items are all resolved or explicitly deferred with a
   reason.
4. The README's Limitations section states the three-seed calibration, the twelve-recording
   validation set, translation-only measurement, D12's resolved status, and the competing-plugin
   disclosure.
5. The engine list appears **above** the feature list in both the README and the wiki page.
6. The figure exists, is reproducible from a committed script or macro, and appears in both.
7. Macro recording round-trips for all five modes; `hide_display` produces no window; the public Java
   API runs headless.
8. `grep -rin "accuracy\|\bbest\b\|optimal" README.md docs/ src/main/` returns nothing in
   user-facing text.
9. **Nothing has been pushed, uploaded or submitted.** The repo is ready; the outward steps are a
   separate decision.

## Known risks

- **The drafts predate the build.** `drafts/wiki-page.md` and `drafts/README.md` describe a plugin
  whose scope changed twice during planning. Diff, do not promote.
- **If stage 15 cut compare mode**, most of the README's second half is about a feature that no
  longer exists. Write the plugin that shipped.
- **The audit is the gate.** An active update site is not readiness, and neither is a green build. If
  the audit raises something, it is resolved here rather than noted.
- **Telling the authors is a social step with a lead time.** Start it as soon as the figure exists,
  not on the day of release, or the answer arrives after the thing it was meant to inform.
