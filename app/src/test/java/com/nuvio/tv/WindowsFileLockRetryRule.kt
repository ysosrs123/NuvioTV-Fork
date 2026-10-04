package com.nuvio.tv

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException

/**
 * Windows refuses to replace or open a file while another handle is on it, which DataStore's
 * atomic rename runs into when a reader has the file open. Android has no such rule, so on a
 * Windows PC a test that fails only for that reason is run again. Nothing else is retried.
 */
class WindowsFileLockRetryRule(private val attempts: Int = 3) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            var last: Throwable? = null
            repeat(attempts) {
                try {
                    base.evaluate()
                    return
                } catch (error: Throwable) {
                    if (!isWindows || !error.isWindowsFileLock()) throw error
                    last = error
                }
            }
            throw last!!
        }
    }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun Throwable.isWindowsFileLock(): Boolean =
        generateSequence(this) { it.cause }.take(16).any { cause ->
            cause is AccessDeniedException ||
                (cause is FileNotFoundException && lockMessages.any { cause.message.orEmpty().contains(it) })
        }

    private companion object {
        val lockMessages = listOf("Access is denied", "being used by another process")
    }
}
