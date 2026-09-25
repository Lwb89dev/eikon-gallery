# eikon

A local-first, privacy-first photo and video gallery for Android, inspired by the interaction model of
Apple Photos and built natively with Kotlin and Jetpack Compose.

eikon reads the photos and videos where Android already keeps them (MediaStore). It never copies them,
has no account, no analytics and, in this version, no internet permission at all.

> **Status: Phases 1 and 2 (gallery foundation, albums and utilities).** Everything listed under
> "Implemented" is built and tested as described under "Verification status" and in
> [docs/ROADMAP.md](docs/ROADMAP.md). Features that are not built yet are not shown in the app.

## Implemented

- Permission flow for Android 11 and newer, including Android 14+ "selected photos" access
  ("Select more" / "Allow all" when access is limited).
- Chronological library backed by a local index, so it opens instantly and scales to very large
  libraries (paged, never loaded into memory as a whole).
- Date headers per day or per month (coarser at dense grid settings), fast scroller with a
  month/year bubble.
- Pinch to change grid density (2 to 7 columns); sort by date taken or date added, either direction;
  filters: photos, videos, favorites, screenshots, screen recordings, panoramas, RAW.
- Multi-select (tap, or long-press then drag to select a range), share, favorite, delete.
- Full-screen viewer: swipe between items, pinch / double-tap zoom, pan, tap for immersive mode, drag
  down to close, drag up (or the button) for the info panel.
- Video playback (Media3 / ExoPlayer) with play/pause, seek, mute.
- Info panel with real metadata read from the file: name, type, resolution, megapixels, size, dates,
  color profile, camera, lens, focal length, aperture, shutter speed, sensitivity, exposure bias,
  flash, orientation, location (on request), and for videos duration, frame rate, codecs, bitrate,
  dynamic range.
- Light, dark or system theme; English and Italian; TalkBack labels.
- Sharing through the Android Sharesheet, deletion into the system trash, favorites through the
  system, all via the platform's own confirmation dialogs.
- **Collections**: automatic ones (Favorites, Recently added, Videos, Screenshots, Screen recordings,
  Panoramas, RAW), device folders (real MediaStore locations, read-only) and your own **albums**
  (virtual: create, rename, reorder, delete, add and remove photos; files are never moved or copied).
- Combinable **filters** in the Library: type (all/photos/videos), favorites only, and one kind
  (screenshots, screen recordings, panoramas, RAW).
- **Hidden**: hide photos from the Library, Collections and albums; opening Hidden asks for fingerprint,
  face or screen lock (see the security model in [docs/PRIVACY.md](docs/PRIVACY.md)). Optional, and can be
  removed from Collections entirely.
- **Recently deleted**: the system trash with days remaining, restore, and delete-for-good, through
  the platform's confirmation dialogs; optionally behind authentication.

## Not implemented yet

Search, OCR, people/pets, places, trips, memories, duplicates, editing, backup to a home server. See [docs/ROADMAP.md](docs/ROADMAP.md). The
"selfie" and "edited" filters are also missing because Android exposes nothing reliable to detect them.

## Privacy in one paragraph

No `INTERNET` permission, so the operating system itself prevents eikon from sending anything anywhere.
Android backups of the app's data are disabled. Location data is read only when you ask for it, is
shown and forgotten, and is never stored. The local index holds file names, dates, sizes and folders
and is deleted when you uninstall the app or revoke photo access. Details, including what this does
*not* protect against, are in [docs/PRIVACY.md](docs/PRIVACY.md).

## Building

Requirements: JDK 17 or newer, the Android SDK with the API 37 platform and matching build tools.

```sh
export ANDROID_HOME=/path/to/Android/Sdk      # or create a local.properties with sdk.dir=...
./gradlew :app:assembleDebug                  # debug APK in app/build/outputs/apk/debug
./gradlew :app:testDebugUnitTest              # unit tests
./gradlew :app:lintDebug                      # Android lint
./gradlew :app:assembleRelease                # R8-shrunk, unsigned release APK
```

To try the real (fast) build on a phone, use the release variant signed with the debug key:
`./gradlew :app:installRelease -Peikon.signReleaseWithDebugKey`. Debug builds are several times slower
(Compose runs unoptimized): measured on a Pixel 10, scrolling had a 57 ms median frame time in debug
versus 6 ms in release.

The debug build installs as `app.eikon.gallery.debug` so it can live next to a release build. The
release build is unsigned: signing configuration is deliberately not part of the repository.

`local.properties` and Gradle/Android caches must never be committed (they contain machine-specific
paths); `.gitignore` already excludes them.

## Verification status

| Check | State |
| --- | --- |
| Debug and release (R8) builds | passing |
| 102 JVM unit tests (sync engine, all library SQL run against a real SQLite, timeline layout, classifier, EXIF formatting, sharing, grid sources, locks, trash policy) | passing |
| 18 instrumented tests (albums, hidden, folder summaries, queries on the device's SQLite, the real 1 to 2 database migration) | passing on a Pixel 10 (Android 17) |
| Android lint | no errors |
| Manual use on a Pixel 10 | Library, scrolling, viewer, Info: checked by the developer. Collections, album create/add/remove/rename/delete, hiding, live detection of new photos and the Hidden lock prompt: driven on the device. **Not yet checked**: unlocking Hidden with real authentication, Recently deleted (system dialogs), restore and permanent delete |

Areas that can only be confirmed on a device and deserve attention: the system trash/favorite/restore
dialogs, opening Hidden and Recently deleted with real authentication, and gesture interplay in the
viewer and grid.

## Project layout

```
app/src/main/java/app/eikon/gallery/
  core/        dependency injection, theme, image loading (Coil), permission checks
  data/        Room index, MediaStore access and sync, metadata reading, settings
  domain/      plain models and pure logic (timeline layout, EXIF formatting)
  feature/     library, viewer, info, permissions, settings screens
docs/          architecture, indexing, privacy, ML notes, roadmap
```

## Third-party components

All runtime dependencies are open source under the Apache License 2.0: AndroidX (Compose, Room,
Paging, Navigation, Lifecycle, DataStore, ExifInterface, Core), Media3, Coil, Dagger/Hilt and
kotlinx.coroutines. Test-only: JUnit (EPL 1.0), sqlite-jdbc (Apache 2.0). Versions are pinned in
[gradle/libs.versions.toml](gradle/libs.versions.toml). No machine-learning models are bundled yet;
each one will be license-checked before it is added (see [docs/ML.md](docs/ML.md)).

## Assets

`assets/icon.png` is the source artwork of the launcher icon. The app uses a cropped, re-encoded copy
(`app/src/main/res/drawable-nodpi/ic_launcher_art.webp`) without the file's embedded metadata.

## License

No license has been chosen for this repository yet.
