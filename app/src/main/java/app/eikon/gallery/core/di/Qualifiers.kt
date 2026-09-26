package app.eikon.gallery.core.di

import javax.inject.Qualifier

/** Process-wide scope for work that must outlive any screen (library sync). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** DataStore holding user-visible settings and the last library view. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsStore

/** DataStore holding the backup's settings and the summary of its last run (the credential is not in it: it is encrypted, see SecretStore). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupStore

/** DataStore holding sync bookkeeping (last generation, MediaStore version, access level). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SyncStore
