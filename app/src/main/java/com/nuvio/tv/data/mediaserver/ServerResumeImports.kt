package com.nuvio.tv.data.mediaserver

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Remembers which server resume points were already brought into Continue Watching. */
interface ServerResumeImports {
    fun read(profileId: Int, connectionId: String): Map<String, Long>
    fun write(profileId: Int, connectionId: String, imported: Map<String, Long>)
}

@Singleton
class AndroidServerResumeImports @Inject constructor(
    @ApplicationContext context: Context
) : ServerResumeImports {
    private val preferences = context.getSharedPreferences("media_server_resume_imports", Context.MODE_PRIVATE)
    private val serializer = MapSerializer(String.serializer(), Long.serializer())

    override fun read(profileId: Int, connectionId: String): Map<String, Long> =
        preferences.getString(key(profileId, connectionId), null)
            ?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    override fun write(profileId: Int, connectionId: String, imported: Map<String, Long>) {
        preferences.edit().putString(key(profileId, connectionId), Json.encodeToString(serializer, imported)).apply()
    }

    private fun key(profileId: Int, connectionId: String) = "$profileId:$connectionId"
}
