package app.eikon.gallery

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.eikon.gallery.core.di.ApplicationScope
import app.eikon.gallery.data.backup.BackupService
import app.eikon.gallery.data.edit.SharedFiles
import app.eikon.gallery.data.indexing.IndexingScheduler
import app.eikon.gallery.core.image.MediaThumbnailFetcher
import app.eikon.gallery.core.image.MediaThumbnailKeyer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class EikonApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var indexingScheduler: IndexingScheduler

    @Inject
    lateinit var backup: BackupService

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    /** WorkManager is initialized here (not by its startup provider) so workers can use Hilt. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        indexingScheduler.start()
        backup.start()
        // Pictures made to share an edit are for one hand-over: any that were left behind (the app was killed, nobody shared again) go now.
        scope.launch(Dispatchers.IO) { SharedFiles.sweep(File(cacheDir, SharedFiles.FOLDER), System.currentTimeMillis()) }
    }

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
