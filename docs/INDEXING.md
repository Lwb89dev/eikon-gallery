# Indexing

## Current state (Phase 1)

The only indexing is the MediaStore sync described in [ARCHITECTURE.md](ARCHITECTURE.md#sync):
file-level metadata is mirrored into the Room `media` table. It runs in the foreground while the app is
visible and is cheap: an unchanged library costs one generation check; a first run on a large library
reads the whole table once, newest first, in batches of 1,000, showing progress
("Loading library: n / total") when there are more than 1,000 items.

There is no WorkManager job, no EXIF pass, no OCR, no embeddings, no faces and no hashing yet.

## Planned pipeline (Phases 3 to 5) — design only, nothing below exists

Per-item stages, each recorded per item so work survives restarts and partial runs:

1. MediaStore metadata (done)
2. EXIF (GPS, camera) into searchable columns
3. OCR text
4. image embeddings for semantic search
5. face detection and embeddings
6. file hash and perceptual hash (duplicates)
7. location clustering (places, trips)

Rules the implementation must follow, taken from the project requirements:

- The app stays fully usable while indexing; recent and on-screen items first.
- Heavy stages run through WorkManager with constraints: charging and sufficient battery preferred,
  idle preferred, paused or throttled under thermal stress, batched.
- The user sees progress ("Analyzing library: 4,321 / 18,205") and can pause it.
- Per-item state (`pending`, `done`, `failed` + attempts) lives in Room, so a crash or reboot resumes.
- Everything on-device. Any stage that would need the cloud stays behind an interface, disabled.

The `media` table will not be widened with these results; each stage gets its own table keyed by
media id, added through a proper Room migration.
