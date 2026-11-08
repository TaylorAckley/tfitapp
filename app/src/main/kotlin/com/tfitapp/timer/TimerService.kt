package com.tfitapp.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tfitapp.R
import com.tfitapp.domain.timer.TimerSnapshot
import com.tfitapp.domain.timer.TimerStatus
import com.tfitapp.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the timer alive with the screen off: a foreground notification with
 * pause/skip/stop actions, a partial wake lock, and the audio cues. All timing
 * lives in [TimerController]; this class only reacts to it.
 */
@AndroidEntryPoint
class TimerService : LifecycleService() {

    @Inject lateinit var controller: TimerController

    private lateinit var cues: TimerCues
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        cues = TimerCues(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(controller.snapshot.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tfit:timer").also {
            it.acquire(MAX_WAKE_MS)
        }

        lifecycleScope.launch { controller.cues.collect { cues.play(it) } }
        lifecycleScope.launch {
            controller.snapshot
                .distinctUntilChangedBy { it?.let { s -> Triple(s.phaseIndex, s.remainingWholeSeconds, s.status) } }
                .collectLatest { snap ->
                    if (snap == null) { stopSelf(); return@collectLatest }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(snap))
                    if (snap.status == TimerStatus.FINISHED) {
                        // Leave the "Done" notification visible briefly, then go away.
                        kotlinx.coroutines.delay(4_000)
                        controller.stop()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_ADVANCE -> controller.advance()
            ACTION_STOP -> controller.stop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        cues.release()
        super.onDestroy()
    }

    private fun buildNotification(snap: TimerSnapshot?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = snap?.phase?.label ?: "Timer"
        val text = when {
            snap == null -> "Starting…"
            snap.status == TimerStatus.FINISHED -> "Done"
            snap.remainingWholeSeconds != null -> format(snap.remainingWholeSeconds!!) + ", ${snap.phaseIndex + 1} of ${snap.phaseCount}"
            else -> "Self-paced, ${snap.phaseIndex + 1} of ${snap.phaseCount}"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (snap != null && snap.status != TimerStatus.FINISHED) {
            if (snap.status == TimerStatus.PAUSED) builder.addAction(0, "Resume", action(ACTION_RESUME))
            else builder.addAction(0, "Pause", action(ACTION_PAUSE))
            builder.addAction(0, if (snap.phase.isSelfPaced) "Done" else "Skip", action(ACTION_ADVANCE))
            builder.addAction(0, "Stop", action(ACTION_STOP))
        }
        return builder.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, name.hashCode(), Intent(this, TimerService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.timer_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.timer_channel_description)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun format(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    companion object {
        const val CHANNEL_ID = "timer"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PAUSE = "com.tfitapp.timer.PAUSE"
        const val ACTION_RESUME = "com.tfitapp.timer.RESUME"
        const val ACTION_ADVANCE = "com.tfitapp.timer.ADVANCE"
        const val ACTION_STOP = "com.tfitapp.timer.STOP"
        private const val MAX_WAKE_MS = 2 * 60 * 60 * 1000L

        fun intent(context: Context) = Intent(context, TimerService::class.java)
    }
}
