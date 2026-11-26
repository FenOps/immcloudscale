package app.immcloudscale.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.immcloudscale.ImmApp
import app.immcloudscale.MainActivity
import app.immcloudscale.ui.account.GoogleSteps
import java.util.concurrent.TimeUnit

/**
 * Every few days after moving from Google, reminds the person to switch off Google Photos backup until they tick
 * it off (or ask not to be reminded): other apps can't switch it off for them.
 */
class GoogleReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = (applicationContext as ImmApp).graph.settings.current()
        if (!due(s)) {
            cancel(applicationContext)
            return Result.success()
        }
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            applicationContext, 1,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "Your photos now go to your own server. In Google Photos: profile picture → Photos settings → Backup → off. " +
            "Then tick it off in ImmCloudScale (Account → Import from Google)."
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Turn off Google Photos backup")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
        return Result.success()
    }

    companion object {
        private const val WORK = "google-reminder"
        private const val CHANNEL = "reminders"
        private const val NOTIFICATION_ID = 7301

        fun due(s: AppSettings) = GoogleSteps.STARTED in s.googleDone &&
            GoogleSteps.PHOTOS_BACKUP !in s.googleDone && GoogleSteps.NO_REMINDERS !in s.googleDone

        /** Keeps the reminder scheduled exactly while it's [due]. */
        fun update(context: Context, s: AppSettings) {
            if (!due(s)) return cancel(context)
            val req = PeriodicWorkRequestBuilder<GoogleReminderWorker>(3, TimeUnit.DAYS).setInitialDelay(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}
