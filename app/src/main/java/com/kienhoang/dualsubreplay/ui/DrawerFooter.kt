package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's licence, the libraries' notices, then the ML Kit translation terms. */
internal val LICENSE_ASSETS =
    listOf("licenses/MIT.txt", "licenses/libraries.txt", "licenses/hanzi.txt", "licenses/translation.txt")

internal const val PROJECT_REPOSITORY_URL = "https://github.com/hoangkien1703/dual-sub-replay"
internal const val FORK_SOURCE_URL = "https://github.com/mrAlishah/triple-sub-replay/tree/mydevelop"

@Composable
private fun FooterLinkText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The bottom of the drawer: Check for updates, Report a problem, then the open-source licences and the GitHub link in small text. */
@Composable
internal fun DrawerFooter(beforeOpen: suspend () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLicense by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    val loadingLicense = stringResource(R.string.navigation_loading_license)
    val uriHandler = LocalUriHandler.current
    CheckForUpdatesRow(onOpen = { scope.launch { beforeOpen() } })
    TextButton(
        onClick = {
            scope.launch {
                beforeOpen()
                showReport = true
            }
        },
        modifier = Modifier.padding(start = 4.dp).testTag("report_problem"),
    ) {
        FooterLinkText(stringResource(R.string.report_problem_title))
    }
    if (showReport) ReportProblemDialog(onDismiss = { showReport = false })
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                scope.launch {
                    beforeOpen()
                    showLicense = true
                }
            },
            modifier = Modifier.testTag("open_source_licenses"),
        ) {
            FooterLinkText(stringResource(R.string.navigation_licenses))
        }
    }
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            Triple(R.string.updates_link_fork, FORK_SOURCE_URL, "github_fork_link"),
            Triple(R.string.updates_link_upstream, PROJECT_REPOSITORY_URL, "github_repository_link"),
        ).forEachIndexed { index, (label, url, tag) ->
            if (index > 0) Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = {
                    scope.launch {
                        beforeOpen()
                        uriHandler.openUri(url)
                    }
                },
                modifier = Modifier.testTag(tag),
            ) {
                FooterLinkText(stringResource(label))
            }
        }
    }
    if (showLicense) {
        val license by produceState(loadingLicense) {
            value =
                withContext(Dispatchers.IO) {
                    LICENSE_ASSETS.joinToString("\n\n") { name ->
                        context.assets
                            .open(name)
                            .bufferedReader()
                            .use { it.readText() }
                    }
                }
        }
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { DialogTitle(stringResource(R.string.navigation_licenses), onClose = { showLicense = false }) },
            text = {
                Text(
                    stringResource(R.string.navigation_license_summary) + "\n\n" + license,
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {},
        )
    }
}
