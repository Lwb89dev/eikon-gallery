package app.eikon.gallery.core.di

import app.eikon.gallery.data.backup.BackupService
import app.eikon.gallery.data.backup.NoBackup
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object BackupFlavorModule {
    @Provides
    fun backupService(): BackupService = NoBackup
}
