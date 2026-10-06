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
    @Provides @Singleton fun catalogue(@ApplicationContext context: Context) = IptvCatalogueStore(context)
    @Provides @Singleton fun guides(@ApplicationContext context: Context) = IptvGuideStore(context)
    @Provides @Singleton fun shortGuides(catalogue: IptvCatalogueStore) = IptvShortGuideRepository(catalogue)
    @Provides @Singleton fun access(catalogue: IptvCatalogueStore, guides: IptvGuideStore) = IptvProfileAccess(catalogue, guides)
    @Provides @IntoSet fun credentials(access: IptvProfileAccess): ProfileScopedCredentialStore = object : ProfileScopedCredentialStore {
        override fun removeProfile(profileId: Int) = access.removeProfile(profileId)
        override fun clearAllProfiles() = access.clearAllProfiles()
    }
}
