package com.nuvio.tv.core.di

import com.nuvio.tv.core.calendar.CalendarClock
import com.nuvio.tv.core.calendar.CalendarRefreshInputs
import com.nuvio.tv.core.calendar.CalendarReminderData
import com.nuvio.tv.core.calendar.CalendarRemoteSources
import com.nuvio.tv.core.calendar.CalendarStorage
import com.nuvio.tv.core.calendar.DefaultCalendarRefreshInputs
import com.nuvio.tv.core.calendar.DefaultCalendarReminderData
import com.nuvio.tv.core.calendar.DefaultCalendarRemoteSources
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.remote.api.TvMazeApi
import com.nuvio.tv.ui.screens.calendar.CalendarScreenData
import com.nuvio.tv.ui.screens.calendar.DefaultCalendarScreenData
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.time.Clock
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@Module
@InstallIn(SingletonComponent::class)
abstract class CalendarModule {
    @Binds
    abstract fun bindCalendarRemoteSources(sources: DefaultCalendarRemoteSources): CalendarRemoteSources

    @Binds
    abstract fun bindCalendarRefreshInputs(inputs: DefaultCalendarRefreshInputs): CalendarRefreshInputs

    @Binds
    abstract fun bindCalendarScreenData(data: DefaultCalendarScreenData): CalendarScreenData

    @Binds
    abstract fun bindCalendarReminderData(data: DefaultCalendarReminderData): CalendarReminderData

    @Binds
    @IntoSet
    abstract fun bindCalendarStorage(storage: CalendarStorage): ProfileScopedCredentialStore

    companion object {
        @Provides
        @Singleton
        @Named("tvmaze")
        fun provideTvMazeRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
            Retrofit.Builder()
                .baseUrl("https://api.tvmaze.com/")
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

        @Provides
        @Singleton
        fun provideTvMazeApi(@Named("tvmaze") retrofit: Retrofit): TvMazeApi = retrofit.create(TvMazeApi::class.java)

        @Provides
        @CalendarClock
        fun provideCalendarClock(): Clock = Clock.systemUTC()
    }
}
