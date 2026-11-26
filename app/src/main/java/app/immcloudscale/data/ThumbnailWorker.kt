package app.immcloudscale.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.immcloudscale.ImmApp
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import java.util.concurrent.TimeUnit

/**
 * On Wi-Fi and while charging, downloads the thumbnails of the whole timeline into the phone's image cache, so the
 * Photos page shows real pictures straight away, like Google Photos (a few hundred MB for a large library).
 */
class ThumbnailWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as ImmApp).graph
        val s = g.settings.current()
        if (!s.keepThumbnails || !s.immichConfigured) return Result.success()
        val saved = g.timelineCache.load(s.immichUrl, s.immichEmail.ifBlank { s.immichUserName }) ?: return Result.success()
        val loader = SingletonImageLoader.get(applicationContext)
        val disk = loader.diskCache ?: return Result.success()
        var fetched = 0
        var failed = 0
        for (a in saved.assets) {
            if (isStopped || fetched >= PER_RUN) break
            val url = g.immich.thumbnailUrl(a.id)
            if (disk.openSnapshot(url)?.use { true } == true) continue // already here
            val r = loader.execute(ImageRequest.Builder(applicationContext).data(url).memoryCachePolicy(CachePolicy.DISABLED).build())
            if (r is ErrorResult) {
                // Many in a row: the server isn't answering, so later. A few here and there (gone since): skipped.
                if (++failed >= 20) return Result.retry()
            } else {
                fetched++
                failed = 0
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "thumbnails"
        private const val PER_RUN = 20_000

        /** Keeps it scheduled while it's wanted (see [AppSettings.keepThumbnails]). */
        fun update(context: Context, s: AppSettings) {
            val wm = WorkManager.getInstance(context)
            if (!s.keepThumbnails || !s.immichConfigured) return run { wm.cancelUniqueWork(WORK) }
            val req = PeriodicWorkRequestBuilder<ThumbnailWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
