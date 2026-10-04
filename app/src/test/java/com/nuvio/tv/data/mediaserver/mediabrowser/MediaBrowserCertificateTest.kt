package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBrowserCertificateTest {
    private suspend fun failureFor(error: IOException): ServerException {
        val http = TestHttp { throw error }
        return runCatching {
            JellyfinProvider(http.client, testIdentity).signIn("https://media.home", "viewer", "pw")
        }.exceptionOrNull() as ServerException
    }

    @Test
    fun certificateProblemsAreToldApartFromAnUnreachableServer() = runTest {
        val untrusted = failureFor(SSLHandshakeException("untrusted"))
        assertEquals(ServerFailure.CERTIFICATE, untrusted.failure)
        assertFalse(untrusted.network)
        assertEquals(ServerFailure.CERTIFICATE, failureFor(SSLPeerUnverifiedException("wrong name")).failure)

        val offline = failureFor(IOException("connection refused"))
        assertEquals(ServerFailure.UNREACHABLE, offline.failure)
        assertTrue(offline.network)
    }
}
