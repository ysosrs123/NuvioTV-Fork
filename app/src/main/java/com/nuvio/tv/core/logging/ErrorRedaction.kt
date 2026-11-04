package com.nuvio.tv.core.logging

import java.net.UnknownHostException

private val HEADERS_LINE = Regex("""(?m)^[ \t]*Headers:.*$""")
private val BEARER = Regex("""(?i)\b(bearer[ \t]+)[^\s,\]]+""")
private val JWT = Regex("""\beyJ[\w-]+\.[\w-]+\.[\w-]*""")

/** Supabase errors list the request headers (access token, apikey) in their message; those are dropped. */
fun String.withoutRequestSecrets(): String {
    val noHeaders = HEADERS_LINE.replace(this, "Headers: REDACTED")
    val noBearer = BEARER.replace(noHeaders) { match -> match.groupValues[1] + "REDACTED" }
    return JWT.replace(noBearer, "REDACTED").redactLinksForLog()
}

/** The error to hand to Log: same class name, stack and causes, with each message cleaned. */
fun Throwable.forLog(): Throwable {
    val chain = generateSequence(this) { it.cause }.take(MAX_CAUSES).toList()
    // Log prints nothing for these; keeping the original keeps it that way.
    if (chain.any { it is UnknownHostException }) return this
    return chain.foldRight<Throwable, Throwable?>(null) { error, cause -> LoggedError(error, cause) } ?: this
}

private const val MAX_CAUSES = 16

private class LoggedError(source: Throwable, cause: Throwable?) : Throwable(
    source.message?.withoutRequestSecrets(),
    cause
) {
    private val className = source.javaClass.name

    init {
        stackTrace = source.stackTrace
    }

    override fun fillInStackTrace(): Throwable = this

    override fun toString(): String = message?.let { "$className: $it" } ?: className
}
