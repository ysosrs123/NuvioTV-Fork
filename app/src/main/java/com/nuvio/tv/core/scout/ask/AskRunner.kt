package com.nuvio.tv.core.scout.ask

import javax.inject.Inject
import javax.inject.Singleton

/** The one way a question gets asked, so every page also fills You asked. */
@Singleton
class AskRunner @Inject constructor(
    private val rows: AskRowsStore,
    private val service: AskService
) {
    suspend fun ask(rawQuestion: String): AskOutcome {
        val question = rawQuestion.trim().take(ASK_MAX_QUESTION)
        if (question.isEmpty()) return AskOutcome.Failed(AskFailure.NOTHING_FOUND)
        rows.remember(question)
        val outcome = service.ask(question)
        if (outcome is AskOutcome.Answer) {
            rows.rememberArt(question, outcome.items.ifEmpty { outcome.held }.mapNotNull { it.meta.poster })
        }
        return outcome
    }
}
