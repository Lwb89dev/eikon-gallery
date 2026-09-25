# eikon

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A local-first, privacy-first photo and video gallery for Android, built natively with Kotlin and
Jetpack Compose and inspired by the interaction model of Apple Photos.

eikon reads your photos and videos where Android already keeps them (MediaStore). It never copies
them, has no account, no analytics and no internet permission at all.

Repository: <https://github.com/Lwb89dev/eikon-gallery>

## Status

Early development. Phases 1 (gallery foundation), 2 (albums and utilities), 3 (search foundation), 4
(intelligent indexing: search by what photos show, people, pets), 5 (smart collections: places, trips,
memories, duplicates) and 6 (non-destructive editing) are implemented. Phases 1 and 2 were checked on a real phone; **Phases 3, 4, 5 and 6 are covered
by unit tests, including tests that run the real models, but have not been run on a device yet**, so how fast and how
battery-hungry the analysis is on a phone, how fast editing is, and how the new screens behave, is unchecked. The full plan, with what is done and what is not, is in [docs/ROADMAP.md](docs/ROADMAP.md).
Features that are not built yet are not shown in the app.

## Features

**Library**
- Chronological library that opens instantly and scales to very large collections: it is served from
  a local index in pages, never loaded into memory as a whole.
- Date headers per day or month, a fast scroller with a month/year bubble, pinch to change grid
  density (2 to 7 columns), sorting by date taken or date added.
- Combinable filters: photos or videos, favorites only, and one kind (screenshots, screen recordings,
  panoramas, RAW).
- Multi-select by tap or long-press-and-drag, then share, favorite, add to album, hide or delete.

**Viewer and details**
- Full-screen viewer: swipe between items, pinch and double-tap zoom, immersive mode, drag down to
  close, drag up for details. Video playback with seek and mute.
- Info panel with the real metadata of the file: resolution, size, dates, camera, lens, exposure,
  color profile, location (on request), and for videos duration, frame rate, codecs and bitrate.

**Collections**
- Automatic collections (Favorites, Recently added, Videos, Screenshots, Screen recordings, Panoramas,
  RAW), device folders, and your own albums. Albums are virtual: files are never moved or copied.
- **Hidden**: keeps photos out of the Library and Collections, behind fingerprint, face or screen
  lock. See [docs/PRIVACY.md](docs/PRIVACY.md) for exactly what that does and does not protect.
- **Recently deleted**: the system trash with days remaining, restore and delete-for-good.

**Search**
- A Search tab that understands what you type and shows how it read it: dates in Italian and English
  ("agosto 2025", "estate 2025", "14 agosto", "yesterday", "last month"), photos or videos, file names,
  places ("foto a Roma") and text found inside photos (a receipt, a document, a screenshot).
- **By what photos show**: "cane", "tramonto", "a red car", "una foto sulla neve", in Italian or English, matched
  to the photos by an on-device image model (CLIP). It combines with everything else: "cane sulla neve 2024".
- Places are resolved **offline**: a photo's coordinates are matched to the nearest city in a bundled
  copy of GeoNames data. No lookup service is ever contacted.
- Text in photos is read by Tesseract, on the phone, in English and Italian.
- Place, text, content and people search need a background analysis of your library. **It is off by default**:
  you switch on each step in Settings ("Places", "What photos show", "People", "Text in photos"), choose whether
  it may run only while charging, and can pause it. It works in short slices, resumes after interruptions,
  backs off when the phone gets warm, and shows its progress. Hidden photos are never searchable.

**Smart collections**
- **Places**: photos grouped by country, region and city, and a **map** drawn offline from bundled country outlines: no map service, no tiles, no position ever sent.
  Tap a marker to see its photos.
- **Trips**: stretches of days spent far from where most of your photos are taken, found by rules you can read (100 km, at least two days or a busy day, at least 10
  photos), each showing why it counts.
- **Memories**: "On this day", "A year ago", trips and weekends away, a season, a named person in a year, as a slideshow. You can hide a memory, ask for fewer like it or
  for less of a person, or leave out a date. No music; photos are picked by favorites, size and faces, not by how good they look.
- **Duplicates and similar shots**: identical files and copies of the same picture, and shots of one moment that look alike. eikon only finds them; you choose, and photos
  go to Recently deleted through Android's own confirmation.

**Editing**
- **Non-destructive**: an edit is a small recipe kept in eikon's database and drawn over the photo; the file is never changed and there is no "replace the original". Revert removes the recipe.
- Auto enhance, exposure, brightness, contrast, highlights, shadows, black point, saturation, vibrance, temperature, tint, sharpness, vignette, crop (free, 1:1, 4:3, 3:2, 16:9), rotate, flip,
  straighten, perspective, and eight filters with a strength. Edited photos look edited in the grid and the viewer, where a chip shows the original.
- **Save a copy** writes a new JPEG next to the original, keeping its date and camera details. **Copy edits** and **Paste edits** apply one photo's look to many at once; each keeps its own crop.
- Limits, stated up front: photos only; other apps and Share see the original until you save a copy; recipes are lost if the app's data is cleared. See [docs/EDITING.md](docs/EDITING.md).

**People and pets**
- **People**: eikon finds faces on the phone and *groups* the ones that look alike. It does not identify anyone and
  never compares faces with anything outside your library; a group has a name only when you type it. You can name,
  rename, favorite, hide, merge two groups and split photos out of a group. Names work in Search ("foto di Giulia").
  Face data is biometric data: see [docs/PRIVACY.md](docs/PRIVACY.md).
- **Pets**: Dogs and Cats collections, from what the photos show. Other animals are not attempted.

**Platform**
- Works with Android 11 and newer, including Android 14+ "selected photos" access. Sharing, deleting
  and favoriting go through the platform's own confirmation dialogs.
- Light, dark or system theme; English and Italian; TalkBack labels.

Not implemented yet: backup to a home server. The "selfie" filter is missing because Android exposes
nothing reliable to detect it, and the "edited" filter is not built yet.

## Privacy

- No `INTERNET` permission, so the operating system itself stops eikon from sending anything anywhere.
- No account, no analytics, no crash reporting.
- Android backups of the app's data are disabled.
- Edits are recipes in the private database; **your files are never modified**. A copy you save keeps the original's date, camera details and (if you allowed it) location.
- The local index holds file names, dates, sizes and folders. If you turn on the analysis, it also holds
  where your photos were taken, the text found in them, a numeric description of what they show, where the
  faces are with a numeric description of each, and fingerprints to find copies; that is why every step is off until you choose. All of it is
  deleted when you uninstall the app or revoke photo access.
- The build fails if the `INTERNET` permission ever appears in the app's manifest, so a library update cannot add it silently.

Details, including the limits of these guarantees, are in [docs/PRIVACY.md](docs/PRIVACY.md).

## Building

Requirements: JDK 17 or newer, and the Android SDK with the API 37 platform and matching build tools.

The first build **downloads about 265 MB of machine-learning models** from Hugging Face and GitHub (OpenCV Zoo), each
pinned to a commit and checked against a SHA-256 (see [app/model-manifest.tsv](app/model-manifest.tsv)); they are not
in this repository. They make the release APK about 310 MB. This is the only time the build needs those servers, and
the app itself has no network access at all.

```sh
git clone https://github.com/Lwb89dev/eikon-gallery.git
cd eikon-gallery
export ANDROID_HOME=/path/to/Android/Sdk   # or create local.properties with sdk.dir=...

./gradlew :app:assembleDebug               # debug APK in app/build/outputs/apk/debug
./gradlew :app:testDebugUnitTest           # unit tests (JVM)
./gradlew :app:lintDebug                   # Android lint
./gradlew :app:assembleRelease             # R8-shrunk, unsigned release APK
```

To try the real (fast) build on a connected phone, install the release variant signed with the debug
key: `./gradlew :app:installRelease -Peikon.signReleaseWithDebugKey`. Debug builds are several times
slower because Compose runs unoptimized (on one test phone, scrolling had a 57 ms median frame time in
debug versus 6 ms in release), so judge smoothness on the release build.

The debug build installs as `app.eikon.gallery.debug`, next to a release build. The release build is
unsigned: signing configuration is deliberately not part of the repository. `local.properties` and
Gradle/Android caches contain machine-specific paths and must not be committed; `.gitignore` excludes
them.

### Tests

- **JVM unit tests** (`./gradlew :app:testDebugUnitTest`, 490 tests): the sync engine, every library and
  search query run against a real SQLite including full-text search (checking, for instance, that each
  item lands in the right date section), the database migrations, the search query parser, the offline
  place lookup, the analysis runner (retries, resuming, pausing, heat, a model that cannot load), the edit renderer and recipe format, timeline
  layout, classification heuristics, EXIF formatting, grid sources, locks and more. Some tests **run the real
  models** on the JVM with ONNX Runtime and compare them with reference tooling (a tokenizer, ONNX Runtime for Python,
  OpenCV): a phrase in Italian or English must find the right one of four public-domain photos, and the face
  detector, alignment and face vectors must match OpenCV's. Running the tests fetches the models first.
- **Instrumented tests** (`./gradlew :app:connectedDebugAndroidTest`, needs a device or emulator):
  album and hidden-media behavior on Room, the queries on the device's own SQLite, the real database
  migration, the edit renderer on Android bitmaps, the analysis pipeline, and the image and face models read out of the installed APK, which also
  report how long each step takes on the phone (written, not yet run). They use an in-memory database and never touch app data. Note that Gradle
  uninstalls the debug app when they finish.

Some behaviors can only be confirmed on a device, for example unlocking Hidden with real
authentication and the system trash dialogs; [docs/ROADMAP.md](docs/ROADMAP.md) says what has and has
not been checked that way.

## Project layout

```
app/src/main/java/app/eikon/gallery/
  core/        dependency injection, theme, image loading, security
  data/        Room index, MediaStore access and sync, background analysis, OCR, offline places and trips,
               image/text embeddings and semantic search, faces and people, duplicates, memories, edits and export, metadata, settings
  domain/      plain models and pure logic (timeline layout, search parser, map geometry, trip and memory rules, the edit recipe and renderer)
  feature/     library, search, collections, people, places, trips, memories, duplicates, edit, viewer, info, trash, security, settings
tools/         scripts that build bundled assets (the world map)
app/src/main/assets/   place data (GeoNames) and OCR language data; see NOTICE.md
app/model-manifest.tsv the machine-learning models fetched at build time (URL and SHA-256 of each)
docs/          architecture, indexing, editing, privacy, ML notes, roadmap
```

## Documentation

[Architecture](docs/ARCHITECTURE.md) · [Privacy](docs/PRIVACY.md) · [Indexing](docs/INDEXING.md) ·
[Machine learning](docs/ML.md) · [Editing](docs/EDITING.md) · [Roadmap](docs/ROADMAP.md)

## License

eikon is released under the [MIT License](LICENSE). Third-party libraries and bundled data keep their
own licenses; see [NOTICE.md](NOTICE.md).
