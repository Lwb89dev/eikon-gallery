# Architecture

## Principles

- **Files stay where Android keeps them.** eikon indexes MediaStore; it never copies or moves media.
- **The UI reads from a local index, not from MediaStore directly.** That is what makes cold start
  instant and lets later phases attach search data, hashes and embeddings to each item.
- **Simple over abstract.** One Gradle module, MVVM with `StateFlow`, repositories, Hilt. Interfaces
  exist only where a test or a future implementation needs a seam (`MediaStoreSource`, `MediaIndex`,
  `SyncStateStore`).
- **No fake features.** Anything not implemented is absent from the UI.

## Data flow

```
Android MediaStore (files table, external volume)
        |  ContentResolverMediaStoreSource        (read-only queries)
        v
  MediaSyncer  ----------------------------------- SyncStateStore (DataStore: generation, version, access)
        |  upsert / delete
        v
  Room index: table `media`
        |  MediaRepository: Paging 3 source + section counts
        v
  LibraryViewModel  --->  LibraryScreen (grid)  --->  MediaViewer / InfoSheet
```

Writes to media (trash, favorite) never go through the index first: they go through the system's
`MediaStore.create*Request` confirmation dialogs, and the index is updated once the user approved
(or when the next sync sees the change).

## Package structure

| Package | Contents |
| --- | --- |
| `core.di` | Hilt modules and qualifiers |
| `core.image` | Coil thumbnail fetcher backed by `ContentResolver.loadThumbnail` (draws a photo's edit on it), and the transformation that draws an edit in the viewer |
| `core.permissions` | `MediaAccessChecker` (full / limited / none) |
| `core.ui` | theme, system-bar helpers, paging helpers |
| `data.db` | Room entity, DAO, database, `LibraryQueryBuilder` (SQL from a query object) |
| `data.mediastore` | MediaStore reading, change observer, category heuristics, share/trash actions |
| `data.sync` | sync engine, sync coordinator, state store |
| `data.metadata` | on-demand EXIF / container reading for the info panel |
| `data.settings` | DataStore-backed settings |
| `domain` | models, `TimelineLayout`, date labels, EXIF formatting (pure Kotlin) |
| `domain.edit` | the edit recipe and its text format, the renderer (geometry, colour, detail), auto enhance and the crop tool's rules: pure Kotlin, see [EDITING.md](EDITING.md) |
| `data.edit` | edits by photo, the copy/paste clipboard, decoding for editing and "Save a copy" |
| `feature.*` | screens and view models |

## MediaStore model

- One query over `MediaStore.Files` for the merged external volume, `media_type IN (image, video)`,
  gives images and videos a single id space. An item's URI is rebuilt from `(id, isVideo)`, so it is
  not stored.
- MediaStore hides pending and trashed items by default, and under Android 14+ "selected photos"
  access shows only the selected ones. The index therefore mirrors exactly what the OS lets eikon see.
- `_id` is treated as unique across the merged external volume. This matches the MediaProvider
  design but has not been verified on a device with an SD card; if it were wrong, items from two
  volumes could collide.
- Capture time is `datetaken`, falling back to `date_modified`, then `date_added`, because many files
  (downloads, screenshots) have no capture date.

## Room schema (version 6)

Schemas are exported to `app/schemas`. Two kinds of tables live in the database:

**`media`** is a rebuildable cache of MediaStore metadata.

| Column | Meaning |
| --- | --- |
| `id` (PK) | MediaStore `_id` |
| `displayName`, `mimeType`, `isVideo` | basic identity |
| `takenAt`, `addedAt`, `modifiedAt` | epoch milliseconds |
| `width`, `height`, `durationMs`, `sizeBytes` | as reported by MediaStore |
| `relativePath`, `bucketName` | folder and MediaStore "album" |
| `isFavorite` | MediaStore `is_favorite` |
| `isScreenshot`, `isScreenRecording`, `isPanorama`, `isRaw` | heuristic categories, see below |

Indexes on `takenAt` and `addedAt`. Because `id` is the rowid, `ORDER BY takenAt DESC, id DESC` is
served straight from the index.

**User data**, never derived from MediaStore and never touched when the cache is cleared or re-synced:

| Table | Meaning |
| --- | --- |
| `album` (`id`, `name`, `createdAt`, `position`) | virtual albums, manual order |
| `album_item` (`albumId`, `mediaId`, `addedAt`) | membership; cascades on album delete; **no foreign key to `media`** so a re-sync can never destroy an album |
| `hidden_media` (`mediaId`, `hiddenAt`) | items hidden from everything except the Hidden section |

**Edits** (`edit_recipe`: `mediaId`, `recipe` text, `updatedAt`, `baseModifiedAt`): one recipe per edited photo. User data, kept when the media cache is cleared, because it cannot be rebuilt. The photo's file is never changed;
see [EDITING.md](EDITING.md).

**What the user told eikon** about collections, kept when the media cache is cleared: `duplicate_dismissed` (`key`, `dismissedAt`: groups the user said are
not copies) and `memory_preference` (`key`, `value`, `createdAt`: memories hidden, kinds to show fewer of, people to show less of, dates to leave out).

**Derived data** about photos, produced by the background analysis and cleared with the media cache:

| Table | Meaning |
| --- | --- |
| `index_state` (`mediaId`, `stage`, `status`, `attempts`, `updatedAt`) | which analysis stage has been done for which photo, so work survives restarts |
| `media_geo` (`mediaId`, `latitude`, `longitude`, `cityId`, `countryCode`, `regionKey`) | where a photo was taken, resolved offline to the nearest city |
| `media_search` (FTS4, `rowid` = media id; `filename`, `ocr`) | full-text index over file-name words and recognized text; case and accent insensitive |
| `media_embedding` (`mediaId`, `model`, `vector`) | what a photo looks like to the image model: 512 signed bytes; `model` says which model made it, older ones are ignored |
| `search_hit` (`queryId`, `mediaId`, `score`) | scratch: the photos that matched one semantic query (a search, or the dogs collection); each query has its own id and only a few recent ones are kept |
| `perceptual_hash` (`mediaId`, `hash`, `modifiedAt`) / `content_hash` (`mediaId`, `hash`, `modifiedAt`) | fingerprints of what a photo looks like and of a file's bytes, to find copies |
| `face` (`id`, `mediaId`, box, `score`, `vector`, `personId`, `ignored`) | a face found in a photo, its 128-number description and the person it was grouped with |
| `person` (`id`, `name`, `isFavorite`, `isHidden`, `isPinned`, `createdAt`) | a group of faces the user can name, merge, split, hide or favorite; wiped with the faces when photo access is revoked |

Rows whose media is currently not visible (deleted, or outside a "selected photos" grant) are simply
not shown and reappear if the media does. A destructive migration is never configured; version 1 to 2
is an automatic migration, and versions 2 to 3, 3 to 4, 4 to 5 and 5 to 6 are explicit ones whose SQL is checked against the schema export
and run on a real SQLite in a JVM test (and, separately, by an instrumented test).

Category heuristics (`MediaClassifier`) rely on folder names, file names and image shape, because
Android exposes no such flags. They can be wrong for renamed or moved files.

## Slices of the library

Every grid is a `GridSource` (Library, Preset, Album, Folder, Hidden, Search, Person, Pets, Place, Area, Period, Memory) turned into one `LibraryQuery`
(scope + filters + sort) and then into SQL by `LibraryQueryBuilder`, the single place that builds
queries. It binds every value (album id, folder path, cutoff time) as an argument, and it is where
hidden items are excluded from every scope except Hidden, so a screen cannot forget to. The same
builder produces the paged rows, the date sections, counts and covers, and JVM tests run all of them
against a real SQLite, checking that every item falls in the right section.

## Search

`SearchQueryParser` turns the typed text into a `SearchSpec` using plain rules (no model): dates in Italian
and English (years, months, days, ISO and numeric forms, "yesterday", "this week", seasons), kind words
("video", "screenshot", "favorites"), places from the offline gazetteer, and the rest as words. The screen
shows the user how the text was understood. `LibraryQueryBuilder` turns the spec into SQL: every word must
match either the full-text index (prefix match on file-name words and recognized text) or, if it names a
place, a photo taken there; several dates are alternatives; hidden photos are always excluded. User text
reaches the query only as letters and digits plus `*`, and as bound arguments.

`Gazetteer` (`data/places`) is the offline place lookup: nearest city for a coordinate (grid index, 100 km
limit) and place names, aliases and countries for a word. Its data is the bundled GeoNames extract.

## Places, trips, memories and duplicates

These four are read from what the analysis stored, and never contact anything.

- **Places.** `media_geo` grouped by country, region and city into a tree (`PlaceTree`), shown as a list and as a map. The map draws country outlines
  (Natural Earth, 214 KB in `places/world.bin`) with Web Mercator (`MapProjection`), and clusters photo positions with a grid the size of a marker
  (`PointClusterer`, tested for panning, zooming and photos split by a grid line). No map service is used, so no coordinate leaves the phone.
- **Trips.** `TripDetector` (pure, `domain/places`) works on geotagged photos with rules anyone can check: each day gets a position (the median of its
  photos), "home" is the busiest place in the half year around that day, a day is *away* at 100 km or more from home, and a run of away days with at
  least 10 photos (and two days, or one busy day) is a trip. Names come from the gazetteer. The numbers are in `TripPolicy`.
- **Memories.** `MemoryPlanner` (pure, `domain/memories`) proposes memories from dates, trips and named people: *On this day*, *A year ago*, trips, weekends away,
  day trips, seasons, a person in a year; scores favour recent, larger and anniversary ones, a few of each kind are kept and no two are about the same days.
  A `MemoryId` says everything needed to find a memory's photos, so it travels in a route. `KeyPhotoPicker` chooses up to 30 photos spread across the
  memory, favoring favorites, larger photos and photos with faces. The slideshow zooms and drifts each photo (Ken Burns) on one animation clock that also drives the progress bar.
- **Duplicates.** `DuplicateFinder` (pure) joins photos with the same byte hash and photos whose 64-bit perceptual hashes differ by at most 6 bits (eight one-byte
  buckets guarantee any pair within seven bits is compared); `SimilarShotFinder` compares stored image vectors of photos taken within two minutes.
  `DuplicatesViewModel` only ever asks Android to move photos to the trash, one group at a time, with the system's own confirmation.

## Locks

`AreaLocks` keeps, in memory only, which protected areas (Hidden, Recently deleted) are unlocked.
`LockGate` asks the system BiometricPrompt (strong biometrics or screen-lock credential) and unlocks on
success; leaving the screen or the app going to the background locks again. On a phone with no screen
lock there is nothing to authenticate against, so the area opens and says it is unprotected.

## Recently deleted

There is no copy of the trash in the database. `TrashRepository` reads MediaStore's trashed items
(`MATCH_ONLY`) with their expiry, and restores or deletes for good through `MediaStore.create*Request`
system dialogs, then reloads.

## Sync

`MediaSyncer` keeps the index equal to what MediaStore shows:

1. Read the volume's current *generation* (a change counter) and MediaStore version **before**
   reading rows, so a change racing with the sync is caught next time.
2. Incremental: upsert rows with `_generation_modified` newer than the last synced generation.
3. Full re-read instead when incremental cannot be trusted: first run, MediaStore version changed,
   access level changed, *limited access* (choosing more photos does not bump any generation), or the
   platform reports no generation.
4. Deletions cannot be observed, so the id set is diffed against the index and vanished ids removed.
   A failed id query throws instead of returning empty, so a glitch can never wipe the index.
5. Without any media access the index is **wiped** and sync state reset.

`LibrarySyncCoordinator` serialises syncs (requests collapse into one follow-up). Changes are observed
with a `ContentObserver` only while the UI is visible: nothing runs in the background in Phase 1.

## Library grid and viewer

- Paging 3 with placeholders over a `@RawQuery` gives the exact item count and lets the viewer swipe
  anywhere. `jumpThreshold` makes a far scroll reload around the new position instead of loading every
  page in between; `maxSize` bounds memory.
- **Date headers without materialising items.** A `GROUP BY` query yields (day or month, count)
  pairs, a few thousand rows at most. `TimelineLayout` maps between *media index* (paging source, pager)
  and *grid position* (media plus one header cell per section) with binary search. Unit tests check
  the invariant this relies on against a real SQLite.
- Thumbnails come from `ContentResolver.loadThumbnail`, i.e. the system thumbnail cache. The grid never
  decodes a full-resolution image.
- The viewer is an overlay on the library, sharing the grid's paged list. A photo is shown as
  thumbnail, then a screen-sized decode, then (only while zoomed) a decode up to 4096 px, dropped again
  when zoom returns to 1. A photo with an edit is drawn edited at each of those steps (see [EDITING.md](EDITING.md)). Only the visible video page owns an ExoPlayer.
- Grid gestures: pinch (Initial pass, consumed only for two fingers), long-press-drag range selection
  (scrolling is disabled while dragging, with edge auto-scroll).

## Permissions and scoped storage

`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED` (Android 13+ / 14+),
`READ_EXTERNAL_STORAGE` up to Android 12L, and `ACCESS_MEDIA_LOCATION` requested only from the info
panel. No broad storage permission. Modifying media that eikon does not own always goes through the
system dialog. `MANAGE_MEDIA` (silent edits after a one-time user grant) is a possible later option.

## Known limitations

- Favorite and trash need a system confirmation each time.
- Animated GIF/WebP show their first frame; motion photos and Live Photo-style clips are plain images.
- Grouping uses the device's *current* time zone.
- `ACCESS_NETWORK_STATE` is added to the merged manifest by Media3; it grants no network access and
  is documented in [PRIVACY.md](PRIVACY.md).
- Dependencies use `androidx.media3` APIs marked `@UnstableApi`; they are confined to
  `feature/viewer/VideoPage.kt` and the Media3 version is pinned.
