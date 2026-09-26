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
2. Grid to viewer transition: a flight of the thumbnail since Phase 7 (not seen on a device yet).
3. "Selfies" filter: no reliable signal in MediaStore. It would need eikon to know who the phone's owner is (it never asks). The "Edited" filter exists since Phase 7.
4. No thumbnail scrubber for video; date, location and caption can be edited since Phase 9; "add to album" and "hide"
   in multi-select arrive with Phase 2.
5. Media3 adds `ACCESS_NETWORK_STATE` to the manifest; remove it after checking playback on a device.
6. GIF/animated WebP are shown as still images.
7. Performance on a fresh install: ART compiles a sideloaded app lazily, so the first sessions run
   partly interpreted (release scroll: p90 14-19 ms) until background compilation finishes (p90 9 ms
   after full AOT). A Baseline Profile would give the fast state from the first launch (Phase 7).
8. Opening an image from another app (`ACTION_VIEW`): supported for pictures since Phase 7 (not videos); not run on a device yet.

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

Known gaps: videos are not analyzed; on-screen photos are analysed first since Phase 9; no "select and copy text" overlay
on the photo itself (text is shown in Info); no URL, phone or email detection in recognized text; only
English and Italian text; region names are English except a small Italian alias list.

## Phase 4 — Intelligent indexing

- [x] Image embeddings (CLIP ViT-B/32 int8) and multilingual text embeddings (Italian and English); ONNX Runtime pinned to
      a version without telemetry; models fetched at build time with checksums, memory-mapped from the APK
- [x] Semantic search: "cane", "tramonto", "una macchina rossa"; combines with dates, places, kinds, names and text; hits
      kept per query; thresholds chosen on 1,000 labelled photos
- [x] Face detection (YuNet, multi-scale) and description (SFace); grouping into people that only ever places
      ungrouped faces
- [x] People: list, per-person photos, name, rename, favorite, hide, merge, "Not this person" (split), "Not a face", people
      in the info panel, names usable in Search ("foto di Giulia con il cane")
- [x] Pets: dogs and cats collections from the image vectors
- [x] Database version 4 with a tested migration; the build fails if the `INTERNET` permission ever appears
- [~] **Not run on a device yet**: everything above except what the JVM tests exercise with the real models (they run
      the models and compare with reference tooling). Speed, battery, memory and heat on a phone are **unmeasured**.
      `SemanticOnDeviceTest` and `FacesOnDeviceTest` are written to measure them.

Known gaps: videos are not analyzed; on-screen photos are analysed first since Phase 9; people cannot be told apart from pets or
between two dogs; no age handling; no "selfies" collection (would need to know who the phone's owner is, which eikon
never asks); captions exist since Phase 9; the release APK is about 310 MB, mostly models (see [ML.md](ML.md)).

## Phase 5 — Smart collections

- [x] Places: a list by country, region and city; a map with clustered markers drawn offline from bundled country outlines (public domain); tap a marker or a place to see its photos
- [x] Trips: rules anyone can check (100 km from home for at least two days, or a busy day; 10 photos), each with the reason it counts; grouped by year
- [x] Memories: On this day, A year ago, trips, weekends away, day trips, seasons, a person in a year; slideshow with the Ken Burns effect and a cross-fade; hide a memory,
      show fewer like it, show less of a person, leave out a date; all of it undoable
- [x] Duplicates: identical files (SHA-256, only for files sharing a size) and copies of the same picture (64-bit perceptual hash); suggests which to keep; moves to the trash only after Android's own confirmation, the kept copy joins the albums of the removed ones
- [x] Similar shots: photos within two minutes whose image vectors are close; the user picks what to remove
- [x] Database version 5 with a tested migration; the analysis gets two fingerprint steps (off by default)
- [~] **Not run on a device yet**: every screen of this phase, the map's gestures and drawing, the slideshow's animation and the hash steps on real files. The logic (trip and
      memory rules, clustering, projection, hashing, grouping, SQL) is covered by JVM tests, some against reference values.

Known gaps: **no music** in memories; no memories about pets or "family moments" (no reliable signal); photo quality is favorites, size and faces only (no blur or exposure analysis);
"merging" duplicates keeps the best copy and trashes the others, it does not combine metadata; videos are only compared as exact copies; the map has outlines and names of
larger cities only (no roads, no imagery); trips need Places analysis and its permission; trip and memory titles use GeoNames' English region names.

## Phase 6 — Editing

- [x] Non-destructive by construction: an edit is a recipe (text) in the database; the original file is never written; no "replace original" exists; Revert deletes the recipe ([EDITING.md](EDITING.md))
- [x] Tools: auto enhance, exposure, brightness, contrast, highlights, shadows, black point, saturation, vibrance, temperature, tint, sharpness, vignette, crop (free and fixed shapes), rotate, flip, straighten, perspective (vertical and horizontal)
- [x] Eight filters with a strength; a thumbnail of each on the photo being edited
- [x] Edits drawn in the editor, the grid and every thumbnail, the viewer (with an Edited chip to look at the original) and marked with a badge; "Edited" is now a real thing, not a guess
- [x] Save a copy: full resolution (up to 24 megapixels), new JPEG next to the original with date, camera and (if allowed) location; nothing partial left on failure
- [x] Copy edits, Paste edits (in the editor and on a multi-selection) and Remove edits; the crop and turns of each photo stay its own; an `EditAdaptation` seam for smarter pasting later
- [x] Database version 6 with a tested migration
- [~] **Not run on a device yet**: every screen, the crop overlay's gestures, how each tool looks on real photos, the renderer's speed and memory on a phone, and the saved copy's metadata. The renderer, recipe
      format, copy/paste rules and database are covered by JVM tests on synthetic pictures; `EditOnDeviceTest` is written but not run.

Known gaps: videos cannot be edited; no undo stack inside a session; no local adjustments, curves, per-colour tools, noise
reduction or retouching; perspective is two sliders, not four corners; no Ultra HDR or wide-gamut output (copies are sRGB JPEG); if another app changes the file afterwards, eikon still draws its recipe over it;
the "selfies" filter still has no signal. (Sharing an edited photo shares the edit, and the "Edited" filter exists: both added in Phase 7.)

## Phase 7 — Polish

- [x] Grid to viewer transition: the thumbnail flies from its cell to the viewer and back (`HeroFlight`), with tested geometry; falls back to a fade when it cannot be made ([PERFORMANCE.md](PERFORMANCE.md))
- [x] Baseline profile for eikon's own code (hand-written, all of `app.eikon.gallery`), checked to be in the release APK
- [x] Large-library tuning found by looking at the query plans: a device folder had no index; database version 7 adds two, with a tested migration and a test that fails if a main query starts to sort or scan a whole table
- [x] Battery: a background analysis run waits in Battery Saver ("Analyze now" does not); thermal handling reviewed
- [x] Accessibility review with fixes: labelled sliders with reset, actions for zoom and for moving or resizing the crop, a tap alternative to press-and-hold, roles and selected state, a scrollable tool row ([ACCESSIBILITY.md](ACCESSIBILITY.md))
- [x] Edge cases: "Open with" for pictures from other apps; a photo that cannot be decoded says so; sharing an edited photo shares the edit (no metadata); an "Edited only" filter
- [~] **Not run on a device**: the flight's alignment on real screens, whether the baseline profile speeds up the first launch, TalkBack, the FileProvider share and "Open with" from a real app. The battery policy, the migration, the query plans and the geometry are covered by JVM tests.

Not done: a *measured* baseline profile (needs a Macrobenchmark module and a device); animated GIF and WebP still show their first frame; the Media3 `ACCESS_NETWORK_STATE` permission is still merged in (removing it needs playback checked on a
device); no thumbnail scrubber for video; very large albums sort their members for each page (see [PERFORMANCE.md](PERFORMANCE.md)).

## Phase 8 — Backup to a home server

- [x] A separate `backup` build (same app id, so it installs over the standard one); the `standard` build keeps having no network permission, checked by the build, and contains no network code ([BACKUP.md](BACKUP.md))
- [x] Two servers behind one interface: **Immich** (what Umbrel installs for Android; upload with an API key, duplicates recognised by SHA-1) and **Nextcloud / WebDAV** (app password; files named by content so nothing is ever overwritten)
- [x] TLS only, the phone's own authorities; a certificate outside them can be **pinned** after comparing its fingerprint; redirects never followed; the credential encrypted under an Android Keystore key and thrown away when the server changes
- [x] Opt-in with a confirmation; only on Wi-Fi by default; battery not low; not in Battery Saver; heat checked; hidden photos left out by default; never deletes or overwrites
- [x] Progress, the last run and why it stopped shown in Settings; "Back up now"; refused photos can be retried
- [x] Database version 8 (`backup_item`) with a tested migration
- [~] **Not run on a device and not tried against a real server.** The clients are tested against stand-ins that check every request, TLS with generated certificates is tested on real handshakes, and the whole path runs against stand-ins that remember what they are given. Umbrel's own Photos app cannot be used (iPhone only, no
      public protocol): use Immich or Nextcloud on Umbrel.

Known gaps: no restore; a file that cannot be sent within one 8-minute run is never finished (no resumable or chunked upload, no foreground service); edits, albums and the hidden list are not part of the backup; server messages are in English; Immich older than 1.120 is not supported.

## Phase 9 — What the specification still asked for, and a hardening pass

- [x] **The on-screen photos are analysed first** (`AnalysisPriority`; database v9 adds the index it needs for duplicates)
- [x] **Captions** (searchable, kept only in eikon's database) and **changing a photo's date or location** from the info panel, with a safety copy, a read-back check and undo ([EDITING.md](EDITING.md), database v9)
- [x] **What a photo shows**, in words, in the info panel (a short vocabulary matched against the stored image vector; no new analysis) and **pets** in it; a small offline **map** of where a photo was taken
- [x] **The library's database is encrypted** (SQLCipher, AES-256, key sealed under the Android Keystore); an old readable database is converted with checks at every step and never deleted before the copy is verified ([PRIVACY.md](PRIVACY.md), [ARCHITECTURE.md](ARCHITECTURE.md))
- [x] The backup's server address, login, pinned certificate and last message are sealed too; Hidden and Recently deleted mark the window secure (no screenshots, blank recent-apps card) and a setting does the same everywhere; shared pictures live an hour; logs carry no content; in-app open-source licenses screen (from `NOTICE.md`)
- [x] **A line-by-line review of the whole code base**, with these changes: a memory whose period list is empty no longer builds invalid SQL; a photo whose file was rewritten no longer keeps the text, place, faces and description learned from the old file; the search vectors are shared, cached softly and refreshed when one is replaced;
  the nearest-city search no longer misses a city just outside the first cells near the poles; faces of a photo that was analysed again no longer stay in a person's average; cancelling a share, a saved copy or a trash count is no longer swallowed as a failure; the Keystore is no longer asked on the main thread;
  a damaged settings file is replaced instead of blocking the app; every function is now at most three levels deep (`tools`-style scan, 0 left in `main`, `backup` and `standard`)
- [~] **Not run on a device**: the SQLCipher glue (`EncryptedDatabaseOnDeviceTest` is written), the conversion of a real library, the metadata writer on real files, the new screens and dialogs. The plan, the copy, the ordering and every failure path of the encryption are covered by JVM tests.

Not done, and why: a video thumbnail scrubber; a "selfies" filter (no signal); music in memories; animated GIF and WebP; merging the metadata of duplicates; the Media3 `ACCESS_NETWORK_STATE` permission; chunked or resumable upload and a foreground service for the backup. Each needs a device to check, or a product decision, or has no reliable source of truth.

