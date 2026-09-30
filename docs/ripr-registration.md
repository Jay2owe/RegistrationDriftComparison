# Bundled RIPR registration

RIPR (Relative-Intensity Pattern Registration) is bundled as an isolated
Windows 64-bit runtime so its OpenCV 4.10 native libraries do not replace the
older OpenCV libraries used by Fiji.

## Reviewed build

The packaged runtime is the locally reviewed assembly from the Auto-Organotypic
motion runtime:

- `r25_a008_build`
- `r23_a003_build`
- `r21_b001_candidate_build`
- frozen baseline `r21_a000_frozen_target/out/baseline-engine.jar`

The bundled `engine.jar` SHA-256 digest is
`8d7723f6708159d552ceee07cf5590a068a5ae53ab9457f22cdc330db734a333`.
The complete native-runtime file hashes are recorded in
`regdrift.ripr.RiprDriver` and are checked again after extraction.

## Behaviour

The default recipe is `landmarks_phase`, which uses the `TISSUE_LANDMARKS`
route and `PHASE_CONTRAST` image type. The adapter:

- estimates one rigid pose (translation plus rotation) per time frame;
- applies the same pose to every channel and Z-slice in that frame;
- preserves byte, unsigned-short and float pixel stacks;
- rewrites the working ImageJ image in place; and
- runs the native engine in a child Java process with a private JavaCPP cache.

The other reviewed recipes are `bright_dim_dense`, `bright_dim_lowlight`,
`landmarks_brightfield` and `moving_cells`. They can be selected in the engine
options as `recipe=<name>`.

RIPR is available only from the packaged plugin jar on 64-bit Windows with
Java 11 or newer. It does not download or replace a runtime at run time.
