package com.nuvio.tv.data.trailer

import io.mockk.every
import io.mockk.mockk

/** Relaxed resolver whose SD fallback is empty unless a test sets it. */
internal fun relaxedImdbResolver(): ImdbTrailerResolver = mockk(relaxed = true) {
    every { lowResolutionFallback(any()) } returns null
}
