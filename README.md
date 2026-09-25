# eikon

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A local-first, privacy-first photo and video gallery for Android, built natively with Kotlin and
Jetpack Compose and inspired by the interaction model of Apple Photos.

eikon reads your photos and videos where Android already keeps them (MediaStore). It never copies
them, has no account, no analytics and no internet permission at all.

Repository: <https://github.com/Lwb89dev/eikon-gallery>

## Status

Early development. Phases 1 (gallery foundation), 2 (albums and utilities) and 3 (search foundation) are
implemented. Phases 1 and 2 were checked on a real phone; **Phase 3 (the Search tab, background analysis,
text recognition and place lookup) is covered by unit tests but has not been run on a device yet**. The
full plan, with what is done and what is not, is in [docs/ROADMAP.md](docs/ROADMAP.md). Features that are
not built yet are not shown in the app.

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
- Places are resolved **offline**: a photo's coordinates are matched to the nearest city in a bundled
  copy of GeoNames data. No lookup service is ever contacted.
- Text in photos is read by Tesseract, on the phone, in English and Italian.
- Place and text search need a background analysis of your library. **It is off by default**: you switch
  on "Places" and/or "Text in photos" in Settings, choose whether it may run only while charging, and can
  pause it. It works in short slices, resumes after interruptions, backs off when the phone gets warm, and
  shows its progress. Hidden photos are never searchable.

**Platform**
- Works with Android 11 and newer, including Android 14+ "selected photos" access. Sharing, deleting
  and favoriting go through the platform's own confirmation dialogs.
- Light, dark or system theme; English and Italian; TalkBack labels.

Not implemented yet: search by what a photo shows ("dog in the snow"), people and pets, trips, memories,
duplicates, editing and backup to a home server. The "selfie" and "edited" filters are missing because Android exposes
nothing reliable to detect them.

## Privacy

- No `INTERNET` permission, so the operating system itself stops eikon from sending anything anywhere.
- No account, no analytics, no crash reporting.
- Android backups of the app's data are disabled.
- The local index holds file names, dates, sizes and folders. If you turn on the analysis, it also holds
  where your photos were taken and the text found in them; that is why it is off until you choose. All of
  it is deleted when you uninstall the app or revoke photo access.

Details, including the limits of these guarantees, are in [docs/PRIVACY.md](docs/PRIVACY.md).

## Building

Requirements: JDK 17 or newer, and the Android SDK with the API 37 platform and matching build tools.

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

- **JVM unit tests** (`./gradlew :app:testDebugUnitTest`, 187 tests): the sync engine, every library and
  search query run against a real SQLite including full-text search (checking, for instance, that each
  item lands in the right date section), the database migrations, the search query parser, the offline
  place lookup, the analysis runner (retries, resuming, pausing, heat), timeline layout, classification
  heuristics, EXIF formatting, grid sources, locks and more.
- **Instrumented tests** (`./gradlew :app:connectedDebugAndroidTest`, needs a device or emulator):
  album and hidden-media behavior on Room, the queries on the device's own SQLite, the real database
  migration, and the analysis pipeline on real images (written, not yet run). They use an in-memory database and never touch app data. Note that Gradle
  uninstalls the debug app when they finish.

Some behaviors can only be confirmed on a device, for example unlocking Hidden with real
authentication and the system trash dialogs; [docs/ROADMAP.md](docs/ROADMAP.md) says what has and has
not been checked that way.

## Project layout

```
app/src/main/java/app/eikon/gallery/
  core/        dependency injection, theme, image loading, security
  data/        Room index, MediaStore access and sync, background analysis, OCR, offline places,
               metadata reading, settings
  domain/      plain models and pure logic (timeline layout, search query parser, EXIF formatting)
  feature/     library, search, collections, viewer, info, trash, security, settings, permissions
app/src/main/assets/   place data (GeoNames) and OCR language data; see NOTICE.md
docs/          architecture, indexing, privacy, ML notes, roadmap
```

## Documentation

[Architecture](docs/ARCHITECTURE.md) · [Privacy](docs/PRIVACY.md) · [Indexing](docs/INDEXING.md) ·
[Machine learning](docs/ML.md) · [Roadmap](docs/ROADMAP.md)

## License

eikon is released under the [MIT License](LICENSE). Third-party libraries and bundled data keep their
own licenses; see [NOTICE.md](NOTICE.md).
