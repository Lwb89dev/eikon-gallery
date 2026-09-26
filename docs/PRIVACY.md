# Privacy

This document says what eikon does with your data today, and where its guarantees stop.

## What eikon stores on the device

| Data | Where | Content |
| --- | --- | --- |
| Library index | app-private database `eikon-encrypted.db`, **encrypted** (see below) | file name, MIME type, dates, dimensions, duration, size, folder, favorite flag, category flags. **No pixels and no thumbnails.** |
| What analysis learned | same database | for each photo, only for the steps you turned on: where it was taken (coordinates and the nearest city, resolved offline); the words found in it; a 512-number description of what it shows; where each face is and a 128-number description of it, with the group ("person") the face was put in and the name you gave that group. **Everything here is off by default**: only created if you turn those steps on in Settings; wiped when you revoke photo access |
| Albums and hidden list | same database | album names and which media ids belong to them; ids of hidden media. Kept when the library index is cleared |
| Captions and what a file said before | same database | the captions you wrote (searchable; never written into the photo's file) and, if you changed a photo's date or location from the info panel, what the file said before, so the change can be undone. Kept when the library index is cleared |
| Edits | same database | for each edited photo, a few lines of text: the values of the sliders, the filter and the crop (see [EDITING.md](EDITING.md)). No pixels; the photo's file is never changed. Kept when the library index is cleared, because it cannot be rebuilt |
| Settings | app-private DataStore | theme, grid density, filter, sort, the screen-privacy switch, and the look last copied with "Copy edits" (a few lines of text) |
| Backup settings (`backup` build) | app-private DataStore | the switches of the backup, and, **sealed under the Android Keystore**, the server address, the login name, the pinned certificate and the last message of a run |
| Keys | app-private preferences, sealed by the Android Keystore | the key of the library's database, and the backup's password or API key. The Keystore keys cannot be read out of the phone, not even by eikon |
| Sync bookkeeping | app-private DataStore | last MediaStore generation and version, access level |
| Memory cache | RAM only | decoded thumbnails; nothing is written to a disk cache |
| Shared edits | app-private cache folder | only while sharing an edited photo: the drawn picture without metadata, removed after an hour and every time the app starts |
| Safety copy (only if a change of date or location is interrupted) | app-private files | a full copy of the photo as it was, kept only until the change is finished or undone (see below) |

All of it lives in the app's private storage: other apps cannot read it, and it disappears when eikon is
uninstalled. Revoking photo access makes eikon delete the index at the next launch.

## Encryption at rest

The library's database (everything in the first rows of the table above: the index, what the analysis learned, albums, edits, captions) is **encrypted with SQLCipher (AES-256)**. The key is 256 random bits made on
the phone at first launch; it is stored sealed under a key of the **Android Keystore**, which cannot be read out of the phone (on phones with secure hardware, it never leaves it). What this protects against: someone who gets a copy of
the app's files (a forensic image, a rooted phone, a cloud copy that should not exist) but cannot use the Keystore of that phone. What it does not: someone who can unlock the phone and open eikon.

- An **older, readable database** (from before the encryption) is converted at the first start of this version: eikon copies it into an encrypted one, checks the copy (row counts, SQLite's own check, and that Room accepts it),
  and only then overwrites and deletes the readable one. If anything fails, nothing is deleted, the app works with what it had and **Settings says so** ("could not be encrypted"), and it tries again the next time it starts.
- If the Keystore key is ever lost (it should not be), the encrypted file cannot be opened by anyone; eikon then starts a new database and tells you once. Albums, edits, captions and names are lost; your photos are untouched.
- Overwriting the old file with zeros does not guarantee that flash storage forgets it (that is up to the file system); it makes the old content much harder to find.
- Not encrypted: your photos and videos (they stay where Android keeps them), the small settings file (theme, grid density), and the bundled models.

The index and settings are excluded from Android cloud backup and device-to-device transfer
(`allowBackup=false` plus explicit `dataExtractionRules`/`fullBackupContent`), and are rebuilt from
MediaStore after a restore.

## What eikon never does

- The `standard` build declares **no `INTERNET` permission**. Without it Android refuses every network connection
  from the app, so this is enforced by the OS, not by promise, and the build fails if the permission ever appears. Only the separate `backup` build has it, and uses it only
  for the backup you set up (see [BACKUP.md](BACKUP.md)).
- No account, no analytics, no crash reporting, no advertising identifiers. eikon writes almost nothing to the system log, and never a file name, a place, a word or a key (a failure is logged as the kind of error only).
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
| `INTERNET` (**`backup` build only**) | copy your photos to the server you configured, over TLS, and for nothing else. Unencrypted traffic is forbidden by the build, which also fails if any permission appears that is not on the reviewed list |
| `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED` | **not requested by eikon itself**: added to the merged manifest by WorkManager (background analysis, resumed after a reboot) and Media3. None of them can send or receive data; only `INTERNET` could, and it is absent from the `standard` build |

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

It lives in the encrypted database (see "Encryption at rest"), so a copy of the phone's files is unreadable without the phone's Keystore; anyone who can unlock the phone and open eikon can still see it.

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
photos. All of it is in the encrypted database (see "Encryption at rest"); it is protected against a copy of the files, not against someone who can unlock the phone and open eikon.

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

## Opening a picture from another app

Any app can ask eikon to show one picture ("Open with"). eikon accepts only pictures handed over as a `content://` address, reads that one picture to show it, and does not add it to the library, analyze it or remember it. Videos and
files are not offered. That window does not run the library sync.

## Editing and saved copies

An edit changes nothing in the photo's file: it is a recipe in eikon's private database, drawn over the original (see [EDITING.md](EDITING.md)). It holds no picture and no personal
data beyond the fact that you edited that photo. Like the rest of the database it is excluded from Android backups, so it is lost if you clear the app's data or uninstall.

**Save a copy** creates a new JPEG in your library, next to the original. The copy keeps the original's date and camera details and, **if eikon holds the "read photo
locations" permission, its location**, so that it sits at the right place in the timeline; sharing the copy shares that metadata like any other photo. eikon never deletes or
replaces the original.

**Sharing an edited photo shares the edit**, drawn into a temporary picture in eikon's private cache folder and handed to the app you choose through a `FileProvider` (not exported, one file granted per share). That picture carries no metadata: no location and no camera
details. Temporary pictures older than an hour are removed the next time an edited photo is shared and every time eikon starts. Photos without an edit are shared as the files they are.

## Changing a photo's date or location

From the info panel you can change the date or the location written *inside* a photo (only JPEG, PNG and WebP). It is the one place where eikon writes to a file of yours, so it does so with a safety net (docs/EDITING.md): it asks the system first, works on a copy,
checks the result byte for byte, and puts the original back if anything fails. **While it works, a full copy of the photo is kept in eikon's private storage** (not encrypted: it is a photo file); it is deleted as soon as the change is finished or undone, and if the app is
killed in the middle it stays until the next time you open that photo's info panel, which offers to restore it. A caption never touches a file.

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

That is a **gate on the screen, not encryption of the files**. The photo files stay in the phone's normal shared
storage, exactly where they were, so other gallery or file apps with photo access can still see them,
and anyone with a full device image can read them. What eikon *does* protect: while Hidden or Recently deleted is open, the window is **marked secure**, so Android blocks screenshots and shows a blank card in the recent-apps list; and
the database that lists what is hidden is encrypted. A switch in Settings ("Hide eikon in recent apps and block screenshots") does the same for the whole app. On a phone with no screen lock at all there is
nothing to check against: Hidden then opens without asking, and says so. Recently deleted can
optionally be gated the same way (off by default).

## Limits of these guarantees

- The media files are not encrypted; only what eikon itself stores is. Anyone who can unlock the phone can open
  eikon's ungated screens and see everything eikon can see. Outside Hidden and Recently deleted, screenshots and the recent-apps card show library content unless you turn the Settings switch on.
- Media files themselves stay in shared storage under Android's normal protections.
- A phone with root access *and a running, unlocked* eikon can be made to read the database through the app: the encryption protects files at rest, not a live process.

## Backup to a home server (the `backup` build only)

Full details, including what was and was not checked, are in [BACKUP.md](BACKUP.md). The rules it keeps:

- **Off until you turn it on**, and it asks first, saying how many photos go to which server. Nothing leaves the phone before that.
- **Your server only**, software you run (Immich or Nextcloud/WebDAV): no third-party service, no account of ours, no analytics.
- **TLS only**; the phone's own certificate authorities (not ones you installed), or the one certificate you pinned after comparing its fingerprint. Redirects are never followed.
- **The credential**, and the server address, the login and the pinned certificate, are encrypted under an Android Keystore key, excluded from backups, never logged, and thrown away with everything else when the server changes.
- **What is sent** is the files, their names and dates, a favourite flag, a checksum and a random installation name. Not sent: places, text, what photos show, faces, fingerprints, albums, hidden list, edit recipes. **A file's own metadata, including its location, goes with it**; Android hides the
  location unless you allow the photo-location permission.
- **Hidden photos are left out** unless you include them.
- **It never deletes or overwrites** on the server or on the phone.
- The list of what was sent is app-private, cleared when the server changes or photo access is revoked.
- Anyone who can unlock the phone can open eikon and turn the backup off or on. What is on your server is protected as well as your server is.
