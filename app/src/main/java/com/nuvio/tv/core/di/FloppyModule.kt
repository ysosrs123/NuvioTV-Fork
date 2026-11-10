package com.nuvio.tv.core.di

import android.content.Context
import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.data.floppy.AndroidFloppyAuthPersistence
import com.nuvio.tv.data.floppy.AndroidFloppyPlayPersistence
import com.nuvio.tv.data.floppy.FloppyApiClient
import com.nuvio.tv.data.floppy.FloppyAuthRepository
import com.nuvio.tv.data.floppy.FloppyAuthStore
import com.nuvio.tv.data.floppy.FloppyHttpEngine
import com.nuvio.tv.data.floppy.FloppyPlayLedger
import com.nuvio.tv.data.floppy.FloppyTrackingHistoryWriter
import com.nuvio.tv.data.floppy.FloppyTrackingProvider
import com.nuvio.tv.data.floppy.OkHttpFloppyEngine
import com.nuvio.tv.data.local.ProfileDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object FloppyModule {
    @Provides
    @Singleton
    fun http(): FloppyHttpEngine = OkHttpFloppyEngine(
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .socketFactory(NoDelaySocketFactory)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build(),
        BuildConfig.VERSION_NAME
    )

    @Provides
    @Singleton
    fun authStore(persistence: AndroidFloppyAuthPersistence, profileDataStore: ProfileDataStore): FloppyAuthStore {
        val store = FloppyAuthStore(persistence)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            profileDataStore.activeProfileId.collect(store::selectProfile)
        }
        return store
    }

    @Provides
    @IntoSet
    fun credentialStore(store: FloppyAuthStore): ProfileScopedCredentialStore = store

    @Provides
    @Singleton
    fun auth(@ApplicationContext context: Context, http: FloppyHttpEngine, store: FloppyAuthStore) =
        FloppyAuthRepository(
            http,
            store,
            context.getString(R.string.floppy_client_name, context.getString(R.string.app_name), Build.MODEL.orEmpty().ifBlank { "Android TV" })
        )

    @Provides
    @Singleton
    fun api(http: FloppyHttpEngine, auth: FloppyAuthRepository, store: FloppyAuthStore) =
        FloppyApiClient(http, auth, store)

    @Provides
    @Singleton
    fun playLedger(@ApplicationContext context: Context) = FloppyPlayLedger(AndroidFloppyPlayPersistence(context))

    @Provides
    @IntoSet
    fun provider(provider: FloppyTrackingProvider): TrackingProvider = provider

    @Provides
    @IntoSet
    fun historyWriter(writer: FloppyTrackingHistoryWriter): TrackingHistoryWriter = writer
}
