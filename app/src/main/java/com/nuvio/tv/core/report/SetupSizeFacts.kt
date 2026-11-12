package com.nuvio.tv.core.report

import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.repository.ArcSetupSize
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/** Adds the size of the viewer's set-up to every report they send: how many addons, catalogues and list entries. */
@Singleton
class SetupSizeFacts @Inject constructor(
    private val library: LibraryPreferences,
    private val watched: WatchedItemsPreferences,
    private val progress: WatchProgressPreferences
) : ReportFactsProvider {
    override suspend fun reportFacts(): Map<String, String> {
        ArcSetupSize.noteLists(
            library = library.getAllItems().size,
            watched = watched.getAllItems().size,
            progress = progress.getAllRawEntries().size
        )
        return ArcSetupSize.facts()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SetupSizeFactsModule {
    @Binds
    @IntoSet
    abstract fun setupSize(facts: SetupSizeFacts): ReportFactsProvider
}
