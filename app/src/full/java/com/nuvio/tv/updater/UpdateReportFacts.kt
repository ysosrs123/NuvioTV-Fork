package com.nuvio.tv.updater

import android.content.Context
import android.os.Build
import com.nuvio.tv.core.performance.ArtCompileStatus
import com.nuvio.tv.core.report.ReportFactsProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tells a report whether Android has compiled this version yet and how it got onto the box: an update that went in
 * without its profile runs slower until the box compiles it, which looks like Arc got slower.
 */
@Singleton
class UpdateReportFacts @Inject constructor(
    @ApplicationContext private val context: Context
) : ReportFactsProvider {

    override suspend fun reportFacts(): Map<String, String> = withContext(Dispatchers.IO) {
        buildMap {
            put("Compiled", ArtCompileStatus.read(context).removePrefix("ART_COMPILE: "))
            installedBy()?.let { put("Installed", it) }
            UpdateInstallLog.steps(context).takeIf { it.isNotEmpty() }?.let { put("Update installs", it.joinToString(" | ")) }
        }
    }

    @Suppress("DEPRECATION")
    private fun installedBy(): String? = runCatching {
        val pm = context.packageManager
        val ageHours = (System.currentTimeMillis() - pm.getPackageInfo(context.packageName, 0).lastUpdateTime) / 3_600_000L
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = pm.getInstallSourceInfo(context.packageName)
            "installer=${name(info.installingPackageName)} started by=${name(info.initiatingPackageName)}"
        } else {
            "installer=${name(pm.getInstallerPackageName(context.packageName))}"
        }
        "$source, ${ageHours} h ago"
    }.getOrNull()

    private fun name(pkg: String?): String = when (pkg) {
        null -> "none"
        context.packageName -> "Arc"
        else -> pkg
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateReportFactsModule {
    @Binds
    @IntoSet
    abstract fun facts(facts: UpdateReportFacts): ReportFactsProvider
}
