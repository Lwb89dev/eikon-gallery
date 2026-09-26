package app.eikon.gallery.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackupSettingsTest {
    @Test
    fun onlyHttpsAddressesAreAccepted() {
        assertEquals("https://cloud.example.com", BackupSettings.normalizedUrl("https://cloud.example.com"))
        assertEquals("", BackupSettings.normalizedUrl("http://cloud.example.com"))
        assertEquals("", BackupSettings.normalizedUrl("cloud.example.com"))
        assertEquals("", BackupSettings.normalizedUrl("ftp://cloud.example.com"))
        assertEquals("", BackupSettings.normalizedUrl("https://"))
        assertEquals("", BackupSettings.normalizedUrl(""))
    }

    @Test
    fun theAddressIsTidiedButItsPathKeepsItsCase() {
        assertEquals("https://cloud.example.com:8443/Nextcloud", BackupSettings.normalizedUrl("  HTTPS://Cloud.Example.com:8443/Nextcloud/ "))
    }

    @Test
    fun changingTheServerTheLoginOrTheFolderIsADifferentDestination() {
        val base = BackupSettings(kind = ServerKind.NEXTCLOUD, serverUrl = "https://a.example", username = "me", folder = "eikon")
        assertNotEquals(base.destination, base.copy(serverUrl = "https://b.example").destination)
        assertNotEquals(base.destination, base.copy(username = "you").destination)
        assertNotEquals(base.destination, base.copy(folder = "other").destination)
        assertNotEquals(base.destination, base.copy(kind = ServerKind.IMMICH).destination)
    }

    @Test
    fun optionsThatDoNotChangeWhereItGoesKeepTheDestination() {
        val base = BackupSettings(serverUrl = "https://a.example", username = "me")
        assertEquals(base.destination, base.copy(wifiOnly = false, chargingOnly = true, includeVideos = false, includeHidden = true, enabled = true, pinnedCertificate = "ab").destination)
        assertEquals(base.destination, base.copy(serverUrl = "https://A.example/").destination)
    }

    @Test
    fun nothingIsOnByDefault() {
        val defaults = BackupSettings()
        assertEquals(false, defaults.enabled)
        assertEquals(false, defaults.includeHidden)
        assertEquals(true, defaults.wifiOnly)
    }
}
