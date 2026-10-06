package com.nuvio.tv.core.recording

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
import androidx.core.app.ServiceCompat
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.RecordingStop
import com.nuvio.tv.data.iptv.IptvLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class IptvRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var recorder: IptvRecorder
    private var observing: Job? = null
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        recorder = iptvRecorder(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.iptv_recording_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.iptv_recording_channel_description)
                    setShowBadge(false)
                    setSound(null, null)
                })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val id = intent?.getStringExtra(IptvRecordingAlarms.EXTRA_ID)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(recorder.runningTitles()),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (error: Exception) {
            IptvLog.failure("recording foreground", error)
            if (id != null) recorder.blocked(id)
            if (recorder.running.value.isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (id != null) recorder.begin(id)
        if (observing == null) observing = scope.launch {
            recorder.changes.collect {
                if (recorder.running.value.isEmpty()) {
                    ServiceCompat.stopForeground(this@IptvRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                } else {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(recorder.runningTitles()))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        IptvLog.info("recording time limit reached")
        recorder.stopAll(RecordingStop.TIME_LIMIT)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        observing?.cancel()
        scope.cancel()
        if (::recorder.isInitialized && recorder.running.value.isNotEmpty()) recorder.stopAll(RecordingStop.INTERRUPTED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(titles: List<String>): Notification {
        val launch = packageManager.getLeanbackLaunchIntentForPackage(packageName) ?: packageManager.getLaunchIntentForPackage(packageName)
        val text = when (titles.size) {
            0 -> getString(R.string.iptv_recording_notification_starting)
            1 -> titles.first()
            else -> resources.getQuantityString(R.plurals.iptv_recording_notification_count, titles.size, titles.size)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.iptv_recording_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { launch?.let { setContentIntent(PendingIntent.getActivity(this@IptvRecordingService, 0, it, PendingIntent.FLAG_IMMUTABLE)) } }
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "iptv_recording"
        private const val NOTIFICATION_ID = 9530

        fun intent(context: Context, id: String): Intent =
            Intent(context, IptvRecordingService::class.java).putExtra(IptvRecordingAlarms.EXTRA_ID, id)
    }
}
