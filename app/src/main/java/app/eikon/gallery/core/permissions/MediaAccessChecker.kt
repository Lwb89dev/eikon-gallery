package app.eikon.gallery.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.eikon.gallery.domain.MediaAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the current media permission state. Photo access is three-valued on Android 14+: everything,
 * a hand-picked subset (READ_MEDIA_VISUAL_USER_SELECTED only), or nothing.
 */
@Singleton
class MediaAccessChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun current(): MediaAccess = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> tiramisuAccess()
        granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL
        else -> MediaAccess.NONE
    }

    /** True when ACCESS_MEDIA_LOCATION is granted, i.e. original (un-redacted) GPS can be read. */
    fun canReadLocation(): Boolean = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun tiramisuAccess(): MediaAccess {
        val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
        val video = granted(Manifest.permission.READ_MEDIA_VIDEO)
        return when {
            images && video -> MediaAccess.FULL
            selectedOnly() -> MediaAccess.LIMITED
            images || video -> MediaAccess.FULL
            else -> MediaAccess.NONE
        }
    }

    private fun selectedOnly(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** What to request in one go; the system shows the right dialog for the running Android version. */
        fun mediaPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
