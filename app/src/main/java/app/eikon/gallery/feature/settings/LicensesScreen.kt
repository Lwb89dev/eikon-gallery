package app.eikon.gallery.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** NOTICE.md, bundled into the app by the build (see `bundleNotice`), read once and shaped for the screen. */
@HiltViewModel
class LicensesViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val loaded = MutableStateFlow<List<NoticeLine>>(emptyList())
    val lines: StateFlow<List<NoticeLine>> = loaded.asStateFlow()

    init {
        viewModelScope.launch { loaded.value = withContext(Dispatchers.IO) { read() } }
    }

    private fun read(): List<NoticeLine> = try {
        NoticeText.lines(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    } catch (_: IOException) {
        emptyList()
    }

    private companion object {
        const val ASSET = "NOTICE.md"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: LicensesViewModel = hiltViewModel()) {
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { LicensesTopBar(onBack) },
    ) { inner ->
        LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(lines) { line -> NoticeLineText(line) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensesTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.licenses_title)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun NoticeLineText(line: NoticeLine) {
    if (line.heading) {
        Text(line.text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    } else {
        Text(line.text, style = MaterialTheme.typography.bodySmall)
    }
}
