# Machine learning

**Nothing in this area is implemented.** eikon bundles no model, ships no ML dependency and shows no
ML-derived feature. This file records the constraints decisions will be judged against.

## Non-negotiable rules

- On-device only. Face data, embeddings, OCR text and anything derived from images are never uploaded.
- No identity recognition against external services; faces are only clustered within the user's own
  library, and the user names the clusters.
- Every model and library is **license-checked before it is added**, and its license and provenance
  recorded here. Being fashionable is not a reason to pick a model.
- The embedding model sits behind an interface so it can be replaced without touching search.

## Evaluation criteria for a model

Measure, on real mid-range hardware, before choosing: download/APK size, RAM at inference, images per
second and battery per 1,000 images during indexing, thermal behaviour over a sustained run, and
semantic quality on eikon's own queries (Italian and English: "tramonto", "ricevuta IKEA",
"cane sulla neve", "mare estate 2025").

## Candidates to evaluate (unverified; nothing here is a claim about quality or license)

- Image/text embedding for semantic search: MobileCLIP-family and SigLIP-family compact variants, or a
  TensorFlow Lite / ONNX build of a compact CLIP-like model.
- OCR: ML Kit text recognition (bundled variant, so no download at runtime) or another local engine.
- Faces: on-device detector plus a face-embedding model; clustering by similarity.
- Pets: cats/dogs via the general image classifier rather than a dedicated model, if quality allows.

Each candidate needs an entry with license, size, measured numbers and a decision before any
integration work starts.
