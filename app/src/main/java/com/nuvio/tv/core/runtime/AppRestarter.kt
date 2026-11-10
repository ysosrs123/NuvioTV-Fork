package com.nuvio.tv.core.runtime

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

@Singleton
class AppRestarter @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun restart(): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            context.startActivity(launchIntent)
        } catch (_: RuntimeException) {
            return false
        }
        exitProcess(0)
    }
}
