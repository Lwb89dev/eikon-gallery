# Machine learning and recognition

Nothing in this document is a claim about accuracy on eikon's own data unless it says it was measured.
No model beyond OCR is bundled yet.

## Rules

- On-device only. Anything derived from images (text, faces, embeddings, places) is never uploaded.
- No identity recognition against external services; faces will only be clustered inside the user's own
  library, and the user names the clusters.
- Every model and library is license-checked before it is added and recorded in [NOTICE.md](../NOTICE.md).
- Engines sit behind interfaces (`OcrEngine`, later an embedding interface) so they can be replaced.

## OCR (implemented)

**Decision: Tesseract, through Tesseract4Android, with the `tessdata_fast` English and Italian models.**

Why, from what was actually checked when choosing:

| | ML Kit text recognition (bundled) | Tesseract4Android 4.9.0 | PaddleOCR through ONNX Runtime |
| --- | --- | --- | --- |
| License | Google ML Kit terms (proprietary, free) | Apache-2.0 (Tesseract 5.5.1) | Apache-2.0 models, MIT runtime |
| Dependencies pulled in | Google Play services (base, basement, tasks), Firebase components and encoders, Google's data-transport library (`datatransport`, which ML Kit's usage logging is built on), and an init provider and component-discovery service in the manifest | none beyond the AAR; no manifest permissions, services or providers | ONNX Runtime AAR, hand-written pre/post-processing |
| Fit with "no telemetry, no Google services" | usage-logging code and Google services present (nothing can be sent without INTERNET, but they ship inside the app) | yes | yes |
| Speed and accuracy on photos | expected faster and better | slower (CPU, seconds per photo), good on documents | expected good; not built |
| Distribution | Google Maven | JitPack only (pinned; that repository is restricted to this one group) | Maven Central |

ML Kit would likely read scenery text better, but it brings Google's services and telemetry code into an
app whose promise is that nothing leaves the phone. Tesseract is slower, which is acceptable for a
background job that waits for charging. PaddleOCR is the likely upgrade path for quality; it was not built
because it needs custom detection and decoding code that cannot be validated without a device.

Facts about what ships: Tesseract4Android AAR (SHA-256 of the downloaded file
`bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a`) with native libraries for `arm64-v8a`
and `x86_64` only; `eng.traineddata` (4.1 MB) and `ita.traineddata` (2.7 MB) from `tessdata_fast`, Apache-2.0.
The release APK is about 25 MB with OCR, models and place data.

Results are filtered (`OcrTextFilter`): below a confidence threshold, with fewer than two real words, or
mostly symbols, the text is discarded instead of stored.

Open items: measure seconds per photo and battery per 1,000 photos on a real phone; the JNI keep rules for
release builds are in `proguard-rules.pro` and have not been exercised on a device.

## Semantic search and embeddings (not started)

Candidates to evaluate, none chosen: MobileCLIP-family and SigLIP-family compact variants, or a TensorFlow
Lite / ONNX build of a compact CLIP-like model. Criteria: license, download and APK size, RAM, images per
second, battery per 1,000 images, sustained thermal behavior, and quality on Italian and English queries such
as "tramonto", "cane sulla neve", "macchina rossa". Each needs an entry here with numbers and a decision
before integration.

## Faces and pets (not started)

On-device detector plus a face-embedding model, clustered by similarity, named by the user. Pets through the
general image classifier if quality allows.
