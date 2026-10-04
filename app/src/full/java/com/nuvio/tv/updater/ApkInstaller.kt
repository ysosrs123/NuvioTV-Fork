package com.nuvio.tv.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nuvio.tv.BuildConfig
import java.io.File

object ApkInstaller {

    /** Validate before requesting installation permission and again before handing off. */
    @Suppress("DEPRECATION")
    fun validateUpdate(context: Context, apkFile: File, expectedCode: Long?, expectedSize: Long?) {
        require(expectedSize == null || apkFile.length() == expectedSize) { "Incomplete update download" }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val archiveFlags = if (Build.VERSION.SDK_INT >= 28) flags or PackageManager.GET_SIGNATURES else flags
        val archive = pm.getPackageArchiveInfo(apkFile.absolutePath, archiveFlags)
            ?: error("Invalid update APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "Update is for a different application" }
        val remoteCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val localCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(remoteCode > localCode) { "Update APK is not newer than the installed build" }
        require(expectedCode == null || expectedCode == remoteCode) { "Update version does not match release metadata" }
        require(Build.VERSION.SDK_INT < 24 || archive.applicationInfo?.minSdkVersion?.let {
            it <= Build.VERSION.SDK_INT
        } == true) { "Update requires a newer Android version" }
        val trusted = if (Build.VERSION.SDK_INT >= 28) {
            val old = installed.signingInfo ?: error("Installed signature is unavailable")
            val oldSigners = old.apkContentsSigners.toSet()
            val next = archive.signingInfo
            val nextSigners = next?.apkContentsSigners?.toSet() ?: archive.signatures.orEmpty().toSet()
            if (nextSigners.isEmpty()) error("Update signature is unavailable")
            if (next == null || old.hasMultipleSigners() || next.hasMultipleSigners()) {
                oldSigners.isNotEmpty() && oldSigners == nextSigners
            } else {
                oldSigners.isNotEmpty() && next.signingCertificateHistory.orEmpty().toSet().containsAll(oldSigners)
            }
        } else {
            val old = installed.signatures.orEmpty().toSet()
            old.isNotEmpty() && old == archive.signatures.orEmpty().toSet()
        }
        require(trusted) { "Update signing certificate does not match this installation" }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildUnknownSourcesSettingsIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }
    }

    fun launchInstall(context: Context, apkFile: File) {
        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }
}
