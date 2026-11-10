package com.nuvio.tv.core.scout

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Removes a deleted profile's Scout files, or every Scout file on sign-out, so a later profile starts clean. */
@Singleton
class ScoutProfileFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: dagger.Lazy<ScoutEngine>
) : ProfileScopedCredentialStore {
    private fun folder() = File(context.filesDir, "scout")

    override fun removeProfile(profileId: Int) {
        scoutDeleteProfileFiles(folder(), profileId)
        engine.get().forget(profileId)
    }

    override fun clearAllProfiles() {
        scoutDeleteProfileFiles(folder(), null)
        engine.get().forget(null)
    }
}

internal fun scoutDeleteProfileFiles(folder: File, profileId: Int?) {
    if (profileId == null) {
        folder.deleteRecursively()
        return
    }
    listOf("pool", "ai", "taste", "shown", "mood_ai").forEach { File(folder, "${it}_$profileId.json").delete() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ScoutProfileFilesModule {
    @Binds
    @IntoSet
    abstract fun bindFiles(files: ScoutProfileFiles): ProfileScopedCredentialStore
}
