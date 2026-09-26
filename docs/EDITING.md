# Editing

eikon edits photos **without changing them**. An edit is a short piece of text, a *recipe*, that says what was changed and by how much. The photo's file is
never opened for writing. The recipe is drawn over the original whenever the photo is shown, and deleting it is the whole of "Revert to original".

## The guarantee

- The original file is **read**, never rewritten by the editor. Nothing in the editor, the viewer or the grid writes to it, renames it or moves it. (The one exception is not an edit of the picture: changing the date or the location written inside a file from the info panel, which is described at the end of this page and never happens without the system asking first.)
- There is **no "replace the original" action, by design.** The only way an edit becomes a file is **Save a copy**, which creates a *new* JPEG next to the
  original. If the user wants the original gone, they delete it separately, through Android's own confirmation, like any photo.
- "Revert" (in the editor) and "Remove edits" (in the grid's selection bar) delete the recipe. Nothing has to be restored, because nothing was changed.
- Leaving the editor with the back button asks before throwing away what was done in that session; "Done" stores the recipe.

## Why a recipe in the database, and not the alternatives

| Approach | Why not |
| --- | --- |
| Rewrite the file | Destroys the original; on Android 11+ needs a system write request per photo; every re-save recompresses. The opposite of what this app promises. |
| A sidecar file (XMP) next to the photo | Litters the user's folders, needs write access to them (scoped storage allows it only through a per-file system dialog), and no other gallery would honour it anyway. |
| A recipe in eikon's own database (**chosen**) | Tiny (a few hundred bytes), instant to change or revert, no write access needed, and the same recipe can be copied to other photos. |

The price is honest and stated in the app's documentation, not hidden: **other apps see the original**, because only eikon knows the recipe; and the recipes live in eikon's
database, which is excluded from Android backups, so clearing the app's data or uninstalling it loses them. **Save a copy** is how an edit leaves eikon.

## The recipe

Stored in the `edit_recipe` table (`mediaId`, `recipe`, `updatedAt`, `baseModifiedAt`) as text, versioned so the format can grow:

```
eikon-edit 1
exposure=0.5
saturation=0.2
turns=1
crop=0.1,0.05,0.95,0.9
filter=chrome
filterAmount=0.7
```

Only what differs from neutral is written, so an untouched photo has no row and "edited" always means something: saving a recipe that changes nothing removes the edit instead.
Reading is tolerant: unknown keys are skipped (a later version's recipe does not break an older one) and text that is not a recipe reads as no edit. Every value is clamped to its range when
read. The recipe is a plain value (`EditRecipe` in `domain/edit`), so two recipes that say the same thing are equal.

### What can be set

| Group | Tools |
| --- | --- |
| Auto | **Auto enhance**: measures the picture (mean and spread of brightness, how many pixels sit at either end) and *proposes* exposure, black point, contrast, shadows, highlights and vibrance. It fills the sliders, which stay adjustable. Plain arithmetic, not a model; it leaves a photo that is already well exposed alone. |
| Light | exposure (±2 EV), brightness, contrast, highlights, shadows, black point |
| Color | saturation, vibrance, temperature, tint |
| Detail | sharpness, vignette |
| Filters | Vivid, Warm, Cool, Mono, Noir, Fade, Chrome, Sepia, each with a strength |
| Crop | crop (free, original, 1:1, 4:3, 3:2, 16:9), rotate, flip, straighten (±45°), vertical and horizontal perspective |

Everything is a number in a fixed range and every step does nothing at its neutral value. Tapping a slider's name puts it back to neutral.

### Order of operations

Geometry first, in one step, then colour, then detail:

1. **Geometry**: flip, quarter turns, then straighten and perspective (the picture is enlarged just enough that no empty corner shows), then the crop. All of it is folded into one
   mapping from output pixel to original pixel (`GeometryMap`), so the picture is **resampled once** (bilinear), and pixels that the crop throws away are never computed.
2. **Colour**: white balance and exposure in linear light; then black point, brightness and contrast on display values; then shadows and highlights, saturation and vibrance; last the
   filter. What depends on one channel only is folded into a 256-entry table per channel, so per pixel there are three lookups.
3. **Detail**: sharpening (an unsharp mask on brightness only, radius about 1 px per 800 px of the long side) and the vignette.

## How it is drawn: a CPU renderer, on purpose

The renderer (`EditRenderer`, `ColorPipeline`, `DetailPasses`, `GeometryMap`) is **pure Kotlin on ints and floats**, on all cores, in horizontal bands.

| Option | Why not |
| --- | --- |
| `ColorMatrix` / `ColorFilter` | A 4x5 matrix cannot express highlights and shadows, vibrance, sharpening, the vignette or perspective, and would work on gamma-encoded values. |
| `RenderEffect` / AGSL shaders | Need Android 13 (API 33); eikon supports Android 11. |
| OpenGL / Vulkan | Needs a GL pipeline, driver-dependent behaviour and a device to test anything; results could differ from phone to phone. |
| **Pure Kotlin (chosen)** | The **same code** draws the editor's preview, the thumbnails, the viewer and the exported file, and it runs in JVM unit tests, so what a recipe *means* is tested (against hand-computed values and properties) instead of hoped for. |

The cost is speed. On a desktop JVM, a heavy recipe (all tools) on a 2-megapixel picture takes about 54 ms. **How fast it is on a phone has not been measured**; the preview is drawn from
a 1,400 px copy so that dragging a slider stays responsive even if the phone is several times slower, and a new drawing replaces one still in progress.

Drawing in **bands** (`PixelSource`) means only the rows of the original that a band needs are read: no full-size arrays, whatever the photo's size.

## Where edits show

- **Editor**: the preview, a filter strip with a thumbnail per filter, and holding the picture shows the original.
- **Grid, collections, memories and the rest**: the thumbnail is the system's, with the recipe drawn on it when the cell is loaded; a small pencil marks edited photos. The cache key
  includes the recipe, so changing an edit changes the thumbnail.
- **Viewer**: the photo is drawn edited (screen-sized, and again at up to 4,096 px while zoomed; a crop decodes larger so what remains still fills the screen). An **Edited** chip at
  the top shows the photo as it was taken when tapped, and back. Going to another photo restores the edits.
- **Info** keeps showing the *file's* real metadata: it describes the original.
- Videos are not editable and have no edit action.

## Save a copy

The only way an edit becomes a file. `EditExporter` decodes the photo once (as sRGB, at full resolution, or reduced to at most 24 megapixels if bigger), draws the recipe in bands
straight into a bitmap, and writes a **new** JPEG (quality 95) into the original's folder if that is `DCIM/` or `Pictures/` (otherwise `Pictures/eikon/`), named like the
original with `_edit` added. It is created as *pending* and only becomes visible once complete; if anything fails it is deleted, and no partial file is left.

The copy keeps the original's date, camera, lens and exposure details so it sits at the right place in the timeline. **Location is copied only if eikon holds the "read photo
locations" permission**, because otherwise Android hides it; the confirmation says if the details could not be copied. Orientation is reset (the pixels are already upright).

## Sharing an edited photo

**Share** hands over what you see. For a photo with an edit, eikon draws the edit at full size into a temporary JPEG in its own cache folder (`cache/shared/`, one folder per share) and shares that through a `FileProvider`
that is not exported and grants only that file to the app you choose. The picture carries **no metadata** (no location, no camera details). Photos without an edit, and videos, are shared as the files they are. Temporary
pictures older than a day are removed the next time something edited is shared. A thin bar moves across the top of the screen while the pictures are being drawn; if that fails the share does not happen and says so, and
it never falls back to sending the unedited file.

## Copy edits, Paste edits

- **Copy edits** (editor menu) puts the photo's *look* (adjustments and filter) on eikon's clipboard: a few lines of text kept across restarts.
- **Paste edits** (editor menu) gives the open photo that look. **Paste edits** in the grid's selection menu gives it to every selected photo at once, and reports how many. Both keep each photo's
  **own crop, turns and straightening**, which belong to one picture and would cut or tilt another.
- **Remove edits** in the selection menu reverts the selected photos that have an edit.
- Today "the same look" means **the same numbers**. The seam for doing better is `EditAdaptation`: pasting passes the recipe through it, with room for statistics of the source and
  target photos, so a later version can, for instance, scale exposure to how bright the target is. Only `EditAdaptation.Same` exists.

## Limits

- **Photos only**; videos cannot be edited. RAW files, animated images and photos with Ultra HDR gain maps are not specially handled (they are decoded as ordinary pictures; the exported
  copy is an ordinary sRGB JPEG, so HDR information is dropped). Not tried on any.
- **sRGB only**: wide-gamut photos are converted on decoding, so an edit means the same thing everywhere.
- **No history**: one recipe per photo. Revert and "discard changes" are all there is; there is no undo stack inside a session.
- **Perspective** is two sliders (vertical and horizontal), not four draggable corners.
- **Not offered**: local adjustments and masks, curves, per-colour adjustments, noise reduction, red-eye or retouching, text and drawing.
- **The file can change under a recipe.** The file's modification time is stored with the recipe, but nothing uses it yet: if another app edits the file afterwards, eikon still draws its
  recipe over the result.
- **Deleted photos**: the recipe of a photo deleted for good stays in the database as a few bytes of text. Media ids are never reused, so it can never apply to another photo.
- **Memory**: a 24-megapixel copy needs about 100 MB for the decoded photo and as much for the result at once. On a phone short of memory, "Save a copy" can fail; it says so and
  leaves nothing behind.
- **Not run on a device yet.** The renderer, the recipe format, the copy/paste rules, the database and its migration are covered by JVM tests (85 of them plus the migration's, on synthetic pictures
  whose expected values are computed by hand or checked as properties). The screens, the gestures of the crop overlay, the look of every tool on real photos, the speed on a phone and the
  saved copy's metadata have **not** been checked; `EditOnDeviceTest` covers the Android bitmap glue and is written but not run.

## The one place eikon writes to a photo: its date and its location

From the info panel a photo's **date taken** and **location** can be changed. This changes what the file says about itself (its EXIF tags), not its picture. It is the only thing in eikon that writes to a file of yours, and it is built so that it cannot cost you the photo:

1. **Only JPEG, PNG and WebP** (the formats Android's EXIF library can write). HEIC, GIF and videos show their date and location but cannot be changed.
2. **The system asks first** (`MediaStore.createWriteRequest`), for that one photo. Nothing is written before the answer.
3. **The location permission is required.** Without `ACCESS_MEDIA_LOCATION` Android shows eikon a copy of the file with the location taken out; writing that back would destroy the location, so eikon refuses to work without it.
4. eikon copies the **original bytes** (asked for unredacted) to a safety copy in its private storage, makes a working copy, and changes the tags **there**, after writing down in its database what the file said before (only the first time, so the true original is never lost).
5. Only then does it write the working copy over the photo, and **reads the photo back and compares it, byte for byte (SHA-256), with what it wrote**.
6. If anything fails, or the app is stopped by the system while it works, the safety copy is written back over the photo. If even that cannot be done (the app was killed), the safety copy stays, the info panel says so and offers to restore it, and no new change is accepted for that photo until it is.
7. "Undo" (revert date, revert location) writes the recorded original tags back the same way.

The photo's date in the library is updated at once (MediaStore is told the new date taken), and the places analysis of that photo is redone from the new file.

A **caption** is different: it is text in eikon's own database (searchable), never written into a file.

What has been checked: the arithmetic (coordinate parsing, tag formatting and snapshot round-trip, the writer's order of steps and every failure path against a stand-in) on the JVM, and `MetadataFileEditorTest` on real EXIF files on a device (written, **not run**). Not checked: the system's write dialog, MediaStore's reaction to a rewritten file, on any real phone.

