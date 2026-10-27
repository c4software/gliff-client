package io.github.c4software.gliff.presentation.machines

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.presentation.settings.ResolutionPicker
import io.github.c4software.gliff.presentation.settings.SettingsDialog
import io.github.c4software.gliff.presentation.theme.Strings
import io.github.c4software.gliff.presentation.theme.rememberStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.compose.viewmodel.koinViewModel

/** What a keyboard shortcut of the host asks of the list. */
enum class MachinesCommand { Add, Options }

/**
 * The saved machines: open one, add, edit, remove, and show this device's key.
 * `commands`: the host's keyboard shortcuts. `desktop`: the platform has
 * desktop windows, and the options offer full screen there.
 */
@Composable
fun MachinesRoute(
    onOpen: (Machine) -> Unit,
    commands: Flow<MachinesCommand> = emptyFlow(),
    desktop: Boolean = false,
    strings: Strings = rememberStrings(),
    viewModel: MachinesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showOptions by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Machine?>(null) }
    var cloning by remember { mutableStateOf<Machine?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    LaunchedEffect(commands) {
        commands.collect {
            when (it) {
                MachinesCommand.Add -> adding = true
                MachinesCommand.Options -> showOptions = true
            }
        }
    }

    MachinesContent(
        machines = state.machines,
        strings = strings,
        onOpen = onOpen,
        onAdd = { adding = true },
        onEdit = { editing = it },
        onDelete = viewModel::remove,
        onClone = { cloning = it },
        previews = state.previews,
        open = state.open,
        onShowKey = {
            viewModel.loadKey()
            showKey = true
        },
        onShowOptions = { showOptions = true },
    )

    if (showOptions) {
        SettingsDialog(
            settings = state.settings,
            mirrorUrls = state.mirrorUrls,
            strings = strings,
            desktop = desktop,
            onChange = viewModel::updateSettings,
            onDismiss = { showOptions = false },
        )
    }
    if (adding || editing != null || cloning != null) {
        // A clone is a new machine with the same settings, its own id from the start.
        val clone = cloning
        MachineDialog(
            initial = editing ?: clone?.let { remember(it) { it.copy(id = Machine.newId()) } },
            strings = strings,
            title = if (clone != null) strings.cloneMachine else null,
            onDismiss = {
                adding = false
                editing = null
                cloning = null
            },
            onSave = {
                viewModel.save(it)
                adding = false
                editing = null
                cloning = null
            },
        )
    }
    if (showKey) KeyDialog(state.authorizedKey, strings) { showKey = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachinesContent(
    machines: List<Machine>,
    strings: Strings,
    onOpen: (Machine) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Machine) -> Unit,
    onDelete: (Machine) -> Unit,
    onShowKey: () -> Unit,
    onShowOptions: () -> Unit = {},
    onClone: (Machine) -> Unit = {},
    /** What each machine's screen last looked like, by machine id. */
    previews: Map<String, ImageBitmap> = emptyMap(),
    /** The machines whose session is open. */
    open: Set<String> = emptySet(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.appName) },
                actions = {
                    TextButton(onClick = onShowOptions) { Text(strings.options) }
                    TextButton(onClick = onShowKey) { Text(strings.sshKey) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd) { Text(strings.addMachine) }
        },
    ) { padding ->
        if (machines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(strings.noMachines, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        strings.firstSteps,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                // Clear of the button floating over the last row.
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(machines, key = { it.id }) { machine ->
                    MachineTile(machine, previews[machine.id], machine.id in open, strings, onOpen, onEdit, onClone, onDelete)
                }
            }
        }
    }
}

/**
 * One machine: what its screen looked like last time, or its initial while
 * it has never been seen, and a dot while its session is open.
 */
@Composable
private fun MachineTile(
    machine: Machine,
    preview: ImageBitmap?,
    open: Boolean,
    strings: Strings,
    onOpen: (Machine) -> Unit,
    onEdit: (Machine) -> Unit,
    onClone: (Machine) -> Unit,
    onDelete: (Machine) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val mode = when {
        machine.headless -> strings.privateScreen
        machine.output.isBlank() -> strings.mirror
        else -> strings.mirrorOf(machine.output.trim())
    }
    val colors = MaterialTheme.colorScheme
    Card(
        // A right click or a long press opens the tile's menu, as its button does.
        modifier = Modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            menu = true
                        }
                    }
                }
            }
            .clip(CardDefaults.shape)
            .combinedClickable(onLongClick = { menu = true }) { onOpen(machine) },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(colors.surfaceVariant)) {
            if (preview != null) {
                Image(preview, contentDescription = null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    machine.host.take(1).uppercase(),
                    Modifier.align(Alignment.Center),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            if (open) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(OPEN_DOT)
                        .semantics { contentDescription = strings.sessionOpen },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    machine.address,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$mode · " + (machine.hostKey?.let(::shortFingerprint) ?: strings.hostKeyNotSeen),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(strings.edit) }, onClick = {
                        menu = false
                        onEdit(machine)
                    })
                    DropdownMenuItem(text = { Text(strings.clone) }, onClick = {
                        menu = false
                        onClone(machine)
                    })
                    DropdownMenuItem(text = { Text(strings.delete) }, onClick = {
                        menu = false
                        onDelete(machine)
                    })
                }
            }
        }
    }
}

/** A session running, as a status light shows it. */
private val OPEN_DOT = Color(0xFF4CAF50)

/** `SHA256:abcdefgh…wxyz`: enough to recognise a key at a glance. */
internal fun shortFingerprint(fingerprint: String): String {
    val body = fingerprint.substringAfter(':')
    return if (body.length <= 14) fingerprint else "SHA256:${body.take(8)}…${body.takeLast(4)}"
}

@Composable
fun MachineDialog(
    initial: Machine?,
    strings: Strings,
    onDismiss: () -> Unit,
    onSave: (Machine) -> Unit,
    /** Instead of adding or editing, as the dialog would say from `initial`. */
    title: String? = null,
) {
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var headless by remember { mutableStateOf(initial?.headless ?: true) }
    var output by remember { mutableStateOf(initial?.output ?: "") }
    var serverBin by remember { mutableStateOf(initial?.serverBin ?: Machine.DEFAULT_SERVER_BIN) }
    // This machine's own resolution: as the options say, the window's, or a fixed one.
    var forced by remember {
        mutableStateOf((initial?.forcedWidth ?: Machine.INHERIT) to (initial?.forcedHeight ?: Machine.INHERIT))
    }
    var showError by remember { mutableStateOf(false) }
    val parsed = Machine.parseAddress(address)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: if (initial == null) strings.addMachine else strings.editMachine) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(strings.address) },
                    placeholder = { Text(strings.addressHint) },
                    singleLine = true,
                    isError = showError && parsed == null,
                    supportingText = if (showError && parsed == null) {
                        { Text(strings.addressError) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(headless, onClick = { headless = true }, label = { Text(strings.privateScreen) })
                    FilterChip(!headless, onClick = { headless = false }, label = { Text(strings.mirror) })
                }
                if (!headless) {
                    OutlinedTextField(
                        value = output,
                        onValueChange = { output = it },
                        label = { Text(strings.outputName) },
                        placeholder = { Text(strings.outputHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.resolutionTitle, style = MaterialTheme.typography.titleSmall)
                    ResolutionPicker(forced.first, forced.second, inherit = true, strings) { width, height ->
                        forced = width to height
                    }
                }
                OutlinedTextField(
                    value = serverBin,
                    onValueChange = { serverBin = it },
                    label = { Text(strings.serverPath) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (user, host, port) = parsed ?: run {
                    showError = true
                    return@TextButton
                }
                val base = initial ?: Machine(user = user, host = host)
                onSave(base.edited(user, host, port, headless, output, serverBin, forced.first, forced.second))
            }) { Text(strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
fun KeyDialog(key: String?, strings: Strings, onDismiss: () -> Unit) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.keyTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.keyExplain)
                SelectionContainer {
                    Text(
                        key ?: strings.keyLoading,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = key != null, onClick = {
                clipboard.setText(AnnotatedString(key.orEmpty()))
                copied = true
            }) { Text(if (copied) strings.copied else strings.copy) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
    )
}
