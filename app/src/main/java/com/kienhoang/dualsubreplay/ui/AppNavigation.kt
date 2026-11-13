package com.kienhoang.dualsubreplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.launch

@Composable
internal fun AppNavigation(
    onPractice: () -> Unit,
    onSettings: () -> Unit,
    onProgress: () -> Unit = {},
    onVisibilityChange: (Boolean) -> Unit = {},
    tabs: BrowserTabs? = null,
    differentTabAccounts: Boolean = false,
    tabActions: BrowserTabActions = BrowserTabActions(),
    content: @Composable (menuButton: @Composable () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val tvLayout = LocalTvLayout.current
    val drawerActive = drawer.currentValue == DrawerValue.Open || drawer.targetValue == DrawerValue.Open
    val menuFocus = remember { FocusRequester() }
    val drawerFocus = remember { FocusRequester() }
    LaunchedEffect(drawerActive) {
        onVisibilityChange(drawerActive)
    }
    // A remote enters the drawer when it opens and returns to the menu button when it closes.
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(drawerActive, tvLayout) {
        if (!tvLayout) return@LaunchedEffect
        if (drawerActive) {
            wasOpen = true
            runCatching { drawerFocus.requestFocus() }
        } else if (wasOpen) {
            wasOpen = false
            runCatching { menuFocus.requestFocus() }
        }
    }
    DisposableEffect(Unit) { onDispose { onVisibilityChange(false) } }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                BoxWithConstraints(Modifier.fillMaxHeight()) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(12.dp)
                            .focusRequester(drawerFocus)
                            .focusGroup()
                            // The sheet stays composed while closed: the remote may only reach it while it is open.
                            // After focusGroup() on purpose: focus properties stop at the nearest focus target above.
                            .focusProperties { canFocus = drawerActive || !tvLayout },
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            DrawerHeader(onClose = { scope.launch { drawer.close() } })
                            Spacer(Modifier.height(8.dp))
                            if (tabs != null) {
                                DrawerTabsSection(tabs, differentTabAccounts, tabActions, closeDrawer = { scope.launch { drawer.close() } })
                            }
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.School, contentDescription = null) },
                                label = { Text(stringResource(R.string.navigation_practice)) }, selected = false,
                                modifier = Modifier.tvFocusRing(DRAWER_ITEM_SHAPE).testTag("open_saved_words"),
                                onClick = { scope.launch { drawer.close(); onPractice() } },
                            )
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Insights, contentDescription = null) },
                                label = { Text(stringResource(R.string.navigation_progress)) },
                                selected = false,
                                modifier = Modifier.tvFocusRing(DRAWER_ITEM_SHAPE).testTag("open_progress"),
                                onClick = {
                                    scope.launch {
                                        drawer.close()
                                        onProgress()
                                    }
                                },
                            )
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text(stringResource(R.string.navigation_settings)) }, selected = false,
                                modifier = Modifier.tvFocusRing(DRAWER_ITEM_SHAPE),
                                onClick = { scope.launch { drawer.close(); onSettings() } },
                            )
                        }
                        Column(Modifier.padding(horizontal = 4.dp)) {
                            HorizontalDivider(Modifier.padding(vertical = 16.dp))
                            AppLanguageButton()
                            DrawerFooter(beforeOpen = { drawer.close() })
                        }
                    }
                }
            }
        },
    ) {
        AppUpdateDialogHost()
        // While the drawer is open the remote must not reach the screen behind its scrim.
        Box(Modifier.fillMaxSize().focusProperties { canFocus = !(drawerActive && tvLayout) }) {
            content { NavigationMenuButton(menuFocus) { scope.launch { drawer.open() } } }
        }
        AppNoticesHost()
    }
}

/** The hamburger: in every layout, TV included, where the remote and a mouse click both open the drawer. */
@Composable
private fun NavigationMenuButton(
    focus: FocusRequester,
    onOpen: () -> Unit,
) {
    Box(
        modifier = Modifier.size(width = 72.dp, height = 48.dp)
            .focusRequester(focus)
            .tvFocusRing(RoundedCornerShape(24.dp))
            .testTag("navigation_menu_button")
            .clickable(role = Role.Button, onClick = onOpen),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            Icons.Default.Menu,
            contentDescription = stringResource(R.string.navigation_open_menu),
            modifier = Modifier.padding(start = 12.dp).size(24.dp),
        )
    }
}

/** Under TV home the page and panels are not focus targets; everywhere else they are. */
internal fun pageFocusable(
    tvLayout: Boolean,
    tvHomeShown: Boolean,
): Boolean = !(tvLayout && tvHomeShown)

private val DRAWER_ITEM_SHAPE = RoundedCornerShape(28.dp)

@Composable
private fun DrawerHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ClosedCaption, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.navigation_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.navigation_fork_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("close_navigation_menu")) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.navigation_close_menu))
        }
    }
}
