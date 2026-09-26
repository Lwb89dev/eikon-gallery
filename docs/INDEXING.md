# Indexing

Two things keep eikon's library data current, and they are deliberately separate.

## 1. Library sync (foreground, cheap)

Mirrors MediaStore metadata (name, date, size, folder...) into the Room `media` table, incrementally, while
the app is visible. Details are in [ARCHITECTURE.md](ARCHITECTURE.md#sync). It also keeps the file-name part
of the search index in step, and removes everything learned about a photo when the photo goes away.

## 2. Analysis (background, heavier)

Learns things that are not in MediaStore by opening each photo. **Every step is off by default** and must be
turned on in Settings, because they record where photos were taken, the text in them, what they show and who is in
them. Implemented steps, in the order they run:

| Stage | What it produces | Cost | Needs |
| --- | --- | --- | --- |
| `GEO` | where the photo was taken, resolved **offline** to the nearest city of the bundled GeoNames data (about 32,000 places) | fast (EXIF read) | permission to read photo locations (`ACCESS_MEDIA_LOCATION`) |
| `PHASH` | a 64-bit fingerprint of what the photo looks like, to find copies of it | fast (decode to 64 x 64 pixels) | nothing |
| `FILEHASH` | a SHA-256 of the file's bytes, **only for files that share their size with another file of the same kind** | reads the whole file, but only for candidates | nothing |
| `EMBED` | what the photo shows, as a 512-byte vector (CLIP image model), so a phrase can be matched to it; also the source of the dogs and cats collections | moderate (decode to 224 px, one model run; measured on a desktop only, see [ML.md](ML.md)) | nothing |
| `FACES` | the faces in the photo (box, 128-number description) and the person each was grouped with | moderate (decode to 1,280 px, three detector passes, one model run per face) | nothing |
| `OCR` | text found inside the photo, into the full-text index | slow (seconds per photo, CPU) | nothing |

Location clustering (the map's markers) is not a stage: it is computed when the map is opened, from the stored positions.

### How it runs

- **WorkManager**, periodic (every 15 minutes at most) and unique, with these constraints: battery not low,
  and by default charging. "Analyze now" runs one slice immediately, still not on a low battery (but even in Battery Saver).
- One run is a **time slice of 8 minutes** (WorkManager allows about 10). Whatever is left is picked up by
  the next run; nothing needs to finish in one go.
- **State is per photo and per stage** in `index_state` (done, skipped because there was nothing to
  extract, or failed with an attempt count). The app being killed, the phone rebooting or a run being
  cancelled loses at most the photo in flight; the next run continues where it stopped. A photo that fails
  3 times is left alone.
- **Order**: places first (fast), then the fingerprints for duplicates (fast), then what photos show, then faces, then text (slowest); within a stage, newest photos first, because those are the ones
  most likely to be searched for. The photos currently on screen come before the rest (`AnalysisPriority`: the screens tell the analysis which photos they show once scrolling settles; only ids, in memory, forgotten when the screen goes).
- **Battery Saver**: a scheduled run does not start on a photo while Battery Saver is on (nothing is marked failed; the next run tries again). "Analyze now" is the user's own request and goes ahead.
- **Battery and heat**: the runner reads the platform thermal status between photos. Moderate heat adds a
  pause after each photo; severe heat stops the run. The user can pause everything, turn each step off, and
  choose whether to require charging.
- **Visibility**: Settings shows "Places: n of N" and "Text in photos: n of N" and whether a run is in
  progress; Search shows a note while results may still be incomplete.
- **Isolation**: a failure on one photo is recorded against that photo and the run continues; cancellation is
  never counted as a failure.
- **A model that cannot load is not the photo's fault.** If a step's model will not load, that step stops for this
  run, **no photo is marked failed or skipped**, the other steps carry on, and Settings and Search say the step is not
  running. It is retried at the next run.
- **Models are loaded once per run** (about 90 MB for the image model, 40 MB for the two face models) and freed when the
  run ends. The text model used by Search is loaded on demand and freed after a minute of not searching.

### Adding a stage

Implement `StageProcessor` (`process(item)` returns `DONE` or `SKIPPED`, or throws), add an `IndexStage`
value and one `@IntoMap` line in `IndexingModule`. Scheduling, state, retries, progress and thermal handling
come with it.

### What analysis stores

All of it in the private app database, excluded from backups, and **deleted when photo access is revoked**:

- `media_geo`: latitude, longitude and the resolved city/region/country of each geotagged photo.
- `media_search`: the words of each file name and the text read from each photo.
- `index_state`: which stage has been done for which photo.
- `media_embedding`: the 512-byte description of what each photo shows.
- `perceptual_hash` and `content_hash`: fingerprints of pictures and files, each with the file's modification time so an edited photo is fingerprinted again.
- When a sync finds that a photo's **file was rewritten** (its modification time moved *and* its size or dimensions changed; a time that moved alone, as marking a favorite may do, does not count), everything the analysis learned about it is forgotten (`FileChange`, `RoomMediaIndex`) so it is learned again from the file as it is now: a photo edited in another app no longer matches its old text, place or faces.
- `face` and `person`: the faces found, their 128-number descriptions, and the groups they were put in and the names you gave them.

See [PRIVACY.md](PRIVACY.md) for what this means for the user.

## Known limits

- Videos are not analyzed yet (no location, no text, no content description, no faces).
- People: a group of one photo is not listed unless you named, favorited or made it; faces under 36 pixels across in
  the analysed picture are ignored; grouping is automatic and will sometimes be wrong, which is what Merge and
  "Not this person" are for. New faces are grouped with the closest existing person; the analysis never moves a face you placed.
- What photos show: the model is CLIP ViT-B/32 (int8), good at scenes and everyday objects, weak at counting and fine detail.
- OCR quality is that of Tesseract: good on documents, receipts and screenshots, weak on handwriting and
  stylized text. Anything below a confidence threshold, or that does not look like words, is discarded rather
  than indexed, so photos of scenery do not fill the index with noise.
- Only English and Italian text is recognized.
- Places resolve to the nearest city within 100 km; a photo further from any known city has coordinates but
  no place name.
- Not verified on a device yet: the WorkManager schedule, the native code and models of OCR, the CLIP models and the face
  models on real photos (and therefore their speed, battery use and memory), and the permission flow. The logic around them (runner, policies, queue, index) is covered by JVM
  tests and by instrumented tests written but not yet run.
