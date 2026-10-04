package com.nuvio.tv.core.di

import com.nuvio.tv.core.party.PartyFriendStore
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class PartyModule {
    @Binds
    @IntoSet
    abstract fun bindPartyFriendStore(store: PartyFriendStore): ProfileScopedCredentialStore
}
