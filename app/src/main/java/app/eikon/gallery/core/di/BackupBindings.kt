package app.eikon.gallery.core.di

import android.content.Context
import app.eikon.gallery.data.backup.BackupEnvironment
import app.eikon.gallery.data.backup.BackupQueue
import app.eikon.gallery.data.backup.BackupSource
import app.eikon.gallery.data.backup.ContentResolverBackupSource
import app.eikon.gallery.data.backup.DeviceBackupEnvironment
import app.eikon.gallery.data.backup.KeystoreSecretStore
import app.eikon.gallery.data.backup.RoomBackupQueue
import app.eikon.gallery.data.backup.SecretStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The parts of the backup that have no network code and are the same in both builds. The service itself is bound per build (see src/standard and src/backup). */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupBindings {
    @Binds
    abstract fun queue(impl: RoomBackupQueue): BackupQueue

    @Binds
    abstract fun source(impl: ContentResolverBackupSource): BackupSource

    @Binds
    abstract fun environment(impl: DeviceBackupEnvironment): BackupEnvironment

    companion object {
        @Provides
        @Singleton
        fun secretStore(@ApplicationContext context: Context): SecretStore = KeystoreSecretStore.backup(context)
    }
}
