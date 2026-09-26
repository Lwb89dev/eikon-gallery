# Performance, battery and the parts that were tuned

What was measured, on what, and what was only reasoned about. Numbers marked *desktop* come from a JVM on a development machine (SQLite through `sqlite-jdbc`); they show that
something is faster than something else, not how fast it is on a phone. **Nothing in this file was measured on a phone in this phase.**

## First launch: the baseline profile

A sideloaded app is compiled lazily by Android's runtime, so the first sessions run partly interpreted. On the one test phone used in Phase 1 (measured then), scrolling the release build
had a 90th-percentile frame time of 14-19 ms until background compilation had finished, and 9 ms after. A *baseline profile* tells Android what to compile ahead of time, at install.

- Compose, Paging, Room, Media3 and the other libraries already ship profiles; the build merges them (the release APK contains `assets/dexopt/baseline.prof`).
- eikon's own code had none. `app/src/main/baseline-prof.txt` adds one, **written by hand**: it lists every class and method of `app.eikon.gallery` with wildcards, so all of the app's code is
  compiled at install (the grid, the viewer, the editor's renderer and the analysis steps all have hot loops). The build expands the wildcards against the classes that really exist and R8 rewrites
  them to the shrunk names.
- Checked at build level: the compiled profile went from 11,042 to 14,299 entries (the app's classes and methods) and the profile in the APK from 11,388 to 12,739 bytes.
- **Not checked**: the effect on a phone. Compiling all of the app's code makes the first install a little slower and its compiled file larger; it should make the first scrolling and the first analysis
  run as fast as they are after full compilation, but that is the expectation, not a measurement.
- A profile *measured* by running the app is better, because it lists only what is used. That needs a Macrobenchmark module that drives the app on a device; there is none in the project. To make one, add the
  `androidx.baselineprofile` plugin and a test module that scrolls the library, opens the viewer and the editor, then replace the hand-written file with the generated one.

## Big libraries: the queries

The grid pages over `ORDER BY takenAt`, so the order must come from an index. `LibraryQueryPlanTest` asks SQLite (`EXPLAIN QUERY PLAN`, on 20,000 rows, on the tables Room exported) and fails if:

- the library, a filter, a cover or a sort by date added has to sort a whole table;
- a device folder is read by scanning the library;
- the date sections or the counts need anything but an index;
- an album, Hidden, a person, a semantic hit list or the *Edited* filter reads the whole library.

One thing it found and fixed: **a device folder had no index of its own**, so a small folder in a big library read the whole date index and looked up every row to find its few photos. Database version 7
adds two indexes (`relativePath, takenAt` and `relativePath, addedAt`). *Desktop*, 50,000 photos, a folder of 100: a page took 4.4 ms (7.5 ms deep in the list) before and 0.1 ms after. The price is two more
indexes to keep up to date when the library syncs and a little more space.

Known and left alone: the members of an **album, Hidden, a person or the edited photos** are found from their own list and then sorted, so the cost of a page follows the size of that list. *Desktop*, an album of 25,000
of 50,000 photos: 10 ms for the first page and 18 ms deep in the list. On a phone that is probably several times more; it is not measured, and an album that large is unusual.

## Lists and the analysis (1.1.0)

Every list of photos (the library, a folder, an album, a collection, a search) is a Room query that is run again when a table it reads changes. They all used to watch the tables the analysis writes to (places, faces, text in photos) as well, so **each photo the analysis
finished redid every open list**, including the date sections of the whole library, which read the entire index (*desktop*, 100,000 photos: 190 ms for the sections against 0.1 ms for a page). While an analysis was running, the database was never idle. Now:

- A list watches only what it reads. `LibraryQueryBuilder.readsAnalysis` says whether it reads what the analysis stores (a place, an area, a person, a search do; the library, folders, albums, presets, Hidden, periods and hit lists do not), and the DAO has a "steady" variant of each query that
  watches the media, album, hidden and edit tables only. A test compares the answer with the SQL itself (whether it names `media_geo`, `face`, `media_search` or `media_caption`), so a query that starts to read one of them cannot stay "steady" by accident. (The *Edited* filter now follows edits too, which it did not before.)
- A **search** is redone when the analysis has stored something it reads, but at most every 8 seconds and **only when the results are not being scrolled** (`RefreshSearchWhenStill`); before, it was redone after every photo, shifting under the finger. **Not seen on a device**: that this is what made scrolling the results stall was the leading suspicion, not something measured.
- **The vectors** that image search reads (50 MB for 100,000 photos) were read from the database again in full at each search made while the analysis was running, because the analysis had stored more since. `MatrixKeeper` now reads only the vectors saved since and puts them into the copy it holds; if the database says something else changed (a photo was removed), it reads everything again, as before.
  A builder that is exactly full now hands its arrays over instead of copying them, which saves 50 MB of transient memory on each full read.
- *Desktop*, 100,000 photos with 10% carrying text: a text search is 3-5 ms for a page and 25 ms for a place, so the queries themselves are not slow; the cost was in how often they ran.

## The viewer: first zoom, closing, video

- **The first zoom of a photo** used to start decoding its large version (up to 4096 px, drawn with the edit for an edited photo) at the first pinch, which stopped the picture for a moment mid-gesture. Once a photo has been on screen for 600 ms (`HIGH_RES_DWELL_MS`) its large version is now prepared, out of sight (drawn with zero alpha), so the first
  pinch finds it ready. A photo only swiped past costs nothing. `ZoomState.isZoomed` is a derived state, so the picture's layers are composed when it turns true or false and not at every step of a pinch. **Not measured on a device**: the diagnosis (the decode, and the upload of a large bitmap) is the likely one, not a profiled one.
- **Closing** a photo that has flown back into its cell used to bring the viewer's picture and its black backdrop back for the length of the viewer's own fade-out, which is the "photo you just closed" flashing over the grid. The viewer now leaves at once once the photo has landed (`HeroController.landedInGrid`).
- **Videos** zoom like photos (`ZoomableBox` around the picture, with the controls outside it) and use a texture rather than a surface, because a surface is not clipped to the zoomed picture's window. The seek bar drives the player's **scrubbing mode** (`Scrubber`): the video is paused while the thumb moves, every position is sought as fast as the player can and shows frames on the way, and
  the last one is sought exactly when the finger lifts, after which playback goes on if it was playing. A texture costs a little more power than a surface. **Not seen on a device.**

## Grid to viewer: a flight, not a shared element

Opening a photo now flies its thumbnail from its cell to its place in the viewer, and back when the viewer closes (`HeroFlight`). The picture is drawn once at its size in the viewer and shown through a window that
grows from the cell to that size, scaled so that it always covers the window: the first frame looks exactly like the cell and the last like the viewer, and nothing is stretched (`HeroGeometry`, tested).

Compose's shared-element API was not used because it puts a modifier node on every visible cell of a scrolling grid, which is exactly where the cost of scrolling is, and because how it resizes content between a cropped
square and a fitted photo could not be tried without a device. The flight costs nothing while scrolling: the cell's place is read from the grid's layout only when a photo is tapped.

If a flight cannot be made (the cell is not on screen, the photo is not loaded yet, the viewer is zoomed or being pulled down) the viewer fades, as before. The animation follows Android's "remove animations" setting.
**Not seen on a device yet**: the coordinates of the flight are worked out from the grid's layout, and whether they line up on real screens (status bars, cutouts, different densities) is unchecked.

## Battery and heat

- Analysis is background work behind WorkManager constraints: battery not low, and (by default) charging. **Battery Saver** now also holds a background run back before it touches a photo; nothing is marked failed and
  the next scheduled run continues. "Analyze now" is the user's own request and goes ahead in Battery Saver (still not on a low battery).
- Between photos the runner reads the platform's thermal status: moderate heat pauses 3 seconds after each photo, severe heat stops the run.
- Models are loaded once per run and freed at its end; the search model is freed a minute after the last search.
- Editing draws on the CPU only while something is on screen: the editor's preview (a 1,400-pixel copy), and a thumbnail or a viewer page of an *edited* photo (drawn once when it loads, and cached). *Desktop*: a heavy recipe on a
  2-megapixel picture takes about 54 ms. Sharing edited photos draws each at full size (up to 24 megapixels) into the cache folder; a bar across the top says so while it works.
- Nothing runs when the app is not visible except the scheduled analysis. The library sync runs only while the app is visible.

## The backup

- It runs as background work behind WorkManager: every 30 minutes at most, on the network the user allowed (Wi-Fi by default), battery not low, charging if chosen, **not in Battery Saver** (a run started with *Back up now* goes ahead there), 8-minute slices, and it stops
  between photos when the phone is too hot ([BACKUP.md](BACKUP.md)).
- Each photo is **read twice**: once to find its SHA-1 and exact size (a sequential read, 64 KB at a time), once to send it. That doubles the reading for the sake of two things the servers need (the checksum, which is what lets them recognise a copy, and a known length, which avoids chunked
  upload); the alternative of computing the hash while sending cannot work for Immich, which wants the checksum before the upload. Nothing is held in memory: a photo is streamed, never loaded.
- Hashing is cheap next to sending, but **neither has been measured on a phone**, nor has the battery cost of a first backup of a large library, which will take a long time on a real connection whatever the code does.
- The queue is one indexed query (`backup_item` joined to `media`, newest first, `LIMIT`); its counts for the settings screen are three `COUNT(*)` queries that Room re-runs when either table changes.

## What is not tuned

- Scrolling was measured on a phone in Phase 1 only. Nothing added since has been scrolled on a device: the edited badge, the per-photo edit lookup for thumbnails and the new folder indexes.
- Analysis speed, battery cost and heat on a phone are unmeasured (see [ML.md](ML.md) for the desktop numbers of the models).
- On-screen photos are analysed first (`AnalysisPriority`: the screens report the ids on screen once scrolling settles, at most 200, in memory only); this has been tested against a real SQLite but not on a phone.
- The vectors that search, pets, labels and similar shots read are kept in memory once (about 0.5 KB per photo, 50 MB for 100,000 photos) and shared; the copy is a *soft* reference, so Android may take it back under memory pressure, and it is read again in full only when a vector was removed or the database no longer agrees with it (a vector that was added or replaced is read in on its own, see above).
- The encrypted database costs a little on every read and write (AES on each page). It has not been measured on a phone; the queries and their plans are unchanged.
