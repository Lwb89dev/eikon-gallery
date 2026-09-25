package app.eikon.gallery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.eikon.gallery.data.db.EikonDatabase
import app.eikon.gallery.data.db.MediaEntity

/** In-memory database on the real device SQLite, so tests never touch the app's own data. */
fun inMemoryDatabase(): EikonDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), EikonDatabase::class.java)
        .allowMainThreadQueries()
        .build()

/** Noon UTC, `days` after 2025-01-01: the same calendar day in every time zone from UTC-11 to UTC+11. */
fun utcNoon(days: Int): Long = (1_735_689_600L + days * 86_400L + 12 * 3_600L) * 1000

fun media(
    id: Long,
    day: Int = 1,
    video: Boolean = false,
    favorite: Boolean = false,
    path: String = "DCIM/Camera/",
    added: Int = day,
) = MediaEntity(
    id = id,
    displayName = "IMG_$id.jpg",
    mimeType = if (video) "video/mp4" else "image/jpeg",
    isVideo = video,
    takenAt = utcNoon(day),
    addedAt = utcNoon(added),
    modifiedAt = utcNoon(day),
    width = 4000,
    height = 3000,
    durationMs = 0,
    sizeBytes = 1,
    relativePath = path,
    bucketName = path.trimEnd('/').substringAfterLast('/'),
    isFavorite = favorite,
    isScreenshot = false,
    isScreenRecording = false,
    isPanorama = false,
    isRaw = false,
)
