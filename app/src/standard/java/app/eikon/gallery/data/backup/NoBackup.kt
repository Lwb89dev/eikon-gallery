package app.eikon.gallery.data.backup

/** The `standard` build has no network access at all, so there is nothing to back up to. The `backup` build is the same app with the backup added (see docs/BACKUP.md). */
object NoBackup : BackupService {
    override val isAvailable: Boolean = false

    override fun start() = Unit
}
