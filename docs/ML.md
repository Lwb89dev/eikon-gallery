# Machine learning and recognition

Nothing in this document is a claim about accuracy on eikon's own data unless it says it was measured.
The models bundled so far are Tesseract's language data (OCR) and the CLIP image and text models (semantic search).

## Rules

- On-device only. Anything derived from images (text, faces, embeddings, places) is never uploaded.
- No identity recognition against external services; faces will only be clustered inside the user's own
  library, and the user names the clusters.
- Every model and library is license-checked before it is added and recorded in [NOTICE.md](../NOTICE.md).
- Engines sit behind interfaces (`OcrEngine`, `ImageEmbedder`, `TextEmbedder`) so they can be replaced.

## OCR (implemented)

**Decision: Tesseract, through Tesseract4Android, with the `tessdata_fast` English and Italian models.**

Why, from what was actually checked when choosing:

| | ML Kit text recognition (bundled) | Tesseract4Android 4.9.0 | PaddleOCR through ONNX Runtime |
| --- | --- | --- | --- |
| License | Google ML Kit terms (proprietary, free) | Apache-2.0 (Tesseract 5.5.1) | Apache-2.0 models, MIT runtime |
| Dependencies pulled in | Google Play services (base, basement, tasks), Firebase components and encoders, Google's data-transport library (`datatransport`, which ML Kit's usage logging is built on), and an init provider and component-discovery service in the manifest | none beyond the AAR; no manifest permissions, services or providers | ONNX Runtime AAR, hand-written pre/post-processing |
| Fit with "no telemetry, no Google services" | usage-logging code and Google services present (they would ship inside the app, and eikon, which has the `INTERNET` permission for the backup since 1.1.0, could no longer point to the operating system for proof that they are silent) | yes | yes |
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

## Semantic search and image embeddings (implemented)

Search by what a photo shows ("cane", "tramonto", "a dog in the snow"). Each photo is turned into a
512-number vector by an image model; a typed phrase is turned into a vector of the same space by a text
model; the photos whose vectors are closest to the phrase's are the matches. Both models run on the phone.

**Decision: CLIP ViT-B/32 image tower, int8, with the multilingual CLIP text tower, int8, on ONNX Runtime 1.28.0.**

| Part | What | License | Size |
| --- | --- | --- | --- |
| Image model | OpenAI CLIP ViT-B/32 vision tower and projection, ONNX export by Xenova (transformers.js), dynamic int8 quantization | MIT (OpenAI CLIP) | 88.6 MB |
| Text model | `sentence-transformers/clip-ViT-B-32-multilingual-v1`: a multilingual DistilBERT trained to land where CLIP puts the matching photo (50+ languages, Italian included), int8 for ARM64, plus its 768 to 512 projection | Apache-2.0 | 135.3 MB + 1.6 MB + 1 MB vocabulary |
| Runtime | ONNX Runtime 1.28.0 (Android build) | MIT | 28.6 MB (arm64) |

The files are not in the repository: `./gradlew :app:fetchModels` (run by every build) downloads them from
Hugging Face pinned to a commit and checks each SHA-256 ([model-manifest.tsv](../app/model-manifest.tsv)). They are
bundled uncompressed in the APK and memory-mapped in place, so they are not copied a second time on the phone.
The release APK is about 270 MB, most of it these models.

### What was measured, and how

All of this was measured on a desktop CPU with ONNX Runtime for Python, on **1,000 random photos of the COCO
val2017 set** (photographs of everyday scenes, five English captions each). It says how the models compare with
each other; it does **not** say how they will perform on the phone or on your own photos.

| Image model | Size | Caption to photo R@1 / R@5 / R@10 (multilingual text) | Speed, 1 thread |
| --- | --- | --- | --- |
| fp32 | 352 MB | 44.5 / 77.4 / 87.9 | 72 ms |
| **int8 (chosen)** | **88.6 MB** | **44.0 / 76.1 / 87.7** | **28 ms** |
| uint8 "quantized" export | 89 MB | 44.1 / 76.2 / 87.8 | 43 ms |
| 4-bit | 63.6 MB | 43.4 / 75.8 / 87.4 | 74 ms |

int8 is within a point of the full-precision model at a quarter of the size, and it is the fastest; 4-bit is
smaller but 2.6 times slower for no gain. For the text side, the multilingual model beat the English-only CLIP
text tower on English captions (R@1 44.5 against 38.3, both int8-quantized), and quantizing it changed nothing
(44.6). So one text model serves Italian and English queries.

Italian and English words against 20 object categories (dog, cat, pizza, train...; a photo counts as a
positive when the object covers at least 5% of it): precision of the first ten results 65% for a bare Italian
word, 74% for a bare English word. Wrapping the words as a caption, **"una foto di {words}"**, gives 73% for
Italian and 73% for English, so the app always does that (`SemanticQuery`). Wrapping in English instead helps
English and hurts Italian (61%).

**Which photos count as matches.** Scores are only meaningful relative to each other, so a photo must score at least
**0.23** and be within **0.07** of the best one. On those 1,000 photos this kept 93% of the photos that clearly
show the searched thing and let through 2% of those without it. It is a first guess to be tuned on real libraries;
the values are `SemanticCutoff`.

**Storage.** Vectors are stored as 512 signed bytes (127 times the value): 512 bytes per photo, 50 MB for
100,000 photos, and the loss is below 0.2% of cosine similarity. Search is a brute-force scan of all of them held in memory.

**How much the way a photo is shrunk matters.** The reference shrinks with a bicubic filter; the phone decodes
straight to the small size with the platform decoder. Emulating a decoder-style shrink (power-of-two reduction then
a bilinear step) cost 1.3 points of R@1 (44.0 to 42.7); the crudest possible resize (nearest neighbour) cost 1.8.

### What the tests prove

`ClipModelsTest` runs the real models through the same Kotlin code the app uses (ONNX Runtime for the desktop JVM):
the image and text vectors match the ones produced by the reference tooling for the same input (cosine above
0.999), the tokenizer reproduces the reference tokenizer's ids on 36 Italian, English and awkward inputs, and,
end to end, phrases in both languages find the right one of four public-domain photos (a cat, an astronaut, a coffee
cup, a rocket launch) while an unrelated phrase finds nothing. `SemanticOnDeviceTest` runs the same on the phone
and reports timings; **it has not been run yet**, so there are no numbers for the phone: speed, battery per 1,000
photos, RAM and heat are unmeasured.

### Why not the others

- **MobileCLIP (Apple):** the weights are under the "Apple Machine Learning Research Model" license, which
  limits use to non-commercial research and excludes "product development or use in any commercial product". It
  cannot be redistributed in an MIT-licensed app, however good it is.
- **SigLIP 2 base (Google, Apache-2.0):** likely better quality, not measured here. Its multilingual text tower has a
  256,000-word vocabulary: 378 MB for the image and text models against 224 MB, and 196 image patches per photo
  against 49, so about four times the work per photo. Left as the upgrade path if quality proves insufficient.
- **English-only CLIP text tower:** worse on English captions and cannot read Italian.

### Known limits

- Photos only; videos are not analyzed.
- The text model was cased and trained on Latin-script languages among others; a phrase is cut at 128 tokens.
- CLIP is weak at counting, at reading text (that is what OCR is for), and at telling apart things that look alike.
- The vocabulary could be cut to Latin-script tokens to save about 38 MB; not done, so the shipped files are exactly the upstream ones.
- The ONNX Runtime version is pinned at 1.28.0 on purpose: 1.29 and 1.30 add telemetry classes and the `INTERNET`
  permission to the Android package. eikon itself has `INTERNET` since 1.1.0 (for the backup, behind a consent switch), so a
  permission added by a library would be caught by the build's reviewed list (`verifyNetworkPermissionsRelease`), but the telemetry classes would still be unwanted code. The
  int8 models also give results that differ by about half a percent between ONNX Runtime versions (the tests' reference
  vectors come from the pinned version).
- Changing the model means new vectors: `EMBEDDING_MODEL_ID` names the model that made the stored ones and older ones are ignored.

## Faces and people (implemented)

eikon finds the faces in each photo, describes each face by 128 numbers, and groups faces that look alike into
"people". It **groups, it does not identify**: nothing is ever compared with anything outside your own library, there
is no name database, and a group only gets a name when you type one.

**Decision: YuNet to find faces, SFace to describe them, both from OpenCV Zoo, on ONNX Runtime.**

| Part | What | License | Size |
| --- | --- | --- | --- |
| Detector | YuNet (`face_detection_yunet_2026may`): finds face boxes and five landmarks (eyes, nose, mouth corners) | MIT | 0.23 MB |
| Face model | SFace (`face_recognition_sface_2021dec`, fp32): a MobileFaceNet trained with the SFace loss, 128 numbers per face | Apache-2.0 | 38.7 MB |

Both files are fetched at build time, pinned to an OpenCV Zoo commit and checked by SHA-256, like the CLIP models.

### What was measured, and how

All on a desktop CPU with ONNX Runtime for Python, so speeds are indicative only and **nothing here is measured on a
phone**.

- **It is the same computation as OpenCV's.** The detection decoding, the alignment and the vector were reproduced from
  OpenCV's `FaceDetectorYN` and `FaceRecognizerSF`: on 25 photos the box and landmarks differ by 0.000 pixels and
  the vectors have cosine 1.00000. The Kotlin port is tested against OpenCV's output on a public-domain photo
  (`FaceModelsTest`: box and landmarks within 1 pixel, aligned crop within 3 gray levels on average, vector cosine
  above 0.995 from our crop and above 0.9995 from OpenCV's crop).
- **Are two faces the same person?** On LFW (4,324 photos of 158 people with at least 10 photos each), 20,000
  same-person and 20,000 different-person pairs: mean cosine 0.70 for the same person against 0.10 for different
  people. At a threshold of 0.363 (the OpenCV Zoo default) 99.9% of same-person pairs pass and 0.24% of different
  pairs are wrongly joined (accuracy 99.82%); at 0.5, 98.2% and 0.000%.
- **Grouping.** With the same photos, an offline average-linkage clustering reached 99.9% F1 on pairs; the incremental
  method the app uses (each new face joins the person whose average face is closest, if the cosine is at least the
  threshold, otherwise starts a new person) reached F1 0.986 at 0.45 and 0.995 at 0.5 (precision 99.4%, recall 99.7%,
  182 groups for 158 people). The app uses **0.5**, erring towards splitting one person into two rather than merging
  two people, because a wrong merge is worse for the user than a person listed twice.
- **Small faces.** 1,500 LFW photos shrunk step by step: a face 59 pixels across still matches its full-size self well
  (mean cosine 0.95), 43 pixels 0.93, 35 pixels 0.92, 28 pixels 0.86, 23 pixels 0.78. Same-person pairs above the
  0.5 threshold: 98.7% at 59 px, 96.9% at 43, 96.2% at 35, 88.3% at 28, 78.6% at 23; wrongly joined different pairs
  stayed around 0.005% at every size. Small faces cost recall, not precision. Faces narrower than **36 pixels** (in the
  photo decoded to at most 1,280 pixels on its long side) are therefore not kept, nor is anything the detector is
  less than 80% sure of, and a photo keeps at most 12 faces.
- **Big faces.** YuNet only finds faces up to about 300 pixels across. On 200 photos enlarged step by step, a single pass
  found 100% of faces 288 pixels across, 97.5% at 384, 70.5% at 480 and 17% at 576. Searching also at half and quarter size
  and merging finds 100% up to 576, for about a third more time, so the app does that.
- **Speed (one thread, desktop).** YuNet on a 960 x 720 image 12 ms; SFace 15 ms per face. The int8 SFace (9.9 MB) is
  *slower* here (39 ms), and its vectors differ from the fp32 ones (mean cosine 0.97), so the app uses fp32.

**What this does not say.** LFW is clean, mostly frontal, well-lit press photos of adults, so real phone photos
(profiles, dim light, sunglasses, children growing up, twins) will do worse; expect some wrong groups and use Merge
and "Not this person". Nothing was measured on your library.

### Why not others

Larger detectors and recognisers give better accuracy on hard cases but were not evaluated: a phone gallery has to
analyze tens of thousands of photos on battery, and these two are small enough to do so. The decision is easy to
revisit because the detector and the face model each sit behind an interface (`FaceDetector`, `FaceEmbedder`).

### What is stored

Per face: the photo, a box (fractions of the picture), the detector's score, the 128 numbers as 512 bytes, and the
person it was grouped with. Nothing else about the face, and no crop. All in the private database, wiped when photo
access is revoked (see [PRIVACY.md](PRIVACY.md)).

## Pets (implemented)

"Dogs" and "Cats" collections, found from the same image vectors as semantic search: no extra model and no extra
analysis. Each photo's vector is compared with a description of a dog, a cat and 17 other things it might show (a
person, a landscape, food, a car, a building, a bird, a horse...), the similarities go through a softmax, and a photo
counts as a dog (or cat) when that is the most likely description and at least 60% likely (`PetPrompts`).

Measured on the same 1,000 COCO photos (24 with a clearly visible dog, 25 with a cat; a photo counts when the animal
covers at least 5% of it): dogs 71% found with 94% of the results correct; cats 92% found with 85% correct; of about 965
photos with no such animal, 0 were taken for a dog and 1 for a cat; **no cat was ever taken for a dog or the reverse**
(0 of 33). These are small samples: read them as "works", not as precise rates. Only dogs and cats: other pets
are not attempted, and there is no per-animal recognition (two dogs are not told apart).


## Duplicates and similar shots (implemented)

Three levels, as three different mechanisms, none of them deleting anything by itself.

- **Identical files.** A SHA-256 of the file's bytes. Reading every file in full would take hours, so only files that share their size
  with another file of the same kind (photo or video) are read: identical files always have the same size. A file is hashed again if it
  has been modified since (the fingerprint stores the file's modification time). No model is involved.
- **The same picture in different files** (recompressed, resized, sent through a messaging app). A 64-bit perceptual hash: the picture is reduced to
  32 x 32 shades of gray, its lowest 8 x 8 DCT frequencies are computed, and each is recorded as above or below the median. Two photos are
  copies when at most **6 of 64 bits** differ. No model is involved either.
- **Similar shots** (a burst, several takes of a selfie, a few shots of one subject). Two photos taken within **two minutes** of
  each other whose CLIP vectors (the ones stored for search) have a cosine similarity of at least **0.88**.

### What was measured, and how

Desktop, Python, the same 1,000 COCO photos as elsewhere in this document.

- **Perceptual hash choices.** Copies were made of every photo: JPEG at quality 40 and 70, half size, a messaging-app style re-encode (long side 1,600
  pixels, JPEG 60), brightness +15%, a 1 degree rotation and a 3% crop. Bits that differ between a photo and its copy: DCT hash stayed within 4 bits for
  100% of the recompressed, halved and messaging copies, 96.4% of the brighter ones and 82.4% of the rotated ones (95.1% within 6); a 3% crop is beyond it
  (62% within 6, 85% within 8). Among the **499,500 pairs of different photos** the closest were 14 bits apart (mean 31.5), so a limit of 6 produced no wrong
  pair. Two simpler hashes were compared: the average hash confused different photos (204 pairs within 4 bits) and the difference hash was
  slightly worse than the DCT one on every copy. The limit is 6 rather than the safe 10 so that near-identical burst frames land in "Similar shots", not in "Duplicates".
- **Similarity threshold.** Cosine similarity of a photo to a modified version of itself (shifted 3% and 8%, zoomed 8% and 20%, rotated 4 degrees, darkened 25%, cropped
  10%; 400 photos): mean 0.92 to 0.97, 5th percentile 0.86 to 0.95, lowest 0.70. Different photos: mean 0.48, 99th percentile 0.72, 99.9th 0.84, highest 0.93
  (COCO has many photos of the same kind of scene). The threshold 0.88 keeps about 95% of the modified copies; the two-minute window is what keeps different
  photos of the same kind of scene apart.
- **Speed.** The perceptual hash needs the photo decoded to 64 x 64 pixels only, which is cheap; the byte hash is limited by reading the file.

**What this does not say.** It was measured on synthetic copies of everyday photos, not on real bursts or on your library. Real bursts
with people moving may fall below 0.88, and a burst of a static scene above 6 bits may appear in Similar shots instead of Duplicates. A group of more than 300
photos with the same fingerprint (near-blank pictures, identical screenshots) is not compared, to keep the search fast. Which copy to keep is suggested (most pixels,
then the biggest file, then the oldest arrival) and always confirmed by the user; for similar shots there is no suggestion, because which of several near-identical
shots is best is a matter of taste.
