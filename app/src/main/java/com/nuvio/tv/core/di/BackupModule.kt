package com.nuvio.tv.core.di

import com.nuvio.tv.core.backup.BackupSection
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Multibinds
    abstract fun backupSections(): Set<BackupSection>
}
