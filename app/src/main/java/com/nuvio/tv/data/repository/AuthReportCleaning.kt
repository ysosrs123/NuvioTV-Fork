package com.nuvio.tv.data.repository

import com.nuvio.tv.core.logging.ReportHosts
import com.nuvio.tv.data.remote.dto.AuthDiagnosticEventDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticExceptionDto
import com.nuvio.tv.data.remote.dto.AuthDiagnosticReportRequestDto

/**
 * A sign-in report records request and response headers and bodies, which can hold tokens, codes and passwords.
 * Only harmless header values and cleaned bodies leave the box.
 */
internal fun AuthDiagnosticReportRequestDto.forUpload(): AuthDiagnosticReportRequestDto = copy(
    environment = environment.copy(
        supabaseUrl = ReportText.clean(environment.supabaseUrl, 200).orEmpty(),
        supabaseHost = ReportHosts.label(environment.supabaseHost),
        tvLoginWebBaseUrl = ReportText.clean(environment.tvLoginWebBaseUrl, 200).orEmpty(),
        tvLoginHost = ReportHosts.label(environment.tvLoginHost),
        tvLoginWebHost = ReportHosts.label(environment.tvLoginWebHost)
    ),
    timeline = timeline.map { it.forUpload() },
    exceptions = exceptions.map { it.forUpload() },
    rawLogs = rawLogs.mapNotNull { ReportText.rawLine(it, 2000) }
)

private fun AuthDiagnosticEventDto.forUpload(): AuthDiagnosticEventDto = copy(
    url = ReportText.clean(url?.substringBefore('?')?.substringBefore('#'), 500),
    request = request?.let { it.copy(headers = it.headers.safeValues(), body = it.body.cleanBody()) },
    response = response?.let { it.copy(headers = it.headers.safeValues(), body = it.body.cleanBody()) },
    network = network?.let { it.copy(proxy = ReportText.clean(it.proxy, 120), message = ReportText.clean(it.message, 500)) },
    exception = exception?.forUpload(),
    detail = detail?.mapValues { (_, value) -> ReportText.clean(value, 240).orEmpty() }
)

private fun AuthDiagnosticExceptionDto.forUpload(): AuthDiagnosticExceptionDto = copy(
    message = ReportText.clean(message, 1000),
    causeChain = causeChain.mapNotNull { ReportText.clean(it, 500) },
    stackTrace = ReportText.clean(stackTrace, 8000).orEmpty()
)

private fun Map<String, String>.safeValues(): Map<String, String> =
    mapValues { (name, value) -> if (name.lowercase() in SAFE_HEADERS) value.take(120) else "[redacted]" }

private fun String?.cleanBody(): String? {
    val body = this ?: return null
    return ReportText.clean(JSON_SECRET.replace(body) { "\"${it.groupValues[1]}\":\"[redacted]\"" }, 4000)
}

private val SAFE_HEADERS = setOf(
    "content-type", "content-length", "content-encoding", "date", "server", "retry-after", "cf-cache-status", "age",
    "cache-control", "x-ratelimit-limit", "x-ratelimit-remaining", "x-ratelimit-reset"
)

private val JSON_SECRET = Regex(
    """(?i)"(access_token|refresh_token|id_token|provider_token|provider_refresh_token|token|password|secret|code|device_code|user_code|api_key|apikey|email|phone)"\s*:\s*"[^"\\]*+(?:\\.?[^"\\]*+)*+(?:"|$)"""
)
