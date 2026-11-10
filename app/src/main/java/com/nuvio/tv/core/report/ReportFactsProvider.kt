package com.nuvio.tv.core.report

import com.nuvio.tv.data.repository.ReportText
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A feature adds a few plain facts of its own to every report by binding one of these into the set. */
interface ReportFactsProvider {
    suspend fun reportFacts(): Map<String, String>
}

@Singleton
class ReportFacts @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards ReportFactsProvider>
) {
    suspend fun collect(): Map<String, String>? {
        val merged = LinkedHashMap<String, String>()
        for (provider in providers) {
            val facts = try {
                withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { provider.reportFacts() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            facts?.let(merged::putAll)
        }
        return cleanReportFacts(merged)
    }

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 1_000L
    }
}

internal fun cleanReportFacts(facts: Map<String, String>): Map<String, String>? =
    facts.entries
        .mapNotNull { (key, value) ->
            val name = ReportText.clean(key, REPORT_FACT_KEY_MAX) ?: return@mapNotNull null
            val text = ReportText.clean(value, REPORT_FACT_VALUE_MAX) ?: return@mapNotNull null
            name to text
        }
        .take(REPORT_FACTS_MAX)
        .toMap()
        .takeIf { it.isNotEmpty() }

private const val REPORT_FACT_KEY_MAX = 50
private const val REPORT_FACT_VALUE_MAX = 240
private const val REPORT_FACTS_MAX = 24

@Module
@InstallIn(SingletonComponent::class)
abstract class ReportFactsModule {
    @Multibinds
    abstract fun providers(): Set<ReportFactsProvider>
}
