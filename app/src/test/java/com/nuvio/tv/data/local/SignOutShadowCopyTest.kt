package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SignOutShadowCopyTest {
    private val apiKey = stringPreferencesKey("real_debrid_api_key")
    private val dirs = mutableListOf<File>()
    private val factories = mutableListOf<ProfileDataStoreFactory>()

    private fun factoryIn(dir: File): ProfileDataStoreFactory {
        val context = mockk<Context>()
        every { context.filesDir } returns dir
        every { context.applicationContext } returns context
        return ProfileDataStoreFactory(context).also { factories += it }
    }

    private fun newDir(): File = Files.createTempDirectory("shadow-copy-").toFile().also { dirs += it }

    private suspend fun awaitExists(file: File) {
        withTimeout(10_000) { while (!file.exists()) delay(10) }
    }

    private suspend fun readAfterCorruption(dir: File): Preferences {
        val restart = newDir()
        File(dir, "datastore").copyRecursively(File(restart, "datastore"))
        File(restart, "datastore/debrid_settings.preferences_pb").writeBytes(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f))
        return factoryIn(restart).get(1, "debrid_settings").data.first()
    }

    @After
    fun cleanUp() = runBlocking {
        factories.forEach { runCatching { it.clearProfileScopedData() } }
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `a damaged store still comes back from its shadow copy`() = runBlocking {
        val dir = newDir()
        val count = intPreferencesKey("count")
        val enabled = booleanPreferencesKey("enabled")
        val tags = stringSetPreferencesKey("tags")
        factoryIn(dir).get(1, "debrid_settings").edit {
            it[apiKey] = "current-key"
            it[count] = 7
            it[enabled] = true
            it[tags] = setOf("a", "b")
        }
        awaitExists(File(dir, "datastore/debrid_settings.preferences_pb.bak"))

        val restored = readAfterCorruption(dir)
        assertEquals("current-key", restored[apiKey])
        assertEquals(7, restored[count])
        assertEquals(true, restored[enabled])
        assertEquals(setOf("a", "b"), restored[tags])
    }

    @Test
    fun `the shadow copy ends on the newest values after quick edits`() = runBlocking {
        val dir = newDir()
        val store = factoryIn(dir).get(1, "debrid_settings")
        repeat(50) { i -> store.edit { it[apiKey] = "key-$i" } }
        val shadow = File(dir, "datastore/debrid_settings.preferences_pb.bak")

        withTimeout(20_000) {
            do delay(1_000) while (shadowValue(shadow) != "key-49")
        }
        assertEquals("key-49", readAfterCorruption(dir)[apiKey])
    }

    private suspend fun shadowValue(shadow: File): String? = runCatching {
        val source = okio.Buffer().write(shadow.readBytes())
        PreferencesSerializer.readFrom(source)[apiKey]
    }.getOrNull()

    @Test
    fun `after sign-out no shadow copy holds the old account's keys`() = runBlocking {
        val dir = newDir()
        val factory = factoryIn(dir)
        factory.get(1, "debrid_settings").edit { it[apiKey] = "old-account-key" }
        val shadow = File(dir, "datastore/debrid_settings.preferences_pb.bak")
        awaitExists(shadow)

        factory.clearProfileScopedData()

        assertFalse(shadow.exists())
        assertNull(readAfterCorruption(dir)[apiKey])
    }

    @Test
    fun `a shadow copy left behind by an interrupted sign-out goes at the next start`() = runBlocking {
        val dir = newDir()
        factoryIn(dir).get(1, "debrid_settings").edit { it[apiKey] = "old-account-key" }
        awaitExists(File(dir, "datastore/debrid_settings.preferences_pb.bak"))
        val restart = newDir()
        File(dir, "datastore").copyRecursively(File(restart, "datastore"))
        File(restart, "datastore/debrid_settings.preferences_pb").writeBytes(ByteArray(0))
        val shadow = File(restart, "datastore/debrid_settings.preferences_pb.bak")
        assertTrue(shadow.exists())

        assertNull(factoryIn(restart).get(1, "debrid_settings").data.first()[apiKey])
        withTimeout(10_000) { while (shadow.exists()) delay(10) }
    }

    @Test
    fun `emptying a store removes its shadow copy`() = runBlocking {
        val dir = newDir()
        val store = factoryIn(dir).get(1, "debrid_settings")
        store.edit { it[apiKey] = "key" }
        val shadow = File(dir, "datastore/debrid_settings.preferences_pb.bak")
        awaitExists(shadow)

        store.edit { it.remove(apiKey) }
        withTimeout(10_000) { while (shadow.exists()) delay(10) }

        assertNull(readAfterCorruption(dir)[apiKey])
    }

    @Test
    fun `a start with an up-to-date shadow copy leaves it alone`() = runBlocking {
        val dir = newDir()
        factoryIn(dir).get(1, "debrid_settings").edit { it[apiKey] = "key" }
        val shadow = File(dir, "datastore/debrid_settings.preferences_pb.bak")
        awaitExists(shadow)
        delay(300)
        val restart = newDir()
        File(dir, "datastore").copyRecursively(File(restart, "datastore"))
        val now = System.currentTimeMillis()
        File(restart, "datastore/debrid_settings.preferences_pb").setLastModified(now - 20_000)
        val restartShadow = File(restart, "datastore/debrid_settings.preferences_pb.bak")
        restartShadow.setLastModified(now - 10_000)
        val writtenAt = restartShadow.lastModified()

        assertEquals("key", factoryIn(restart).get(1, "debrid_settings").data.first()[apiKey])
        delay(500)

        assertEquals(writtenAt, restartShadow.lastModified())
    }

    @Test
    fun `an edit that changes nothing writes no shadow copy`() = runBlocking {
        val dir = newDir()
        val store = factoryIn(dir).get(1, "debrid_settings")
        store.edit { it[apiKey] = "key" }
        val shadow = File(dir, "datastore/debrid_settings.preferences_pb.bak")
        awaitExists(shadow)
        delay(300)
        val writtenAt = shadow.lastModified()

        store.edit { it[apiKey] = "key" }
        delay(500)

        assertEquals(writtenAt, shadow.lastModified())
    }

    @Test
    fun `tracker histories keep no shadow copy and an old one goes`() = runBlocking {
        val dir = newDir()
        val datastore = File(dir, "datastore").apply { mkdirs() }
        val oldShadow = File(datastore, "mdblist_sync.preferences_pb.bak").apply { writeBytes(ByteArray(64) { 1 }) }
        val snapshot = stringPreferencesKey("snapshot")

        factoryIn(dir).get(1, "mdblist_sync").edit { it[snapshot] = "{}" }
        withTimeout(10_000) { while (oldShadow.exists()) delay(10) }
        delay(300)

        assertFalse(oldShadow.exists())
    }
}
