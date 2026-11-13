package com.nuvio.tv.core.di

import com.nuvio.tv.core.auth.SignOutSafetyCopy
import com.nuvio.tv.core.backup.BackupManager
import com.nuvio.tv.core.backup.BackupSection
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Multibinds
    abstract fun backupSections(): Set<BackupSection>

    companion object {
        @Provides
        fun signOutSafetyCopy(manager: Lazy<BackupManager>): SignOutSafetyCopy =
            SignOutSafetyCopy { manager.get().saveSafetyCopy() }
    }
}
