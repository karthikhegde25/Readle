package com.readle.app.reader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.readle.app.R
import com.readle.app.ReadleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps Readle alive while it reads aloud, so speech continues with the screen off
 * or the app closed. It owns no audio itself: speech runs in [ReadAloudManager]. Its job is the ongoing
 * notification (title, current section, Pause / Resume and Stop buttons) and staying alive until reading ends.
 */
class ReadAloudService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    private val manager: ReadAloudManager get() = (application as ReadleApp).container.readAloud

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        when (intent?.action) {
            ACTION_TOGGLE -> manager.togglePlayback()
            ACTION_STOP -> manager.end()
        }
        // Must be called promptly after startForegroundService().
        promote(buildNotification(manager.session.value, manager.controller.state.value))
        if (watcher == null) {
            watcher = scope.launch {
                combine(manager.session, manager.controller.state) { s, st -> s to st }.collect { (s, st) ->
                    if (s == null) {
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(s, st))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        manager.onServiceStopped()
        super.onDestroy()
    }

    private fun promote(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Read aloud", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shows while Readle is reading a book aloud, with Pause and Stop buttons."
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(session: SpeechSession?, state: TtsState): Notification {
        fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
            this, code, Intent(this, ReadAloudService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val paused = state == TtsState.Paused
        val text = when {
            session == null -> "Reading aloud"
            session.chapterTitle != null -> (if (paused) "Paused · " else "") + session.chapterTitle
            else -> if (paused) "Paused" else "Reading aloud"
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_readaloud)
            .setContentTitle(session?.title ?: "Readle")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (open != null) b.setContentIntent(open)
        b.addAction(
            if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            if (paused) "Resume" else "Pause",
            action(ACTION_TOGGLE, 1)
        )
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(ACTION_STOP, 2))
        return b.build()
    }

    companion object {
        const val ACTION_TOGGLE = "com.readle.app.readaloud.TOGGLE"
        const val ACTION_STOP = "com.readle.app.readaloud.STOP"
        private const val CHANNEL_ID = "read_aloud"
        private const val NOTIFICATION_ID = 4101
    }
}
