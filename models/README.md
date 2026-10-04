# models/

Model files used by the engine live here and are **not committed** (`*.onnx`, `*.tflite` and similar are gitignored).

Every file placed here must have a row in the model table of `docs/licences.md`, keyed by its exact file name.
`./gradlew check` fails if one is missing (Build Map #7).
