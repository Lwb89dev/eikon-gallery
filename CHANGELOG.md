# Changelog

## 1.0.0 — 2026-09-26

First release. eikon is a local-first, privacy-first photo and video gallery for Android (11 and newer), built natively with Kotlin and Jetpack Compose, inspired by the interaction model of Apple Photos.
It reads your photos where Android already keeps them (MediaStore), never copies them (unless you set up the optional backup to a server of your own), has no account and no analytics, and in its standard build has **no internet permission at all**.

### Two builds, one app

| File | What it is |
| --- | --- |
| `eikon-1.0.0-arm64-v8a.apk` | **Standard.** No `INTERNET` permission (the build fails if it ever appears) and no network code. |
| `eikon-1.0.0-backup-arm64-v8a.apk` | The same app plus the **backup to your own server** (Immich, Nextcloud / WebDAV). It is the only one with the network permission, used for that backup and nothing else. |

Both have the same application id and are signed with the same key, so one installs over the other and keeps your library, albums and edits. Each is about 310 MB, mostly the on-device machine-learning models. arm64-v8a phones only (nearly every phone since 2017).
Certificate SHA-256: `97:B2:F7:23:0B:82:4C:C9:85:A0:5F:F3:EE:DD:A9:4E:8B:62:40:14:09:6F:61:01:B3:6C:27:9C:77:64:2F:EF`

### Library
- Chronological library that opens instantly and scales to very large collections: served from a local index in pages, never loaded whole into memory.
- Date headers per day or month, a fast scroller with a month/year bubble, pinch to change grid density (2 to 7 columns), sorting by date taken or date added.
- Combinable filters: photos or videos, favorites only, edited only, and one kind (screenshots, screen recordings, panoramas, RAW).
- Multi-select by tap or long-press-and-drag, then share, favorite, add to album, hide or delete (deleting and favoriting go through Android's own confirmation dialogs).
- Opening a photo grows it from its thumbnail; closing it shrinks it back into place. "Open with" from other apps shows a picture without adding it to the library.

### Viewer and details
- Full-screen viewer: swipe between items, pinch and double-tap zoom, immersive mode, drag down to close, drag up for details. Video playback with seek and mute.
- Info panel with the real metadata of the file: resolution, size, dates, camera, lens, exposure, color profile, location (on request, with a small **offline map**), and for videos duration, frame rate, codecs and bitrate.
  If the photo has been analysed it also says, in a few words, what it shows and any pet in it.
- **Captions** you write for a photo (searchable, kept only in eikon's database, never written into the file).
- **Change the date or the location** written inside a JPEG, PNG or WebP file, with a safety net: the system asks first, eikon works on a copy, checks the result byte for byte, restores the original if anything fails, and offers undo.

### Collections
- Automatic collections (Favorites, Recently added, Videos, Screenshots, Screen recordings, Panoramas, RAW), device folders and your own albums. Albums are virtual: files are never moved or copied.
- **Hidden** photos stay out of the Library, Collections, search, places, people, trips, memories and duplicates, behind fingerprint, face or screen lock. **Recently deleted** is the system trash with days remaining, restore and delete-for-good (optionally locked too).

### Search
- One search box that understands dates in Italian and English ("agosto 2025", "estate 2025", "14 agosto", "yesterday", "last month"), photos or videos, file names, places ("foto a Roma"), people you named ("foto di Giulia"), captions and text found inside photos (a receipt, a document, a screenshot). It shows how it read what you typed.
- **By what photos show**: "cane", "tramonto", "a red car", "una foto sulla neve", in Italian or English, matched by an on-device image model (CLIP). It combines with everything else: "cane sulla neve 2024".
- Places are resolved **offline** from a bundled copy of GeoNames data. Text in photos is read on the phone by Tesseract, in English and Italian.
- Place, text, content and people search need a background analysis of your library. **It is off by default**: you switch on each step in Settings, choose whether it may run only while charging, and can pause it. It works in short slices, resumes after interruptions, waits in Battery Saver, backs off when the phone gets warm, and analyses the photos on screen first.

### Smart collections
- **Places**: photos by country, region and city, and a **map** drawn offline from bundled country outlines: no map service, no tiles, no position ever sent.
- **Trips**: stretches of days spent far from where most of your photos are taken, found by rules you can read (100 km, two days or a busy day, at least 10 photos), each showing why it counts.
- **Memories**: "On this day", "A year ago", trips and weekends away, a season, a named person in a year, as a slideshow. Hide a memory, ask for fewer like it or for less of a person, or leave out a date.
- **Duplicates and similar shots**: identical files and copies of the same picture, and shots of one moment that look alike. eikon only finds them; you choose, and photos go to Recently deleted through Android's own confirmation.
- **People**: faces are found and grouped on the phone; nothing is identified and nothing is compared with anything outside your library. Name, rename, favorite, hide, merge two groups, split photos out of a group. **Pets**: Dogs and Cats collections.

### Editing
- **Non-destructive**: an edit is a small recipe kept in eikon's database and drawn over the photo; the file is never changed and there is no "replace the original".
- Auto enhance, exposure, brightness, contrast, highlights, shadows, black point, saturation, vibrance, temperature, tint, sharpness, vignette, crop (free, 1:1, 4:3, 3:2, 16:9), rotate, flip, straighten, perspective, and eight filters with a strength.
- Edited photos look edited in the grid and the viewer, where a chip shows the original. **Save a copy** writes a new JPEG next to the original. **Copy edits** and **Paste edits** apply one photo's look to many at once. Sharing an edited photo shares the edit, without metadata.

### Backup to a server of your own (backup build only)
- Copies photos and videos to **Immich** (what Umbrel installs for Android) or **Nextcloud / any WebDAV server**, over TLS only, on the network you allow, only after you turn it on and confirm.
- Never deletes or overwrites anything, on the server or the phone; files the server already has are recognised, not sent again. Hidden photos are left out unless you say otherwise. A certificate from an authority your phone does not know can be pinned after you compare its fingerprint.

### Privacy and security
- Standard build without `INTERNET`, enforced by the operating system and checked by the build. No account, no analytics, no crash reporting. Android backups of the app's data are disabled.
- **The library's database is encrypted** (SQLCipher, AES-256) with a key held by the Android Keystore. A database from an earlier version is converted safely: copied, checked, and only then wiped.
- The backup's password or API key, and its server address, login and pinned certificate, are sealed under the Keystore too.
- Hidden and Recently deleted mark the window secure (no screenshots, blank recent-apps card) while open; a setting does the same for the whole app. Shared pictures live an hour. Logs carry no content.
- Every analysis step (places, text, what photos show, people, duplicates) is off until you turn it on.
- Light, dark or system theme; English and Italian; TalkBack labels; an in-app screen with the open-source licenses.

### What has and has not been checked
- The gallery foundation and albums (the first two development phases) were used on a real phone.
- Everything else is covered by 705 automated tests (764 with the backup build's), several of which run the real machine-learning models and compare them with reference tools, and the SQL is run against a real SQLite. Lint is clean and both builds compile in release mode.
- **Not run on a device yet**: the newer screens, the background analysis on a real library (its speed, battery and heat are unmeasured), editing performance, the metadata writer on real files, the SQLCipher database with a real library, the backup against a real Immich or Nextcloud server. Treat 1.0.0 as feature-complete but not field-tested; keep your usual backups.

### Known limits
No restore from the backup, and edits, albums and the hidden list are not part of it; no "selfies" filter (Android exposes no reliable signal); videos are not analysed or edited; animated GIF and WebP show their first frame; no music in memories; a file that takes longer than one 8-minute background run to upload is not resumed.
Details: [docs/ROADMAP.md](docs/ROADMAP.md) and [docs/PRIVACY.md](docs/PRIVACY.md).
