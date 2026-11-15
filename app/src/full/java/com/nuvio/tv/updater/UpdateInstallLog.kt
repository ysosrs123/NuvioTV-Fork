package com.nuvio.tv.updater

import android.content.Context
import com.nuvio.tv.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last few steps of in-app update installs (which route, what Android answered), kept so a report can say how
 * the running version got onto the box. Plain SharedPreferences: the result receiver may run in a fresh process.
 */
object UpdateInstallLog {
    private const val PREFS = "update_install_log"
    private const val KEY = "steps"
    private const val KEEP = 3

    fun note(context: Context, step: String) {
        val time = SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val line = "$time v${BuildConfig.VERSION_CODE} $step"
        prefs.edit().putString(KEY, append(prefs.getString(KEY, null), line)).commit()
    }

    fun steps(context: Context): List<String> =
        split(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    internal fun append(stored: String?, line: String, keep: Int = KEEP): String =
        (split(stored) + line).takeLast(keep).joinToString("\n")

    private fun split(stored: String?): List<String> = stored.orEmpty().lines().filter { it.isNotBlank() }
}
