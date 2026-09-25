# Privacy

This document says what eikon does with your data today, and where its guarantees stop.

## What eikon stores on the device

| Data | Where | Content |
| --- | --- | --- |
| Library index | app-private database `eikon.db` | file name, MIME type, dates, dimensions, duration, size, folder, favorite flag, category flags. **No pixels, no thumbnails, no location, no faces, no text.** |
| Albums and hidden list | same database | album names and which media ids belong to them; ids of hidden media. Kept when the library index is cleared |
| Settings | app-private DataStore | theme, grid density, filter, sort |
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

## Permissions

| Permission | Why |
| --- | --- |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` (Android 13+), `READ_EXTERNAL_STORAGE` (up to 12L) | show your photos and videos |
| `READ_MEDIA_VISUAL_USER_SELECTED` (Android 14+) | lets you share only some photos; eikon then works with that subset |
| `ACCESS_MEDIA_LOCATION` | Android hides GPS from apps without it. Requested only when you tap "Allow" in the info panel; declining changes nothing else |
| `ACCESS_NETWORK_STATE` | **not requested by eikon**: added to the merged manifest by the Media3 player library. It cannot send or receive data. Removing it is a tracked follow-up, to be done together with an on-device playback check |

## Location

GPS coordinates are read from the file only when the info panel is open and you granted
`ACCESS_MEDIA_LOCATION`. They are shown, then dropped. They are not written to the index. Nothing
about location history is kept.

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
