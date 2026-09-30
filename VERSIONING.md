# Versioning

This project uses semantic versioning:

- patch releases fix behaviour without intentionally changing outputs;
- minor releases add backward-compatible measurements, modes, engines or options;
- major releases may change defaults, column meaning, the saved-tree layout, the
  macro-option grammar or the Java API.

During development, Maven builds use `-SNAPSHOT`. A release removes that suffix,
updates `CHANGELOG.md`, `CITATION.cff` and `RegDrift.VERSION`, and tags the
matching version as `vX.Y.Z`.

Changes to a measured output must be called out in the changelog, including the
verdict rules, the calibration table, column definitions and any change that can
move a number in the Diagnosis, Recommendation, Comparison or Frames tables.
`src/test/resources/golden/modes.tsv` holds a digest of every output of every
mode on five fixed recordings; a release that changes one says why in the
changelog entry that regenerated it.

The macro options (`mode`, `channel`, `engines` and the rest) are a published
grammar: a macro recorded with one release keeps running on the next minor
release.
