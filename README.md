# eikon

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A local-first, privacy-first photo and video gallery for Android, built natively with Kotlin and
Jetpack Compose and inspired by the interaction model of Apple Photos.

eikon reads your photos and videos where Android already keeps them (MediaStore). It never copies
them (unless you set up the optional backup to a server of your own), has no account, no analytics, and does not talk to the network unless you allow it, for that one backup, in its first-run screens or in Settings.

Repository: <https://github.com/Lwb89dev/eikon-gallery>

## Status

Version 1.1.0 ([CHANGELOG.md](CHANGELOG.md)) builds on the first release, 1.0.0; it is feature-complete for the plan but **not field-tested**. Phases 1 (gallery foundation), 2 (albums and utilities), 3 (search foundation), 4
(intelligent indexing: search by what photos show, people, pets), 5 (smart collections: places, trips,
memories, duplicates), 6 (non-destructive editing), 7 (polish: transitions, baseline profile, query tuning, battery, accessibility) 8 (backup to your own server) and 9 (what the specification still asked for, and a review and hardening pass, including an encrypted database) are implemented. Phases 1 and 2 were checked on a real phone; **Phases 3 to 9 are covered
by unit tests, including tests that run the real models, but have not been run on a device yet**, so how fast and how
battery-hungry the analysis is on a phone, how fast editing is, and how the new screens behave, is unchecked. The full plan, with what is done and what is not, is in [docs/ROADMAP.md](docs/ROADMAP.md).
Features that are not built yet are not shown in the app.

## Features

**Library**
- Chronological library that opens instantly and scales to very large collections: it is served from
  a local index in pages, never loaded into memory as a whole.
- Date headers per day or month, a fast scroller with a month/year bubble, pinch to change grid
  density (2 to 7 columns, continuously: the pictures follow your fingers and the grid settles on the nearest count), sorting by date taken or date added.
- Combinable filters: photos or videos, favorites only, edited only, and one kind (screenshots, screen recordings,
  panoramas, RAW).
- Multi-select by tap or long-press-and-drag, then share, favorite, add to album, hide or delete.
- Opening a photo grows it from its thumbnail, and closing it shrinks it back to its place in the grid. "Open with" from other apps shows a picture without adding it to the library.

**Viewer and details**
- Full-screen viewer: swipe between items, pinch and double-tap zoom (photos **and videos**), immersive mode, drag down to
  close, drag up for details. Video playback with mute and a seek bar that shows the picture as you drag it.
- Info panel with the real metadata of the file: resolution, size, dates, camera, lens, exposure,
  color profile, location (on request, with a small offline map), and for videos duration, frame rate, codecs and bitrate. If the photo has been analysed it also says, in a few words, what it shows (and any pet in it).
- **Captions** you write for a photo (searchable, kept only in eikon's database) and **changing the date or the location** written in a JPEG, PNG or WebP file, with the system's own confirmation, a safety copy, a byte-for-byte check
  and undo ([docs/EDITING.md](docs/EDITING.md)).

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
  backs off when the phone gets warm, and shows its progress. The photos on screen are analysed first. Hidden photos are never searchable.

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
- Limits, stated up front: photos only; other apps see the original until you save a copy (Share sends the edit, without metadata); recipes are lost if the app's data is cleared. See [docs/EDITING.md](docs/EDITING.md).

**Backup to a server of your own**
- Copies photos and videos to **Immich** (what Umbrel installs for Android) or to **Nextcloud / any WebDAV server**, over TLS, only on the network you allow, and only after you turn it on and confirm. It **never deletes or overwrites** anything, on the server or the phone; files the server already has are recognised, not sent again.
- eikon has the `INTERNET` permission (Android grants it at install, there is no runtime prompt), so **eikon itself keeps the promise**: nothing connects until you switch on "Allow eikon to use the network", which the first-run screens and Settings both offer, and every connecting path checks that switch. A certificate that is not from an authority your phone knows can be pinned after you compare its fingerprint. The key or password is encrypted under an Android Keystore key. See [docs/BACKUP.md](docs/BACKUP.md).

**People and pets**
- **People**: eikon finds faces on the phone and *groups* the ones that look alike. It does not identify anyone and
  never compares faces with anything outside your library; a group has a name only when you type it. You can name,
  rename, favorite, hide, merge two groups and split photos out of a group; **two groups given the same name become one**. Names work in Search ("foto di Giulia").
  Face data is biometric data: see [docs/PRIVACY.md](docs/PRIVACY.md).
- **Pets**: Dogs and Cats collections, from what the photos show. Other animals are not attempted.

**Platform**
- Works with Android 11 and newer, including Android 14+ "selected photos" access. Sharing, deleting
  and favoriting go through the platform's own confirmation dialogs.
- **First-run screens** say what eikon is, what each permission is for and that nothing depends on anything outside your phone, and ask (Allow / not now) for photo locations and for the network.
- Light, dark or system theme; **27 languages**: English, the other 23 official languages of the European Union (Italian included), Chinese (Simplified), Russian and Japanese, chosen by the phone or per app in the system settings. Search understands English and Italian ([docs/TRANSLATING.md](docs/TRANSLATING.md)); TalkBack labels.
- **Open from the camera**: a photo you have just taken opens in eikon's viewer, inside your library ("open with", and the camera's review action); one that is not in the library yet is shown on its own with a button to open it there.
- A small **Support** section at the bottom of Settings: eikon is free and has no ads; a Lightning address is there if you want to send a few sats. Nothing is contacted by the app.

Not implemented: restoring from the backup, and backup of edits, albums and the hidden list. The "selfie" filter is missing because Android exposes
nothing reliable to detect it; there is no thumbnail scrubber for video, no music in memories, and animated GIFs show their first frame.

## Privacy

- eikon has the `INTERNET` permission for one feature only, the backup to a server you run, and **does not use it until you allow it**: the network switch is off by default, and it is checked at every place that could connect (scheduling, the worker, the runner, creating a connection). TLS only, your server only. There is no Google Play services, Firebase or ML Kit dependency; the build fails if the manifest allows unencrypted traffic or has any permission that is not on a reviewed list.
- Earlier, 1.0.0 shipped a second APK with no network permission at all. 1.1.0 is a single APK: the operating system no longer stops a connection by itself, so the guarantee is now the app's own switch plus a build-checked permission list; if you want the stronger guarantee back, do not allow the network or use the [1.0.0](CHANGELOG.md) release.
- No account, no analytics, no crash reporting.
- Android backups of the app's data are disabled.
- **The library's database is encrypted** (SQLCipher, AES-256) with a key held by the Android Keystore; an older readable database is converted safely at the first start. Hidden and Recently deleted block screenshots and the recent-apps preview while open, and a setting does it everywhere. Details and limits in [docs/PRIVACY.md](docs/PRIVACY.md).
- Edits are recipes in the private database; **your files are never modified**. A copy you save keeps the original's date, camera details and (if you allowed it) location.
- The local index holds file names, dates, sizes and folders. If you turn on the analysis, it also holds
  where your photos were taken, the text found in them, a numeric description of what they show, where the
  faces are with a numeric description of each, and fingerprints to find copies; that is why every step is off until you choose. All of it is
  deleted when you uninstall the app or revoke photo access.
- The build fails if the merged manifest has a permission that is not on the reviewed list, or allows cleartext traffic, so a library update cannot add one silently.

Details, including the limits of these guarantees, are in [docs/PRIVACY.md](docs/PRIVACY.md).

## Building

Requirements: JDK 17 or newer, and the Android SDK with the API 37 platform and matching build tools.

The first build **downloads about 265 MB of machine-learning models** from Hugging Face and GitHub (OpenCV Zoo), each
pinned to a commit and checked against a SHA-256 (see [app/model-manifest.tsv](app/model-manifest.tsv)); they are not
in this repository. They make the release APK about 310 MB. This is the only time the build needs those servers; the
app itself connects only to the server you configure for the backup, and only if you allow it.

```sh
git clone https://github.com/Lwb89dev/eikon-gallery.git
cd eikon-gallery
export ANDROID_HOME=/path/to/Android/Sdk   # or create local.properties with sdk.dir=...

./gradlew :app:assembleDebug               # debug APK in app/build/outputs/apk/debug
./gradlew :app:testDebugUnitTest           # unit tests (JVM), including the backup's network tests
./gradlew :app:lintDebug                   # Android lint
./gradlew :app:assembleRelease             # R8-shrunk, unsigned release APK; also runs verifyNetworkPermissionsRelease
```

To try the real (fast) build on a connected phone, install the release variant signed with the debug
key: `./gradlew :app:installRelease -Peikon.signReleaseWithDebugKey` (an APK signed with another key cannot be installed over one signed with the first, so uninstall that one first). Debug builds are several times
slower because Compose runs unoptimized (on one test phone, scrolling had a 57 ms median frame time in
debug versus 6 ms in release), so judge smoothness on the release build.

The debug build installs as `app.eikon.gallery.debug`, next to a release build. The release build is
unsigned: signing configuration is deliberately not part of the repository. `local.properties` and
Gradle/Android caches contain machine-specific paths and must not be committed; `.gitignore` excludes
them.

### Tests

- **JVM unit tests** (`./gradlew :app:testDebugUnitTest`, 845 tests, including the backup's own network tests against a local server): the sync engine, every library and
  search query run against a real SQLite including full-text search (checking, for instance, that each
  item lands in the right date section), the database migrations, the encryption of the database (the plan, the copy of every table, every failure path), the search query parser, the offline
  place lookup, the analysis runner (retries, resuming, pausing, heat, a model that cannot load), the edit renderer and recipe format, timeline
  layout, classification heuristics, EXIF formatting, grid sources, locks and more. Some tests **run the real
  models** on the JVM with ONNX Runtime and compare them with reference tooling (a tokenizer, ONNX Runtime for Python,
  OpenCV): a phrase in Italian or English must find the right one of four public-domain photos, and the face
  detector, alignment and face vectors must match OpenCV's. Running the tests fetches the models first.
- **Instrumented tests** (`./gradlew :app:connectedDebugAndroidTest`, needs a device or emulator):
  album and hidden-media behavior on Room, the queries on the device's own SQLite, the real database
  migration, the encrypted database with SQLCipher and the Keystore (conversion of a readable database, wrong key, lost key), the edit renderer on Android bitmaps, the analysis pipeline, and the image and face models read out of the installed APK, which also
  report how long each step takes on the phone (written, not yet run). They use an in-memory database and never touch app data. Note that Gradle
  uninstalls the debug app when they finish.

Some behaviors can only be confirmed on a device, for example unlocking Hidden with real
authentication and the system trash dialogs; [docs/ROADMAP.md](docs/ROADMAP.md) says what has and has
not been checked that way.

## Project layout

```
app/src/main/java/app/eikon/gallery/
  core/        dependency injection, theme, image loading, security
  data/        Room index (encrypted, `data/db/encryption`), MediaStore access and sync, background analysis, OCR, offline places and trips,
               image/text embeddings and semantic search, faces and people, duplicates, memories, edits and export, metadata, settings
  domain/      plain models and pure logic (timeline layout, search parser, map geometry, trip and memory rules, the edit recipe and renderer)
  feature/     library, search, collections, people, places, trips, memories, duplicates, edit, viewer, info, trash, security, settings
tools/         scripts that build bundled assets (the world map)
app/src/main/assets/   place data (GeoNames) and OCR language data; see NOTICE.md
app/model-manifest.tsv the machine-learning models fetched at build time (URL and SHA-256 of each)
docs/          architecture, indexing, editing, performance, accessibility, privacy, ML notes, backup, translating, releasing, roadmap
```

## Documentation

[Architecture](docs/ARCHITECTURE.md) · [Privacy](docs/PRIVACY.md) · [Indexing](docs/INDEXING.md) ·
[Machine learning](docs/ML.md) · [Editing](docs/EDITING.md) · [Performance](docs/PERFORMANCE.md) · [Accessibility](docs/ACCESSIBILITY.md) · [Backup](docs/BACKUP.md) · [Translating](docs/TRANSLATING.md) · [Roadmap](docs/ROADMAP.md) · [Releasing](docs/RELEASING.md) · [Changelog](CHANGELOG.md)

## License

eikon is released under the [MIT License](LICENSE). Third-party libraries and bundled data keep their
own licenses; see [NOTICE.md](NOTICE.md).
