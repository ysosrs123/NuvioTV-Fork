package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEmptyRetryTest {

    @Test fun `a Home still loading with nothing to show gets a fresh load`() {
        assertTrue(homeStillEmpty(loading = true, hasContent = false, hasError = false, presentationReady = false))
    }

    @Test fun `rows the Modern layout never drew get a fresh load`() {
        assertTrue(homeStillEmpty(loading = false, hasContent = true, hasError = false, presentationReady = false))
    }

    @Test fun `a drawn Home, an error message or an empty finished Home are left alone`() {
        assertFalse(homeStillEmpty(loading = false, hasContent = true, hasError = false, presentationReady = true))
        assertFalse(homeStillEmpty(loading = true, hasContent = true, hasError = false, presentationReady = true))
        assertFalse(homeStillEmpty(loading = true, hasContent = false, hasError = true, presentationReady = false))
        assertFalse(homeStillEmpty(loading = false, hasContent = false, hasError = false, presentationReady = false))
    }

    @Test fun `the caller line keeps app and navigation frames only`() {
        val frames = arrayOf(
            StackTraceElement("com.nuvio.tv.ui.screens.home.HomeViewModel", "<init>", "HomeViewModel.kt", 549),
            StackTraceElement("dagger.hilt.android.internal.Factory", "create", "Factory.java", 10),
            StackTraceElement("androidx.navigation.compose.NavHostKt", "NavHost", "NavHost.kt", 300),
            StackTraceElement("com.nuvio.tv.ui.navigation.NuvioNavHostKt", "PlaybackNavHost", "NuvioNavHost.kt", 243),
        )
        assertEquals("NavHostKt.NavHost:300 < NuvioNavHostKt.PlaybackNavHost:243", homeViewModelCaller(frames))
        assertEquals("unknown", homeViewModelCaller(emptyArray()))
    }
}
