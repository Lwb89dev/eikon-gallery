# Indexing

Two things keep eikon's library data current, and they are deliberately separate.

## 1. Library sync (foreground, cheap)

Mirrors MediaStore metadata (name, date, size, folder...) into the Room `media` table, incrementally, while
the app is visible. Details are in [ARCHITECTURE.md](ARCHITECTURE.md#sync). It also keeps the file-name part
of the search index in step, and removes everything learned about a photo when the photo goes away.

## 2. Analysis (background, heavier)

Learns things that are not in MediaStore by opening each photo. **Both steps are off by default** and must be
turned on in Settings, because they record where photos were taken and the text in them. Implemented steps:

| Stage | What it produces | Cost | Needs |
| --- | --- | --- | --- |
| `GEO` | where the photo was taken, resolved **offline** to the nearest city of the bundled GeoNames data (about 32,000 places) | fast (EXIF read) | permission to read photo locations (`ACCESS_MEDIA_LOCATION`) |
| `OCR` | text found inside the photo, into the full-text index | slow (seconds per photo, CPU) | nothing |

Not implemented yet: image embeddings, faces, hashes, location clustering (see [ROADMAP.md](ROADMAP.md)).

### How it runs

- **WorkManager**, periodic (every 15 minutes at most) and unique, with these constraints: battery not low,
  and by default charging. "Analyze now" runs one slice immediately, still not on a low battery.
- One run is a **time slice of 8 minutes** (WorkManager allows about 10). Whatever is left is picked up by
  the next run; nothing needs to finish in one go.
- **State is per photo and per stage** in `index_state` (done, skipped because there was nothing to
  extract, or failed with an attempt count). The app being killed, the phone rebooting or a run being
  cancelled loses at most the photo in flight; the next run continues where it stopped. A photo that fails
  3 times is left alone.
- **Order**: places first (fast), then text; within a stage, newest photos first, because those are the ones
  most likely to be searched for. (Prioritizing what is currently on screen is not implemented.)
- **Battery and heat**: the runner reads the platform thermal status between photos. Moderate heat adds a
  pause after each photo; severe heat stops the run. The user can pause everything, turn each step off, and
  choose whether to require charging.
- **Visibility**: Settings shows "Places: n of N" and "Text in photos: n of N" and whether a run is in
  progress; Search shows a note while results may still be incomplete.
- **Isolation**: a failure on one photo is recorded against that photo and the run continues; cancellation is
  never counted as a failure.

### Adding a stage

Implement `StageProcessor` (`process(item)` returns `DONE` or `SKIPPED`, or throws), add an `IndexStage`
value and one `@IntoMap` line in `IndexingModule`. Scheduling, state, retries, progress and thermal handling
come with it.

### What analysis stores

All of it in the private app database, excluded from backups, and **deleted when photo access is revoked**:

- `media_geo`: latitude, longitude and the resolved city/region/country of each geotagged photo.
- `media_search`: the words of each file name and the text read from each photo.
- `index_state`: which stage has been done for which photo.

See [PRIVACY.md](PRIVACY.md) for what this means for the user.

## Known limits

- Videos are not analyzed yet (no location, no text).
- OCR quality is that of Tesseract: good on documents, receipts and screenshots, weak on handwriting and
  stylized text. Anything below a confidence threshold, or that does not look like words, is discarded rather
  than indexed, so photos of scenery do not fill the index with noise.
- Only English and Italian text is recognized.
- Places resolve to the nearest city within 100 km; a photo further from any known city has coordinates but
  no place name.
- Not verified on a device yet: the WorkManager schedule, the OCR engine's native code and its models on real
  photos, and the permission flow. The logic around them (runner, policies, queue, index) is covered by JVM
  tests and by instrumented tests written but not yet run.
