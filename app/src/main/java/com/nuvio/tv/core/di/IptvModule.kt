package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.iptv.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IptvModule {
    @Provides @Singleton fun deviceProfile(@ApplicationContext context: Context) = AndroidDeviceProfile.read(context)
    @Provides @Singleton fun admission(profile: com.nuvio.tv.core.iptv.IptvDeviceProfile) = com.nuvio.tv.core.iptv.LiveSessionAdmission(
        com.nuvio.tv.core.iptv.DeviceAdmissionLimits(profile.maxTiles, 192L * 1024 * 1024, 0))
    @Provides @Singleton fun liveRuntime(admission: com.nuvio.tv.core.iptv.LiveSessionAdmission) = com.nuvio.tv.core.iptv.LivePlaybackRuntime(admission)
    @Provides @Singleton fun vodStore(@ApplicationContext context: Context) = IptvVodStore(context)
    @Provides @Singleton fun catalogue(@ApplicationContext context: Context, vod: IptvVodStore) = IptvCatalogueStore(context).also { it.addRemovalListener(vod) }
    @Provides @Singleton fun vod(store: IptvVodStore, catalogue: IptvCatalogueStore, live: IptvLivePreferences) =
        IptvVodRepository(store, catalogue, userAgent = live::userAgent)
    @Provides @Singleton fun guides(@ApplicationContext context: Context) = IptvGuideStore(context)
    @Provides @Singleton fun shortGuides(catalogue: IptvCatalogueStore) = IptvShortGuideRepository(catalogue)
    @Provides @Singleton fun access(catalogue: IptvCatalogueStore, guides: IptvGuideStore) = IptvProfileAccess(catalogue, guides)
    @Provides @Singleton fun livePreferences(@ApplicationContext context: Context) = IptvLivePreferences(context)
    @Provides @Singleton fun sportsPreferences(@ApplicationContext context: Context) = IptvSportsPreferences(context)
    @Provides @Singleton fun sportsFixtures(@ApplicationContext context: Context, preferences: IptvSportsPreferences, catalogue: IptvCatalogueStore,
        guides: IptvGuideStore, live: IptvLivePreferences) = IptvSportsFixturesRepository(preferences, catalogue, guides, IptvSportsFixturesClient(),
        IptvSportsFixturesStore(java.io.File(context.cacheDir, "iptv-sports"))) { IptvGuideDaysPreference(live).days.future }
    @Provides @Singleton fun sportsLive(repository: IptvSportsFixturesRepository, preferences: IptvSportsPreferences) = IptvSportsLive(repository, preferences)
    @Provides @Singleton fun sportsSummary() = IptvSportsSummaryClient()
    @Provides @IntoSet fun credentials(access: IptvProfileAccess, live: IptvLivePreferences): ProfileScopedCredentialStore = object : ProfileScopedCredentialStore {
        override fun removeProfile(profileId: Int) { access.removeProfile(profileId); live.removeProfile(profileId) }
        override fun clearAllProfiles() { access.clearAllProfiles(); live.clearAllProfiles() }
    }
}
