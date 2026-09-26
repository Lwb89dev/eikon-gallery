# Backup to a server of your own

eikon can copy your photos and videos to a server **you** run, over an encrypted connection. It is opt-in and it only ever *adds*: eikon never
deletes, overwrites or changes anything on the server or on the phone because of it.

## One APK, and a switch you own

Version 1.0.0 was built twice: a `standard` APK with no `INTERNET` permission, so that Android itself stopped it from connecting anywhere, and a `backup` APK that had it. That kept a strong promise, but it made
people choose between two downloads and made every install after a switch start again. **From 1.1.0 there is one APK.** It holds the `INTERNET` permission (granted at install, with no prompt to refuse) and keeps the promise itself:

- **"Allow eikon to use the internet" is off by default.** The first-run screens explain what the network is for and offer it (Allow / not now); Settings, Backup has the same switch and turns everything below it on only when it is on.
- The switch is checked at **every place that could connect**: `BackupSettings.active` (used to decide whether to schedule anything), the worker, the runner and the creation of a connection (`BackupException.NetworkOff`). Each has a test that fails if it connects without consent.
- TLS only: `usesCleartextTraffic="false"` and a network-security config that forbids unencrypted traffic. Nothing but the server you configured is ever contacted.
- The build checks the merged manifest (`verifyNetworkPermissionsRelease`, run by `assembleRelease`): `INTERNET` is present, cleartext is off, and **every permission is on a reviewed list**, so a library update cannot add one silently.
- Someone who had 1.0.0's backup build with the backup on keeps it on after updating (the consent is inferred from it); anyone else starts with it off.

What is lost, honestly: with 1.0.0's `standard` APK the operating system made connecting impossible; now the guarantee is the app's own switch and the build check, which a reader of the code can verify but the operating system does not enforce. If you want the stricter guarantee, keep 1.0.0's `standard` APK.

```sh
./gradlew :app:assembleRelease                       # the APK; also runs verifyNetworkPermissionsRelease
./gradlew :app:testDebugUnitTest                     # includes the backup's network tests (against a local server)
./gradlew :app:installRelease -Peikon.signReleaseWithDebugKey   # to try it on a phone
```

## Which servers

Two kinds, behind one interface (`BackupTarget`), chosen in Settings:

- **Immich**: a self-hosted photo library with its own apps. This is what [Umbrel](https://umbrel.com) offers for Android backup. *Umbrel's own Photos app (umbrelOS 2.0) backs up from an iPhone through its own
  "Umbrel for iPhone" app, and its documentation names no protocol another app could use, so eikon cannot talk to it; on Umbrel, install Immich (or Nextcloud) from its App Store and point eikon at that.*
- **Nextcloud, or any WebDAV server** (Synology, `rclone serve webdav`, nginx/Caddy/Apache with DAV): plain files in a folder.

### Setting up

1. **Immich**: Account settings, *API keys*, *New API key*, with the permission `asset.upload` (or all). Paste the key into eikon. The address is the one you open Immich at (`https://photos.example.com`, with a port and path if it has them).
2. **Nextcloud**: Personal settings, *Security*, *Devices and sessions*, create a new **app password**. Use it, not your login password, with your login name. The photos go in a folder you name (default `eikon`).
3. In eikon, Settings, Backup: choose the kind, fill the form, **Save and test the connection**. The test reaches the server, checks the certificate, checks the credential (and for Immich that the key may upload) and, for Nextcloud, makes the folder.
4. Switch on **Allow eikon to use the internet** (if you did not in the first-run screens), then turn on **Back up my photos and videos**. eikon says how many will be copied and to which server, and waits for your yes.

Options: only on Wi-Fi (default on), only while charging, include videos (on), include hidden photos (**off**: hiding a photo was a choice about who sees it; if you include them they are as visible on the server as anything else).

## The server's certificate

A home server rarely has a certificate from an authority that ships with phones. eikon's rules:

- The phone's own list of authorities is used, like any app. **Authorities you installed on the phone are not trusted**, and plain `http://` is refused outright.
- If the certificate is not trusted, eikon **reads it without trusting it and without sending anything** (a handshake, then it closes) and shows you its **SHA-256 fingerprint**, who it was issued to and when it expires. Compare the fingerprint with your server's
  (`openssl x509 -noout -fingerprint -sha256 -in cert.pem`). Only if you press *Trust this certificate* is that one certificate accepted, **for that server only**, from then on. Nothing else about checking is switched off.
- A pinned certificate that the server changes (a renewal) is refused until you trust the new one; a pin is not a way to let a different certificate in.
- A server's own pinned certificate is accepted even if its name is not the address you use (a NAS reached by IP address). A pinned *authority* does not buy that: the certificate must still be for the right name.
- Redirects are **never followed**, so a credential can never be sent somewhere the address did not name; the message says where the server tried to send you.

## The credential

The API key or app password is kept **encrypted with AES-256-GCM under a key held by the Android Keystore** (it cannot be read out, not even by eikon), in a private file that is excluded from Android backups. Each value is sealed together with its name, so one cannot be
passed off as another. It is never logged and never put in an address. The **server address, the login name, the pinned certificate and the last message of a run** are sealed the same way (with the same key) inside the settings file, so the file says nothing about where your server is or who you are on it; a value that cannot be opened (the Keystore key is gone) reads as "not set", and a settings file written before this existed is still read, and sealed the next time a setting is saved. **Changing the address, the login, the folder or the kind of server throws the credential away**, together with the trust given to a certificate and the record of what was sent, and turns the backup off, so what was set
up for one server is never sent to another (a typo in the address cannot leak the key to a stranger's server).

## What is sent

Only the **file itself**, its name, the date it was taken, its modification date, whether it is a favourite (Immich), a SHA-1 of its bytes, and a random name for this installation (`eikon-` and eight characters, made once; it identifies nothing else). The file's own metadata travels inside it,
**including where it was taken if the file has it**. Android hides the location from apps without the photo-location permission, so with the permission eikon asks for the original, and without it the copy on the server has no location (Settings says so while the backup is on).

Not sent: anything eikon learned about your photos (places, text, what they show, faces, fingerprints), your albums, the list of hidden photos, edit recipes, your settings. **Edits are not part of the backup**: the original is. A copy made with *Save a copy* is an ordinary file and is backed up like any other.

## How it works

- A **queue** in the database (`backup_item`) says, per photo, whether it was sent and for which version of the file. Photos not sent, changed since, or refused fewer than three times are pending, **newest first**.
- Each photo is read once to find its exact size and **SHA-1**, then sent with a known length (no chunked upload, which servers behind a reverse proxy often mishandle) streaming from the phone: a 4 GB video is never held in memory.
- **Immich**: `POST /api/assets/bulk-upload-check` with the checksums first, so what Immich already has (from this phone or any other) is only written down; the rest goes to `POST /api/assets` as a multipart form with the checksum in `x-immich-checksum` and the key in `x-api-key`. The form carries what Immich 1.x, 2.x and 3.x each ask
  for (`deviceAssetId`, `deviceId`, `fileCreatedAt`, `fileModifiedAt`, `filename`, `isFavorite`, `metadata`); a version that does not know a field ignores it.
- **Nextcloud/WebDAV**: `MKCOL` for each level of the folder (already there is fine), `HEAD` to see whether the file exists, then `PUT` with `If-None-Match: *`, the checksum in `OC-Checksum` (the server verifies what it received) and the modification date in `X-OC-MTime`. The name is
  `folder/YYYY/MM/name_<first 8 of the SHA-1>.ext`, the year and month being those of the photo in UTC. So the same picture always lands in the same place, whichever phone it comes from, and **two different pictures with the same name (`IMG_0001.jpg`) can never overwrite each other**.
- A photo the server refuses (too large, a type it does not take) is written down against that photo and the run goes on; after three refusals in a row for the same version of the file it is left alone until you press *Try the refused ones again*. **A server that cannot be reached, that does not
  accept the credential, whose certificate is not trusted, or whose address is wrong ends the run at once and counts against no photo**, so a night without Wi-Fi does not use up anyone's chances. If the server refuses 25 files in a run, the run stops (that points to a limit on the server).
- **When**: WorkManager, every 30 minutes at most, only on the network you allowed (Wi-Fi by default), battery not low, charging if you chose that, and **not in Battery Saver** (a run you start with *Back up now* goes ahead there). Between photos it checks the heat of the phone (too hot: stop), the time (an 8-minute slice, then the next
  run continues) and whether you turned the backup off. Settings shows how many are backed up, how many were refused, whether it is running, how the last run ended and why it stopped.
- The list of what was sent is cleared when you change the server and when you revoke photo access (it says which photos were sent where, so it goes with everything else eikon learned about them).

## What was checked, and what was not

Checked by tests that run without a phone (about 100 for the backup): the runner (order, what counts against a photo and what only ends the run, time, Battery Saver, heat, resuming), the queue's SQL on a real SQLite, the settings on a real DataStore (what a change of server throws away), the database
migration, the remote names, the credential's encryption, the small JSON reader, **both clients against stand-in servers** (every request, header and body checked, every status turned into the right kind of failure), **TLS handshakes with generated certificates** (a trusted authority, a self-signed certificate refused
and then pinned, a wrong pin, a renewed certificate, a pinned server with the wrong name, a pinned authority not vouching for other names, the probe reading a fingerprint without sending anything) and the **whole path** against stand-ins that remember what they are given (first run, second run, another phone with the same photos, two different photos with the same name, Immich recognising what another phone sent).

**Not checked**: any real Immich, Nextcloud or Umbrel server. The Immich API was read from its published OpenAPI description (versions 1.120, 1.135, 2.0, 2.4 and 3.2); the Nextcloud side follows its WebDAV documentation. Nothing was run on a phone: the screens, WorkManager's schedule, the Keystore, reading a photo through Android with
and without the location permission, the speed and battery cost of hashing and uploading, and behaviour on a real, unreliable network.

## Limits

- **Backup only**: there is no restore or download in eikon. Immich's and Nextcloud's own apps and web pages show what was sent.
- **Big files on slow connections**: a run lasts 8 minutes and a file is sent in one piece, so one that cannot be sent in that time on your connection is started again each run and may never finish. Resumable or chunked upload, and a foreground service to lift the time limit, are not implemented.
- **Servers have their own limits** on the size of one upload (a reverse proxy's `client_max_body_size`, a tunnel service's cap): a file above it is refused (HTTP 413) and marked, and the message says so.
- A photo that is **edited by another app** afterwards is sent again as a new file (Immich keeps both, Nextcloud has both under different names). Nothing on the server is ever removed, including photos you delete on the phone.
- **Live and motion photos** are ordinary files; their video part is not linked to them on Immich.
- Only what eikon can see is backed up: with "selected photos" access on Android 14, only those.
- Messages coming from the server or the network are shown in English.
- Immich older than 1.120 is not supported.
