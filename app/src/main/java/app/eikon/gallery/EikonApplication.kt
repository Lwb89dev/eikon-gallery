package app.eikon.gallery

import android.app.Application
import app.eikon.gallery.core.image.MediaThumbnailFetcher
import app.eikon.gallery.core.image.MediaThumbnailKeyer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers

@HiltAndroidApp
class EikonApplication : Application(), SingletonImageLoader.Factory {
    /**
     * Only local images are ever loaded: no network fetcher is registered (coil-network is not even a
     * dependency), and nothing is written to a disk cache because MediaStore already caches thumbnails.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(MediaThumbnailKeyer())
            add(MediaThumbnailFetcher.Factory(context.contentResolver))
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_FRACTION).build() }
        .diskCache(null)
        .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(THUMBNAIL_PARALLELISM))
        .crossfade(false)
        .build()

    private companion object {
        const val MEMORY_CACHE_FRACTION = 0.2

        /** Concurrent thumbnail loads: enough to fill a screen quickly, few enough not to starve the UI thread. */
        const val THUMBNAIL_PARALLELISM = 6
    }
}
