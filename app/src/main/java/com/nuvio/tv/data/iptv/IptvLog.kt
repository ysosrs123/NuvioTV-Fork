package com.nuvio.tv.data.iptv

import java.util.logging.Logger

object IptvLog {
    private val logger = Logger.getLogger("NuvioIptv")
    fun info(message: String) = logger.info(message)
    fun failure(stage: String, error: Throwable) {
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.nuvio") } ?: error.stackTrace.firstOrNull()
        val cause = generateSequence(error.cause) { it.cause }.take(3).joinToString(" <- ") { it.javaClass.simpleName }
        logger.warning("$stage ${error.javaClass.simpleName}${(error as? MetadataException)?.let { " ${it.failure}${it.status?.let { s -> " $s" }.orEmpty()}" }.orEmpty()}" +
            " at ${frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()}${if (cause.isEmpty()) "" else " cause $cause"}")
    }
}
