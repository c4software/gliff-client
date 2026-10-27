package io.github.c4software.gliff.presentation.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.presentation.theme.Strings
import io.github.c4software.gliff.presentation.theme.rememberStrings
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The window's open sessions, one tab each, and a button to open another.
 * A tab is named after its host; tabs of the same host (a machine and its
 * clone) also say what they show.
 */
@Composable
fun SessionTabBar(
    tabs: List<SessionTab>,
    selected: String?,
    strings: Strings,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val active = tab.machineId == selected
            val label = tabLabel(tab.machine, tabs, strings)
            Row(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) colors.surfaceVariant else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(tab.machineId) }
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    Modifier.widthIn(max = 220.dp),
                    color = if (active) colors.onSurface else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onClose(tab.machineId) }
                        .semantics { contentDescription = "${strings.closeTab}: $label" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onAdd)
                .semantics { contentDescription = strings.openAnother },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun tabLabel(machine: Machine?, tabs: List<SessionTab>, strings: Strings): String {
    machine ?: return "…"
    val twins = tabs.count { it.machine?.host == machine.host } > 1
    if (!twins) return machine.host
    return "${machine.host} · " + when {
        machine.headless -> strings.privateScreen
        machine.output.isBlank() -> strings.mirror
        else -> machine.output.trim()
    }
}

/**
 * The selected tab's session, each tab with its own ViewModels and screen
 * state (pointer, keyboard, zoom). `content` draws it, given how to close the
 * tab and the tab bar to put above the picture. The tab bar's + shows
 * `machines` in its place, under the tabs, in the same window; it is given
 * what opens a machine in a tab. `onEmpty`: the last tab was closed.
 */
@Composable
fun SessionTabs(
    tabs: SessionTabsViewModel,
    onEmpty: () -> Unit,
    machines: @Composable (onOpen: (Machine) -> Unit) -> Unit,
    strings: Strings = rememberStrings(),
    content: @Composable (viewModel: SessionViewModel, onClose: () -> Unit, tabBar: @Composable () -> Unit) -> Unit,
) {
    val state by tabs.uiState.collectAsState()
    val machineId = state.selected
    if (machineId == null) {
        // Before the first tab shows, null is only the state's start.
        if (tabs.isEmpty) LaunchedEffect(Unit) { onEmpty() }
        return
    }
    if (state.listing) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing.only(TOP_AND_SIDES))) {
                SessionTabBar(state.tabs, null, strings, tabs::select, tabs::close) {}
            }
            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                machines { tabs.open(it.id) }
            }
        }
        return
    }
    key(machineId) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides tabs.owner(machineId)) {
            val viewModel = koinViewModel<SessionViewModel> { parametersOf(machineId) }
            content(viewModel, { tabs.close(machineId) }) {
                SessionTabBar(state.tabs, machineId, strings, tabs::select, tabs::close) { tabs.showList(true) }
            }
        }
    }
}

private val TOP_AND_SIDES = WindowInsetsSides.Top + WindowInsetsSides.Horizontal
