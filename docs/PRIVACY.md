# Privacy

This document says what eikon does with your data today, and where its guarantees stop.

## What eikon stores on the device

| Data | Where | Content |
| --- | --- | --- |
| Library index | app-private database `eikon.db` | file name, MIME type, dates, dimensions, duration, size, folder, favorite flag, category flags. **No pixels and no thumbnails.** |
| What analysis learned | same database | for each photo, only for the steps you turned on: where it was taken (coordinates and the nearest city, resolved offline); the words found in it; a 512-number description of what it shows; where each face is and a 128-number description of it, with the group ("person") the face was put in and the name you gave that group. **Everything here is off by default**: only created if you turn those steps on in Settings; wiped when you revoke photo access |
| Albums and hidden list | same database | album names and which media ids belong to them; ids of hidden media. Kept when the library index is cleared |
| Edits | same database | for each edited photo, a few lines of text: the values of the sliders, the filter and the crop (see [EDITING.md](EDITING.md)). No pixels; the photo's file is never changed. Kept when the library index is cleared, because it cannot be rebuilt |
| Settings | app-private DataStore | theme, grid density, filter, sort, and the look last copied with "Copy edits" (a few lines of text) |
| Sync bookkeeping | app-private DataStore | last MediaStore generation and version, access level |
| Memory cache | RAM only | decoded thumbnails; nothing is written to a disk cache |

All of it lives in the app's private storage: other apps cannot read it, and it disappears when eikon is
uninstalled. Revoking photo access makes eikon delete the index at the next launch.

The index and settings are excluded from Android cloud backup and device-to-device transfer
(`allowBackup=false` plus explicit `dataExtractionRules`/`fullBackupContent`), and are rebuilt from
MediaStore after a restore.

## What eikon never does

- It declares **no `INTERNET` permission**. Without it Android refuses every network connection
  from the app, so this is enforced by the OS, not by promise.
- No account, no analytics, no crash reporting, no advertising identifiers.
- No map tiles: the info panel shows coordinates and hands them to *your* maps app only when you tap
  "Open in a maps app".
- No online place lookup: turning coordinates into "Rome, Lazio, Italy" uses place data bundled inside the app
  (GeoNames, see [NOTICE.md](../NOTICE.md)); the coordinates never leave the phone.

## Permissions

| Permission | Why |
| --- | --- |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` (Android 13+), `READ_EXTERNAL_STORAGE` (up to 12L) | show your photos and videos |
| `READ_MEDIA_VISUAL_USER_SELECTED` (Android 14+) | lets you share only some photos; eikon then works with that subset |
| `ACCESS_MEDIA_LOCATION` | Android hides GPS from apps without it. Requested only when you tap "Allow" (in the info panel, or in Settings to search by place); declining changes nothing else except that place search has nothing to work with |
| `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` | **not requested by eikon itself**: added to the merged manifest by WorkManager (background analysis, resumed after a reboot) and Media3. None of them can send or receive data; only `INTERNET` could, and it is absent |

## Location and text found in photos

If you turn the analysis steps on (they are **off by default**), eikon stores, on the phone only, where each geotagged photo was taken
(coordinates plus the nearest city) and the text it could read inside each photo. That is a real record of
places you have been and things you have photographed (receipts, documents, screenshots), which is why:

- both steps are off until you switch them on in Settings, each has its own switch, and analysis can be paused entirely;
- place analysis does nothing until you grant `ACCESS_MEDIA_LOCATION`;
- it all lives in the app's private storage, is excluded from Android backups, and is deleted when you revoke
  photo access or uninstall the app;
- **hidden photos are excluded from search** and their text and places never appear in results;
- nothing derived from your photos is ever sent anywhere.

Like everything else, it is **not encrypted** (see the limits below), so it is readable by anyone with root
access or a full image of the phone.

## What a photo is described as, and faces

Two more analysis steps are **off by default** and each has its own switch:

- **What photos show.** A photo becomes 512 numbers (one signed byte each), a numeric description that lets a phrase like
  "dog" or "sunset" be matched to it. It is not a caption, and the photo cannot be rebuilt from it.
- **People.** Each face becomes a box and 128 numbers, and faces that look alike are grouped. **This is biometric data**
  in the sense of the word: a face description can be used to tell whether two photos show the same person. So:
  it is computed on the phone by models bundled in the app, stored only in the app's private database, excluded from
  backups, never sent anywhere (there is no network permission to send it with), and deleted when you revoke photo access or
  uninstall. eikon only groups faces **within your own library**; it never compares them with anything else, has no list
  of known people, and a group only has a name if you typed one. Hiding a person removes them from the People list and from name
  searches, but their photos stay in the Library; hide the photos to take them out of the Library.

Photos you have hidden do not count towards a person's photos or picture, and never appear in a search or in a person's
photos. Like everything else, none of this is encrypted (see the limits below).

## Places, trips, memories and duplicates

- **Places and trips reveal where you go.** A list of countries and cities, a map with a marker per group of photos, and trips (stretches away from where most of your photos are taken)
  are a picture of your movements. They are drawn from the positions the Places analysis stored (off by default, needs `ACCESS_MEDIA_LOCATION`). The map is
  drawn on the phone from country outlines bundled in the app, with no map tiles, no map service and no network permission: nothing about where you have been is ever sent.
  "Home" is not a stored place or an address: it is worked out each time from where most photos were taken.
- **Memories** are made from dates, trips and the people you named, on the phone. Photos you hid never appear in them, in trips, in places or in duplicate lists.
  What you tell Memories (hide one, show fewer of a kind, show less of a person, leave out a date) is stored on the phone and can be undone from the menu of Memories.
- **Duplicates** are found from fingerprints of your photos: a SHA-256 of the file (only for files that share their size with another) and a 64-bit description of
  the picture; similar shots reuse the description stored for search. The fingerprints are private, excluded from backups and deleted when you revoke photo access. eikon
  never removes anything by itself: a photo leaves only when you confirm in Android's own dialog, and goes to Recently deleted.

## Editing and saved copies

An edit changes nothing in the photo's file: it is a recipe in eikon's private database, drawn over the original (see [EDITING.md](EDITING.md)). It holds no picture and no personal
data beyond the fact that you edited that photo. Like the rest of the database it is excluded from Android backups, so it is lost if you clear the app's data or uninstall.

**Save a copy** creates a new JPEG in your library, next to the original. The copy keeps the original's date and camera details and, **if eikon holds the "read photo
locations" permission, its location**, so that it sits at the right place in the timeline; sharing the copy shares that metadata like any other photo. eikon never deletes or
replaces the original.

**Sharing an edited photo from eikon shares the original file**, not the edited picture, because the edit only exists inside eikon. To share the edit, save a copy first.

## Deleting and sharing

- Delete moves items into the **system trash** after the system's own confirmation. Android empties it
  automatically after about 30 days. eikon has no screen to browse or restore the trash yet (planned for
  Phase 2); until then restoring depends on other apps that support Android's trash.
- Sharing hands the file to the app you choose through the Sharesheet. Android may strip location from
  what the receiver reads unless the receiver holds `ACCESS_MEDIA_LOCATION`, but this is not a
  guarantee: **shared photos can still carry location and camera metadata.** A "strip metadata when
  sharing" option is not implemented.

## Hidden and Recently deleted: what the protection really is

Hidden items are excluded from the Library, Collections, albums and (later) search, and opening Hidden
asks for your fingerprint, face or screen lock through Android's own prompt. eikon never sees the
biometric or the PIN; Android only answers yes or no. The app locks again when you leave the screen or
the app.

That is a **gate on the screen, not encryption**. The photo files stay in the phone's normal shared
storage, exactly where they were, so other gallery or file apps with photo access can still see them,
and anyone with a full device image can read them. On a phone with no screen lock at all there is
nothing to check against: Hidden then opens without asking, and says so. Recently deleted can
optionally be gated the same way (off by default).

## Limits of these guarantees

- Nothing is encrypted: not the library index, not the media. Anyone who can unlock the phone can open
  eikon's ungated screens and see everything eikon can see. eikon does not set `FLAG_SECURE`, so
  screenshots and the recent-apps thumbnail show library content.
- The index reveals which files exist (names, dates) to anyone with root access or a full device image.
- Media files themselves stay in shared storage under Android's normal protections.

## Planned: backup to a home server

A later phase will back photos up to a server you run yourself. Requirements set now: strictly
opt-in (adding the network permission only in that phase, documented here at that time), TLS only,
credentials kept in the Android Keystore, no third-party services, and the same "nothing leaves the
device unless you configured it" rule for any machine-learning data (OCR text, embeddings, faces).
