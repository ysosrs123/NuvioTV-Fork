package com.nuvio.tv.core.performance

import android.content.Context
import android.util.Log
import androidx.profileinstaller.ProfileVerifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether Android has compiled the app from its profile yet. Until it has, every fresh start runs the playback code
 * through the interpreter and the JIT first, which on a slow box can cost the first minutes of a heavy film.
 * Logged once per process with the player's tag, so a playback log shows it.
 */
object ArtCompileStatus {
    private val logged = AtomicBoolean(false)

    /** Blocking file reads: call off the main thread. */
    fun logOnce(context: Context, tag: String) {
        if (!logged.compareAndSet(false, true)) return
        val line = runCatching {
            val status = ProfileVerifier.writeProfileVerification(context.applicationContext)
            describe(
                status.profileInstallResultCode,
                status.isCompiledWithProfile,
                status.hasProfileEnqueuedForCompilation(),
                status.appApkHasEmbeddedProfile()
            )
        }.getOrElse { "ART_COMPILE: unknown (${it.javaClass.simpleName})" }
        Log.i(tag, line)
    }

    internal fun describe(resultCode: Int, compiledWithProfile: Boolean, enqueued: Boolean, embedded: Boolean): String {
        val state = when (resultCode) {
            ProfileVerifier.CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE -> "compiled"
            ProfileVerifier.CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE_NON_MATCHING -> "compiled-other-profile"
            ProfileVerifier.CompilationStatus.RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION -> "waiting-for-compile"
            ProfileVerifier.CompilationStatus.RESULT_CODE_NO_PROFILE_INSTALLED -> "not-compiled"
            ProfileVerifier.CompilationStatus.RESULT_CODE_ERROR_NO_PROFILE_EMBEDDED -> "no-profile-in-apk"
            ProfileVerifier.CompilationStatus.RESULT_CODE_ERROR_UNSUPPORTED_API_VERSION -> "unsupported-android"
            else -> "error-$resultCode"
        }
        return "ART_COMPILE: state=$state code=$resultCode compiledWithProfile=$compiledWithProfile " +
            "enqueued=$enqueued apkProfile=$embedded"
    }
}
