package app.immcloudscale.data.backup

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.immcloudscale.ImmApp
import app.immcloudscale.MainActivity
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.HttpException
import app.immcloudscale.data.immich.BulkCheckItem
import app.immcloudscale.data.immich.ImmichClient
import app.immcloudscale.ui.components.formatBytes
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** A photo or video on the phone, from MediaStore. */
data class LocalMedia(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val dateAdded: Long,
    val dateTaken: Long,
    val dateModified: Long,
    val bucket: String,
)

object LocalMediaSource {
    @SuppressLint("InlinedApi")
    fun query(context: Context, sinceSeconds: Long, videos: Boolean, folders: Set<String>): List<LocalMedia> {
        val uris = buildList {
            add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            if (videos) add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        }
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        val out = mutableListOf<LocalMedia>()
        for (base in uris) {
            context.contentResolver.query(
                base, proj, "${MediaStore.MediaColumns.DATE_ADDED} >= ?", arrayOf(sinceSeconds.toString()),
                "${MediaStore.MediaColumns.DATE_ADDED} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val bucket = c.getString(7) ?: ""
                    if (folders.isNotEmpty() && bucket !in folders) continue
                    val id = c.getLong(0)
                    val added = c.getLong(4)
                    out += LocalMedia(
                        id = id, uri = ContentUris.withAppendedId(base, id), name = c.getString(1) ?: "$id",
                        mime = c.getString(2) ?: "application/octet-stream", size = c.getLong(3), dateAdded = added,
                        dateTaken = c.getLong(6).takeIf { it > 0 } ?: (added * 1000), dateModified = c.getLong(5) * 1000,
                        bucket = bucket,
                    )
                }
            }
        }
        return out.sortedBy { it.dateAdded }
    }

    /**
     * What's in the phone's trash (Android 11+), the soonest to be deleted for good first, with when that happens
     * (epoch seconds; 0 when Android doesn't say). Empty on older phones, which have no trash.
     */
    @SuppressLint("InlinedApi")
    fun trashed(context: Context): List<Pair<LocalMedia, Long>> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_EXPIRES,
        )
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        val out = mutableListOf<Pair<LocalMedia, Long>>()
        for (base in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            runCatching {
                context.contentResolver.query(base, proj, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val added = c.getLong(4)
                        out += LocalMedia(
                            id = id, uri = ContentUris.withAppendedId(base, id), name = c.getString(1) ?: "$id",
                            mime = c.getString(2) ?: "application/octet-stream", size = c.getLong(3), dateAdded = added,
                            dateTaken = c.getLong(6).takeIf { it > 0 } ?: (added * 1000), dateModified = c.getLong(5) * 1000,
                            bucket = c.getString(7) ?: "",
                        ) to c.getLong(8)
                    }
                }
            }
        }
        return out.sortedBy { if (it.second > 0) it.second else Long.MAX_VALUE } // soonest gone first, unknown last
    }

    fun folders(context: Context): List<String> {
        val set = sortedSetOf<String>()
        for (base in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            runCatching {
                context.contentResolver.query(base, arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) c.getString(0)?.let(set::add)
                }
            }
        }
        return set.toList()
    }
}

/**
 * Immich-style backup: finds new photos/videos, asks the server which it already has (by SHA-1),
 * then uploads the rest. Runs periodically and on demand ("Back up now").
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app get() = applicationContext as ImmApp

    override suspend fun doWork(): Result {
        try {
            return backUp()
        } finally {
            // A scheduled run books the next one, even when this one failed (after this run, so it doesn't cancel itself).
            if (SCHEDULED_TAG in tags) schedule(applicationContext, app.graph.settings.current(), fromWorker = true)
        }
    }

    private suspend fun backUp(): Result {
        val s = app.graph.settings.current()
        if (!s.immichConfigured) return Result.success()
        // Away from home, back up through Tailscale (started here if needed).
        // Tailscale stays up afterwards like it does for the app on screen; it stops by itself back at home.
        val ts = app.graph.tailscale
        if (s.pairedServer.isNotBlank() && !ts.checkHome()) {
            if (ts.inviting) return Result.retry() // joining a server with an invite: back up once that's done
            if (!ts.ensureConnected()) {
                if (ts.inviting) return Result.retry()
                return Result.success(workDataOf(KEY_ERROR to "Away from home: turn on Remote access in the Network tab to back up"))
            }
        }
        val result = upload(s)
        runCatching { findDeletedElsewhere() }.onFailure { Log.w("BackupWorker", "checking deletions", it) }
        if (s.freeUpAuto) runCatching { offerFreeUp(s) }.onFailure { Log.w("BackupWorker", "free up space", it) }
        return result
    }

    private suspend fun upload(s: AppSettings): Result {
        // Look back a day past the last file handled: MediaStore can add items with an older DATE_ADDED
        // (e.g. a long video finishing), and files sharing one second could be cut off. Re-checking is cheap:
        // the server says which checksums it already has, so nothing is uploaded twice.
        val since = (s.backupSince - 24 * 3600).coerceAtLeast(0)
        val media = LocalMediaSource.query(applicationContext, since, s.backupVideos, s.backupFolders)
        if (media.isEmpty()) {
            app.graph.settings.update { it.copy(lastBackupAt = System.currentTimeMillis()) }
            return Result.success(workDataOf(KEY_UPLOADED to 0))
        }
        runCatching { setForeground(foreground(0, media.size)) }
        val deviceId = PhoneCopies.deviceId(applicationContext)
        var uploaded = 0
        var skipped = 0
        var done = 0
        try {
            for (batch in media.chunked(30)) {
                // Files that can't be read (deleted meanwhile, no permission) are skipped, not fatal.
                val sums = batch.mapNotNull { m -> runCatching { m to PhoneUpload.sha1(applicationContext, m) }.onFailure { if (!PhoneUpload.permanent(it)) throw it }.getOrNull() }.toMap()
                skipped += batch.size - sums.size
                val results = if (sums.isEmpty()) emptyMap() else
                    app.graph.immich.bulkUploadCheck(sums.map { (m, sum) -> BulkCheckItem(m.id.toString(), sum) }).associateBy { it.id }
                for (m in batch) {
                    if (isStopped) return Result.retry()
                    val sum = sums[m]
                    if (sum != null && results[m.id.toString()]?.action != "reject") {
                        try {
                            app.graph.immich.upload(
                                file = PhoneUpload.contentBody(applicationContext, m), fileName = PhoneUpload.uploadName(m), deviceAssetId = "${m.id}-${m.size}", deviceId = deviceId,
                                createdAt = Instant.ofEpochMilli(m.dateTaken), modifiedAt = Instant.ofEpochMilli(m.dateModified.takeIf { it > 0 } ?: m.dateTaken),
                                checksum = sum,
                            )
                            uploaded++
                        } catch (e: Exception) {
                            if (!PhoneUpload.permanent(e)) throw e // network trouble or server error: retry later from here
                            skipped++ // unsupported/unreadable file: skip it so it can't block every later backup
                        }
                    }
                    done++
                    // Remember progress after every file so an interrupted run resumes where it stopped.
                    app.graph.settings.update { it.copy(backupSince = maxOf(it.backupSince, m.dateAdded)) }
                    setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to media.size))
                    runCatching { setForeground(foreground(done, media.size)) }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Backup failed")))
        }
        app.graph.settings.update { it.copy(lastBackupAt = System.currentTimeMillis(), backupSkipped = skipped) }
        return Result.success(workDataOf(KEY_UPLOADED to uploaded, KEY_SKIPPED to skipped))
    }

    /**
     * Photos this phone backed up that were deleted in the library since the last look (in the app on another
     * device, or on the web) are still on this phone. Remember them and tell the person: moving them to the
     * phone's trash needs Android's confirmation, which only the app on screen can ask for.
     */
    private suspend fun findDeletedElsewhere() {
        val graph = app.graph
        val checkedAt = System.currentTimeMillis()
        val since = Instant.ofEpochMilli(graph.settings.current().trashSyncedAt)
        val trashed = graph.immich.trashedFrom(PhoneCopies.deviceId(applicationContext), since)
        val known = PhoneCopies.backedUp(applicationContext, trashed)
        val still = PhoneCopies.find(applicationContext, known.keys, known)
            .mapNotNull { it.lastPathSegment?.toLongOrNull() }.map { PhoneCopies.key(it, known.getValue(it)) }
        graph.settings.update { it.copy(trashSyncedAt = checkedAt, phoneTrashPending = it.phoneTrashPending + still) }
        if (still.isNotEmpty()) notifyDeletedElsewhere(graph.settings.current().phoneTrashPending.size)
    }

    /** Automatic "Free up space": remembers what can go and asks the app (on screen) to remove it. */
    private suspend fun offerFreeUp(s: AppSettings) {
        val f = findFreeUp(applicationContext, app.graph.immich, s.freeUpKeepDays)
        if (f.sizes.isEmpty()) return
        app.graph.settings.update { it.copy(freeUpPending = f.keys) }
        notify(44, "Free up ${formatBytes(f.bytes)}", "${f.sizes.size} backed-up photos and videos can be removed from this phone. Tap to free up space.")
    }

    private fun notifyDeletedElsewhere(count: Int) = notify(
        43,
        if (count == 1) "A deleted photo is still on this phone" else "$count deleted photos are still on this phone",
        "Tap to move them to the phone's trash too",
    )

    private fun notify(id: Int, title: String, text: String) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SYNC, "Phone storage", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    private fun foreground(done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up photos")
            .setContentText("$done of $total")
            .setProgress(total, done, false)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(42, n)
    }

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_SKIPPED = "skipped"
        const val KEY_ERROR = "error"
        const val NOW = "backup-now"
        const val PERIODIC = "backup-periodic"
        const val SCHEDULED = "backup-scheduled"
        private const val SCHEDULED_TAG = "scheduled"
        private const val CHANNEL = "backup"
        private const val CHANNEL_SYNC = "deleted"

        /**
         * Time until the next scheduled backup: the first of [days] (1 = Monday … 7 = Sunday) at [minuteOfDay]
         * that is still ahead of [now].
         */
        fun untilNextRun(days: Set<Int>, minuteOfDay: Int, now: LocalDateTime): Duration {
            val at = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
            for (ahead in 0..7L) {
                val day = now.toLocalDate().plusDays(ahead)
                val run = day.atTime(at)
                if (day.dayOfWeek.value in days && run.isAfter(now)) return Duration.between(now, run)
            }
            return Duration.ofDays(1)
        }

        private fun constraints(s: AppSettings) = Constraints.Builder()
            .setRequiredNetworkType(if (s.backupWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(s.backupChargingOnly)
            .build()

        fun schedule(context: Context, s: AppSettings, fromWorker: Boolean = false) {
            val wm = WorkManager.getInstance(context)
            if (!s.backupEnabled || !s.immichConfigured) {
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(SCHEDULED)
                return
            }
            if (s.backupDays.isNotEmpty()) {
                // Chosen days and time: one run at the next slot, which books the one after.
                wm.cancelUniqueWork(PERIODIC)
                val wait = untilNextRun(s.backupDays, s.backupTime, LocalDateTime.now())
                wm.enqueueUniqueWork(
                    SCHEDULED, if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(wait.toMillis(), TimeUnit.MILLISECONDS)
                        .setConstraints(constraints(s)).addTag(SCHEDULED_TAG).build(),
                )
                return
            }
            wm.cancelUniqueWork(SCHEDULED)
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS).setConstraints(constraints(s)).build(),
            )
        }

        fun runNow(context: Context, s: AppSettings) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
        }
    }
}

/** Uploading one phone photo or video to Immich, the way backup does it (also for "Back up now" on chosen ones). */
object PhoneUpload {
    /** Uploads [m] unless the server already has it; false when it was already there. */
    suspend fun upload(context: Context, immich: ImmichClient, m: LocalMedia, deviceId: String): Boolean {
        val sum = sha1(context, m)
        if (immich.bulkUploadCheck(listOf(BulkCheckItem(m.id.toString(), sum))).firstOrNull()?.action == "reject") return false
        immich.upload(
            file = contentBody(context, m), fileName = uploadName(m), deviceAssetId = "${m.id}-${m.size}", deviceId = deviceId,
            createdAt = Instant.ofEpochMilli(m.dateTaken), modifiedAt = Instant.ofEpochMilli(m.dateModified.takeIf { it > 0 } ?: m.dateTaken),
            checksum = sum,
        )
        return true
    }

    /** True for problems retrying won't fix: the file is gone or unreadable, or the server refuses it (4xx except auth). */
    fun permanent(e: Throwable): Boolean = when (e) {
        is FileNotFoundException, is SecurityException -> true
        is HttpException -> e.code in 400..499 && e.code != 401 && e.code != 403 && e.code != 408 && e.code != 429
        else -> false
    }

    /** MediaStore names sometimes lack an extension, which Immich needs to know the file type. */
    fun uploadName(m: LocalMedia): String {
        if ('.' in m.name.substringAfterLast('/')) return m.name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(m.mime)
        return if (ext != null) "${m.name}.$ext" else m.name
    }

    fun sha1(context: Context, m: LocalMedia): String {
        val md = MessageDigest.getInstance("SHA-1")
        val stream = context.contentResolver.openInputStream(m.uri) ?: throw FileNotFoundException("${m.uri}")
        stream.use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun contentBody(context: Context, m: LocalMedia) = object : RequestBody() {
        override fun contentType() = m.mime.toMediaTypeOrNull()
        // Unknown length (chunked): MediaStore's SIZE can be stale, and a mismatch fails the upload.
        override fun contentLength() = -1L
        override fun writeTo(sink: BufferedSink) {
            val input = context.contentResolver.openInputStream(m.uri) ?: throw FileNotFoundException("${m.uri}")
            input.source().use { sink.writeAll(it) }
        }
    }
}
