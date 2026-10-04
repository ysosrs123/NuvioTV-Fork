package com.nuvio.tv.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import io.mockk.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@Suppress("DEPRECATION")
class ApkInstallerValidationTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = mockk<Context>()
    private val pm = mockk<PackageManager>()
    private val signer = mockk<Signature>()
    private val installed = PackageInfo().apply {
        packageName = "com.nuvio.tv.test"; versionCode = 1373; signatures = arrayOf(signer)
    }
    private val archive = PackageInfo().apply {
        packageName = "com.nuvio.tv.test"; versionCode = 1374; signatures = arrayOf(signer)
    }
    private fun validate(expected: Long? = 1374, size: Long? = 1) {
        val apk = temp.newFile().apply { writeBytes(byteArrayOf(1)) }
        every { context.packageName } returns "com.nuvio.tv.test"
        every { context.packageManager } returns pm
        every { pm.getPackageArchiveInfo(any(), any<Int>()) } returns archive
        every { pm.getPackageInfo(any<String>(), any<Int>()) } returns installed
        ApkInstaller.validateUpdate(context, apk, expected, size)
    }
    @Test fun `same package newer code and existing certificate are accepted`() = validate()
    @Test(expected = IllegalArgumentException::class) fun `official package is rejected`() {
        archive.packageName = "com.nuvio.tv"; validate()
    }
    @Test(expected = IllegalArgumentException::class) fun `older apk is rejected`() {
        archive.versionCode = 1372; validate()
    }
    @Test(expected = IllegalArgumentException::class) fun `different certificate is rejected`() {
        archive.signatures = arrayOf(mockk<Signature>()); validate()
    }
    @Test(expected = IllegalArgumentException::class) fun `release code mismatch is rejected`() = validate(expected = 1375)
    @Test(expected = IllegalArgumentException::class) fun `truncated download is rejected`() = validate(size = 3)
}
