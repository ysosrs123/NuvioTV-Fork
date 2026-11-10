package com.nuvio.tv.ui.screens.search

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchVoiceWordsTest {
    @Test
    fun `final words win over the words heard so far`() {
        assertEquals("the lord of the rings", voiceSearchWords(" the lord of the rings ", "the lord of"))
    }

    @Test
    fun `an empty final result keeps the words heard so far`() {
        assertEquals("the lord of the", voiceSearchWords("", "the lord of the "))
        assertEquals("the lord of the", voiceSearchWords(null, "the lord of the"))
        assertEquals("", voiceSearchWords(null, ""))
    }

    @Test
    fun `a pause or timeout after words still searches for them`() {
        assertEquals("dune", voiceSearchWordsOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "dune"))
        assertEquals("dune", voiceSearchWordsOnError(SpeechRecognizer.ERROR_NO_MATCH, " dune"))
        assertEquals("dune", voiceSearchWordsOnError(SpeechRecognizer.ERROR_NETWORK, "dune"))
    }

    @Test
    fun `no words, a cancel or a busy service searches nothing`() {
        assertNull(voiceSearchWordsOnError(SpeechRecognizer.ERROR_NO_MATCH, " "))
        assertNull(voiceSearchWordsOnError(SpeechRecognizer.ERROR_CLIENT, "dune"))
        assertNull(voiceSearchWordsOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, "dune"))
        assertNull(voiceSearchWordsOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, "dune"))
    }
}
