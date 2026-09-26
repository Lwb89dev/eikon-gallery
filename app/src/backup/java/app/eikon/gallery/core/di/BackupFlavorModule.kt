package app.eikon.gallery.core.di

import app.eikon.gallery.data.backup.BackupService
import app.eikon.gallery.data.backup.HomeServerBackup
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupFlavorModule {
    @Binds
    abstract fun backupService(impl: HomeServerBackup): BackupService
}
