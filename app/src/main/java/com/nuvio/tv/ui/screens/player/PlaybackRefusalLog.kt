package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.logging.withoutRequestSecrets
import com.nuvio.tv.core.player.thumbnail.RequestBudget
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Says who turned a request away and how busy this box had been with that domain. Sees every hop, so a refusal behind
 * a redirect names the server that sent it. Only the listed headers and the start of the page are logged.
 */
internal class PlaybackRefusalLog(
    private val budget: RequestBudget = RequestBudget.app,
    private val sink: (String) -> Unit = { line ->
        Log.w("NuvioNet", line)
        PlaybackConnectionEvents.recordEvent(line)
    },
    private val recorder: (String) -> Unit = { detail ->
        com.nuvio.tv.core.report.PlaybackRecorder.event(com.nuvio.tv.core.report.RecorderEvent.NETWORK, detail)
    }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val host = chain.request().url.host
        budget.note(host)
        if (response.code == 429 || response.code == 503) {
            budget.noteRefused(host, ParallelRangeRetryAfter.parseHeaderMs(response.header("Retry-After")))
            runCatching {
                val page = if (isText(response.header("Content-Type"))) response.peekBody(PEEK_BYTES).string() else null
                val domain = RequestBudget.domainOf(host)
                recorder(
                    "refused code=${response.code} server=${com.nuvio.tv.core.logging.ReportHosts.label(host)} last60s=${budget.requests(domain, 60_000L)} " +
                        "retryAfter=${response.header("Retry-After")?.take(20) ?: "none"}"
                )
                sink(
                    "NET_REFUSED code=${response.code} host=$host domain=$domain " +
                        "last60s=${budget.requests(domain, 60_000L)} last300s=${budget.requests(domain, 300_000L)} " +
                        "headers=[${interestingHeaders(response.headers)}] page=[${pageStart(page)}]"
                )
            }
        }
        return response
    }

    companion object {
        private const val PEEK_BYTES = 2_048L
        private const val MAX_VALUE = 100
        private const val MAX_PAGE = 160
        private val NAMES = listOf("server", "cf-ray", "cf-mitigated", "cf-cache-status", "retry-after", "via", "content-type", "content-length")
        private val TITLE = Regex("""(?is)<title[^>]*>(.*?)</title>""")
        private val SPACES = Regex("""\s+""")

        fun interestingHeaders(headers: Headers): String =
            headers.names()
                .filter { name -> name.lowercase().let { it in NAMES || it.startsWith("x-ratelimit") || it.startsWith("ratelimit") } }
                .sorted()
                .joinToString(" ") { name -> "$name=${headers[name].orEmpty().take(MAX_VALUE)}" }

        fun pageStart(page: String?): String {
            if (page.isNullOrBlank()) return ""
            val text = TITLE.find(page)?.groupValues?.get(1) ?: page
            return SPACES.replace(text, " ").trim().withoutRequestSecrets().take(MAX_PAGE)
        }

        private fun isText(type: String?): Boolean =
            type != null && (type.startsWith("text/", ignoreCase = true) || type.contains("json", ignoreCase = true))
    }
}
