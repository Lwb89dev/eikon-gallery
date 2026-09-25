# Roadmap

Legend: [x] done and covered by build/tests, [~] done but only checkable on a device, [ ] not started.

## Phase 1 — Gallery foundation

- [x] MediaStore integration with local index, incremental sync, deletion detection
- [x] Permissions for Android 11 and newer incl. limited ("selected photos") access
- [x] Library grid, date headers (day / month), fast scroller, density pinch, sort, filters
- [x] Multi-select with range drag; share; favorite and delete through system dialogs
- [x] Viewer: swipe, zoom, pan, immersive mode, drag to close, drag up for info
- [x] Video playback: play/pause, seek, mute
- [x] Info panel with real EXIF / container metadata; location on request
- [x] Light / dark / system theme, English and Italian, persisted settings
- [x] Privacy baseline: no INTERNET, backups disabled, index wiped when access is revoked
- [~] Everything above confirmed on a real device (**not done yet**)

Known gaps inside Phase 1, in priority order:

1. Run on a device and fix what it reveals.
2. Grid to viewer transition is a fade/scale, not a shared-element transition (Phase 7).
3. "Selfies" and "edited" filters: no reliable signal in MediaStore. Selfies may become possible with
   ML in Phase 4; edited media with eikon's own edits in Phase 6.
4. No thumbnail scrubber for video; no editing of date/location/caption; "add to album" and "hide"
   in multi-select arrive with Phase 2.
5. Media3 adds `ACCESS_NETWORK_STATE` to the manifest; remove it after checking playback on a device.
6. GIF/animated WebP are shown as still images.
7. Performance on a fresh install: ART compiles a sideloaded app lazily, so the first sessions run
   partly interpreted (release scroll: p90 14-19 ms) until background compilation finishes (p90 9 ms
   after full AOT). A Baseline Profile would give the fast state from the first launch (Phase 7).
8. Opening an image from another app (`ACTION_VIEW`) is not supported yet.

## Phase 2 — Albums and utilities

- [x] Virtual albums in Room (create, rename, reorder, delete, add, remove), separate from device folders
- [x] Collections tab: Favorites, Recently added, Videos, Screenshots, Screen recordings, Panoramas,
      RAW, Recently deleted, Hidden, albums, device folders
- [x] Hidden: hide/unhide, excluded from every other query in one place, biometric / screen-lock gate,
      can be removed from Collections
- [x] Recently deleted over the system trash: days remaining, restore, delete permanently
- [x] Combinable filters (type + favorites + kind); multi-select actions: add to album, hide, remove
- [x] Database version 2 with a tested migration
- [~] Confirmed on a device: albums, hiding, Hidden lock prompt. **Not yet**: unlocking with real
      authentication, Recently deleted dialogs, restore and permanent delete.

Known gaps: album cover choice is always the newest item; albums cannot be shared; Hidden is a gate
and an exclusion, not encryption (stated in the app and in [PRIVACY.md](PRIVACY.md)); "select all in a
day" is not implemented (drag-select covers ranges).

## Phase 3 — Search foundation

- [x] Search tab: date, file name, place and recognized-text search, with the interpretation shown
- [x] Query parser (Italian and English dates, kinds, places), SQL run against a real SQLite in tests
- [x] Offline places: bundled GeoNames data, nearest-city lookup, no network
- [x] Background analysis with WorkManager: per-photo state, retries, time slices, thermal handling,
      pause / charging-only / per-step switches, visible progress; both steps are **off by default**
- [x] OCR with Tesseract (English and Italian), results filtered, text shown and copyable in Info
- [x] Database version 3 with a tested migration; derived data cleared when access is revoked
- [~] **Not run on a device yet**: the search screen, the WorkManager schedule, the OCR engine's native code
      and models on real photos, the location-permission flow, the info panel additions. The logic around
      them is covered by 100+ new JVM tests; new instrumented tests are written but have not been run.

Known gaps: videos are not analyzed; no thumbnail-on-screen prioritization; no "select and copy text" overlay
on the photo itself (text is shown in Info); no URL, phone or email detection in recognized text; only
English and Italian text; region names are English except a small Italian alias list.

## Phase 4 — Intelligent indexing
WorkManager pipeline, embeddings and semantic search, faces and people, pets. See
[ML.md](ML.md) and [INDEXING.md](INDEXING.md).

## Phase 5 — Smart collections
Places, trips, memories, duplicates (exact, perceptual) and similar shots.

## Phase 6 — Editing
Non-destructive edit recipes, revert, copy/paste edits.

## Phase 7 — Polish
Shared-element transitions, baseline profiles and large-library tuning, accessibility audit, battery
and thermal behaviour, edge cases.

## Later — Home-server backup
Opt-in automatic backup to a self-hosted server. Constraints are in [PRIVACY.md](PRIVACY.md).
