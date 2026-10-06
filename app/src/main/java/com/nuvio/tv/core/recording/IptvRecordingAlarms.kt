package com.nuvio.tv.core.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.iptv.IptvLog
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

internal object IptvRecordingAlarms {
    const val ACTION_START = "com.nuvio.tv.iptv.RECORDING_START"
    const val EXTRA_ID = "recording"
    private const val WINDOW_MILLIS = 2 * 60 * 1000L

    fun exactAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    fun arm(context: Context, id: String, atMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pending(context, id)
        try {
            if (exactAllowed(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
            else manager.setWindow(AlarmManager.RTC_WAKEUP, atMillis - WINDOW_MILLIS, WINDOW_MILLIS, intent)
        } catch (error: SecurityException) {
            IptvLog.failure("recording alarm", error)
            manager.setWindow(AlarmManager.RTC_WAKEUP, atMillis - WINDOW_MILLIS, WINDOW_MILLIS, intent)
        }
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, id))
    }

    private fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, IptvRecordingAlarmReceiver::class.java).setAction(ACTION_START).setData(Uri.parse("nuvio-recording:$id")).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class IptvRecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.FEATURE_IPTV_ENABLED || intent.action != IptvRecordingAlarms.ACTION_START) return
        val id = intent.getStringExtra(IptvRecordingAlarms.EXTRA_ID) ?: return
        val pending = goAsync()
        iptvRecorder(context).onAlarm(id) { pending.finish() }
    }
}

class IptvRecordingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.FEATURE_IPTV_ENABLED || intent.action !in ACTIONS) return
        val pending = goAsync()
        iptvRecorder(context).rearm { pending.finish() }
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface IptvRecorderEntryPoint {
    fun recorder(): IptvRecorder
}

internal fun iptvRecorder(context: Context): IptvRecorder =
    EntryPointAccessors.fromApplication(context.applicationContext, IptvRecorderEntryPoint::class.java).recorder()

@Module
@InstallIn(SingletonComponent::class)
object IptvRecordingModule {
    @Provides @IntoSet fun recordings(recorder: IptvRecorder): ProfileScopedCredentialStore = recorder
}
