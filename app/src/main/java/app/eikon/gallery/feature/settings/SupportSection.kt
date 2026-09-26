package app.eikon.gallery.feature.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R

/** Where a donation goes: a Lightning address, the same one the author's other apps use. Nothing is sent from eikon; the wallet the user picks does it. */
internal object Support {
    const val LIGHTNING_ADDRESS = "lwb89@blink.sv"

    /** Opens the Lightning wallet the user has installed; without one, copies the address so it can be pasted anywhere. Returns true when it had to copy. */
    fun open(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$LIGHTNING_ADDRESS")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return false
        } catch (_: ActivityNotFoundException) {
            copy(context)
            return true
        }
    }

    private fun copy(context: Context) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Lightning address", LIGHTNING_ADDRESS))
    }
}

/** The last thing in Settings: eikon is free, and this is how to say thanks. [onCopied] is called when no wallet was found and the address went to the clipboard instead. */
@Composable
internal fun SupportSection(onCopied: () -> Unit) {
    val context = LocalContext.current
    GroupTitle(R.string.support_title)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.support_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = { if (Support.open(context)) onCopied() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Icon(painterResource(R.drawable.ic_favorite), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.support_button))
        }
    }
}
