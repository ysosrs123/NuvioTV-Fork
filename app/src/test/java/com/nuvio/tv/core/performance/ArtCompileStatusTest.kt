package com.nuvio.tv.core.performance

import androidx.profileinstaller.ProfileVerifier.CompilationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtCompileStatusTest {

    @Test
    fun `a box still running the app uncompiled says so`() {
        assertEquals(
            "ART_COMPILE: state=waiting-for-compile code=${CompilationStatus.RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION} " +
                "compiledWithProfile=false enqueued=true apkProfile=true",
            ArtCompileStatus.describe(CompilationStatus.RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION, false, true, true)
        )
    }

    @Test
    fun `every result code gets a plain name`() {
        val names = mapOf(
            CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE to "compiled",
            CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE_NON_MATCHING to "compiled-other-profile",
            CompilationStatus.RESULT_CODE_NO_PROFILE_INSTALLED to "not-compiled",
            CompilationStatus.RESULT_CODE_ERROR_NO_PROFILE_EMBEDDED to "no-profile-in-apk",
            CompilationStatus.RESULT_CODE_ERROR_UNSUPPORTED_API_VERSION to "unsupported-android",
            9_999 to "error-9999"
        )
        names.forEach { (code, name) ->
            val line = ArtCompileStatus.describe(code, false, false, true)
            assertEquals(line, "state=$name", line.substringAfter("ART_COMPILE: ").substringBefore(" code="))
        }
    }
}
