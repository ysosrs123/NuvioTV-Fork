package com.nuvio.tv.core.di

import com.nuvio.tv.core.iptv.LiveSessionAdmission
import com.nuvio.tv.data.iptv.IptvCatalogueStore
import com.nuvio.tv.data.iptv.IptvVodRepository
import com.nuvio.tv.data.iptv.IptvVodResolver
import com.nuvio.tv.data.iptv.IptvVodStore
import com.nuvio.tv.data.iptv.IptvVodStreams
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IptvVodModule {
    @Provides @Singleton fun vodStreams(repository: IptvVodRepository, catalogue: IptvCatalogueStore, store: IptvVodStore) =
        IptvVodStreams(repository, catalogue, store)
    @Provides @Singleton fun vodResolver(repository: IptvVodRepository, catalogue: IptvCatalogueStore, admission: LiveSessionAdmission) =
        IptvVodResolver(repository, catalogue, admission)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IptvVodEntryPoint {
    fun iptvVodResolver(): IptvVodResolver
}
