package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideFormatException
import java.util.logging.Logger

object IptvLog {
    private val logger = Logger.getLogger("NuvioIptv")
    fun info(message: String) = logger.info(message)
    fun failure(stage: String, error: Throwable) {
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.nuvio") } ?: error.stackTrace.firstOrNull()
        val cause = generateSequence(error.cause) { it.cause }.take(3).joinToString(" <- ") { it.javaClass.simpleName }
        val detail = when {
            error is MetadataException -> " ${error.failure}${error.status?.let { " $it" }.orEmpty()}"
            error is GuideFormatException -> " ${error.issue}"
            error is IllegalArgumentException && error.stackTrace.firstOrNull()?.className?.startsWith("com.nuvio.tv.core.iptv.") == true -> " ${error.message}"
            else -> ""
        }
        logger.warning("$stage ${error.javaClass.simpleName}$detail" +
            " at ${frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()}${if (cause.isEmpty()) "" else " cause $cause"}")
    }
}
